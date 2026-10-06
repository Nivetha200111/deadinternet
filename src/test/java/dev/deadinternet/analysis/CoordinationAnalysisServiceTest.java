package dev.deadinternet.analysis;

import dev.deadinternet.Fixtures;
import dev.deadinternet.model.Conversation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static dev.deadinternet.Fixtures.established;
import static dev.deadinternet.Fixtures.young;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CoordinationAnalysisServiceTest {

    private final CoordinationAnalysisService coordination = new CoordinationAnalysisService(Fixtures.properties());
    private final TextNormalizationService normalization = new TextNormalizationService();
    private final FeatureExtractionService extraction = new FeatureExtractionService(normalization,
            new TextSimilarityService(new TfIdfSimilarityModel(), Fixtures.properties()),
            new RepeatedPhraseService(normalization), new TimingAnalysisService());

    @Test
    void scoreUsesConfiguredWeights() {
        // 0.55 * 0.8 + 0.30 * 0.5 + 0.15 * 1.0
        assertThat(coordination.score(0.8, 0.5, 1.0)).isCloseTo(0.74, within(1e-9));
        assertThat(coordination.score(1, 1, 1)).isEqualTo(1.0);
        assertThat(coordination.score(0, 0, 0)).isZero();
    }

    @Test
    void timingProximityFallsLinearlyToZeroAtTheWindow() {
        assertThat(coordination.timingProximity(0)).isEqualTo(1.0);
        assertThat(coordination.timingProximity(60)).isEqualTo(0.5);
        assertThat(coordination.timingProximity(-60)).isEqualTo(0.5);
        assertThat(coordination.timingProximity(500)).isZero();
    }

    @Test
    void sharedPatternScoreIsJaccardOverlap() {
        assertThat(CoordinationAnalysisService.sharedPatternScore(Set.of("p1", "p2"), Set.of("p2", "p3"))).isCloseTo(1 / 3.0, within(1e-9));
        assertThat(CoordinationAnalysisService.sharedPatternScore(Set.of(), Set.of("p1"))).isZero();
    }

    @Test
    void synchronizedNearDuplicatesAreCoordinatedButTheSameTextFarApartIsNot() {
        Conversation conversation = Fixtures.conversation(
                young("b1"), "The future is already here. AI agents will change everything. Adapt now.", 40,
                young("b2"), "The future is already here. AI agents will change everything. Adapt now!", 43,
                young("b3"), "The future is already here! AI agents will change everything. Adapt now.", 46,
                established("late"), "The future is already here. AI agents will change everything. Adapt now.", 1900,
                established("h1"), "Our build pipeline is the bottleneck, not code generation.", 44);
        var edges = coordination.edges(conversation, extraction.extract(conversation));
        var pairs = edges.stream().map(e -> e.sourceAccountId() + "-" + e.targetAccountId()).toList();
        assertThat(pairs).contains("b1-b2", "b1-b3", "b2-b3");
        assertThat(pairs).noneMatch(p -> p.contains("late") || p.contains("h1"));
        assertThat(edges).allSatisfy(e -> {
            assertThat(e.score()).isGreaterThanOrEqualTo(0.75);
            assertThat(e.sourceAccountId()).isLessThan(e.targetAccountId());
        });
    }

    @Test
    void anAccountIsNeverCoordinatedWithItself() {
        var same = young("solo");
        Conversation conversation = Fixtures.conversation(
                same, "Exactly this. Everyone needs to pay attention.", 10,
                same, "Exactly this. Everyone needs to pay attention.", 12,
                established("other"), "Something entirely different about compilers.", 11);
        assertThat(coordination.edges(conversation, extraction.extract(conversation))).isEmpty();
    }
}
