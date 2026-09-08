package com.mordor.kmate.ui.chat;

import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectableTextFlowTest {

    @Test
    void forText_returnsFocusableTextFlow() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        assertTrue(flow.isFocusTraversable(), "TextFlow 默认可聚焦才能显示选区");
    }

    @Test
    void forText_empty_returnsFlowWithNoChildren() {
        SelectableTextFlow flow = SelectableTextFlow.forText("");
        assertEquals(0, flow.getChildren().size());
    }

    @Test
    void forText_emojiSplitIntoImageView() {
        // "a🍕b" → Text("a") + ImageView(🍕) + Text("b")
        SelectableTextFlow flow = SelectableTextFlow.forText("a🍕b");
        assertEquals(3, flow.getChildren().size());
        assertInstanceOf(Text.class, flow.getChildren().get(0));
        assertInstanceOf(ImageView.class, flow.getChildren().get(1));
        assertInstanceOf(Text.class, flow.getChildren().get(2));
    }

    @Test
    void forText_addsSelectableStyleClass() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        assertTrue(flow.getStyleClass().contains("selectable-text-flow"));
    }

    @Test
    void currentRawSubstring_noSelection_returnsNull() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        assertNull(flow.currentRawSubstring());
    }

    @Test
    void currentRawSubstring_partialSelection_returnsSubstring() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello world");
        // 只有一个 Text 子节点,选 "hello"
        Text text = (Text) flow.getChildren().get(0);
        text.setSelectionStart(0);
        text.setSelectionEnd(5);
        assertEquals("hello", flow.currentRawSubstring());
    }

    @Test
    void currentRawSubstring_acrossEmojiImageView() {
        SelectableTextFlow flow = SelectableTextFlow.forText("a🍕b");
        // Text("a") 在 idx 0, ImageView 在 idx 1 (不参与), Text("b") 在 idx 2
        // 选 "a" + 整段 + "b" → raw[0..4) = "a🍕b"
        Text a = (Text) flow.getChildren().get(0);
        Text b = (Text) flow.getChildren().get(2);
        a.setSelectionStart(0);
        a.setSelectionEnd(1);
        b.setSelectionStart(0);
        b.setSelectionEnd(1);
        assertEquals("a🍕b", flow.currentRawSubstring());
    }
}