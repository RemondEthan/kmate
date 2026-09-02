package com.glodon.mordor.kmate.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeStoreSearchTest {

    @TempDir
    Path dir;

    @Test
    void hitsDatedDiaryAndSkipsOutOfWindow() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        Files.createDirectories(dir.resolve("knowledge"));
        Files.writeString(dir.resolve("MEMORY.md"), "- 张三：长期合作\n");
        Files.writeString(dir.resolve("memory/2026-03-15.md"), "- 与张三敲定评审方案\n");
        Files.writeString(dir.resolve("memory/2026-08-01.md"), "- 与张三喝咖啡\n");
        var store = new KnowledgeStore(dir);
        var q = FindQuery.parse("半年前 张三", LocalDate.of(2026, 9, 2));
        var hits = store.search(q);
        assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("memory/2026-03-15.md")));
        assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("MEMORY.md")));
        assertTrue(hits.stream().noneMatch(h -> h.relativePath().equals("memory/2026-08-01.md")));
        assertTrue(hits.size() >= 2);
    }
}
