package dev.deadinternet.controller;

import dev.deadinternet.capture.CaptureService;
import dev.deadinternet.classification.JevClassificationService;
import dev.deadinternet.graph.FalkorGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets clients such as the browser extension check that the server and FalkorDB are up before analyzing. */
@RestController
public class HealthController {

    /** @param capture whether live capture of X / LinkedIn is available (local app only) */
    public record Health(boolean falkordb, String classifier, boolean capture) {}

    private final FalkorGateway falkor;
    private final JevClassificationService classifier;
    private final CaptureService captures;

    public HealthController(FalkorGateway falkor, JevClassificationService classifier, CaptureService captures) {
        this.falkor = falkor;
        this.classifier = classifier;
        this.captures = captures;
    }

    @GetMapping("/api/health")
    public Health health() {
        return new Health(falkor.ping(), classifier.provider().name(), captures.enabled());
    }
}
