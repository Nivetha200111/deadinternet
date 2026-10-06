package dev.deadinternet.classification;

import java.util.List;

/** A classification as stored on the Account node, including where it came from. */
public record ClassificationResult(Classification classification, double automationLikelihood,
                                   double coordinationLikelihood, double confidence,
                                   List<String> signalsForAutomation, List<String> signalsAgainstAutomation,
                                   List<String> coordinationSignals, String summary, ClassificationSource source) {}
