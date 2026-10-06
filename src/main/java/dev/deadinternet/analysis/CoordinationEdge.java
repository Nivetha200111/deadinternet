package dev.deadinternet.analysis;

/**
 * Account-level coordination evidence, derived from the closest pair of replies the two accounts posted.
 * {@code sourceAccountId} is always the lexicographically smaller id so each pair appears once.
 */
public record CoordinationEdge(String sourceAccountId, String targetAccountId, double score, double textSimilarity,
                               double timingProximity, double sharedPatternScore, long timeDifferenceSeconds,
                               String sourceReplyId, String targetReplyId) {}
