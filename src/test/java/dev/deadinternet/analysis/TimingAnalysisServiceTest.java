package dev.deadinternet.analysis;

import dev.deadinternet.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.deadinternet.Fixtures.established;
import static org.assertj.core.api.Assertions.assertThat;

class TimingAnalysisServiceTest {

    private final TimingAnalysisService timing = new TimingAnalysisService();

    @Test
    void countsSimilarRepliesWithinThirtyAndSixtySeconds() {
        var conversation = Fixtures.conversation(
                established("a"), "x", 100, established("b"), "x", 120, established("c"), "x", 150, established("d"), "x", 400);
        double[][] allSimilar = new double[4][4];
        for (var row : allSimilar) java.util.Arrays.fill(row, 1.0);
        var result = timing.analyze(conversation.post(), conversation.replies(), allSimilar, 0.72);
        var first = result.get("reply_1");
        assertThat(first.secondsAfterParent()).isEqualTo(100);
        assertThat(first.similarRepliesWithin30Seconds()).isEqualTo(1);
        assertThat(first.similarRepliesWithin60Seconds()).isEqualTo(2);
        assertThat(first.inBurst()).isTrue();
        assertThat(result.get("reply_4").inBurst()).isFalse();
    }

    @Test
    void fastRepliesWithoutSimilarTextAreNotABurst() {
        var conversation = Fixtures.conversation(
                established("a"), "first", 2, established("b"), "second", 3, established("c"), "third", 4);
        double[][] unrelated = new double[3][3];
        var result = timing.analyze(conversation.post(), conversation.replies(), unrelated, 0.72);
        assertThat(result.values()).allSatisfy(t -> {
            assertThat(t.inBurst()).isFalse();
            assertThat(t.similarRepliesWithin30Seconds()).isZero();
        });
    }

    @Test
    void regularityNeedsFourEventsAndRewardsEvenSpacing() {
        assertThat(timing.regularity(List.of(0L, 10L, 20L))).isNull();
        assertThat(timing.regularity(List.of(0L, 10L, 20L, 30L, 40L))).isEqualTo(1.0);
        assertThat(timing.regularity(List.of(0L, 1L, 2L, 300L))).isLessThan(0.3);
    }
}
