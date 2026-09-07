package com.glodon.mordor.kmate.kelsy.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationTurnTest {

    @Test
    void beginAskDoesNotClearShown() {
        CitationTurn t = new CitationTurn();
        t.addRetrievalText("read knowledge/meetings/a.md");
        assertTrue(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/a.md"), t.shown());
        t.beginAsk();
        assertEquals(List.of("knowledge/meetings/a.md"), t.shown());
        assertFalse(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/a.md"), t.shown());
    }

    @Test
    void commitReplacesShownWhenPendingNonEmpty() {
        CitationTurn t = new CitationTurn();
        t.addRetrievalText("knowledge/meetings/old.md");
        t.commitIfRetrieved();
        t.beginAsk();
        t.addRetrievalText("见 knowledge/meetings/new.md 和 memory/2026-09-04.md");
        assertTrue(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/new.md", "memory/2026-09-04.md"), t.shown());
        assertEquals("memory/2026-09-04.md", t.lastShown());
    }

    @Test
    void writesAreNotRetrievals() {
        assertFalse(CitationTurn.isRetrievalTool("memory_save"));
        assertFalse(CitationTurn.isRetrievalTool("write_file"));
        assertFalse(CitationTurn.isRetrievalTool("edit_file"));
        assertTrue(CitationTurn.isRetrievalTool("memory_get"));
        assertTrue(CitationTurn.isRetrievalTool("memory_search"));
        assertTrue(CitationTurn.isRetrievalTool("read_file"));
        CitationTurn t = new CitationTurn();
        t.addRetrievalText("knowledge/meetings/a.md");
        t.commitIfRetrieved();
        t.beginAsk();
        assertFalse(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/a.md"), t.shown());
    }

    @Test
    void splitDeltasStillExtractPath() {
        CitationTurn t = new CitationTurn();
        t.beginTool("read_file");
        t.appendToolArgs("{\"path\":\"knowledge/meetings/2026-09-04-");
        assertTrue(t.pendingPaths().isEmpty());
        t.appendToolArgs("业务.md\"}");
        assertEquals(List.of("knowledge/meetings/2026-09-04-业务.md"), t.pendingPaths());
        t.appendToolResult("正文前半 knowledge/meetings/2026-09-04-");
        t.appendToolResult("业务连接部-cursor账号管理.md 后半");
        assertEquals(
                List.of("knowledge/meetings/2026-09-04-业务.md",
                        "knowledge/meetings/2026-09-04-业务连接部-cursor账号管理.md"),
                t.pendingPaths());
    }

    @Test
    void toolNameAloneDoesNotAddPath() {
        CitationTurn t = new CitationTurn();
        t.beginTool("read_file");
        t.appendToolResult("read_file");
        assertTrue(t.pendingPaths().isEmpty());
        t.beginTool("write_file");
        t.appendToolArgs("{\"path\":\"knowledge/meetings/a.md\"}");
        assertTrue(t.pendingPaths().isEmpty());
    }

    @Test
    void replaceShownOpensFirst() {
        CitationTurn t = new CitationTurn();
        t.addRetrievalText("knowledge/meetings/old.md");
        t.commitIfRetrieved();
        t.replaceShown(List.of(
                "knowledge/todos/2026-09-10-申请-licence.md",
                "knowledge/todos/2026-09-12-发周报.md"));
        assertEquals(
                List.of("knowledge/todos/2026-09-10-申请-licence.md",
                        "knowledge/todos/2026-09-12-发周报.md"),
                t.shown());
        assertEquals("knowledge/todos/2026-09-10-申请-licence.md", t.firstShown());
        assertEquals("knowledge/todos/2026-09-12-发周报.md", t.lastShown());
    }

    @Test
    void addPathsCommitsForFind() {
        CitationTurn t = new CitationTurn();
        t.addPaths(List.of("knowledge/meetings/hit.md"));
        assertTrue(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/hit.md"), t.shown());
    }
}
