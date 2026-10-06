package dev.deadinternet.classification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Validates JEV's JSON strictly: a malformed or unexplained score is treated as a failure, not guessed at. */
@Component
public class JevResponseParser {

    private final ObjectMapper mapper;

    public JevResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JevClassification parse(String json) {
        JsonNode tree;
        try {
            tree = mapper.readTree(json);
        } catch (Exception e) {
            throw new JevException("JEV returned invalid JSON", e);
        }
        if (tree == null || !tree.isObject()) throw new JevException("JEV response must be a JSON object");
        String summary = text(tree, "summary");
        if (summary == null || summary.isBlank()) throw new JevException("JEV response must include a summary");
        Classification classification;
        try {
            classification = Classification.fromWire(text(tree, "classification"));
        } catch (IllegalArgumentException e) {
            throw new JevException("JEV returned an unknown classification", e);
        }
        return new JevClassification(classification,
                probability(tree, "automationLikelihood"),
                probability(tree, "coordinationLikelihood"),
                probability(tree, "confidence"),
                strings(tree, "signalsForAutomation"),
                strings(tree, "signalsAgainstAutomation"),
                strings(tree, "coordinationSignals"),
                summary);
    }

    private static double probability(JsonNode tree, String field) {
        var node = tree.get(field);
        if (node == null || !node.isNumber()) throw new JevException("JEV response is missing numeric " + field);
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new JevException(field + " must be within [0, 1]");
        return value;
    }

    private static String text(JsonNode tree, String field) {
        var node = tree.get(field);
        return node == null || !node.isTextual() ? null : node.asText();
    }

    private static List<String> strings(JsonNode tree, String field) {
        var node = tree.get(field);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new JevException(field + " must be an array of strings");
        var result = new ArrayList<String>();
        node.forEach(item -> {
            if (!item.isTextual()) throw new JevException(field + " must be an array of strings");
            result.add(item.asText());
        });
        return List.copyOf(result);
    }
}
