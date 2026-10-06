package dev.deadinternet.analysis;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** TF-IDF weighted cosine similarity, fitted on the replies of a single conversation. */
@Component
public class TfIdfSimilarityModel implements SimilarityModel {

    @Override
    public double[][] pairwise(List<List<String>> texts) {
        int n = texts.size();
        var documentFrequency = new HashMap<String, Integer>();
        for (var tokens : texts) {
            for (var term : new HashSet<>(tokens)) documentFrequency.merge(term, 1, Integer::sum);
        }
        var vectors = texts.stream().map(tokens -> vector(tokens, documentFrequency, n)).toList();
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) {
            matrix[i][i] = vectors.get(i).isEmpty() ? 0 : 1;
            for (int j = i + 1; j < n; j++) {
                matrix[i][j] = matrix[j][i] = cosine(vectors.get(i), vectors.get(j));
            }
        }
        return matrix;
    }

    private Map<String, Double> vector(List<String> tokens, Map<String, Integer> df, int documents) {
        var termFrequency = new HashMap<String, Integer>();
        tokens.forEach(t -> termFrequency.merge(t, 1, Integer::sum));
        var weights = new HashMap<String, Double>();
        double norm = 0;
        for (var entry : termFrequency.entrySet()) {
            double idf = Math.log((1.0 + documents) / (1.0 + df.get(entry.getKey()))) + 1;
            double weight = entry.getValue() * idf;
            weights.put(entry.getKey(), weight);
            norm += weight * weight;
        }
        double length = Math.sqrt(norm);
        weights.replaceAll((term, weight) -> weight / length);
        return weights;
    }

    private double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        var small = a.size() <= b.size() ? a : b;
        var large = small == a ? b : a;
        double dot = 0;
        for (var entry : small.entrySet()) dot += entry.getValue() * large.getOrDefault(entry.getKey(), 0.0);
        return Math.min(1, Math.max(0, dot));
    }
}
