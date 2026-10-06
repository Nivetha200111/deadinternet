package dev.deadinternet.analysis;

public record SimilarityEdge(String sourceReplyId, String targetReplyId, double similarity, long timeDifferenceSeconds) {}
