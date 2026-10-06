package dev.deadinternet.analysis;

/**
 * Experimental thread-level heuristic. Not ground truth and not a probability that a conversation is fake.
 */
public record DeadInternetScore(double score, double automationShare, double coordinatedAccountShare,
                                double duplicateReplyShare, double largeClusterShare) {}
