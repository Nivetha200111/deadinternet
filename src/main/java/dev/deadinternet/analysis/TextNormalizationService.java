package dev.deadinternet.analysis;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

@Service
public class TextNormalizationService {

    /** Lowercase, Unicode-normalized, URLs/mentions/punctuation stripped, whitespace collapsed. */
    public String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("https?://\\S+", " ")
                .replaceAll("@\\w+", " ")
                .replaceAll("'", "")
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public List<String> tokens(String text) {
        String normalized = normalize(text);
        return normalized.isEmpty() ? List.of() : List.of(normalized.split(" "));
    }

    /** Type/token ratio: distinct tokens divided by total tokens. */
    public double lexicalDiversity(List<String> tokens) {
        return tokens.isEmpty() ? 0 : (double) new HashSet<>(tokens).size() / tokens.size();
    }

    /** Contiguous word n-grams, joined by single spaces. */
    public List<String> shingles(List<String> tokens, int n) {
        var result = new ArrayList<String>();
        for (int i = 0; i + n <= tokens.size(); i++) {
            result.add(String.join(" ", tokens.subList(i, i + n)));
        }
        return result;
    }
}
