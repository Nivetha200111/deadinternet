package dev.deadinternet.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pairwise reply similarities for one conversation, addressable by reply id. */
public final class SimilarityMatrix {

    private final Map<String, Integer> index = new HashMap<>();
    private final double[][] values;

    public SimilarityMatrix(List<String> replyIds, double[][] values) {
        for (int i = 0; i < replyIds.size(); i++) index.put(replyIds.get(i), i);
        this.values = values;
    }

    public double get(String replyA, String replyB) {
        return values[index.get(replyA)][index.get(replyB)];
    }
}
