package dev.deadinternet.analysis;

import org.springframework.stereotype.Service;

/**
 * <pre>
 * score = 0.40 * automation-like account share
 *       + 0.20 * coordinated account share (accounts with at least one COORDINATED_WITH edge)
 *       + 0.25 * near-duplicate reply share
 *       + 0.15 * share of accounts in large clusters (size >= 5)
 * </pre>
 */
@Service
public class DeadInternetScoreService {

    static final double AUTOMATION_WEIGHT = 0.40;
    static final double COORDINATION_WEIGHT = 0.20;
    static final double DUPLICATE_WEIGHT = 0.25;
    static final double LARGE_CLUSTER_WEIGHT = 0.15;
    static final int LARGE_CLUSTER_SIZE = 5;

    public record Inputs(int accounts, int automationLikeAccounts, int coordinatedAccounts, int replies,
                         int duplicateReplies, int accountsInLargeClusters) {}

    public DeadInternetScore score(Inputs in) {
        if (in.accounts() == 0) return new DeadInternetScore(0, 0, 0, 0, 0);
        double automation = (double) in.automationLikeAccounts() / in.accounts();
        double coordinated = (double) in.coordinatedAccounts() / in.accounts();
        double duplicates = in.replies() == 0 ? 0 : (double) in.duplicateReplies() / in.replies();
        double largeClusters = (double) in.accountsInLargeClusters() / in.accounts();
        double score = AUTOMATION_WEIGHT * automation + COORDINATION_WEIGHT * coordinated
                + DUPLICATE_WEIGHT * duplicates + LARGE_CLUSTER_WEIGHT * largeClusters;
        return new DeadInternetScore(round(Math.min(1, score)), round(automation), round(coordinated),
                round(duplicates), round(largeClusters));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
