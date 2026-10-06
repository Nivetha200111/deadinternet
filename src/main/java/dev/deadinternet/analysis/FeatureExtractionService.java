package dev.deadinternet.analysis;

import dev.deadinternet.model.Conversation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;

@Service
public class FeatureExtractionService {

    /** Normalized texts at or above this similarity count as near-duplicates. */
    static final double DUPLICATE_SIMILARITY = 0.93;

    private final TextNormalizationService normalization;
    private final TextSimilarityService similarity;
    private final RepeatedPhraseService phrases;
    private final TimingAnalysisService timing;

    public FeatureExtractionService(TextNormalizationService normalization, TextSimilarityService similarity,
                                    RepeatedPhraseService phrases, TimingAnalysisService timing) {
        this.normalization = normalization;
        this.similarity = similarity;
        this.phrases = phrases;
        this.timing = timing;
    }

    public ConversationFeatures extract(Conversation conversation) {
        var replies = conversation.replies();
        int n = replies.size();
        var tokenized = replies.stream().map(r -> normalization.tokens(r.text())).toList();
        var normalized = replies.stream().map(r -> normalization.normalize(r.text())).toList();
        double[][] matrix = similarity.matrix(tokenized);
        double threshold = similarity.edgeThreshold();

        var tokensByReply = new LinkedHashMap<String, List<String>>();
        var accountByReply = new LinkedHashMap<String, String>();
        for (int i = 0; i < n; i++) {
            tokensByReply.put(replies.get(i).id(), tokenized.get(i));
            accountByReply.put(replies.get(i).id(), replies.get(i).author().id());
        }
        var patterns = phrases.detect(tokensByReply, accountByReply);
        var timings = timing.analyze(conversation.post(), replies, matrix, threshold);

        var features = new LinkedHashMap<String, ReplyFeatures>();
        for (int i = 0; i < n; i++) {
            final int row = i;
            var reply = replies.get(i);
            var others = IntStream.range(0, n).filter(j -> j != row).boxed()
                    .sorted(Comparator.comparingDouble(j -> -matrix[row][j])).toList();
            var neighbors = others.stream().filter(j -> matrix[row][j] >= threshold).toList();
            double max = others.isEmpty() ? 0 : matrix[row][others.getFirst()];
            boolean duplicate = others.stream().anyMatch(j ->
                    matrix[row][j] >= DUPLICATE_SIMILARITY || normalized.get(row).equals(normalized.get(j)));
            var patternIds = new ArrayList<String>();
            for (var p : patterns) if (p.replyIds().contains(reply.id())) patternIds.add(p.id());
            features.put(reply.id(), new ReplyFeatures(
                    reply.id(), reply.author().id(), normalized.get(i), tokenized.get(i).size(),
                    TextSimilarityService.round(normalization.lexicalDiversity(tokenized.get(i))),
                    TextSimilarityService.round(max),
                    others.isEmpty() ? null : replies.get(others.getFirst()).id(),
                    TextSimilarityService.round(neighbors.stream().mapToDouble(j -> matrix[row][j]).average().orElse(0)),
                    neighbors.size(), duplicate,
                    neighbors.stream().limit(5).map(j -> replies.get(j).id()).toList(),
                    List.copyOf(patternIds), timings.get(reply.id())));
        }
        var matrixById = new SimilarityMatrix(replies.stream().map(r -> r.id()).toList(), matrix);
        return new ConversationFeatures(features, similarity.edges(replies, matrix), patterns, n * (n - 1) / 2, matrixById);
    }
}
