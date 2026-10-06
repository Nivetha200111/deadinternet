package dev.deadinternet.dto;

import java.util.List;

public record ClusterView(String id, int index, List<String> accountIds, double averageAutomation,
                          double averageCoordination, double averageSimilarity, long timeSpanSeconds,
                          long firstReplySeconds, List<String> patterns) {}
