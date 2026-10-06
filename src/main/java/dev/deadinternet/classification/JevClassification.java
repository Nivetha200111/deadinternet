package dev.deadinternet.classification;

import java.util.List;

/** JEV's response for one account. All likelihoods are probabilistic signals in [0, 1], not verdicts. */
public record JevClassification(Classification classification, double automationLikelihood,
                                double coordinationLikelihood, double confidence,
                                List<String> signalsForAutomation, List<String> signalsAgainstAutomation,
                                List<String> coordinationSignals, String summary) {}
