package dev.deadinternet.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DeadInternetScoreServiceTest {

    private final DeadInternetScoreService service = new DeadInternetScoreService();

    @Test
    void combinesTheFourComponentsWithDocumentedWeights() {
        // 100 accounts: 30 automation-like, 40 coordinated, 50 of 100 replies duplicated, 20 in large clusters.
        var score = service.score(new DeadInternetScoreService.Inputs(100, 30, 40, 100, 50, 20));
        assertThat(score.automationShare()).isEqualTo(0.3);
        assertThat(score.coordinatedAccountShare()).isEqualTo(0.4);
        assertThat(score.duplicateReplyShare()).isEqualTo(0.5);
        assertThat(score.largeClusterShare()).isEqualTo(0.2);
        assertThat(score.score()).isCloseTo(0.40 * 0.3 + 0.20 * 0.4 + 0.25 * 0.5 + 0.15 * 0.2, within(1e-3));
    }

    @Test
    void anOrganicConversationScoresZero() {
        assertThat(service.score(new DeadInternetScoreService.Inputs(50, 0, 0, 60, 0, 0)).score()).isZero();
    }

    @Test
    void emptyInputIsZeroAndScoreNeverExceedsOne() {
        assertThat(service.score(new DeadInternetScoreService.Inputs(0, 0, 0, 0, 0, 0)).score()).isZero();
        assertThat(service.score(new DeadInternetScoreService.Inputs(10, 10, 10, 10, 10, 10)).score()).isEqualTo(1.0);
    }
}
