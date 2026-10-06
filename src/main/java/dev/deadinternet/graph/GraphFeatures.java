package dev.deadinternet.graph;

/**
 * Features read back from FalkorDB after the reply and relationship graph is written.
 *
 * @param componentId    weakly connected component over COORDINATED_WITH (algo.WCC)
 * @param componentSize  accounts in that component (1 when the account has no coordination edges)
 * @param degree         reply count plus distinct accounts reachable through SIMILAR_TO or COORDINATED_WITH
 */
public record GraphFeatures(String accountId, long componentId, int componentSize, double componentSimilarity,
                            int coordinatedAccounts, double maxCoordinationScore, int similarAccounts, int degree) {}
