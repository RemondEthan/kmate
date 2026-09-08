package com.mordor.kmate.ui.chat;

import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 EmojiImages.flowWithMap() 的产物包成一个可复制容器：
 * 监听 TextFlow 内每个 Text 节点的 selectionStart/EndProperty，
 * Ctrl/Cmd+C 时把选区映射回原始字符串（保留 emoji 的 Unicode）写到系统剪贴板。
 *
 * ImageView 节点不参与选择；选区跨 ImageView 时，前后两个 Text 节点
 * 的 selection 拼起来 + charOffsets 映射后仍能得到正确 raw 子串。
 */
public class SelectableTextFlow {

    private static final KeyCombination COPY_WIN = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination COPY_MAC = new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN);

    private final TextFlow flow;
    private final int[] charOffsets;
    private final String raw;
    private final AtomicReference<int[]> currentSelection = new AtomicReference<>(new int[]{0, 0});

    public SelectableTextFlow(TextFlow flow, int[] charOffsets, String raw) {
        this.flow = flow;
        this.charOffsets = charOffsets;
        this.raw = raw == null ? "" : raw;
        attachTextListeners();
    }

    public TextFlow flow() {
        return flow;
    }

    /**
     * 安装 Ctrl/Cmd+C 监听器。同一 Scene 调用多次安全（幂等）。
     */
    public void installCopyHandler(Scene scene) {
        if (scene == null || flow.getScene() == null) {
            return;
        }
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleCopy);
    }

    private void attachTextListeners() {
        for (int i = 0; i < flow.getChildren().size(); i++) {
            if (flow.getChildren().get(i) instanceof Text t) {
                final int idx = i;
                t.selectionStartProperty().addListener((obs, ov, nv) -> updateSelection(idx, true));
                t.selectionEndProperty().addListener((obs, ov, nv) -> updateSelection(idx, false));
            }
        }
    }

    private void updateSelection(int nodeIdx, boolean isStart) {
        int[] cur = currentSelection.get();
        int newStart = cur[0];
        int newEnd = cur[1];
        if (flow.getChildren().get(nodeIdx) instanceof Text t) {
            int ts = t.getSelectionStart();
            int te = t.getSelectionEnd();
            if (ts >= 0 && te >= 0 && te > ts) {
                int rawStart = charOffsets[nodeIdx] + ts;
                int rawEnd = charOffsets[nodeIdx] + te;
                // 简单合并策略：取所有有 selection 的 Text 节点的最小 rawStart + 最大 rawEnd
                int mergedStart = newStart;
                int mergedEnd = newEnd;
                if (rawStart < mergedStart || mergedStart == 0) mergedStart = rawStart;
                if (rawEnd > mergedEnd) mergedEnd = rawEnd;
                currentSelection.set(new int[]{mergedStart, mergedEnd});
                return;
            }
        }
        // 该节点 selection 清零 → 重算
        recomputeSelection();
    }

    private void recomputeSelection() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < flow.getChildren().size(); i++) {
            if (flow.getChildren().get(i) instanceof Text t) {
                int ts = t.getSelectionStart();
                int te = t.getSelectionEnd();
                if (ts >= 0 && te > ts) {
                    int rs = charOffsets[i] + ts;
                    int re = charOffsets[i] + te;
                    if (rs < min) min = rs;
                    if (re > max) max = re;
                }
            }
        }
        if (min == Integer.MAX_VALUE) {
            currentSelection.set(new int[]{0, 0});
        } else {
            currentSelection.set(new int[]{min, max});
        }
    }

    private void handleCopy(KeyEvent e) {
        if (!COPY_WIN.match(e) && !COPY_MAC.match(e)) {
            return;
        }
        int[] sel = currentSelection.get();
        if (sel[0] == sel[1]) {
            return; // 无选区；不消费，按 JavaFX 默认行为
        }
        int start = Math.max(0, Math.min(sel[0], raw.length()));
        int end = Math.max(start, Math.min(sel[1], raw.length()));
        String text = raw.substring(start, end);
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        e.consume();
    }
}
