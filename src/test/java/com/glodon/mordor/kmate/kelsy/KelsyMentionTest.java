package com.glodon.mordor.kmate.kelsy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyMentionTest {

    @Test
    void detectsPrefixCaseInsensitiveAndStrips() {
        assertTrue(KelsyMention.isMention("@tars 你好"));
        assertTrue(KelsyMention.isMention("  @TARS 帮我记 "));
        assertEquals("你好", KelsyMention.strip("@tars 你好"));
        assertEquals("帮我记", KelsyMention.strip("  @TARS 帮我记 "));
    }

    @Test
    void mentionAloneIsEmptyBody() {
        assertTrue(KelsyMention.isMention("@tars"));
        assertTrue(KelsyMention.isMention("@tars   "));
        assertEquals("", KelsyMention.strip("@tars"));
    }

    @Test
    void gluedNameOrOldKelsyNameIsNotMention() {
        assertFalse(KelsyMention.isMention("@tarsfoo 嗨"));
        assertFalse(KelsyMention.isMention("请 @tars 看看"));
        assertFalse(KelsyMention.isMention("@kelsy 你好"));
        assertFalse(KelsyMention.isMention("hello"));
        assertFalse(KelsyMention.isMention(null));
    }
}
