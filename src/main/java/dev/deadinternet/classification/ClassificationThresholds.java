package dev.deadinternet.classification;

import dev.deadinternet.config.LensProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Maps automation likelihood to a label: [0, human) human-like, [human, automation) uncertain, else automation-like. */
@Component
public class ClassificationThresholds {

    private final double human;
    private final double automation;

    @Autowired
    public ClassificationThresholds(LensProperties properties) {
        this(properties.thresholds().human(), properties.thresholds().automation());
    }

    public ClassificationThresholds(double human, double automation) {
        if (!(0 <= human && human <= automation && automation <= 1)) {
            throw new IllegalArgumentException("Thresholds must satisfy 0 <= human <= automation <= 1");
        }
        this.human = human;
        this.automation = automation;
    }

    public Classification classify(double automationLikelihood) {
        if (automationLikelihood < human) return Classification.HUMAN_LIKE;
        if (automationLikelihood < automation) return Classification.UNCERTAIN;
        return Classification.AUTOMATION_LIKE;
    }

    public double human() {
        return human;
    }

    public double automation() {
        return automation;
    }
}
