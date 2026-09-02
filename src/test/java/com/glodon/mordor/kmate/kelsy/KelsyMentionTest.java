package com.glodon.mordor.kmate.kelsy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyMentionTest {

    @Test
    void detectsPrefixCaseInsensitiveAndStrips() {
        assertTrue(KelsyMention.isMention("@kelsy 你好"));
        assertTrue(KelsyMention.isMention("  @KELSY 帮我记 "));
        assertEquals("你好", KelsyMention.strip("@kelsy 你好"));
        assertEquals("帮我记", KelsyMention.strip("  @KELSY 帮我记 "));
    }

    @Test
    void mentionAloneIsEmptyBody() {
        assertTrue(KelsyMention.isMention("@kelsy"));
        assertTrue(KelsyMention.isMention("@kelsy   "));
        assertEquals("", KelsyMention.strip("@kelsy"));
    }

    @Test
    void gluedNameIsNotMention() {
        assertFalse(KelsyMention.isMention("@kelsyfoo 嗨"));
        assertFalse(KelsyMention.isMention("请 @kelsy 看看"));
        assertFalse(KelsyMention.isMention("hello"));
        assertFalse(KelsyMention.isMention(null));
    }
}
