package dev.deadinternet.analysis;

/**
 * @param timingRegularity 1 = perfectly evenly spaced similar replies, 0 = irregular; null when fewer than four
 *                         similar replies exist and regularity cannot be estimated
 */
public record TimingFeatures(long secondsAfterParent, int similarRepliesWithin30Seconds,
                             int similarRepliesWithin60Seconds, boolean inBurst, Double timingRegularity) {}
