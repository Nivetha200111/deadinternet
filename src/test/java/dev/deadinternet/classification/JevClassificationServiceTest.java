package dev.deadinternet.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.deadinternet.Fixtures.established;
import static dev.deadinternet.classification.Requests.request;
import static org.assertj.core.api.Assertions.assertThat;

class JevClassificationServiceTest {

    private final ClassificationThresholds thresholds = new ClassificationThresholds(0.40, 0.65);
    private final ObjectMapper mapper = new ObjectMapper();
    private final JevResponseParser parser = new JevResponseParser(mapper);
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private URI serve(int status, String body, AtomicReference<String> seenAuth, AtomicReference<String> seenBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/classify", exchange -> {
            if (seenAuth != null) seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (seenBody != null) seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/classify");
    }

    private JevClassificationService http(URI uri) {
        return new JevClassificationService(new HttpJevClassifier(uri, "secret-token", Duration.ofSeconds(2), mapper, parser), thresholds, 2);
    }

    @Test
    void usesJevResultAndSendsStructuredRequest() throws IOException {
        var auth = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var service = http(serve(200, JevResponseParserTest.VALID, auth, body));
        var result = service.classify(request(established("a"), 0.94, true, 9, 5, 0.9, 11, 5));
        assertThat(result.source()).isEqualTo(ClassificationSource.JEV);
        assertThat(result.automationLikelihood()).isEqualTo(0.83);
        assertThat(result.classification()).isEqualTo(Classification.AUTOMATION_LIKE);
        assertThat(auth.get()).isEqualTo("Bearer secret-token");
        assertThat(body.get()).contains("\"maxTextSimilarity\":0.94", "\"similarRepliesWithin60Seconds\":9", "\"neighborReplies\"");
    }

    @Test
    void labelFollowsConfiguredThresholdsRatherThanJevsOwnLabel() throws IOException {
        var service = http(serve(200, JevResponseParserTest.VALID.replace("0.83", "0.5"), null, null));
        assertThat(service.classify(request(established("a"), 0.9, false, 0, 0, 0, 1, 0)).classification())
                .isEqualTo(Classification.UNCERTAIN);
    }

    @Test
    void serverErrorFallsBackToUncertain() throws IOException {
        var result = http(serve(500, "{}", null, null)).classify(request(established("a"), 0.97, true, 8, 10, 0.9, 12, 9));
        assertFallback(result);
    }

    @Test
    void invalidJevResponseFallsBackToUncertain() throws IOException {
        var result = http(serve(200, "{\"classification\": \"bot\"}", null, null)).classify(request(established("a"), 0.97, true, 8, 10, 0.9, 12, 9));
        assertFallback(result);
    }

    @Test
    void unreachableJevFallsBackWithoutThrowing() {
        var service = http(URI.create("http://127.0.0.1:9/classify"));
        assertFallback(service.classify(request(established("a"), 0.97, true, 8, 10, 0.9, 12, 9)));
    }

    @Test
    void classifyAllReturnsEveryAccountInOrderEvenWhenSomeFail() {
        JevClassifier flaky = req -> {
            if (req.account().id().equals("b")) throw new JevException("timeout");
            return new JevClassification(Classification.HUMAN_LIKE, 0.1, 0.1, 0.8, List.of(), List.of(), List.of(), "ok");
        };
        var service = new JevClassificationService(flaky, thresholds, 2);
        var results = service.classifyAll(List.of(request(established("a"), 0.1, false, 0, 0, 0, 1, 0),
                request(established("b"), 0.1, false, 0, 0, 0, 1, 0), request(established("c"), 0.1, false, 0, 0, 0, 1, 0)));
        assertThat(results.keySet()).containsExactly("a", "b", "c");
        assertThat(results.get("a").source()).isEqualTo(ClassificationSource.JEV);
        assertFallback(results.get("b"));
    }

    private static void assertFallback(ClassificationResult result) {
        assertThat(result.source()).isEqualTo(ClassificationSource.HEURISTIC_FALLBACK);
        assertThat(result.classification()).isEqualTo(Classification.UNCERTAIN);
        assertThat(result.confidence()).isLessThanOrEqualTo(0.3);
        assertThat(result.signalsForAutomation().getFirst()).startsWith("JEV unavailable");
    }
}
