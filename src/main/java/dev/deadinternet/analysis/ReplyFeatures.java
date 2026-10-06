package dev.deadinternet.analysis;

import java.util.List;

/** Deterministic, per-reply features computed in Java before any classifier is called. */
public record ReplyFeatures(String replyId, String accountId, String normalizedText, int tokenCount,
                            double lexicalDiversity, double maxTextSimilarity, String mostSimilarReplyId,
                            double averageNeighborSimilarity, int similarNeighborCount, boolean duplicate,
                            List<String> neighborReplyIds, List<String> patternIds, TimingFeatures timing) {}
