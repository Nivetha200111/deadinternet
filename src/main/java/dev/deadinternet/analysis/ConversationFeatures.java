package dev.deadinternet.analysis;

import java.util.List;
import java.util.Map;

public record ConversationFeatures(Map<String, ReplyFeatures> replies, List<SimilarityEdge> similarityEdges,
                                   List<PhrasePattern> patterns, int comparisons, SimilarityMatrix similarity) {}
