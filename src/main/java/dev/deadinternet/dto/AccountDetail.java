package dev.deadinternet.dto;

import java.util.List;

/** Account inspector data: the account plus its graph neighborhood, from FalkorDB traversals. */
public record AccountDetail(AccountNode account, List<SimilarReply> similarReplies, List<Neighbor> coordinatedWith,
                            int reachableWithinTwoHops, ClusterView cluster) {

    public record SimilarReply(String replyId, String otherAccountId, String otherUsername, String otherText,
                               double similarity, long timeDifferenceSeconds) {}

    public record Neighbor(String accountId, String username, double score, long timeDifferenceSeconds) {}
}
