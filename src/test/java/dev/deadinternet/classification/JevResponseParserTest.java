package dev.deadinternet.classification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JevResponseParserTest {

    private final JevResponseParser parser = new JevResponseParser(new ObjectMapper());

    static final String VALID = """
            {
              "classification": "automation_like",
              "automationLikelihood": 0.83,
              "coordinationLikelihood": 0.91,
              "confidence": 0.79,
              "signalsForAutomation": ["high reply-text similarity", "low lexical variation"],
              "signalsAgainstAutomation": ["account has long history"],
              "coordinationSignals": ["seven highly similar replies within 42 seconds"],
              "summary": "Behavior shows strong repetitive and coordinated characteristics."
            }
            """;

    @Test
    void parsesTheDocumentedExample() {
        var result = parser.parse(VALID);
        assertThat(result.classification()).isEqualTo(Classification.AUTOMATION_LIKE);
        assertThat(result.automationLikelihood()).isEqualTo(0.83);
        assertThat(result.coordinationLikelihood()).isEqualTo(0.91);
        assertThat(result.confidence()).isEqualTo(0.79);
        assertThat(result.signalsForAutomation()).containsExactly("high reply-text similarity", "low lexical variation");
        assertThat(result.signalsAgainstAutomation()).containsExactly("account has long history");
        assertThat(result.coordinationSignals()).hasSize(1);
    }

    @Test
    void missingSignalListsBecomeEmpty() {
        var result = parser.parse("""
                {"classification": "human_like", "automationLikelihood": 0.1, "coordinationLikelihood": 0.2,
                 "confidence": 0.6, "summary": "Varied, individual activity."}
                """);
        assertThat(result.signalsForAutomation()).isEmpty();
        assertThat(result.coordinationSignals()).isEmpty();
    }

    @Test
    void rejectsOutOfRangeOrMissingProbabilities() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("0.83", "1.4"))).isInstanceOf(JevException.class).hasMessageContaining("automationLikelihood");
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"confidence\": 0.79,", ""))).isInstanceOf(JevException.class).hasMessageContaining("confidence");
        assertThatThrownBy(() -> parser.parse(VALID.replace("0.91", "\"high\""))).isInstanceOf(JevException.class);
    }

    @Test
    void rejectsUnknownClassificationAndUnexplainedScores() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("automation_like", "bot"))).isInstanceOf(JevException.class);
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"summary\": \"Behavior shows strong repetitive and coordinated characteristics.\"", "\"summary\": \"\"")))
                .isInstanceOf(JevException.class);
    }

    @Test
    void rejectsMalformedJsonAndWrongShapes() {
        assertThatThrownBy(() -> parser.parse("{not json")).isInstanceOf(JevException.class);
        assertThatThrownBy(() -> parser.parse("[1, 2]")).isInstanceOf(JevException.class);
        assertThatThrownBy(() -> parser.parse(VALID.replace("[\"account has long history\"]", "\"one string\""))).isInstanceOf(JevException.class);
    }
}
