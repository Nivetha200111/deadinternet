package dev.deadinternet.analysis;

import java.util.List;

/** A behavioral cluster: a connected group of coordinated accounts. {@code index} starts at 1, largest first. */
public record DetectedCluster(String id, int index, List<String> accountIds) {}
