package dev.deadinternet.service;

import dev.deadinternet.analysis.AccountFeatureService;
import dev.deadinternet.analysis.ClusterDetectionService;
import dev.deadinternet.analysis.CoordinationAnalysisService;
import dev.deadinternet.analysis.DeadInternetScoreService;
import dev.deadinternet.analysis.FeatureExtractionService;
import dev.deadinternet.analysis.ReplyFeatures;
import dev.deadinternet.classification.Classification;
import dev.deadinternet.classification.ClassificationThresholds;
import dev.deadinternet.classification.JevClassificationService;
import dev.deadinternet.dto.AnalysisStatus;
import dev.deadinternet.dto.GraphView;
import dev.deadinternet.graph.FalkorGateway;
import dev.deadinternet.graph.FalkorGraphService;
import dev.deadinternet.graph.GraphQueryService;
import dev.deadinternet.model.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.deadinternet.config.LensProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Runs the analysis pipeline. FalkorDB is written early and read back mid-pipeline: connected components and
 * neighborhood sizes computed in the graph feed the classifier and the clustering step.
 */
@Service
public class ConversationAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ConversationAnalysisService.class);

    private final FeatureExtractionService featureExtraction;
    private final AccountFeatureService accountFeatures;
    private final CoordinationAnalysisService coordination;
    private final JevRequestBuilder requests;
    private final JevClassificationService classifier;
    private final ClassificationThresholds thresholds;
    private final ClusterDetectionService clusterDetection;
    private final DeadInternetScoreService scoring;
    private final FalkorGraphService graphWriter;
    private final GraphQueryService graphQueries;
    private final FalkorGateway falkor;
    private final AnalysisRegistry registry;
    private final int maxStoredAnalyses;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ConversationAnalysisService(FeatureExtractionService featureExtraction, AccountFeatureService accountFeatures,
                                       CoordinationAnalysisService coordination, JevRequestBuilder requests,
                                       JevClassificationService classifier, ClassificationThresholds thresholds,
                                       ClusterDetectionService clusterDetection, DeadInternetScoreService scoring,
                                       FalkorGraphService graphWriter, GraphQueryService graphQueries,
                                       FalkorGateway falkor, AnalysisRegistry registry, LensProperties properties) {
        this.featureExtraction = featureExtraction;
        this.accountFeatures = accountFeatures;
        this.coordination = coordination;
        this.requests = requests;
        this.classifier = classifier;
        this.thresholds = thresholds;
        this.clusterDetection = clusterDetection;
        this.scoring = scoring;
        this.graphWriter = graphWriter;
        this.graphQueries = graphQueries;
        this.falkor = falkor;
        this.registry = registry;
        this.maxStoredAnalyses = properties.maxStoredAnalyses();
    }

    /** Validates synchronously, then runs the pipeline in the background. Poll {@link #status(String)}. */
    public AnalysisStatus start(Conversation conversation, boolean demo) {
        validate(conversation);
        if (!falkor.ping()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "FalkorDB is not reachable. Start it with: docker compose up -d");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        var run = registry.start(id, demo, classifier.provider().name());
        executor.submit(() -> {
            try {
                run(id, conversation, demo, run);
            } catch (RuntimeException e) {
                log.error("Analysis {} failed", id, e);
                run.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        return run.snapshot();
    }

    /** Runs the pipeline on the calling thread; used by tests and the startup demo. */
    void run(String id, Conversation conversation, boolean demo, AnalysisRegistry.Run run) {
        run.stage(AnalysisRegistry.Stage.DISCOVERING);
        var accountIds = conversation.replies().stream().map(r -> r.author().id()).collect(Collectors.toSet());
        run.discovered(conversation.replies().size(), accountIds.size());

        run.stage(AnalysisRegistry.Stage.COMPARING);
        var features = featureExtraction.extract(conversation);
        var accounts = accountFeatures.aggregate(conversation, features);
        run.compared(features.comparisons(), features.similarityEdges().size());

        run.stage(AnalysisRegistry.Stage.CONNECTING);
        var edges = coordination.edges(conversation, features);
        run.connected(edges.size());

        run.stage(AnalysisRegistry.Stage.WRITING_GRAPH);
        graphWriter.createAnalysis(id, conversation, demo, classifier.provider().name());
        graphWriter.writeAccountsAndReplies(id, conversation, features, accounts);
        graphWriter.writeSimilarityEdges(id, features);
        graphWriter.writeCoordinationEdges(id, edges);
        var graphFeatures = graphQueries.graphFeatures(id);
        graphWriter.writeGraphFeatures(id, graphFeatures);

        run.stage(AnalysisRegistry.Stage.CLASSIFYING);
        var results = classifier.classifyAll(requests.build(conversation, features, accounts, graphFeatures),
                run::classifiedOne);
        graphWriter.writeClassifications(id, results);

        run.stage(AnalysisRegistry.Stage.CLUSTERING);
        var components = new LinkedHashMap<String, Long>();
        graphFeatures.forEach((account, g) -> components.put(account, g.componentId()));
        var clusters = clusterDetection.detect(components);
        graphWriter.writeClusters(id, clusters);
        run.clustered(clusters.size());

        run.stage(AnalysisRegistry.Stage.SCORING);
        int automationLike = (int) results.values().stream()
                .filter(r -> r.classification() == Classification.AUTOMATION_LIKE).count();
        int coordinated = (int) graphFeatures.values().stream().filter(g -> g.coordinatedAccounts() > 0).count();
        int duplicates = (int) features.replies().values().stream().filter(ReplyFeatures::duplicate).count();
        int inLargeClusters = clusters.stream().mapToInt(c -> c.accountIds().size()).filter(n -> n >= 5).sum();
        var score = scoring.score(new DeadInternetScoreService.Inputs(accounts.size(), automationLike, coordinated,
                conversation.replies().size(), duplicates, inLargeClusters));
        graphWriter.completeAnalysis(id, score, features.comparisons());
        run.stage(AnalysisRegistry.Stage.COMPLETE);
        if (!demo) pruneStoredAnalyses();
        log.info("Analysis {} complete: {} accounts, {} coordination edges, {} clusters, score {}",
                id, accounts.size(), edges.size(), clusters.size(), score.score());
    }

    /** Keeps the newest {@code maxStoredAnalyses} imported analyses; older graphs are deleted. The demo is exempt. */
    void pruneStoredAnalyses() {
        var stored = new ArrayList<String[]>(); // id, createdAt
        for (String id : falkor.listAnalysisIds()) {
            if (registry.find(id).map(r -> "RUNNING".equals(r.snapshot().status())).orElse(false)) continue;
            var rows = falkor.read(id, "MATCH (n:Analysis) RETURN n.demo AS demo, n.createdAt AS createdAt");
            if (rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().getValue("demo"))) continue;
            String createdAt = rows.getFirst().getValue("createdAt");
            stored.add(new String[] {id, createdAt == null ? "" : createdAt});
        }
        stored.sort((a, b) -> b[1].compareTo(a[1]));
        stored.stream().skip(maxStoredAnalyses).forEach(old -> {
            log.info("Removing old analysis {} (keeping the newest {})", old[0], maxStoredAnalyses);
            delete(old[0]);
        });
    }

    public AnalysisStatus status(String id) {
        requireValidId(id);
        var run = registry.find(id);
        if (run.isPresent()) return run.get().snapshot();
        // Analyses from earlier runs of the app are still in FalkorDB.
        return graphQueries.graphView(id).map(g -> new AnalysisStatus(id, "COMPLETE", "COMPLETE",
                        g.analysis().demo(), g.analysis().provider(),
                        new AnalysisStatus.Progress(g.stats().replies(), g.stats().accounts(), g.stats().comparisons(),
                                g.stats().similarityEdges(), g.stats().coordinationEdges(), g.stats().accounts(),
                                g.stats().clusters()), null))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found"));
    }

    public GraphView graph(String id) {
        var status = status(id);
        if (!"COMPLETE".equals(status.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis is " + status.stage().toLowerCase());
        }
        return graphQueries.graphView(id)
                .map(g -> g.withThresholds(new GraphView.Thresholds(thresholds.human(), thresholds.automation())))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found"));
    }

    /** Removes the FalkorDB graph and any progress record. */
    public void delete(String id) {
        requireValidId(id);
        falkor.delete(id);
        registry.forget(id);
    }

    static void validate(Conversation conversation) {
        var replyIds = new HashSet<String>();
        Map<String, String> usernames = new LinkedHashMap<>();
        for (var reply : conversation.replies()) {
            if (!replyIds.add(reply.id())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate reply id: " + reply.id());
            }
            if (reply.createdAt().isBefore(conversation.post().createdAt())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reply " + reply.id() + " predates the post");
            }
            String previous = usernames.putIfAbsent(reply.author().id(), reply.author().username());
            if (previous != null && !previous.equals(reply.author().username())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Account " + reply.author().id() + " appears with two usernames");
            }
        }
        if (replyIds.contains(conversation.post().id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reply reuses the post id");
        }
    }

    private static void requireValidId(String id) {
        if (!FalkorGateway.isValidId(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found");
    }
}
