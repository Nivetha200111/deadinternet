package dev.deadinternet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunable analysis knobs live here so thresholds and weights stay configurable. */
@ConfigurationProperties("lens")
public record LensProperties(Similarity similarity, Coordination coordination, Thresholds thresholds,
                             Jev jev, Falkor falkor, Capture capture, int maxStoredAnalyses) {

    /** Reply-to-reply similarity edges are only created at or above {@code edgeThreshold}. */
    public record Similarity(double edgeThreshold, int maxEdgesPerReply) {}

    /** coordinationScore = text * textSimilarity + timing * timingProximity + pattern * sharedPatternScore */
    public record Coordination(double threshold, double textWeight, double timingWeight, double patternWeight,
                               int timingWindowSeconds, int minClusterSize) {}

    /** automationLikelihood below {@code human} is human_like, below {@code automation} uncertain, else automation_like. */
    public record Thresholds(double human, double automation) {}

    /**
     * provider: auto (Typesafe for its API host, otherwise HTTP when url is set), typesafe, http, or heuristic.
     */
    public record Jev(String provider, String url, String token, int timeoutSeconds, int parallelism, String model) {}

    /** password is optional; managed FalkorDB instances require one. */
    public record Falkor(String host, int port, String username, String password) {}

    /**
     * Live capture of X / LinkedIn threads in a browser the server drives.
     *
     * @param profileDir persistent browser profile, so a login survives restarts (kept apart from the user's Chrome)
     * @param channel    installed browser to drive ("chrome"); blank uses Playwright's bundled Chromium
     * @param allowLocalPages development only: also accept http://localhost pages, such as the test-harness fixtures
     * @param chromePath Chrome executable for the plain (non-automated) sign-in window
     * @param enabled    false on hosted deployments: there is no logged-in browser, and strangers must not drive one
     */
    public record Capture(boolean enabled, String profileDir, String channel, boolean headless, int maxReplies,
                          int maxMinutes, boolean allowLocalPages, String chromePath) {}
}
