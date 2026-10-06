package dev.deadinternet.service;

import dev.deadinternet.dto.AnalysisStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Live progress of analyses started by this process. Completed results live in FalkorDB, not here. */
@Component
public class AnalysisRegistry {

    public enum Stage { QUEUED, DISCOVERING, COMPARING, CONNECTING, WRITING_GRAPH, CLASSIFYING, CLUSTERING, SCORING, COMPLETE, FAILED }

    /** Mutable progress for one run; written by the pipeline thread, read by status polls. */
    public static final class Run {
        private final String id;
        private final boolean demo;
        private final String provider;
        private volatile Stage stage = Stage.QUEUED;
        private volatile String error;
        private volatile int replies, accounts, comparisons, similarityEdges, coordinationEdges, classified, clusters;

        Run(String id, boolean demo, String provider) {
            this.id = id;
            this.demo = demo;
            this.provider = provider;
        }

        public void stage(Stage value) { stage = value; }
        public void discovered(int replies, int accounts) { this.replies = replies; this.accounts = accounts; }
        public void compared(int comparisons, int similarityEdges) { this.comparisons = comparisons; this.similarityEdges = similarityEdges; }
        public void connected(int coordinationEdges) { this.coordinationEdges = coordinationEdges; }
        public synchronized void classifiedOne() { classified++; }
        public void clustered(int clusters) { this.clusters = clusters; }
        public void fail(String message) { error = message; stage = Stage.FAILED; }

        public AnalysisStatus snapshot() {
            String status = switch (stage) {
                case COMPLETE -> "COMPLETE";
                case FAILED -> "FAILED";
                default -> "RUNNING";
            };
            return new AnalysisStatus(id, status, stage.name(), demo, provider,
                    new AnalysisStatus.Progress(replies, accounts, comparisons, similarityEdges, coordinationEdges,
                            classified, clusters), error);
        }
    }

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public Run start(String id, boolean demo, String provider) {
        var run = new Run(id, demo, provider);
        runs.put(id, run);
        return run;
    }

    public Optional<Run> find(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    public void forget(String id) {
        runs.remove(id);
    }
}
