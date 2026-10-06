package dev.deadinternet.analysis;

import dev.deadinternet.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.deadinternet.Fixtures.established;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TextSimilarityTest {

    private final TextNormalizationService normalization = new TextNormalizationService();
    private final TfIdfSimilarityModel model = new TfIdfSimilarityModel();
    private final TextSimilarityService similarity = new TextSimilarityService(model, Fixtures.properties());

    private double[][] matrix(String... texts) {
        return model.pairwise(List.of(texts).stream().map(normalization::tokens).toList());
    }

    @Test
    void identicalTextIsFullySimilarAfterNormalization() {
        var m = matrix("Exactly this. People need to wake up.", "exactly this — people need to WAKE UP!", "Unrelated note about compilers");
        assertThat(m[0][1]).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void disjointTextHasZeroSimilarity() {
        var m = matrix("compilers give feedback", "gardens need water", "something else entirely");
        assertThat(m[0][1]).isZero();
    }

    @Test
    void nearDuplicatesScoreAboveTheEdgeThresholdAndUnrelatedRepliesBelow() {
        var m = matrix(
                "The future is already here. AI agents will change everything. Adapt now or get left behind.",
                "The future is here already. AI agents will change everything. Adapt or get left behind.",
                "Our build pipeline is the bottleneck, faster code generation makes the queue longer.",
                "Teaching students to question generated answers belongs in the curriculum.");
        assertThat(m[0][1]).isGreaterThan(0.72);
        assertThat(m[0][2]).isLessThan(0.3);
        assertThat(m[2][3]).isLessThan(0.3);
    }

    @Test
    void matrixIsSymmetricWithUnitDiagonal() {
        var m = matrix("one two three", "two three four", "five six");
        for (int i = 0; i < 3; i++) {
            assertThat(m[i][i]).isEqualTo(1.0);
            for (int j = 0; j < 3; j++) assertThat(m[i][j]).isEqualTo(m[j][i]);
        }
    }

    @Test
    void edgesRespectThresholdAndPerReplyCap() {
        var builder = new Object[30];
        for (int i = 0; i < 10; i++) {
            builder[i * 3] = established("a" + i);
            builder[i * 3 + 1] = "Exactly this. Everyone needs to pay attention.";
            builder[i * 3 + 2] = 10 + i;
        }
        var conversation = Fixtures.conversation(builder);
        var tokens = conversation.replies().stream().map(r -> normalization.tokens(r.text())).toList();
        var edges = similarity.edges(conversation.replies(), model.pairwise(tokens));
        // Ten identical replies would form 45 edges; each reply keeps at most six neighbours.
        assertThat(edges).hasSizeLessThan(45).allSatisfy(e -> assertThat(e.similarity()).isGreaterThanOrEqualTo(0.72));
        assertThat(edges.stream().filter(e -> e.sourceReplyId().equals("reply_1") || e.targetReplyId().equals("reply_1")))
                .hasSizeGreaterThanOrEqualTo(6);
    }
}
