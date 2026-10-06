package dev.deadinternet.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizationServiceTest {

    private final TextNormalizationService service = new TextNormalizationService();

    @Test
    void normalizesCasePunctuationUrlsAndMentions() {
        assertThat(service.normalize("Exactly THIS!!  @someone see https://example.com/x — wake up."))
                .isEqualTo("exactly this see wake up");
    }

    @Test
    void joinsContractionsInsteadOfSplittingThem() {
        assertThat(service.normalize("They’re right, it's here")).isEqualTo("theyre right its here");
    }

    @Test
    void unicodeCompatibilityFormsCompareEqual() {
        assertThat(service.normalize("ＡＩ agents")).isEqualTo(service.normalize("AI agents"));
    }

    @Test
    void emptyAndNullTextProduceNoTokens() {
        assertThat(service.tokens(null)).isEmpty();
        assertThat(service.tokens("  !!! ")).isEmpty();
    }

    @Test
    void lexicalDiversityIsDistinctOverTotalTokens() {
        assertThat(service.lexicalDiversity(List.of("this", "this", "is", "it"))).isEqualTo(0.75);
        assertThat(service.lexicalDiversity(List.of())).isZero();
    }

    @Test
    void shinglesAreContiguousWordNgrams() {
        assertThat(service.shingles(List.of("a", "b", "c", "d"), 3)).containsExactly("a b c", "b c d");
        assertThat(service.shingles(List.of("a", "b"), 3)).isEmpty();
    }
}
