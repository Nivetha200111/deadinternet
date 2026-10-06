package dev.deadinternet.classification;

import dev.deadinternet.model.Account;

import java.util.List;

/**
 * Structured context sent to JEV for one account. Java derives every feature; JEV interprets them. Account metadata and
 * the ratios derived from it are null when the source (for example a scraped page) does not expose them.
 */
public record JevClassificationRequest(Account account, ReplyContext reply, Features features,
                                       List<String> neighborReplies, List<String> otherRepliesByAccount) {

    public record ReplyContext(String text, long secondsAfterParent) {}

    public record Features(double lexicalDiversity, double maxTextSimilarity, double averageClusterSimilarity,
                           int similarRepliesWithin30Seconds, int similarRepliesWithin60Seconds, int clusterSize,
                           int replyCount, boolean nearDuplicate, boolean inBurst, Double timingRegularity,
                           double selfSimilarity, Double followerFollowingRatio, Double postsPerDay,
                           List<String> repeatedPhrases, int repeatedPhraseAccountCount,
                           int coordinatedAccounts, double maxCoordinationScore) {}
}
