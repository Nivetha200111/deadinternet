package dev.deadinternet.analysis;

import java.util.Set;

/** A phrase repeated across several accounts' replies. */
public record PhrasePattern(String id, String text, Set<String> replyIds, int accountCount) {}
