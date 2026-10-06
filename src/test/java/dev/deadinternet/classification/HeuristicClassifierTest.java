package dev.deadinternet.classification;

import org.junit.jupiter.api.Test;

import static dev.deadinternet.Fixtures.account;
import static dev.deadinternet.Fixtures.established;
import static dev.deadinternet.Fixtures.young;
import static dev.deadinternet.classification.Requests.request;
import static org.assertj.core.api.Assertions.assertThat;

class HeuristicClassifierTest {

    private final HeuristicClassifier classifier = new HeuristicClassifier(new ClassificationThresholds(0.40, 0.65));

    @Test
    void humanCoordinationIsCoordinatedWithoutBeingAutomationLike() {
        // Established accounts posting a similar announcement together.
        var result = classifier.classify(request(established("org"), 0.78, false, 5, 6, 0.85, 7, 7));
        assertThat(result.coordinationLikelihood()).isGreaterThan(0.65);
        assertThat(result.automationLikelihood()).isLessThan(0.4);
        assertThat(result.classification()).isEqualTo(Classification.HUMAN_LIKE);
    }

    @Test
    void isolatedRepetitionIsAutomationLikeWithoutCoordination() {
        var spammy = account("rep", 30, 15, 1900, 4000);
        var result = classifier.classify(request(spammy, 1.0, true, 0, 0, 0, 1, 3));
        assertThat(result.automationLikelihood()).isGreaterThanOrEqualTo(0.65);
        assertThat(result.coordinationLikelihood()).isLessThan(0.25);
        assertThat(result.signalsForAutomation()).anyMatch(s -> s.contains("Near-duplicate"));
    }

    @Test
    void aNewAccountWithOriginalTextIsNotAutomationLike() {
        var result = classifier.classify(request(account("new", 12, 9, 41, 30), 0.15, false, 0, 0, 0, 1, 0));
        assertThat(result.classification()).isEqualTo(Classification.HUMAN_LIKE);
        assertThat(result.signalsAgainstAutomation()).anyMatch(s -> s.contains("distinct"));
    }

    @Test
    void metadataAloneCannotMakeAnAccountAutomationLike() {
        // Youngest possible account, extreme posting rate and follow ratio, but original text.
        var result = classifier.classify(request(account("meta", 1, 1, 5000, 50000), 0.2, false, 0, 0, 0, 1, 0));
        assertThat(result.classification()).isNotEqualTo(Classification.AUTOMATION_LIKE);
    }

    @Test
    void synchronizedNearDuplicatesFromYoungAccountsAreAutomationLikeAndCoordinated() {
        var result = classifier.classify(request(young("burst"), 0.97, true, 8, 11, 0.9, 12, 12));
        assertThat(result.classification()).isEqualTo(Classification.AUTOMATION_LIKE);
        assertThat(result.coordinationLikelihood()).isGreaterThan(0.8);
        assertThat(result.summary()).contains("not proof");
    }

    @Test
    void handleOnlyAccountsAreScoredOnTextTimingAndGraphSignals() {
        var burst = classifier.classify(request(dev.deadinternet.model.Account.handleOnly("x1", "x1"), 0.97, true, 8, 11, 0.9, 12, 12));
        assertThat(burst.classification()).isEqualTo(Classification.AUTOMATION_LIKE);
        assertThat(burst.summary()).contains("metadata was unavailable");

        var original = classifier.classify(request(dev.deadinternet.model.Account.handleOnly("x2", "x2"), 0.12, false, 0, 0, 0, 1, 0));
        assertThat(original.classification()).isEqualTo(Classification.HUMAN_LIKE);
        assertThat(original.signalsForAutomation()).noneMatch(sig -> sig.contains("days old") || sig.contains("per day"));
    }

    @Test
    void neverUsesDefinitiveLanguage() {
        var result = classifier.classify(request(young("burst"), 0.97, true, 8, 11, 0.9, 12, 12));
        assertThat(result.summary().toLowerCase()).doesNotContain("is a bot");
        assertThat(String.join(" ", result.signalsForAutomation()).toLowerCase()).doesNotContain("bot");
    }
}
