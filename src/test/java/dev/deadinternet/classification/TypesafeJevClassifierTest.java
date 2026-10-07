package dev.deadinternet.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.deadinternet.Fixtures;
import dev.deadinternet.config.LensProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TypesafeJevClassifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ClassificationThresholds thresholds = new ClassificationThresholds(0.40, 0.65);
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private HttpServer server;

    private static final String VALID = """
            {"model":"jev-latest","answers":{
              "automation":{"type":"choice","choice":"automated","confidence":0.79,
                "probabilities":{"automated":0.83,"human":0.17}},
              "coordination":{"type":"noul","noul":0.91},
              "repetition":{"type":"noul","noul":0.9},
              "timing":{"type":"noul","noul":0.2},
              "individuality":{"type":"noul","noul":0.8},
              "sharedPattern":{"type":"noul","noul":0.95},
              "aiGenerated":{"type":"noul","noul":0.3},
              "engagementBait":{"type":"noul","noul":0.64}
            },"usage":{"input_tokens":120,"output_tokens":12}}
            """;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private URI serve(int status, String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone");
    }

    private JevClassificationService service(URI endpoint) {
        var p = Fixtures.properties();
        var configured = new LensProperties(p.similarity(), p.coordination(), p.thresholds(),
                new LensProperties.Jev("typesafe", endpoint.toString(), "test-token", 2, 2, "jev-preview"),
                p.falkor(), p.capture(), p.maxStoredAnalyses());
        return new JevClassificationService(configured, thresholds, mapper, new JevResponseParser(mapper));
    }

    private JevClassificationRequest sample() {
        return Requests.request(Fixtures.established("synthetic"), 0.94, true, 9, 5, 0.9, 11, 5);
    }

    @Test
    void sendsTypesafeContractAndMapsTypedAnswersThroughConfiguredProvider() throws Exception {
        var result = service(serve(200, VALID)).classify(sample());
        var sent = mapper.readTree(body.get());
        assertThat(sent.path("model").asText()).isEqualTo("jev-preview");
        assertThat(sent.path("state").path("features").path("maxTextSimilarity").asDouble()).isEqualTo(0.94);
        assertThat(sent.path("questions").path("automation").path("type").asText()).isEqualTo("choice");
        assertThat(sent.path("questions").path("coordination").path("type").asText()).isEqualTo("noul");
        assertThat(sent.path("questions").path("aiGenerated").path("type").asText()).isEqualTo("noul");
        assertThat(sent.path("questions").path("engagementBait").path("type").asText()).isEqualTo("noul");
        assertThat(auth.get()).isEqualTo("Bearer test-token");
        assertThat(result.source()).isEqualTo(ClassificationSource.JEV);
        assertThat(result.classification()).isEqualTo(Classification.AUTOMATION_LIKE);
        assertThat(result.automationLikelihood()).isEqualTo(0.83);
        assertThat(result.coordinationLikelihood()).isEqualTo(0.91);
        assertThat(result.confidence()).isEqualTo(0.79);
        assertThat(result.signalsForAutomation()).containsExactly(
                "JEV: spam, scam or engagement-bait content (64%)",
                "JEV identifies templated or near-duplicate language");
        assertThat(result.signalsAgainstAutomation()).hasSize(1);
        assertThat(result.coordinationSignals()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrongType", "range", "string", "sum", "json"})
    void malformedAnswersFallBackToUncertain(String kind) throws Exception {
        var response = mapper.readTree(VALID);
        var answers = (com.fasterxml.jackson.databind.node.ObjectNode) response.path("answers");
        var coordination = (com.fasterxml.jackson.databind.node.ObjectNode) answers.path("coordination");
        switch (kind) {
            case "missing" -> answers.remove("engagementBait");
            case "wrongType" -> coordination.put("type", "score");
            case "range" -> coordination.put("noul", 1.1);
            case "string" -> coordination.put("noul", "0.9");
            case "sum" -> ((com.fasterxml.jackson.databind.node.ObjectNode) answers.path("automation").path("probabilities")).put("human", 0.5);
        }
        var result = service(serve(200, kind.equals("json") ? "not json" : mapper.writeValueAsString(response))).classify(sample());
        assertThat(result.source()).isEqualTo(ClassificationSource.HEURISTIC_FALLBACK);
        assertThat(result.classification()).isEqualTo(Classification.UNCERTAIN);
    }

    @Test
    void authenticationFailureDoesNotExposeResponseBody() throws Exception {
        var result = service(serve(401, "sensitive-server-response")).classify(sample());
        assertThat(result.source()).isEqualTo(ClassificationSource.HEURISTIC_FALLBACK);
        assertThat(result.signalsForAutomation().getFirst()).contains("HTTP 401").doesNotContain("sensitive-server-response");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JEV_LIVE_TEST", matches = "true")
    void liveSyntheticRequestWorksWithTypesafe() {
        var p = Fixtures.properties();
        var configured = new LensProperties(p.similarity(), p.coordination(), p.thresholds(),
                new LensProperties.Jev("auto", "https://api.typesafe.ai/v1/systemone",
                        System.getenv("JEV_TOKEN"), 20, 2, "jev-latest"),
                p.falkor(), p.capture(), p.maxStoredAnalyses());
        var classifier = new JevClassificationService(configured, thresholds, mapper, new JevResponseParser(mapper));
        var result = classifier.classify(sample());
        assertThat(result.source()).isEqualTo(ClassificationSource.JEV);
        assertThat(result.automationLikelihood()).isBetween(0.0, 1.0);
        assertThat(result.summary()).contains("typed decisions");
    }
}
