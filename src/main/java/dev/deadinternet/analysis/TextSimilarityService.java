package dev.deadinternet.analysis;

import dev.deadinternet.config.LensProperties;
import dev.deadinternet.model.Reply;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.IntStream;

@Service
public class TextSimilarityService {

    private final SimilarityModel model;
    private final double edgeThreshold;
    private final int maxEdgesPerReply;

    public TextSimilarityService(SimilarityModel model, LensProperties properties) {
        this.model = model;
        this.edgeThreshold = properties.similarity().edgeThreshold();
        this.maxEdgesPerReply = properties.similarity().maxEdgesPerReply();
    }

    public double[][] matrix(List<List<String>> tokenizedReplies) {
        return model.pairwise(tokenizedReplies);
    }

    public double edgeThreshold() {
        return edgeThreshold;
    }

    /**
     * Sparse similarity graph: each reply keeps at most {@code maxEdgesPerReply} neighbors at or above the
     * threshold (closest in time first among ties), which avoids a fully connected graph of duplicates.
     */
    public List<SimilarityEdge> edges(List<Reply> replies, double[][] similarity) {
        int n = replies.size();
        var kept = new LinkedHashSet<Long>();
        for (int i = 0; i < n; i++) {
            final int row = i;
            IntStream.range(0, n)
                    .filter(j -> j != row && similarity[row][j] >= edgeThreshold)
                    .boxed()
                    .sorted(Comparator.<Integer>comparingDouble(j -> -similarity[row][j])
                            .thenComparingLong(j -> seconds(replies.get(row), replies.get(j))))
                    .limit(maxEdgesPerReply)
                    .forEach(j -> kept.add(key(Math.min(row, j), Math.max(row, j), n)));
        }
        var edges = new ArrayList<SimilarityEdge>();
        for (long k : kept) {
            int i = (int) (k / n), j = (int) (k % n);
            edges.add(new SimilarityEdge(replies.get(i).id(), replies.get(j).id(), round(similarity[i][j]),
                    seconds(replies.get(i), replies.get(j))));
        }
        return edges;
    }

    private static long key(int i, int j, int n) {
        return (long) i * n + j;
    }

    static long seconds(Reply a, Reply b) {
        return Math.abs(Duration.between(a.createdAt(), b.createdAt()).toSeconds());
    }

    static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
