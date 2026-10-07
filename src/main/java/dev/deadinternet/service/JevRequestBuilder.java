package dev.deadinternet.service;

import dev.deadinternet.analysis.AccountFeatures;
import dev.deadinternet.analysis.ConversationFeatures;
import dev.deadinternet.analysis.PhrasePattern;
import dev.deadinternet.analysis.TextSignalService;
import dev.deadinternet.classification.JevClassificationRequest;
import dev.deadinternet.graph.GraphFeatures;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.model.Reply;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Assembles the structured context JEV receives: account metadata, text/timing features and graph features. */
@Component
public class JevRequestBuilder {

    private final TextSignalService textSignals;

    public JevRequestBuilder(TextSignalService textSignals) {
        this.textSignals = textSignals;
    }

    public List<JevClassificationRequest> build(Conversation conversation, ConversationFeatures features,
                                                Map<String, AccountFeatures> accounts, Map<String, GraphFeatures> graph) {
        var replies = conversation.replies().stream().collect(Collectors.toMap(Reply::id, Function.identity()));
        var patterns = features.patterns().stream().collect(Collectors.toMap(PhrasePattern::id, Function.identity()));
        return accounts.values().stream().map(a -> {
            var g = graph.get(a.account().id());
            var representative = replies.get(a.representativeReplyId());
            var representativeFeatures = features.replies().get(a.representativeReplyId());
            var accountPatterns = a.patternIds().stream().map(patterns::get)
                    .sorted(Comparator.comparingInt(PhrasePattern::accountCount).reversed()).toList();
            var neighborTexts = new LinkedHashSet<String>();
            for (String neighborId : representativeFeatures.neighborReplyIds()) {
                var neighbor = replies.get(neighborId);
                if (!neighbor.author().id().equals(a.account().id())) neighborTexts.add(neighbor.text());
            }
            var others = a.replyIds().stream().filter(id -> !id.equals(a.representativeReplyId()))
                    .map(id -> replies.get(id).text()).toList();
            return new JevClassificationRequest(
                    a.account(),
                    new JevClassificationRequest.ReplyContext(representative.text(),
                            representativeFeatures.timing().secondsAfterParent()),
                    new JevClassificationRequest.Features(
                            a.lexicalDiversity(), a.maxTextSimilarity(), g.componentSimilarity(),
                            a.similarRepliesWithin30Seconds(), a.similarRepliesWithin60Seconds(), g.componentSize(),
                            a.replyCount(), a.duplicate(), a.inBurst(), a.timingRegularity(), a.selfSimilarity(),
                            round(a.account().followerFollowingRatio()), round(a.account().postsPerDay()),
                            accountPatterns.stream().map(PhrasePattern::text).limit(3).toList(),
                            accountPatterns.isEmpty() ? 0 : accountPatterns.getFirst().accountCount(),
                            g.coordinatedAccounts(), g.maxCoordinationScore()),
                    neighborTexts.stream().limit(4).toList(),
                    others, conversation.kind(),
                    // A feed has no parent post to stay on topic with; its root is a synthetic stand-in for the feed.
                    textSignals.analyze(a.replyIds().stream().map(id -> replies.get(id).text()).toList(),
                            conversation.isFeed() ? null : conversation.post().text()));
        }).toList();
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 1000) / 1000.0;
    }
}
