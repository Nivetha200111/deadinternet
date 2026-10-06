package dev.deadinternet.analysis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatedPhraseServiceTest {

    private final TextNormalizationService normalization = new TextNormalizationService();
    private final RepeatedPhraseService service = new RepeatedPhraseService(normalization);

    @Test
    void findsPhrasesSharedByThreeOrMoreAccounts() {
        var tokens = new LinkedHashMap<String, List<String>>();
        var accounts = new LinkedHashMap<String, String>();
        String[] texts = {"Exactly this. Everyone needs to pay attention.", "Exactly this! Everyone needs to pay attention now.",
                "Honestly everyone needs to pay attention here.", "I built a recipe organizer for my family."};
        for (int i = 0; i < texts.length; i++) {
            tokens.put("r" + i, normalization.tokens(texts[i]));
            accounts.put("r" + i, "a" + i);
        }
        var patterns = service.detect(tokens, accounts);
        assertThat(patterns).isNotEmpty();
        assertThat(patterns.getFirst().text()).contains("everyone needs to pay attention");
        assertThat(patterns.getFirst().accountCount()).isEqualTo(3);
        assertThat(patterns).noneMatch(p -> p.replyIds().contains("r3"));
    }

    @Test
    void oneAccountRepeatingItselfIsNotACrossAccountPattern() {
        var tokens = new LinkedHashMap<String, List<String>>();
        var accounts = new LinkedHashMap<String, String>();
        for (int i = 0; i < 4; i++) {
            tokens.put("r" + i, normalization.tokens("The future is already here"));
            accounts.put("r" + i, "same_account");
        }
        assertThat(service.detect(tokens, accounts)).isEmpty();
    }
}
