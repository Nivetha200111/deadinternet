package dev.deadinternet.classification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassificationThresholdsTest {

    private final ClassificationThresholds thresholds = new ClassificationThresholds(0.40, 0.65);

    @ParameterizedTest
    @CsvSource({"0.00, HUMAN_LIKE", "0.39, HUMAN_LIKE", "0.40, UNCERTAIN", "0.64, UNCERTAIN", "0.65, AUTOMATION_LIKE", "1.00, AUTOMATION_LIKE"})
    void defaultBands(double likelihood, Classification expected) {
        assertThat(thresholds.classify(likelihood)).isEqualTo(expected);
    }

    @Test
    void thresholdsAreConfigurable() {
        var aggressive = new ClassificationThresholds(0.2, 0.45);
        assertThat(aggressive.classify(0.5)).isEqualTo(Classification.AUTOMATION_LIKE);
        assertThat(thresholds.classify(0.5)).isEqualTo(Classification.UNCERTAIN);
    }

    @Test
    void rejectsInvertedOrOutOfRangeThresholds() {
        assertThatThrownBy(() -> new ClassificationThresholds(0.7, 0.4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassificationThresholds(-0.1, 0.4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassificationThresholds(0.4, 1.2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wireNamesMatchTheJevContract() {
        assertThat(Classification.fromWire("automation_like")).isEqualTo(Classification.AUTOMATION_LIKE);
        assertThat(Classification.HUMAN_LIKE.wire()).isEqualTo("human_like");
    }
}
