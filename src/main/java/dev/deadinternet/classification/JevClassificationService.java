package dev.deadinternet.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.deadinternet.config.LensProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Chooses the configured classifier and guarantees an answer for every account. When JEV fails for an account, the
 * local heuristic's reasoning is kept for reference but the result is forced to uncertain and marked as a fallback.
 */
@Service
public class JevClassificationService {

    private static final Logger log = LoggerFactory.getLogger(JevClassificationService.class);

    private final JevClassifier primary;
    private final ClassificationSource primarySource;
    private final HeuristicClassifier heuristic;
    private final ClassificationThresholds thresholds;
    private final int parallelism;

    @Autowired
    public JevClassificationService(LensProperties properties, ClassificationThresholds thresholds,
                                    ObjectMapper mapper, JevResponseParser parser) {
        this.thresholds = thresholds;
        this.heuristic = new HeuristicClassifier(thresholds);
        this.parallelism = Math.max(1, properties.jev().parallelism());
        var jev = properties.jev();
        String provider = jev.provider() == null ? "auto" : jev.provider().toLowerCase(Locale.ROOT);
        boolean hasUrl = jev.url() != null && !jev.url().isBlank();
        boolean useHttp = switch (provider) {
            case "http", "typesafe" -> {
                if (!hasUrl) throw new IllegalStateException("lens.jev.provider=" + provider + " requires JEV_URL");
                yield true;
            }
            case "heuristic" -> false;
            case "auto" -> hasUrl;
            default -> throw new IllegalStateException("Unknown lens.jev.provider: " + provider);
        };
        if (useHttp) {
            var endpoint = URI.create(jev.url());
            var transport = new HttpJevClassifier(endpoint, jev.token(),
                    Duration.ofSeconds(jev.timeoutSeconds()), mapper, parser);
            boolean typesafe = provider.equals("typesafe") || (provider.equals("auto")
                    && "api.typesafe.ai".equalsIgnoreCase(endpoint.getHost()));
            this.primary = typesafe
                    ? new TypesafeJevClassifier(transport, mapper, thresholds, jev.model()) : transport;
            this.primarySource = ClassificationSource.JEV;
        } else {
            this.primary = heuristic;
            this.primarySource = ClassificationSource.LOCAL_HEURISTIC;
        }
        log.info("Classification provider: {}", primarySource);
    }

    /** Visible for tests: wire an arbitrary JEV implementation. */
    JevClassificationService(JevClassifier jev, ClassificationThresholds thresholds, int parallelism) {
        this.thresholds = thresholds;
        this.heuristic = new HeuristicClassifier(thresholds);
        this.primary = jev;
        this.primarySource = ClassificationSource.JEV;
        this.parallelism = parallelism;
    }

    public ClassificationSource provider() {
        return primarySource;
    }

    public ClassificationResult classify(JevClassificationRequest request) {
        try {
            var result = primary.classify(request);
            // Labels always follow the configured thresholds so the sensitivity control stays consistent.
            return new ClassificationResult(thresholds.classify(result.automationLikelihood()),
                    result.automationLikelihood(), result.coordinationLikelihood(), result.confidence(),
                    result.signalsForAutomation(), result.signalsAgainstAutomation(), result.coordinationSignals(),
                    result.summary(), primarySource);
        } catch (RuntimeException e) {
            log.warn("JEV classification failed for {}: {}", request.account().id(), e.getMessage());
            return fallback(request, e.getMessage());
        }
    }

    ClassificationResult fallback(JevClassificationRequest request, String reason) {
        var local = heuristic.classify(request);
        var signals = new ArrayList<String>();
        signals.add("JEV unavailable (" + reason + "); local heuristic shown for reference only");
        signals.addAll(local.signalsForAutomation());
        return new ClassificationResult(Classification.UNCERTAIN, local.automationLikelihood(),
                local.coordinationLikelihood(), Math.min(0.3, local.confidence()), List.copyOf(signals),
                local.signalsAgainstAutomation(), local.coordinationSignals(),
                "JEV did not return a valid classification, so this account is marked uncertain.",
                ClassificationSource.HEURISTIC_FALLBACK);
    }

    /** Classifies every request concurrently (bounded by lens.jev.parallelism); keyed by account id, order preserved. */
    public Map<String, ClassificationResult> classifyAll(List<JevClassificationRequest> requests) {
        return classifyAll(requests, () -> {});
    }

    /** As {@link #classifyAll(List)}, invoking {@code onEach} after every completed account (for progress). */
    public Map<String, ClassificationResult> classifyAll(List<JevClassificationRequest> requests, Runnable onEach) {
        var permits = new Semaphore(parallelism);
        var futures = new LinkedHashMap<String, Future<ClassificationResult>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var request : requests) {
                futures.put(request.account().id(), executor.submit(() -> {
                    permits.acquire();
                    try {
                        var result = classify(request);
                        onEach.run();
                        return result;
                    } finally {
                        permits.release();
                    }
                }));
            }
            var results = new LinkedHashMap<String, ClassificationResult>();
            for (var entry : futures.entrySet()) results.put(entry.getKey(), entry.getValue().get());
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Classification interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Classification failed", e.getCause());
        }
    }
}
