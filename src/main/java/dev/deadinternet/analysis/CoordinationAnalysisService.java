package dev.deadinternet.analysis;

import dev.deadinternet.config.LensProperties;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.model.Reply;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores how coordinated each pair of accounts looks:
 * <pre>coordinationScore = textWeight * textSimilarity + timingWeight * timingProximity + patternWeight * sharedPatternScore</pre>
 * Coordination is independent of automation: a group of people posting the same announcement is coordinated
 * without being automated.
 */
@Service
public class CoordinationAnalysisService {

    private final LensProperties.Coordination config;

    public CoordinationAnalysisService(LensProperties properties) {
        this.config = properties.coordination();
    }

    public double score(double textSimilarity, double timingProximity, double sharedPatternScore) {
        double raw = config.textWeight() * textSimilarity + config.timingWeight() * timingProximity
                + config.patternWeight() * sharedPatternScore;
        return TextSimilarityService.round(Math.max(0, Math.min(1, raw)));
    }

    /** 1 for simultaneous replies, falling linearly to 0 at the configured timing window. */
    public double timingProximity(long seconds) {
        return Math.max(0, 1 - (double) Math.abs(seconds) / config.timingWindowSeconds());
    }

    /** Jaccard overlap of the repeated-phrase patterns two replies contain. */
    static double sharedPatternScore(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        var intersection = new HashSet<>(a);
        intersection.retainAll(b);
        var union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    public List<CoordinationEdge> edges(Conversation conversation, ConversationFeatures features) {
        var repliesByAccount = new LinkedHashMap<String, List<Reply>>();
        for (var reply : conversation.replies()) {
            repliesByAccount.computeIfAbsent(reply.author().id(), k -> new ArrayList<>()).add(reply);
        }
        var accountIds = List.copyOf(repliesByAccount.keySet());
        var edges = new ArrayList<CoordinationEdge>();
        for (int i = 0; i < accountIds.size(); i++) {
            for (int j = i + 1; j < accountIds.size(); j++) {
                var best = strongestPair(repliesByAccount.get(accountIds.get(i)), repliesByAccount.get(accountIds.get(j)), features);
                if (best != null && best.score() >= config.threshold()) edges.add(best);
            }
        }
        edges.sort(Comparator.comparingDouble(CoordinationEdge::score).reversed());
        return edges;
    }

    private CoordinationEdge strongestPair(List<Reply> left, List<Reply> right, ConversationFeatures features) {
        CoordinationEdge best = null;
        for (var a : left) {
            for (var b : right) {
                double text = features.similarity().get(a.id(), b.id());
                long seconds = TextSimilarityService.seconds(a, b);
                double timing = timingProximity(seconds);
                double pattern = sharedPatternScore(Set.copyOf(features.replies().get(a.id()).patternIds()),
                        Set.copyOf(features.replies().get(b.id()).patternIds()));
                double score = score(text, timing, pattern);
                if (best == null || score > best.score()) {
                    boolean ordered = a.author().id().compareTo(b.author().id()) < 0;
                    var first = ordered ? a : b;
                    var second = ordered ? b : a;
                    best = new CoordinationEdge(first.author().id(), second.author().id(), score,
                            TextSimilarityService.round(text), TextSimilarityService.round(timing),
                            TextSimilarityService.round(pattern), seconds, first.id(), second.id());
                }
            }
        }
        return best;
    }

    /** Account id → coordination edges touching it. */
    public static Map<String, List<CoordinationEdge>> byAccount(List<CoordinationEdge> edges) {
        var result = new LinkedHashMap<String, List<CoordinationEdge>>();
        for (var e : edges) {
            result.computeIfAbsent(e.sourceAccountId(), k -> new ArrayList<>()).add(e);
            result.computeIfAbsent(e.targetAccountId(), k -> new ArrayList<>()).add(e);
        }
        return result;
    }
}
