package dev.deadinternet.graph;

import dev.deadinternet.analysis.AccountFeatures;
import dev.deadinternet.analysis.ConversationFeatures;
import dev.deadinternet.analysis.CoordinationEdge;
import dev.deadinternet.analysis.DeadInternetScore;
import dev.deadinternet.analysis.DetectedCluster;
import dev.deadinternet.classification.ClassificationResult;
import dev.deadinternet.model.Conversation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an analysis into FalkorDB in stages. Later pipeline stages read graph-derived features back
 * (see {@link GraphQueryService}) before writing classifications and clusters.
 */
@Service
public class FalkorGraphService {

    private final FalkorGateway falkor;

    public FalkorGraphService(FalkorGateway falkor) {
        this.falkor = falkor;
    }

    public void createAnalysis(String id, Conversation conversation, boolean demo, String provider) {
        falkor.delete(id);
        for (String index : List.of("CREATE INDEX FOR (a:Account) ON (a.id)", "CREATE INDEX FOR (r:Reply) ON (r.id)",
                "CREATE INDEX FOR (c:Cluster) ON (c.id)")) {
            falkor.write(id, index, Map.of());
        }
        var post = conversation.post();
        falkor.write(id, """
                CREATE (:Analysis {id: $id, createdAt: $createdAt, demo: $demo, provider: $provider, kind: $kind, status: 'RUNNING'})
                       -[:ANALYZES]->(:Post {id: $postId, author: $author, text: $text, createdAt: $postCreatedAt})
                """, Map.of("id", id, "createdAt", Instant.now().toString(), "demo", demo, "provider", provider, "kind", conversation.kind(),
                "postId", post.id(), "author", post.author(), "text", post.text(),
                "postCreatedAt", post.createdAt().toString()));
    }

    public void writeAccountsAndReplies(String id, Conversation conversation, ConversationFeatures features,
                                        Map<String, AccountFeatures> accounts) {
        var accountRows = new ArrayList<Map<String, Object>>();
        for (var a : accounts.values()) {
            var row = new HashMap<String, Object>();
            row.put("id", a.account().id());
            row.put("username", a.account().username());
            row.put("accountAgeDays", a.account().accountAgeDays());
            row.put("followers", a.account().followers());
            row.put("following", a.account().following());
            row.put("totalPosts", a.account().totalPosts());
            row.put("followerFollowingRatio", round(a.account().followerFollowingRatio()));
            row.put("hasMetadata", a.account().hasMetadata());
            row.put("postsPerDay", round(a.account().postsPerDay()));
            row.put("replyCount", a.replyCount());
            row.put("lexicalDiversity", a.lexicalDiversity());
            row.put("maxTextSimilarity", a.maxTextSimilarity());
            row.put("duplicate", a.duplicate());
            row.put("inBurst", a.inBurst());
            row.put("firstReplySeconds", a.firstReplySecondsAfterParent());
            row.put("selfSimilarity", a.selfSimilarity());
            accountRows.add(row);
        }
        falkor.write(id, "UNWIND $rows AS row CREATE (a:Account) SET a = row", Map.of("rows", accountRows));

        var replyRows = new ArrayList<Map<String, Object>>();
        for (var reply : conversation.replies()) {
            var f = features.replies().get(reply.id());
            var row = new HashMap<String, Object>();
            row.put("id", reply.id());
            row.put("account", reply.author().id());
            row.put("text", reply.text());
            row.put("createdAt", reply.createdAt().toString());
            row.put("secondsAfterParent", f.timing().secondsAfterParent());
            row.put("lexicalDiversity", f.lexicalDiversity());
            row.put("maxTextSimilarity", f.maxTextSimilarity());
            row.put("duplicate", f.duplicate());
            row.put("inBurst", f.timing().inBurst());
            row.put("similarWithin30Seconds", f.timing().similarRepliesWithin30Seconds());
            row.put("similarWithin60Seconds", f.timing().similarRepliesWithin60Seconds());
            replyRows.add(row);
        }
        falkor.write(id, """
                UNWIND $rows AS row
                MATCH (a:Account {id: row.account}), (p:Post)
                CREATE (a)-[:POSTED]->(r:Reply {id: row.id, account: row.account, text: row.text, createdAt: row.createdAt,
                        secondsAfterParent: row.secondsAfterParent, lexicalDiversity: row.lexicalDiversity,
                        maxTextSimilarity: row.maxTextSimilarity, duplicate: row.duplicate, inBurst: row.inBurst,
                        similarWithin30Seconds: row.similarWithin30Seconds,
                        similarWithin60Seconds: row.similarWithin60Seconds})-[:REPLIES_TO]->(p)
                """, Map.of("rows", replyRows));

        var patternRows = features.patterns().stream().map(p -> Map.<String, Object>of("id", p.id(), "text", p.text(),
                "accountCount", p.accountCount(), "replies", List.copyOf(p.replyIds()))).toList();
        falkor.write(id, """
                UNWIND $rows AS row
                CREATE (p:Pattern {id: row.id, text: row.text, accountCount: row.accountCount})
                WITH p, row UNWIND row.replies AS replyId
                MATCH (r:Reply {id: replyId})
                CREATE (r)-[:CONTAINS_PATTERN]->(p)
                """, Map.of("rows", patternRows));
    }

    public void writeSimilarityEdges(String id, ConversationFeatures features) {
        var rows = features.similarityEdges().stream().map(e -> Map.<String, Object>of("a", e.sourceReplyId(),
                "b", e.targetReplyId(), "similarity", e.similarity(), "dt", e.timeDifferenceSeconds())).toList();
        falkor.write(id, """
                UNWIND $rows AS row
                MATCH (a:Reply {id: row.a}), (b:Reply {id: row.b})
                CREATE (a)-[:SIMILAR_TO {similarity: row.similarity, timeDifferenceSeconds: row.dt}]->(b)
                """, Map.of("rows", rows));
    }

    public void writeCoordinationEdges(String id, List<CoordinationEdge> edges) {
        var rows = edges.stream().map(e -> Map.<String, Object>of("a", e.sourceAccountId(), "b", e.targetAccountId(),
                "score", e.score(), "text", e.textSimilarity(), "timing", e.timingProximity(),
                "pattern", e.sharedPatternScore(), "dt", e.timeDifferenceSeconds(),
                "ra", e.sourceReplyId(), "rb", e.targetReplyId())).toList();
        falkor.write(id, """
                UNWIND $rows AS row
                MATCH (a:Account {id: row.a}), (b:Account {id: row.b})
                CREATE (a)-[:COORDINATED_WITH {score: row.score,
                        textSimilarity: row.text, timingProximity: row.timing, sharedPatternScore: row.pattern,
                        timeDifferenceSeconds: row.dt, sourceReplyId: row.ra, targetReplyId: row.rb}]->(b)
                """, Map.of("rows", rows));
    }

    public void writeGraphFeatures(String id, Map<String, GraphFeatures> features) {
        var rows = features.values().stream().map(g -> Map.<String, Object>of("id", g.accountId(),
                "componentId", g.componentId(), "degree", g.degree(),
                "coordinatedAccounts", g.coordinatedAccounts(), "similarAccounts", g.similarAccounts())).toList();
        falkor.write(id, """
                UNWIND $rows AS row
                MATCH (a:Account {id: row.id})
                SET a.componentId = row.componentId, a.degree = row.degree,
                    a.coordinatedAccounts = row.coordinatedAccounts, a.similarAccounts = row.similarAccounts
                """, Map.of("rows", rows));
    }

    public void writeClassifications(String id, Map<String, ClassificationResult> results) {
        var rows = new ArrayList<Map<String, Object>>();
        results.forEach((accountId, r) -> {
            var row = new HashMap<String, Object>();
            row.put("id", accountId);
            row.put("classification", r.classification().wire());
            row.put("automation", r.automationLikelihood());
            row.put("coordination", r.coordinationLikelihood());
            row.put("confidence", r.confidence());
            row.put("source", r.source().name());
            row.put("signals", r.signalsForAutomation());
            row.put("counter", r.signalsAgainstAutomation());
            row.put("coordinationSignals", r.coordinationSignals());
            row.put("summary", r.summary());
            rows.add(row);
        });
        falkor.write(id, """
                UNWIND $rows AS row
                MATCH (a:Account {id: row.id})
                SET a.classification = row.classification, a.automationLikelihood = row.automation,
                    a.coordinationLikelihood = row.coordination, a.confidence = row.confidence,
                    a.classificationSource = row.source, a.signalsForAutomation = row.signals,
                    a.signalsAgainstAutomation = row.counter, a.coordinationSignals = row.coordinationSignals,
                    a.summary = row.summary
                """, Map.of("rows", rows));
    }

    /** Creates Cluster nodes and MEMBER_OF edges, then derives each cluster's statistics with graph queries. */
    public void writeClusters(String id, List<DetectedCluster> clusters) {
        var rows = clusters.stream().map(c -> Map.<String, Object>of("id", c.id(), "index", c.index(),
                "members", c.accountIds())).toList();
        falkor.write(id, """
                UNWIND $rows AS row
                CREATE (c:Cluster {id: row.id, index: row.index, size: size(row.members)})
                WITH c, row UNWIND row.members AS accountId
                MATCH (a:Account {id: accountId})
                SET a.clusterId = c.id
                CREATE (a)-[:MEMBER_OF]->(c)
                """, Map.of("rows", rows));
        falkor.write(id, """
                MATCH (c:Cluster)<-[:MEMBER_OF]-(a:Account)
                WITH c, avg(a.automationLikelihood) AS automation, avg(a.coordinationLikelihood) AS coordination
                SET c.averageAutomation = automation, c.averageCoordination = coordination
                """, Map.of());
        falkor.write(id, """
                MATCH (c:Cluster)<-[:MEMBER_OF]-(a:Account)-[k:COORDINATED_WITH]->(b:Account)-[:MEMBER_OF]->(c)
                WITH c, avg(k.textSimilarity) AS similarity, avg(k.score) AS score,
                     collect(k.sourceReplyId) + collect(k.targetReplyId) AS replyIds
                MATCH (r:Reply) WHERE r.id IN replyIds
                WITH c, similarity, score, min(r.secondsAfterParent) AS first, max(r.secondsAfterParent) AS last
                SET c.averageSimilarity = similarity, c.averageCoordinationScore = score,
                    c.firstReplySeconds = first, c.timeSpanSeconds = last - first
                """, Map.of());
    }

    public void completeAnalysis(String id, DeadInternetScore score, int comparisons) {
        falkor.write(id, """
                MATCH (n:Analysis {id: $id})
                SET n.status = 'COMPLETE', n.completedAt = $completedAt, n.deadInternetScore = $score,
                    n.automationShare = $automation, n.coordinatedShare = $coordinated,
                    n.duplicateShare = $duplicate, n.largeClusterShare = $cluster, n.comparisons = $comparisons
                """, Map.of("id", id, "completedAt", Instant.now().toString(), "score", score.score(),
                "automation", score.automationShare(), "coordinated", score.coordinatedAccountShare(),
                "duplicate", score.duplicateReplyShare(), "cluster", score.largeClusterShare(),
                "comparisons", comparisons));
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 1000) / 1000.0;
    }
}
