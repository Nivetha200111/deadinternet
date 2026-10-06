package dev.deadinternet.classification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transparent, deterministic stand-in for JEV. Every point of automation likelihood comes from a named signal that is
 * reported back, so the score is always explainable. Account metadata is capped at a minority of the total weight, and
 * reply speed only counts when it coincides with similar text from other accounts.
 */
public class HeuristicClassifier implements JevClassifier {

    private static final double BASE = 0.05;
    /** Maximum score reachable from text, timing and graph signals alone (everything except account metadata). */
    static final double NON_METADATA_WEIGHT = 0.76;

    private final ClassificationThresholds thresholds;

    public HeuristicClassifier(ClassificationThresholds thresholds) {
        this.thresholds = thresholds;
    }

    @Override
    public JevClassification classify(JevClassificationRequest request) {
        var f = request.features();
        var account = request.account();
        var signals = new ArrayList<String>();
        var counter = new ArrayList<String>();
        double score = BASE;

        // Textual repetition: the strongest automation signal available without platform data.
        double repetition = clamp((f.maxTextSimilarity() - 0.25) / 0.7);
        score += 0.28 * repetition;
        if (f.maxTextSimilarity() >= 0.5) {
            signals.add(pct(f.maxTextSimilarity()) + " text similarity with another reply");
        }
        if (f.nearDuplicate()) {
            score += 0.10;
            signals.add("Near-duplicate of another reply in this conversation");
        }
        // Speed alone is never a signal; only similar replies from other accounts arriving together count.
        if (f.inBurst()) {
            score += 0.10;
            signals.add(f.similarRepliesWithin60Seconds() + " similar replies posted within 60 seconds");
        }
        if (f.replyCount() > 1 && f.selfSimilarity() >= 0.8) {
            score += 0.10;
            signals.add("Posted the same reply " + f.replyCount() + " times (" + pct(f.selfSimilarity()) + " self-similarity)");
        }
        if (f.repeatedPhraseAccountCount() >= 3) {
            score += 0.06;
            signals.add("Uses a stock phrase shared by " + f.repeatedPhraseAccountCount() + " accounts");
        }
        if (f.timingRegularity() != null && f.timingRegularity() >= 0.85) {
            score += 0.05;
            signals.add("Similar replies arrive at evenly spaced intervals (" + pct(f.timingRegularity()) + " regularity)");
        }
        score += 0.05 * clamp((1 - f.lexicalDiversity()) / 0.4);
        if (f.lexicalDiversity() < 0.75) signals.add("Low lexical variation (" + pct(f.lexicalDiversity()) + ")");

        // Account metadata: at most 0.24 in total, so it can tip a score but never decide it. Often unavailable.
        Integer age = account.accountAgeDays();
        if (age != null) {
            score += 0.12 * clamp(1 - age / 365.0);
            if (age < 180) signals.add("Account is " + age + " days old");
        }
        if (account.following() != null && account.followers() != null
                && account.following() >= 5 * Math.max(1, account.followers()) && account.following() >= 200) {
            score += 0.05;
            signals.add("Follows " + account.following() + " accounts but has " + account.followers() + " followers");
        }
        if (f.postsPerDay() != null) {
            score += 0.07 * clamp((f.postsPerDay() - 15) / 45);
            if (f.postsPerDay() >= 25) {
                signals.add(String.format(Locale.ROOT, "Posts about %.0f times per day", f.postsPerDay()));
            }
        }

        // Without metadata the remaining signals are rescaled to the full range, so a scraped thread is not capped.
        if (!account.hasMetadata()) score /= NON_METADATA_WEIGHT;

        // Counter-signals scale the score down rather than subtracting, so evidence still differentiates accounts.
        double dampening = 0;
        if (age != null && age >= 1500) {
            dampening += 0.25;
            counter.add(String.format(Locale.ROOT, "Account history spans %.1f years", age / 365.0));
        } else if (age != null && age >= 730) {
            dampening += 0.12;
            counter.add("Account is more than two years old");
        }
        if (f.maxTextSimilarity() < 0.35) {
            dampening += 0.15;
            counter.add("Reply language is distinct from every other reply");
        }
        if (f.similarRepliesWithin60Seconds() == 0) counter.add("No similar replies posted around the same time");
        if (f.lexicalDiversity() >= 0.9 && f.maxTextSimilarity() < 0.5) {
            counter.add("Varied vocabulary (" + pct(f.lexicalDiversity()) + " lexical diversity)");
        }
        if (account.followers() != null && account.following() != null && age != null
                && account.followers() >= account.following() && age >= 365) {
            counter.add("Established audience (" + account.followers() + " followers)");
        }
        score *= 1 - Math.min(0.5, dampening);

        double automation = round(clamp(score, 0.01, 0.97));
        double coordination = coordination(f);
        var coordinationSignals = new ArrayList<String>();
        if (f.coordinatedAccounts() > 0) {
            coordinationSignals.add("Coordinated text and timing with " + f.coordinatedAccounts() + " other account"
                    + (f.coordinatedAccounts() == 1 ? "" : "s") + " (strongest link " + pct(f.maxCoordinationScore()) + ")");
        }
        if (f.clusterSize() >= 3) {
            coordinationSignals.add("Member of a " + f.clusterSize() + "-account group averaging "
                    + pct(f.averageClusterSimilarity()) + " text similarity");
        }

        int evidence = signals.size() + counter.size();
        double confidence = round(clamp(0.35 + Math.abs(automation - 0.5) * 0.7 + Math.min(0.12, evidence * 0.02), 0, 0.85));
        return new JevClassification(thresholds.classify(automation), automation, coordination, confidence,
                List.copyOf(signals), List.copyOf(counter), List.copyOf(coordinationSignals),
                summary(automation, coordination, account.hasMetadata()));
    }

    /** Saturates with the number of coordinated partners, scaled by the strongest link. */
    static double coordination(JevClassificationRequest.Features f) {
        if (f.coordinatedAccounts() == 0) return round(Math.min(0.2, 0.05 + 0.03 * f.similarRepliesWithin60Seconds()));
        double partners = 1 - Math.exp(-f.coordinatedAccounts() / 2.5);
        return round(clamp(f.maxCoordinationScore() * (0.45 + 0.55 * partners)));
    }

    private String summary(double automation, double coordination, boolean hasMetadata) {
        String metadataNote = hasMetadata ? "" : " Account metadata was unavailable, so only text, timing and graph signals were used.";
        String automationPart = switch (thresholds.classify(automation)) {
            case AUTOMATION_LIKE -> "Signals suggest automated behavior";
            case UNCERTAIN -> "Signals are mixed";
            case HUMAN_LIKE -> "Signals are consistent with individual human activity";
        };
        String coordinationPart = coordination >= 0.65 ? ", with strong coordination with other accounts."
                : coordination >= 0.4 ? ", with some coordination with other accounts." : ".";
        return automationPart + coordinationPart + metadataNote + " Local heuristic; behavioral patterns are not proof of automation.";
    }

    private static String pct(double value) {
        return Math.round(value * 100) + "%";
    }

    private static double clamp(double value) {
        return clamp(value, 0, 1);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
