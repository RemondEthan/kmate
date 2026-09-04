package com.glodon.mordor.kmate.kelsy.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CitationTurn {

    private static final Set<String> RETRIEVAL = Set.of(
            "memory_get", "memory_search", "read_file");

    private final List<String> shown = new ArrayList<>();
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();

    public static boolean isRetrievalTool(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return RETRIEVAL.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    public void beginAsk() {
        pending.clear();
    }

    public void addRetrievalText(String text) {
        pending.addAll(KnowledgePathExtractor.all(text));
    }

    public void addPaths(List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            if (p != null && !p.isBlank()) {
                pending.add(p);
            }
        }
    }

    public boolean commitIfRetrieved() {
        if (pending.isEmpty()) {
            return false;
        }
        shown.clear();
        shown.addAll(pending);
        pending.clear();
        return true;
    }

    public List<String> shown() {
        return List.copyOf(shown);
    }

    public String lastShown() {
        return shown.isEmpty() ? null : shown.get(shown.size() - 1);
    }
}
