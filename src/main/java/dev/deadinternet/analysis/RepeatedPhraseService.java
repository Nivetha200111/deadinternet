package dev.deadinternet.analysis;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds word trigrams shared by replies from at least {@code MIN_ACCOUNTS} accounts, then stitches trigrams that
 * occur in exactly the same replies into the longest contiguous phrase.
 */
@Service
public class RepeatedPhraseService {

    static final int MIN_ACCOUNTS = 3;
    private static final int MAX_PATTERNS = 30;
    private static final Set<String> STOPWORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "but", "by", "for",
            "from", "has", "have", "i", "if", "in", "is", "it", "its", "of", "on", "or", "so", "that", "the", "this",
            "to", "was", "we", "what", "will", "with", "you", "your", "they", "our", "my", "me", "do", "not", "just");

    private final TextNormalizationService normalization;

    public RepeatedPhraseService(TextNormalizationService normalization) {
        this.normalization = normalization;
    }

    /**
     * @param tokensByReply reply id → tokens (iteration order is preserved)
     * @param accountByReply reply id → account id
     */
    public List<PhrasePattern> detect(Map<String, List<String>> tokensByReply, Map<String, String> accountByReply) {
        var repliesByTrigram = new LinkedHashMap<String, Set<String>>();
        tokensByReply.forEach((replyId, tokens) -> {
            for (String trigram : normalization.shingles(tokens, 3)) {
                if (isContentful(trigram)) repliesByTrigram.computeIfAbsent(trigram, k -> new TreeSet<>()).add(replyId);
            }
        });

        // Group frequent trigrams by the exact set of replies they appear in.
        var trigramsByReplySet = new LinkedHashMap<Set<String>, List<String>>();
        repliesByTrigram.forEach((trigram, replies) -> {
            long accounts = replies.stream().map(accountByReply::get).distinct().count();
            if (accounts >= MIN_ACCOUNTS) trigramsByReplySet.computeIfAbsent(replies, k -> new ArrayList<>()).add(trigram);
        });

        var patterns = new ArrayList<PhrasePattern>();
        trigramsByReplySet.forEach((replies, trigrams) -> {
            var representative = tokensByReply.get(replies.iterator().next());
            int accounts = (int) replies.stream().map(accountByReply::get).distinct().count();
            for (String phrase : stitch(representative, trigrams)) {
                patterns.add(new PhrasePattern(null, phrase, replies, accounts));
            }
        });

        patterns.sort(Comparator.comparingInt(PhrasePattern::accountCount).reversed()
                .thenComparing(p -> -p.text().length()));
        var result = new ArrayList<PhrasePattern>();
        for (var p : patterns.subList(0, Math.min(MAX_PATTERNS, patterns.size()))) {
            result.add(new PhrasePattern("pattern_" + (result.size() + 1), p.text(), p.replyIds(), p.accountCount()));
        }
        return result;
    }

    /** Joins trigrams that are adjacent in the representative reply into maximal phrases. */
    private List<String> stitch(List<String> tokens, List<String> trigrams) {
        var covered = new boolean[tokens.size()];
        var starts = new HashMap<Integer, Boolean>();
        var wanted = Set.copyOf(trigrams);
        for (int i = 0; i + 3 <= tokens.size(); i++) {
            if (wanted.contains(String.join(" ", tokens.subList(i, i + 3)))) {
                starts.put(i, true);
                for (int k = i; k < i + 3; k++) covered[k] = true;
            }
        }
        var phrases = new ArrayList<String>();
        int i = 0;
        while (i < tokens.size()) {
            if (!covered[i]) { i++; continue; }
            int j = i;
            while (j < tokens.size() && covered[j]) j++;
            phrases.add(String.join(" ", tokens.subList(i, j)));
            i = j;
        }
        return phrases;
    }

    private static boolean isContentful(String trigram) {
        for (String token : trigram.split(" ")) if (!STOPWORDS.contains(token)) return true;
        return false;
    }
}
