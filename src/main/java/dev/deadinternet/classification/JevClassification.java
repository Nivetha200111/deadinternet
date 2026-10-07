package dev.deadinternet.classification;

import java.util.List;

/**
 * JEV's response for one account. All likelihoods are probabilistic signals in [0, 1], not verdicts.
 *
 * @param category what kind of posting this is (see {@link PostCategory}); null when the classifier doesn't say
 */
public record JevClassification(Classification classification, double automationLikelihood,
                                double coordinationLikelihood, double confidence,
                                List<String> signalsForAutomation, List<String> signalsAgainstAutomation,
                                List<String> coordinationSignals, String summary, String category) {

    public JevClassification(Classification classification, double automationLikelihood, double coordinationLikelihood,
                             double confidence, List<String> signalsForAutomation, List<String> signalsAgainstAutomation,
                             List<String> coordinationSignals, String summary) {
        this(classification, automationLikelihood, coordinationLikelihood, confidence, signalsForAutomation,
                signalsAgainstAutomation, coordinationSignals, summary, null);
    }
}
