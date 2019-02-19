package io.retel.ariproxy.boundary.commandsandresponses;

import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpMethods;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.HttpCredentials;
import akka.japi.function.Function;
import akka.japi.function.Procedure;
import akka.stream.ActorMaterializer;
import akka.stream.ActorMaterializerSettings;
import akka.stream.Attributes;
import akka.stream.Materializer;
import akka.stream.Supervision;
import akka.stream.Supervision.Directive;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.AriCommand;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.AriCommandEnvelope;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.AriMessageEnvelope;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.AriMessageType;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.AriResponse;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.CallContextAndCommandId;
import io.retel.ariproxy.boundary.commandsandresponses.auxiliary.CommandResponseHandler;
import io.retel.ariproxy.boundary.processingpipeline.ProcessingPipeline;
import io.retel.ariproxy.metrics.StopCallSetupTimer;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.concurrent.Future;
import io.vavr.control.Option;
import io.vavr.control.Try;
import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

public class AriCommandResponseKafkaProcessor {

	private static final Attributes LOG_LEVELS = Attributes.createLogLevels(
			Logging.InfoLevel(),
			Logging.InfoLevel(),
			Logging.ErrorLevel()
	);

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String SERVICE = "service";
	public static final String REST = "rest";
	public static final String URI = "uri";
	public static final String PASSWORD = "password";
	public static final String USER = "user";
	public static final String STASIS_APP = "stasis-app";
	public static final String KAFKA = "kafka";
	public static final String EVENTS_AND_RESPONSES_TOPIC = "events-and-responses-topic";
	public static final String COMMANDS_TOPIC = "commands-topic";

	static {
		mapper.setSerializationInclusion(Include.NON_NULL);
	}

	private static final ObjectReader reader = mapper.readerFor(AriCommandEnvelope.class);
	private static final ObjectWriter ariMessageEnvelopeWriter = mapper.writerFor(AriMessageEnvelope.class);
	private static final ObjectWriter genericWriter = mapper.writer();
	private static final ObjectReader genericReader = mapper.reader();

	public static ProcessingPipeline<ConsumerRecord<String, String>, CommandResponseHandler> commandResponseProcessing() {
		return system -> commandResponseHandler -> callContextProvider -> metricsService -> source -> sink -> () -> run(
				system,
				commandResponseHandler,
				callContextProvider,
				metricsService,
				source,
				sink
		);
	}

	private static ActorMaterializer run(
			ActorSystem system,
			CommandResponseHandler commandResponseHandler,
			ActorRef callContextProvider,
			ActorRef metricsService,
			Source<ConsumerRecord<String, String>, NotUsed> source,
			Sink<ProducerRecord<String, String>, NotUsed> sink) {

		final Function<Throwable, Directive> decider = t -> {
			system.log().error(t, "Error in some stage; restarting stream ...");
			return Supervision.restart();
		};

		final Config serviceConfig = ConfigFactory.load().getConfig(SERVICE);
		final String stasisApp = serviceConfig.getString(STASIS_APP);

		final Config kafkaConfig = serviceConfig.getConfig(KAFKA);
		final String commandsTopic = kafkaConfig.getString(COMMANDS_TOPIC);
		final String eventsAndResponsesTopic = kafkaConfig.getString(EVENTS_AND_RESPONSES_TOPIC);

		final Config restConfig = serviceConfig.getConfig(REST);
		final String restUri = restConfig.getString(URI);
		final String restUser = restConfig.getString(USER);
		final String restPassword = restConfig.getString(PASSWORD);

		final ActorMaterializer materializer = ActorMaterializer.create(
				ActorMaterializerSettings.create(system).withSupervisionStrategy(decider),
				system);

		source
				.log(">>>   ARI COMMAND", ConsumerRecord::value).withAttributes(LOG_LEVELS)
				.map(AriCommandResponseKafkaProcessor::unmarshallAriCommandEnvelope)
				.map(msgEnvelope -> {
					AriCommandResponseProcessing
							.registerCallContext(callContextProvider, msgEnvelope.getCallContext(), msgEnvelope.getAriCommand())
							.getOrElseThrow(t -> t).run();
					return Tuple.of(
							msgEnvelope.getAriCommand(),
							new CallContextAndCommandId(msgEnvelope.getCallContext(), msgEnvelope.getCommandId())
					);
				})
				.map(ariCommandAndContext -> ariCommandAndContext
						.map1(cmd -> toHttpRequest(cmd, restUri, restUser, restPassword)))
				.mapAsync(1, requestAndContext -> commandResponseHandler.apply(requestAndContext)
						.thenApply(response -> Tuple.of(response, requestAndContext._2)))
				.wireTap(Sink.foreach(gatherMetrics(metricsService, stasisApp)))
				.mapAsync(1, rawHttpResponseAndContext -> toAriResponse(rawHttpResponseAndContext, materializer))
				.map(ariResponseAndContext -> envelopeAriResponse(ariResponseAndContext._1, ariResponseAndContext._2,
						commandsTopic))
				.map(ariMessageEnvelopeAndContext -> ariMessageEnvelopeAndContext
						.map1(AriCommandResponseKafkaProcessor::marshallAriMessageEnvelope))
				.map(ariResponseStringAndContext -> new ProducerRecord<>(
						eventsAndResponsesTopic,
						ariResponseStringAndContext._2.getCallContext(),
						ariResponseStringAndContext._1)
				)
				.log(">>>   ARI RESPONSE", ProducerRecord::value).withAttributes(LOG_LEVELS)
				.toMat(sink, Keep.none())
				.run(materializer);

		return materializer;
	}

	private static Procedure<Tuple2<HttpResponse, CallContextAndCommandId>> gatherMetrics(ActorRef metricsService,
			String applicationName) {
		return rawHttpResponseAndContext ->
				metricsService.tell(
						new StopCallSetupTimer(
								rawHttpResponseAndContext._2.getCallContext(),
								applicationName
						),
						ActorRef.noSender()
				);
	}

	private static AriCommandEnvelope unmarshallAriCommandEnvelope(final ConsumerRecord<String, String> record) {
		return Try.of(() -> (AriCommandEnvelope) reader.readValue(record.value()))
				.getOrElseThrow(t -> new RuntimeException(t));
	}

	private static Tuple2<AriMessageEnvelope, CallContextAndCommandId> envelopeAriResponse(
			AriResponse ariResponse, CallContextAndCommandId callContextAndResourceId, String kafkaCommandsTopic) {
		final AriMessageEnvelope envelope = new AriMessageEnvelope(
				AriMessageType.RESPONSE,
				kafkaCommandsTopic,
				ariResponse,
				callContextAndResourceId.getCallContext(),
				callContextAndResourceId.getCommandId()
		);

		return Tuple.of(envelope, callContextAndResourceId);
	}

	private static String marshallAriMessageEnvelope(AriMessageEnvelope messageEnvelope) {
		return Try.of(() -> ariMessageEnvelopeWriter.writeValueAsString(messageEnvelope))
				.getOrElseThrow(t -> new RuntimeException("Failed to serialize AriResponse", t));
	}

	private static CompletionStage<Tuple2<AriResponse, CallContextAndCommandId>> toAriResponse(
			Tuple2<HttpResponse, CallContextAndCommandId> responseWithContext,
			Materializer materializer) {

		final HttpResponse response = responseWithContext._1;

		final long contentLength = response
				.entity()
				.getContentLengthOption()
				.orElseThrow(() -> new RuntimeException("failed to get content length"));

		return response
				.entity()
				.toStrict(contentLength, materializer)
				.thenCompose(strictText -> Option.of(StringUtils.trimToNull(strictText.getData().decodeString(Charset.defaultCharset())))
						.map(rawBody -> Try
								.of(() -> genericReader.readTree(rawBody))
								.map(jsonBody -> new AriResponse( response.status().intValue(), jsonBody))
								.map(res -> responseWithContext.map1(httpResponse -> res))
								.map(tuple -> CompletableFuture.completedFuture(tuple))
								.getOrElseGet(t -> Future.<Tuple2<AriResponse, CallContextAndCommandId>>failed(t).toCompletableFuture()))
						.getOrElse(CompletableFuture.completedFuture(responseWithContext.map1(httpResponse -> new AriResponse(response.status().intValue(), null))))
				);
	}

	private static HttpRequest toHttpRequest(AriCommand ariCommand, String uri, String user, String password) {
		final String method = ariCommand.getMethod();
		final JsonNode body = ariCommand.getBody();

		final String bodyJson = Option.of(body)
				.map(value -> Try.of(() -> genericWriter.writeValueAsString(value)))
				.getOrElse(Try.success(""))
				.getOrElseThrow(t -> new RuntimeException(t));

		return HttpMethods.lookup(method)
				.map(validHttpMethod -> HttpRequest
						.create()
						.withMethod(validHttpMethod)
						.addCredentials(HttpCredentials.createBasicHttpCredentials(user, password))
						.withUri(uri + ariCommand.getUrl())
						.withEntity(ContentTypes.APPLICATION_JSON, bodyJson.getBytes())
				)
				.orElseThrow(() -> new RuntimeException(String.format("Invalid http method: %s", method)));
	}
}
