package dev.deadinternet.analysis;

import dev.deadinternet.model.Post;
import dev.deadinternet.model.Reply;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Timing features. Reply speed alone is not treated as evidence of automation; timing only matters in combination
 * with similar text from other accounts.
 */
@Service
public class TimingAnalysisService {

    /** A reply is in a burst when at least this many similar replies landed within 60 seconds of it. */
    static final int BURST_MIN_NEIGHBORS = 2;

    public Map<String, TimingFeatures> analyze(Post post, List<Reply> replies, double[][] similarity, double threshold) {
        var result = new HashMap<String, TimingFeatures>();
        for (int i = 0; i < replies.size(); i++) {
            var reply = replies.get(i);
            int within30 = 0, within60 = 0;
            var similarTimes = new ArrayList<Long>();
            similarTimes.add(reply.createdAt().getEpochSecond());
            for (int j = 0; j < replies.size(); j++) {
                if (i == j || similarity[i][j] < threshold) continue;
                long dt = Math.abs(Duration.between(reply.createdAt(), replies.get(j).createdAt()).toSeconds());
                if (dt <= 30) within30++;
                if (dt <= 60) within60++;
                similarTimes.add(replies.get(j).createdAt().getEpochSecond());
            }
            long afterParent = Math.max(0, Duration.between(post.createdAt(), reply.createdAt()).toSeconds());
            result.put(reply.id(), new TimingFeatures(afterParent, within30, within60,
                    within60 >= BURST_MIN_NEIGHBORS, regularity(similarTimes)));
        }
        return result;
    }

    /** 1 - coefficient of variation of inter-arrival gaps, clamped to [0, 1]; null with fewer than four events. */
    public Double regularity(List<Long> epochSeconds) {
        if (epochSeconds.size() < 4) return null;
        var sorted = epochSeconds.stream().sorted().toList();
        var gaps = new ArrayList<Long>();
        for (int i = 1; i < sorted.size(); i++) gaps.add(sorted.get(i) - sorted.get(i - 1));
        double mean = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean == 0) return 1.0;
        double variance = gaps.stream().mapToDouble(g -> (g - mean) * (g - mean)).average().orElse(0);
        double cv = Math.sqrt(variance) / mean;
        return Math.round(Math.max(0, Math.min(1, 1 - cv)) * 1000) / 1000.0;
    }
}
