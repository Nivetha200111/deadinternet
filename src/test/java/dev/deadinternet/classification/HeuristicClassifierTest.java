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

    @Test
    void fakeSupportReplyIsAutomationLikeEvenWithDistinctLanguage() {
        var base = request(established("helper"), 0.1, false, 0, 0, 0, 1, 0);
        var signals = new dev.deadinternet.analysis.TextSignalService().analyze(java.util.List.of(
                "Kindly send a direct message to @CryptoFixer_Hub or reach them on telegram at t.me/wallet_helper_01 to "
                        + "fix your locked account immediately."), "My wallet app says my account is suspended");
        var phishing = new JevClassificationRequest(base.account(), base.reply(), base.features(), base.neighborReplies(),
                base.otherRepliesByAccount(), "thread", signals);
        var result = classifier.classify(phishing);
        assertThat(result.classification()).isEqualTo(Classification.AUTOMATION_LIKE);
        assertThat(result.signalsForAutomation()).anyMatch(s -> s.contains("off-platform contact"));
        assertThat(classifier.classify(base).classification()).isEqualTo(Classification.HUMAN_LIKE);
    }

    private JevClassificationRequest withText(String text) {
        var base = request(established("acct"), 0.1, false, 0, 0, 0, 1, 0);
        var signals = new dev.deadinternet.analysis.TextSignalService().analyze(java.util.List.of(text), null);
        return new JevClassificationRequest(base.account(), base.reply(), base.features(), base.neighborReplies(),
                base.otherRepliesByAccount(), "feed", signals);
    }

    @Test
    void researchedScamShapesAreAutomationLike() {
        for (String scam : java.util.List.of(
                "Thanks to @CryptoMentorJane I made $12,400 in just 9 days with her trading signals. Message her!",
                "URGENT HIRING Work from home, earn $500/day, no experience needed! Comment #Interested and DM me on WhatsApp +1 555 0100",
                "lonely tonight 😘 my private pics are in my bio 🔞",
                "Follow for follow! I follow back 100% #F4F #followback drop your @ below")) {
            var result = classifier.classify(withText(scam));
            assertThat(result.classification()).as(scam).isEqualTo(Classification.AUTOMATION_LIKE);
            assertThat(result.category()).as(scam).isIn(PostCategory.SCAM, PostCategory.FOLLOW_FARMING);
        }
    }

    @Test
    void milestonesAdsAndAiStyledPostsGetTheirOwnGroups() {
        assertThat(classifier.classify(withText("Happy to share that I'm starting a new position as Data Analyst at Acme!"))
                .category()).isEqualTo(PostCategory.MILESTONE);
        assertThat(classifier.classify(withText("I've earned my AWS Solutions Architect certification #certification"))
                .category()).isEqualTo(PostCategory.MILESTONE);
        assertThat(classifier.classify(withText("Startup life. Today: Startup Pitch Parade. Looking forward to a packed "
                + "tent and your vote! #FastCodeAI #Gruenderwasen #StartupStuttgart #IndustrialAI")).category())
                .isEqualTo(PostCategory.AI_AD);
        assertThat(classifier.classify(withText("In today's fast-paced world, leadership is a game-changer. "
                + "It's not just about results — it's about empowering people to elevate your team. 🚀")).category())
                .isEqualTo(PostCategory.AI_LOW_VALUE);
    }

    @Test
    void researchedLookAlikesStayHumanLike() {
        for (String ordinary : java.util.List.of(
                "Sold 200 cookies at the school fair and made $340, the kids were thrilled.",
                "We're hiring a backend engineer in Berlin (Go, Postgres). Apply through our careers page.",
                "My portfolio link is in my bio if you want to see the full illustration series.",
                "Thanks for sharing. The part about partial indexes saved us hours on our Postgres migration.")) {
            var result = classifier.classify(withText(ordinary));
            assertThat(result.classification()).as(ordinary).isEqualTo(Classification.HUMAN_LIKE);
            assertThat(result.category()).as(ordinary).isEqualTo(PostCategory.PERSONAL);
        }
    }
}
