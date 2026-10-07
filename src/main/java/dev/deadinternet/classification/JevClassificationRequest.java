package dev.deadinternet.classification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.deadinternet.analysis.TextSignals;
import dev.deadinternet.model.Account;
import dev.deadinternet.model.Conversation;

import java.util.List;

/**
 * Structured context sent to JEV for one account. Java derives every feature; JEV interprets them. Account metadata and
 * the ratios derived from it are null when the source (for example a scraped page) does not expose them.
 */
public record JevClassificationRequest(Account account, ReplyContext reply, Features features,
                                       List<String> neighborReplies, List<String> otherRepliesByAccount, String kind,
                                       TextSignals textSignals) {

    /**
     * @param kind        {@code Conversation.THREAD} (the default) or {@code Conversation.FEED}
     * @param textSignals spam markers measured in the account's text; {@link TextSignals#NONE} when not measured
     */
    public JevClassificationRequest {
        if (kind == null) kind = Conversation.THREAD;
        if (textSignals == null) textSignals = TextSignals.NONE;
    }

    public JevClassificationRequest(Account account, ReplyContext reply, Features features,
                                    List<String> neighborReplies, List<String> otherRepliesByAccount) {
        this(account, reply, features, neighborReplies, otherRepliesByAccount, Conversation.THREAD, TextSignals.NONE);
    }

    /** Feed posts come from unrelated accounts, so reply timing and neighbor similarity carry no meaning there. */
    @JsonIgnore
    public boolean isFeed() {
        return Conversation.FEED.equals(kind);
    }

    public record ReplyContext(String text, long secondsAfterParent) {}

    public record Features(double lexicalDiversity, double maxTextSimilarity, double averageClusterSimilarity,
                           int similarRepliesWithin30Seconds, int similarRepliesWithin60Seconds, int clusterSize,
                           int replyCount, boolean nearDuplicate, boolean inBurst, Double timingRegularity,
                           double selfSimilarity, Double followerFollowingRatio, Double postsPerDay,
                           List<String> repeatedPhrases, int repeatedPhraseAccountCount,
                           int coordinatedAccounts, double maxCoordinationScore) {}
}
