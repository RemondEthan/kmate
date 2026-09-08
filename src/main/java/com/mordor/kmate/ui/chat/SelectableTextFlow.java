package com.mordor.kmate.ui.chat;

import javafx.scene.Node;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 TextFlow 节点装成可复制容器:监听每个 Text 节点 selection,
 * Ctrl/Cmd+C 时把选区映射回 raw 字符串(emoji 保留 Unicode)写到系统剪贴板。
 *
 * ImageView 节点不参与选择;选区跨 ImageView 时,前后 Text 节点 selection
 * 拼起来 + charOffsets 映射后仍能得到正确 raw 子串。
 *
 * Scene 挂载时自动安装 Ctrl/Cmd+C handler;同 Scene 多实例各自检查自身是否有选区,
 * 无选区则不消费 KeyEvent,让 JavaFX 默认行为执行。
 */
public class SelectableTextFlow extends TextFlow {

    private static final KeyCombination COPY_WIN = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination COPY_MAC = new KeyCodeCombination(KeyCode.C, KeyCombination.META_DOWN);

    private final int[] charOffsets;
    private final AtomicReference<int[]> currentSelection = new AtomicReference<>(new int[]{0, 0});
    private String raw;
    private boolean handlerInstalled = false;

    SelectableTextFlow(List<Node> children, int[] charOffsets, String raw) {
        this.charOffsets = charOffsets;
        this.raw = raw == null ? "" : raw;
        getStyleClass().add("selectable-text-flow");
        setFocusTraversable(true);
        getChildren().addAll(children);
        attachTextListeners();
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && !handlerInstalled) {
                newScene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleCopy);
                handlerInstalled = true;
            }
        });
    }

    public static SelectableTextFlow forText(String text) {
        var parts = EmojiImages.flowWithMap(text);
        SelectableTextFlow flow = new SelectableTextFlow(
                List.copyOf(parts.flow().getChildren()),
                parts.charOffsets(),
                text);
        return flow;
    }

    private void attachTextListeners() {
        for (int i = 0; i < getChildren().size(); i++) {
            if (getChildren().get(i) instanceof Text t) {
                t.selectionStartProperty().addListener((obs, ov, nv) -> recomputeSelection());
                t.selectionEndProperty().addListener((obs, ov, nv) -> recomputeSelection());
            }
        }
    }

    private void recomputeSelection() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < getChildren().size(); i++) {
            if (getChildren().get(i) instanceof Text t) {
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

    /** 当前选区对应的 raw 子串;无选区返回 null。 */
    String currentRawSubstring() {
        int[] sel = currentSelection.get();
        if (sel[0] == sel[1]) return null;
        int start = Math.max(0, Math.min(sel[0], raw.length()));
        int end = Math.max(start, Math.min(sel[1], raw.length()));
        return raw.substring(start, end);
    }

    private void handleCopy(KeyEvent e) {
        if (!COPY_WIN.match(e) && !COPY_MAC.match(e)) return;
        String text = currentRawSubstring();
        if (text == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        e.consume();
    }
}
