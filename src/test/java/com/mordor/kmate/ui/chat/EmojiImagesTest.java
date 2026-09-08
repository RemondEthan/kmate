package com.mordor.kmate.ui.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmojiImagesTest {

    @Test
    void resourceKeyMapsSmile() {
        assertEquals("1f600", EmojiImages.resourceKey("😀"));
    }

    @Test
    void resourceKeyMapsHeartWithVariationSelector() {
        assertEquals("2764", EmojiImages.resourceKey("❤️"));
    }
}
