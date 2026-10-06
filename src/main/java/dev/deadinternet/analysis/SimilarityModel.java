package dev.deadinternet.analysis;

import java.util.List;

/**
 * Pairwise text similarity in [0, 1]. TF-IDF cosine is the v1 implementation; an embedding-based
 * model can be added later by providing another bean of this type.
 */
public interface SimilarityModel {
    double[][] pairwise(List<List<String>> tokenizedTexts);
}
