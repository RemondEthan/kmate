package com.glodon.mordor.kmate.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSeederTest {

    @TempDir
    Path dir;

    @Test
    void writesMissingFilesOnce() throws Exception {
        WorkspaceSeeder.seed(dir);
        assertTrue(Files.isRegularFile(dir.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(dir.resolve("MEMORY.md")));
        assertTrue(Files.isRegularFile(dir.resolve("knowledge/KNOWLEDGE.md")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/people")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/projects")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/playbooks")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/inbox")));
        assertTrue(Files.isRegularFile(dir.resolve("skills/kelsy-knowledge/SKILL.md")));
        assertTrue(Files.isRegularFile(dir.resolve("skills/kelsy-knowledge/references/examples.md")));
        Files.writeString(dir.resolve("AGENTS.md"), "keep-me");
        WorkspaceSeeder.seed(dir);
        assertEquals("keep-me", Files.readString(dir.resolve("AGENTS.md")));
    }
}
