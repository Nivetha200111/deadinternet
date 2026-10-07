package dev.deadinternet.graph;

import com.falkordb.Record;
import dev.deadinternet.dto.AccountDetail;
import dev.deadinternet.dto.AccountNode;
import dev.deadinternet.dto.ClusterDetail;
import dev.deadinternet.dto.ClusterView;
import dev.deadinternet.dto.GraphPath;
import dev.deadinternet.dto.GraphView;
import dev.deadinternet.model.Conversation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Graph reads: features for the pipeline, the visualization payload, inspectors and path exploration. */
@Service
public class GraphQueryService {

    private static final String ACCOUNT_PROJECTION = """
            OPTIONAL MATCH (a)-[:POSTED]->(r:Reply)
            WITH a, r ORDER BY r.secondsAfterParent
            WITH a, collect({id: r.id, text: r.text, secondsAfterParent: r.secondsAfterParent, duplicate: r.duplicate}) AS replies
            RETURN properties(a) AS props, replies
            """;

    private static final String CLUSTER_PROJECTION = """
            MATCH (c)<-[:MEMBER_OF]-(a:Account)
            WITH c, a ORDER BY a.firstReplySeconds
            WITH c, collect(a.id) AS members
            OPTIONAL MATCH (c)<-[:MEMBER_OF]-(:Account)-[:POSTED]->(:Reply)-[:CONTAINS_PATTERN]->(p:Pattern)
            WITH c, members, p, count(p) AS uses
            WITH c, members, p, uses * size(split(p.text, ' ')) AS weight ORDER BY weight DESC
            WITH c, members, collect(p.text) AS patterns
            RETURN properties(c) AS props, members, patterns[0..3] AS patterns
            ORDER BY c.index
            """;

    private final FalkorGateway falkor;

    public GraphQueryService(FalkorGateway falkor) {
        this.falkor = falkor;
    }

    /** Graph-derived features used as classifier input: WCC components, coordination degree, neighborhood size. */
    public Map<String, GraphFeatures> graphFeatures(String id) {
        var components = new HashMap<String, Long>();
        for (var r : falkor.read(id, """
                CALL algo.WCC({nodeLabels: ['Account'], relationshipTypes: ['COORDINATED_WITH']})
                YIELD node, componentId
                RETURN node.id AS id, componentId
                """)) {
            components.put(r.getString("id"), num(r, "componentId").longValue());
        }
        var componentSizes = new HashMap<Long, Integer>();
        components.values().forEach(c -> componentSizes.merge(c, 1, Integer::sum));

        var componentSimilarity = new HashMap<String, Double>();
        for (var r : falkor.read(id, """
                MATCH (a:Account)-[k:COORDINATED_WITH]-(:Account)
                RETURN a.id AS id, avg(k.textSimilarity) AS similarity, count(k) AS coordinated, max(k.score) AS maxScore
                """)) {
            componentSimilarity.put(r.getString("id"), num(r, "similarity").doubleValue());
        }
        // Average over each component, not just the account's own edges.
        var simByComponent = new HashMap<Long, double[]>();
        componentSimilarity.forEach((account, sim) -> {
            var acc = simByComponent.computeIfAbsent(components.get(account), k -> new double[2]);
            acc[0] += sim;
            acc[1]++;
        });

        var result = new LinkedHashMap<String, GraphFeatures>();
        for (var r : falkor.read(id, """
                MATCH (a:Account)
                OPTIONAL MATCH (a)-[k:COORDINATED_WITH]-(c:Account)
                WITH a, count(DISTINCT c) AS coordinated, max(k.score) AS maxScore, collect(DISTINCT c.id) AS coordinatedIds
                OPTIONAL MATCH (a)-[:POSTED]->(:Reply)-[:SIMILAR_TO]-(:Reply)<-[:POSTED]-(s:Account)
                WHERE s <> a
                WITH a, coordinated, maxScore, coordinatedIds, collect(DISTINCT s.id) AS similarIds
                RETURN a.id AS id, coordinated, maxScore, size(similarIds) AS similar,
                       size([x IN similarIds WHERE NOT x IN coordinatedIds]) + coordinated + a.replyCount AS degree
                ORDER BY a.firstReplySeconds
                """)) {
            String account = r.getString("id");
            long component = components.getOrDefault(account, -1L);
            var sim = simByComponent.get(component);
            result.put(account, new GraphFeatures(account, component, componentSizes.getOrDefault(component, 1),
                    sim == null ? 0 : round(sim[0] / sim[1]), num(r, "coordinated").intValue(),
                    r.getValue("maxScore") == null ? 0 : num(r, "maxScore").doubleValue(),
                    num(r, "similar").intValue(), num(r, "degree").intValue()));
        }
        return result;
    }

    public Optional<GraphView> graphView(String id) {
        var meta = falkor.read(id, """
                MATCH (n:Analysis)-[:ANALYZES]->(p:Post)
                RETURN properties(n) AS analysis, properties(p) AS post
                """);
        if (meta.isEmpty()) return Optional.empty();
        Map<String, Object> analysis = meta.getFirst().getValue("analysis");
        Map<String, Object> post = meta.getFirst().getValue("post");
        if (!"COMPLETE".equals(analysis.get("status"))) return Optional.empty();

        var accounts = falkor.read(id, "MATCH (a:Account) WITH a ORDER BY a.firstReplySeconds " + ACCOUNT_PROJECTION)
                .stream().map(GraphQueryService::account).toList();

        var edges = new ArrayList<GraphView.AccountEdge>();
        for (var r : falkor.read(id, """
                MATCH (a:Account)-[:POSTED]->(:Reply)-[s:SIMILAR_TO]-(:Reply)<-[:POSTED]-(b:Account)
                WHERE a.id < b.id
                RETURN a.id AS source, b.id AS target, max(s.similarity) AS weight, min(s.timeDifferenceSeconds) AS dt
                """)) {
            edges.add(new GraphView.AccountEdge(r.getString("source"), r.getString("target"), "SIMILAR",
                    num(r, "weight").doubleValue(), num(r, "dt").longValue()));
        }
        for (var r : falkor.read(id, """
                MATCH (a:Account)-[k:COORDINATED_WITH]->(b:Account)
                RETURN a.id AS source, b.id AS target, k.score AS weight, k.timeDifferenceSeconds AS dt
                """)) {
            edges.add(new GraphView.AccountEdge(r.getString("source"), r.getString("target"), "COORDINATED",
                    num(r, "weight").doubleValue(), num(r, "dt").longValue()));
        }

        var clusters = clusters(id);
        var counts = falkor.read(id, """
                OPTIONAL MATCH (r:Reply) WITH count(r) AS replies
                OPTIONAL MATCH (:Reply)-[s:SIMILAR_TO]->(:Reply) WITH replies, count(s) AS similar
                OPTIONAL MATCH (:Account)-[k:COORDINATED_WITH]->(:Account) WITH replies, similar, count(k) AS coordinated
                OPTIONAL MATCH (p:Pattern)
                RETURN replies, similar, coordinated, count(p) AS patterns
                """).getFirst();

        return Optional.of(new GraphView(
                new GraphView.AnalysisInfo(id, Boolean.TRUE.equals(analysis.get("demo")),
                        (String) analysis.get("provider"), (String) analysis.get("createdAt"),
                        Objects.requireNonNullElse((String) analysis.get("kind"), Conversation.THREAD)),
                new GraphView.PostView((String) post.get("id"), (String) post.get("author"), (String) post.get("text"),
                        (String) post.get("createdAt")),
                null,
                new GraphView.Stats(num(counts, "replies").intValue(), accounts.size(),
                        num(analysis, "comparisons").intValue(), num(counts, "similar").intValue(),
                        num(counts, "coordinated").intValue(), clusters.size(), num(counts, "patterns").intValue()),
                new GraphView.ScoreView(dbl(analysis, "deadInternetScore"), dbl(analysis, "automationShare"),
                        dbl(analysis, "coordinatedShare"), dbl(analysis, "duplicateShare"),
                        dbl(analysis, "largeClusterShare")),
                accounts, edges, clusters));
    }

    public List<ClusterView> clusters(String id) {
        return falkor.read(id, "MATCH (c:Cluster) " + CLUSTER_PROJECTION).stream().map(GraphQueryService::cluster).toList();
    }

    public Optional<AccountDetail> account(String id, String accountId) {
        var rows = falkor.read(id, "MATCH (a:Account {id: $id}) " + ACCOUNT_PROJECTION, Map.of("id", accountId));
        if (rows.isEmpty()) return Optional.empty();
        var account = account(rows.getFirst());

        var similar = new ArrayList<AccountDetail.SimilarReply>();
        for (var r : falkor.read(id, """
                MATCH (a:Account {id: $id})-[:POSTED]->(mine:Reply)-[s:SIMILAR_TO]-(theirs:Reply)<-[:POSTED]-(b:Account)
                WHERE b <> a
                RETURN mine.id AS replyId, b.id AS accountId, b.username AS username, theirs.text AS text,
                       s.similarity AS similarity, s.timeDifferenceSeconds AS dt
                ORDER BY similarity DESC, dt ASC LIMIT 5
                """, Map.of("id", accountId))) {
            similar.add(new AccountDetail.SimilarReply(r.getString("replyId"), r.getString("accountId"),
                    r.getString("username"), r.getString("text"), num(r, "similarity").doubleValue(),
                    num(r, "dt").longValue()));
        }

        var coordinated = new ArrayList<AccountDetail.Neighbor>();
        for (var r : falkor.read(id, """
                MATCH (a:Account {id: $id})-[k:COORDINATED_WITH]-(b:Account)
                RETURN b.id AS accountId, b.username AS username, k.score AS score, k.timeDifferenceSeconds AS dt
                ORDER BY score DESC
                """, Map.of("id", accountId))) {
            coordinated.add(new AccountDetail.Neighbor(r.getString("accountId"), r.getString("username"),
                    num(r, "score").doubleValue(), num(r, "dt").longValue()));
        }

        int reach = num(falkor.read(id, """
                MATCH (a:Account {id: $id})
                OPTIONAL MATCH (a)-[:COORDINATED_WITH*1..2]-(b:Account) WHERE b <> a
                RETURN count(DISTINCT b) AS reach
                """, Map.of("id", accountId)).getFirst(), "reach").intValue();

        ClusterView cluster = account.clusterId() == null ? null
                : cluster(falkor.read(id, "MATCH (c:Cluster {id: $cluster}) " + CLUSTER_PROJECTION,
                Map.of("cluster", account.clusterId())).getFirst());
        return Optional.of(new AccountDetail(account, similar, coordinated, reach, cluster));
    }

    public Optional<ClusterDetail> cluster(String id, String clusterId) {
        var rows = falkor.read(id, "MATCH (c:Cluster {id: $cluster}) " + CLUSTER_PROJECTION, Map.of("cluster", clusterId));
        if (rows.isEmpty()) return Optional.empty();
        var cluster = cluster(rows.getFirst());
        var members = new ArrayList<ClusterDetail.Member>();
        for (var r : falkor.read(id, """
                MATCH (a:Account)-[:MEMBER_OF]->(:Cluster {id: $cluster})
                RETURN a.id AS id, a.username AS username, a.classification AS classification,
                       a.automationLikelihood AS automation, a.coordinationLikelihood AS coordination
                ORDER BY automation DESC
                """, Map.of("cluster", clusterId))) {
            members.add(new ClusterDetail.Member(r.getString("id"), r.getString("username"),
                    r.getString("classification"), num(r, "automation").doubleValue(),
                    num(r, "coordination").doubleValue()));
        }
        int internal = num(falkor.read(id, """
                MATCH (:Cluster {id: $cluster})<-[:MEMBER_OF]-(a:Account)-[k:COORDINATED_WITH]->(b:Account)-[:MEMBER_OF]->(:Cluster {id: $cluster})
                RETURN count(k) AS edges
                """, Map.of("cluster", clusterId)).getFirst(), "edges").intValue();
        int n = members.size();
        double density = n < 2 ? 0 : round(internal / (n * (n - 1) / 2.0));
        return Optional.of(new ClusterDetail(cluster, members, internal, density));
    }

    /**
     * Shortest path through behavioral relationships only. REPLIES_TO is excluded because every account shares the
     * original post, which would make every pair trivially two hops apart.
     */
    public GraphPath path(String id, String fromAccount, String toAccount) {
        // FalkorDB's shortestPath() is directed-only; algo.SPpaths traverses in both directions.
        var rows = falkor.read(id, """
                MATCH (a:Account {id: $from}), (b:Account {id: $to})
                CALL algo.SPpaths({sourceNode: a, targetNode: b, relTypes: ['POSTED', 'SIMILAR_TO', 'COORDINATED_WITH'],
                                   relDirection: 'both', maxLen: 12})
                YIELD path
                RETURN [n IN nodes(path) | {label: labels(n)[0], id: n.id, username: n.username, text: n.text, account: n.account}] AS nodes,
                       [r IN relationships(path) | {type: type(r), from: startNode(r).id, to: endNode(r).id,
                            similarity: r.similarity, score: r.score, dt: r.timeDifferenceSeconds}] AS rels
                LIMIT 1
                """, Map.of("from", fromAccount, "to", toAccount));
        if (rows.isEmpty()) return new GraphPath(false, List.of(), List.of());
        List<Map<String, Object>> nodes = rows.getFirst().getValue("nodes");
        List<Map<String, Object>> rels = rows.getFirst().getValue("rels");
        var steps = nodes.stream().map(n -> "Account".equals(n.get("label"))
                ? new GraphPath.Step("Account", (String) n.get("id"), "@" + n.get("username"), (String) n.get("id"))
                : new GraphPath.Step("Reply", (String) n.get("id"), (String) n.get("text"), (String) n.get("account")))
                .toList();
        var links = rels.stream().map(r -> new GraphPath.Link((String) r.get("type"), (String) r.get("from"),
                (String) r.get("to"), optDouble(r.get("similarity")), optDouble(r.get("score")),
                r.get("dt") == null ? null : ((Number) r.get("dt")).longValue())).toList();
        return new GraphPath(true, steps, links);
    }

    private static AccountNode account(Record r) {
        Map<String, Object> a = r.getValue("props");
        List<Map<String, Object>> replies = r.getValue("replies");
        return new AccountNode((String) a.get("id"), (String) a.get("username"), optInt(a.get("accountAgeDays")),
                optInt(a.get("followers")), optInt(a.get("following")), optInt(a.get("totalPosts")),
                num(a, "replyCount").intValue(), num(a, "firstReplySeconds").longValue(),
                (String) a.get("classification"), dbl(a, "automationLikelihood"), dbl(a, "coordinationLikelihood"),
                dbl(a, "confidence"), (String) a.get("classificationSource"), strings(a.get("signalsForAutomation")),
                strings(a.get("signalsAgainstAutomation")), strings(a.get("coordinationSignals")),
                (String) a.get("summary"), num(a, "degree").intValue(), (String) a.get("clusterId"),
                num(a, "coordinatedAccounts").intValue(),
                a.get("category") instanceof String c && !c.isBlank() ? c : null,
                replies.stream().filter(x -> x.get("id") != null).map(x -> new AccountNode.ReplyView(
                        (String) x.get("id"), (String) x.get("text"), num(x, "secondsAfterParent").longValue(),
                        Boolean.TRUE.equals(x.get("duplicate")))).toList());
    }

    private static ClusterView cluster(Record r) {
        Map<String, Object> c = r.getValue("props");
        return new ClusterView((String) c.get("id"), num(c, "index").intValue(), strings(r.getValue("members")),
                dbl(c, "averageAutomation"), dbl(c, "averageCoordination"), dbl(c, "averageSimilarity"),
                num(c, "timeSpanSeconds").longValue(), num(c, "firstReplySeconds").longValue(),
                strings(r.getValue("patterns")));
    }

    private static Number num(Record r, String key) {
        Object value = r.getValue(key);
        return value instanceof Number n ? n : 0;
    }

    private static Number num(Map<String, Object> map, String key) {
        return map.get(key) instanceof Number n ? n : 0;
    }

    private static double dbl(Map<String, Object> map, String key) {
        return num(map, key).doubleValue();
    }

    private static Integer optInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Double optDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(x -> x != null).map(Object::toString).toList();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
