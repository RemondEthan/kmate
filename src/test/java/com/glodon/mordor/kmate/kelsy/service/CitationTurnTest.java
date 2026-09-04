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
    void addPathsCommitsForFind() {
        CitationTurn t = new CitationTurn();
        t.addPaths(List.of("knowledge/meetings/hit.md"));
        assertTrue(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/hit.md"), t.shown());
    }
}
