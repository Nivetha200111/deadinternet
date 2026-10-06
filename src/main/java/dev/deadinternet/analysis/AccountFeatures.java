package dev.deadinternet.analysis;

import dev.deadinternet.model.Account;

import java.util.List;

/**
 * Per-account features aggregated over every reply the account posted in the conversation.
 *
 * @param representativeReplyId the account's reply with the highest similarity to other accounts' replies
 * @param selfSimilarity        highest similarity between two of this account's own replies (0 with one reply)
 * @param timingRegularity      null when there is not enough data to estimate it
 */
public record AccountFeatures(Account account, List<String> replyIds, String representativeReplyId,
                              double lexicalDiversity, double maxTextSimilarity, double averageNeighborSimilarity,
                              boolean duplicate, int similarRepliesWithin30Seconds, int similarRepliesWithin60Seconds,
                              boolean inBurst, Double timingRegularity, long firstReplySecondsAfterParent,
                              double selfSimilarity, List<String> patternIds) {

    public int replyCount() {
        return replyIds.size();
    }
}
