package dev.deadinternet.analysis;

import dev.deadinternet.model.Account;
import dev.deadinternet.model.Conversation;
import dev.deadinternet.model.Reply;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Rolls reply-level features up to the account, which is the unit the classifier and the graph view use. */
@Service
public class AccountFeatureService {

    public Map<String, AccountFeatures> aggregate(Conversation conversation, ConversationFeatures features) {
        var repliesByAccount = new LinkedHashMap<String, List<Reply>>();
        var accounts = new LinkedHashMap<String, Account>();
        for (var reply : conversation.replies()) {
            repliesByAccount.computeIfAbsent(reply.author().id(), k -> new ArrayList<>()).add(reply);
            accounts.putIfAbsent(reply.author().id(), reply.author());
        }
        var result = new LinkedHashMap<String, AccountFeatures>();
        repliesByAccount.forEach((accountId, replies) -> {
            var rf = replies.stream().map(r -> features.replies().get(r.id())).toList();
            var representative = rf.stream().max(Comparator.comparingDouble(ReplyFeatures::maxTextSimilarity)).orElseThrow();
            var regularities = rf.stream().map(f -> f.timing().timingRegularity()).filter(Objects::nonNull).toList();
            var patterns = new LinkedHashSet<String>();
            rf.forEach(f -> patterns.addAll(f.patternIds()));
            result.put(accountId, new AccountFeatures(
                    accounts.get(accountId),
                    replies.stream().map(Reply::id).toList(),
                    representative.replyId(),
                    TextSimilarityService.round(rf.stream().mapToDouble(ReplyFeatures::lexicalDiversity).average().orElse(0)),
                    representative.maxTextSimilarity(),
                    rf.stream().mapToDouble(ReplyFeatures::averageNeighborSimilarity).max().orElse(0),
                    rf.stream().anyMatch(ReplyFeatures::duplicate),
                    rf.stream().mapToInt(f -> f.timing().similarRepliesWithin30Seconds()).max().orElse(0),
                    rf.stream().mapToInt(f -> f.timing().similarRepliesWithin60Seconds()).max().orElse(0),
                    rf.stream().anyMatch(f -> f.timing().inBurst()),
                    regularities.isEmpty() ? null : regularities.stream().mapToDouble(Double::doubleValue).max().orElseThrow(),
                    rf.stream().mapToLong(f -> f.timing().secondsAfterParent()).min().orElse(0),
                    selfSimilarity(replies, features.similarity()),
                    List.copyOf(patterns)));
        });
        return result;
    }

    private double selfSimilarity(List<Reply> replies, SimilarityMatrix similarity) {
        double max = 0;
        for (int i = 0; i < replies.size(); i++) {
            for (int j = i + 1; j < replies.size(); j++) {
                max = Math.max(max, similarity.get(replies.get(i).id(), replies.get(j).id()));
            }
        }
        return TextSimilarityService.round(max);
    }
}
