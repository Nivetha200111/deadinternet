package dev.deadinternet.dto;

import java.util.List;

public record AccountNode(String id, String username, Integer accountAgeDays, Integer followers, Integer following,
                          Integer totalPosts, int replyCount, long firstReplySeconds, String classification,
                          double automationLikelihood, double coordinationLikelihood, double confidence,
                          String classificationSource, List<String> signalsForAutomation,
                          List<String> signalsAgainstAutomation, List<String> coordinationSignals, String summary,
                          int degree, String clusterId, int coordinatedAccounts, List<ReplyView> replies) {

    public record ReplyView(String id, String text, long secondsAfterParent, boolean duplicate) {}
}
