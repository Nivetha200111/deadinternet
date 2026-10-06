package dev.deadinternet.dto;

import java.util.List;

/** Everything the visualization needs for one analysis, read from FalkorDB. */
public record GraphView(AnalysisInfo analysis, PostView post, Thresholds thresholds, Stats stats, ScoreView score,
                        List<AccountNode> accounts, List<AccountEdge> edges, List<ClusterView> clusters) {

    public GraphView withThresholds(Thresholds value) {
        return new GraphView(analysis, post, value, stats, score, accounts, edges, clusters);
    }

    /** @param kind "thread" or "feed" */
    public record AnalysisInfo(String id, boolean demo, String provider, String createdAt, String kind) {}

    public record PostView(String id, String author, String text, String createdAt) {}

    public record Thresholds(double human, double automation) {}

    public record Stats(int replies, int accounts, int comparisons, int similarityEdges, int coordinationEdges,
                        int clusters, int patterns) {}

    public record ScoreView(double score, double automationShare, double coordinatedAccountShare,
                            double duplicateReplyShare, double largeClusterShare) {}

    /** Account-level relationship for drawing: SIMILAR aggregates reply SIMILAR_TO edges; COORDINATED is direct. */
    public record AccountEdge(String source, String target, String type, double weight, long timeDifferenceSeconds) {}
}
