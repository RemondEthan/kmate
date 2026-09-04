package com.glodon.mordor.kmate.ui.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionQueryTest {

    @Test
    void parseAtStartAndAfterSpace() {
        var start = MentionQuery.parse("@", 1).orElseThrow();
        assertEquals(0, start.atIndex());
        assertEquals("", start.query());

        var mid = MentionQuery.parse("hi @张", 5).orElseThrow();
        assertEquals(3, mid.atIndex());
        assertEquals("张", mid.query());
    }

    @Test
    void parseRejectsEmailAndFinishedMention() {
        assertTrue(MentionQuery.parse("a@b", 3).isEmpty());
        assertTrue(MentionQuery.parse("@张三 你好", 8).isEmpty());
        assertTrue(MentionQuery.parse("hello", 5).isEmpty());
    }

    @Test
    void parseQueryIsPrefixBeforeCaret() {
        var t = MentionQuery.parse("@张三", 2).orElseThrow();
        assertEquals(0, t.atIndex());
        assertEquals("张", t.query());
    }
}
