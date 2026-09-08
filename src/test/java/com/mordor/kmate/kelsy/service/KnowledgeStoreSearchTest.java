package com.mordor.kmate.kelsy.service;

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

    @Test
    void hitsMeetingCardByLicenceAlias() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Files.writeString(dir.resolve("MEMORY.md"),
                "- 2026-09-04 客户XX 交付 licence 许可证 → knowledge/meetings/2026-09-04-客户XX-交付licence.md\n");
        Files.writeString(
                dir.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md"),
                """
                # 会议 · 客户XX · 交付 licence
                - 结论：先申请再发货
                - 别名：licence, license, 许可证, 交付许可
                """);
        var store = new KnowledgeStore(dir);
        var byLicense = store.search(FindQuery.parse("许可证", LocalDate.of(2026, 9, 4)));
        var byLicence = store.search(FindQuery.parse("licence", LocalDate.of(2026, 9, 4)));
        assertTrue(byLicense.stream().anyMatch(h ->
                h.relativePath().equals("knowledge/meetings/2026-09-04-客户XX-交付licence.md")));
        assertTrue(byLicence.stream().anyMatch(h ->
                h.relativePath().equals("knowledge/meetings/2026-09-04-客户XX-交付licence.md")));
    }
}
