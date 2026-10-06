package dev.deadinternet.capture;

import com.sun.net.httpserver.HttpServer;
import dev.deadinternet.graph.FalkorGateway;
import dev.deadinternet.service.ConversationAnalysisService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the installed Chrome (headless) through a full live capture of the X-like and LinkedIn-like fixture pages:
 * scrolling, "show more" sections, collection, analysis in FalkorDB and badges in the page.
 * Opt-in because it needs Chrome and FalkorDB:
 * <pre>python3 extension/test-harness/build_fixture.py && ./mvnw test -Dtest=LiveCaptureEndToEndTest -Dlens.e2e=true</pre>
 */
@EnabledIfSystemProperty(named = "lens.e2e", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lens.demo.analyze-on-startup=false", "lens.capture.headless=true",
                "lens.capture.profile-dir=target/e2e-browser-profile"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveCaptureEndToEndTest {
    // Feeds: run ./mvnw test -Dtest=LiveCaptureEndToEndTest -Dlens.e2e=true after build_fixture.py has made the feed pages.

    private static final Path HARNESS = Path.of("extension/test-harness");

    @Autowired CaptureService captures;
    @Autowired ConversationAnalysisService analyses;
    @Autowired FalkorGateway falkor;
    private HttpServer server;
    private String base;

    @BeforeAll
    void serveFixtures() throws IOException {
        assumeTrue(falkor.ping(), "FalkorDB is not running");
        assumeTrue(Files.exists(HARNESS.resolve("x.html")), "Run extension/test-harness/build_fixture.py first");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Path file = path.contains("/status/") ? HARNESS.resolve("x.html")
                    : path.startsWith("/feed/update/") ? HARNESS.resolve("linkedin.html")
                    : path.equals("/home") ? HARNESS.resolve("x_feed.html")
                    : path.equals("/feed/") ? HARNESS.resolve("linkedin_feed.html") : null;
            byte[] body = file == null ? new byte[0] : Files.readAllBytes(file);
            exchange.getResponseHeaders().add("Content-Type", file == null ? "text/plain" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(file == null ? 404 : 200, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    void stop() {
        if (server != null) server.stop(0);
    }

    private CaptureStatus runToEnd(CaptureTarget target) throws InterruptedException {
        var status = captures.start(target);
        for (int i = 0; i < 600 && !java.util.Set.of("COMPLETE", "FAILED", "NEEDS_LOGIN").contains(status.stage()); i++) {
            Thread.sleep(250);
            status = captures.status(status.id());
        }
        return status;
    }

    @Test
    void capturesAnXThreadIncludingHiddenRepliesAndClustersIt() throws InterruptedException {
        var status = runToEnd(new CaptureTarget("X", base + "/alexchen/status/1000"));
        assertThat(status.stage()).as(status.message()).isEqualTo("COMPLETE");
        assertThat(status.collected()).isEqualTo(94); // 70 visible + 24 behind "Show probable spam"
        assertThat(status.skipped()).isEqualTo(1);    // the media-only reply
        assertThat(status.frame()).isPositive();
        assertThat(captures.frame(status.id())).isPresent();
        var graph = analyses.graph(status.analysisId());
        assertThat(graph.accounts()).hasSize(80);
        assertThat(graph.clusters()).hasSize(4);
        analyses.delete(status.analysisId());
    }

    @Test
    void capturesAnInfiniteXFeedAcrossAccounts() throws InterruptedException {
        var status = runToEnd(new CaptureTarget("X", base + "/home", true));
        assertThat(status.stage()).as(status.message()).isEqualTo("COMPLETE");
        assertThat(status.collected()).isEqualTo(94); // 40 on load + 54 loaded by scrolling; the promoted post skipped
        var graph = analyses.graph(status.analysisId());
        assertThat(graph.analysis().kind()).isEqualTo("feed");
        assertThat(graph.post().text()).startsWith("Your X feed");
        assertThat(graph.accounts()).hasSize(80);
        assertThat(graph.clusters()).hasSize(4);
        analyses.delete(status.analysisId());
    }

    @Test
    void capturesALinkedInFeed() throws InterruptedException {
        var status = runToEnd(new CaptureTarget("LinkedIn", base + "/feed/", true));
        assertThat(status.stage()).as(status.message()).isEqualTo("COMPLETE");
        assertThat(status.collected()).isEqualTo(94);
        var graph = analyses.graph(status.analysisId());
        assertThat(graph.analysis().kind()).isEqualTo("feed");
        assertThat(graph.accounts()).hasSize(80);
        analyses.delete(status.analysisId());
    }

    @Test
    void capturesALinkedInThreadWithTimestampsFromCommentIds() throws InterruptedException, IOException {
        String html = Files.readString(HARNESS.resolve("linkedin.html"));
        String postId = html.replaceAll("(?s).*urn:li:activity:(\\d+).*", "$1");
        var status = runToEnd(new CaptureTarget("LinkedIn", base + "/feed/update/urn:li:activity:" + postId + "/"));
        assertThat(status.stage()).as(status.message()).isEqualTo("COMPLETE");
        assertThat(status.collected()).isEqualTo(94); // 60 visible + 34 behind "Load more comments"
        var graph = analyses.graph(status.analysisId());
        assertThat(graph.accounts()).hasSize(80);
        assertThat(graph.clusters()).hasSize(4);
        analyses.delete(status.analysisId());
    }
}
