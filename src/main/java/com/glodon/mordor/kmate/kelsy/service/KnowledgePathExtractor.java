package com.glodon.mordor.kmate.kelsy.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnowledgePathExtractor {

    private static final Pattern PATH = Pattern.compile(
            "(?:MEMORY\\.md|AGENTS\\.md|memory/[\\p{L}\\p{N}._/-]+\\.md|knowledge/[\\p{L}\\p{N}._/-]+\\.md)");

    private KnowledgePathExtractor() {
    }

    public static Optional<String> first(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PATH.matcher(text);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }
}
