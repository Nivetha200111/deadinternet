package dev.deadinternet.classification;

/** A classifier that turns structured account context into probabilistic behavioral scores. */
public interface JevClassifier {
    JevClassification classify(JevClassificationRequest request);
}
