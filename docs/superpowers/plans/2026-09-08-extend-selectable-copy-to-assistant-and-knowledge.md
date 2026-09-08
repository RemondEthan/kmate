# 扩展选中复制到助手气泡 / 知识库 / 工具调用 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `SelectableTextFlow` 抽象提升为统一"可复制容器",扩展到 `AssistantBubble` / `MarkdownView` / `ToolCallCard` / `KnowledgePane`,顺手修复 `MessageBubble` "不能选中"的 bug。

**Architecture:** `SelectableTextFlow` 从 wrapper 升级为 `TextFlow` 子类,提供 `forText(String)` / `forSegments(...)` 工厂方法和 `textProperty`。所有原本用 `Label` 或裸 `TextFlow` 渲染可读文本的地方换成 `SelectableTextFlow`。Scene 级 Ctrl/Cmd+C 由 `SelectableTextFlow` 在 `sceneProperty` 监听里自动安装(每个实例自带 handler,handler 内部判断自身是否有选区,无选区则不消费 KeyEvent)。

**Tech Stack:** Java 21, JavaFX 21, JUnit 5.10.2, Surefire 3.5.2

**Spec:** [`../specs/2026-09-08-extend-selectable-copy-to-assistant-and-knowledge-design.md`](../specs/2026-09-08-extend-selectable-copy-to-assistant-and-knowledge-design.md)

## Global Constraints

- User-visible strings, Java/Kotlin comments, Javadoc in **Chinese** (per `CLAUDE.md`)
- `--add-exports javafx.graphics/com.sun.glass.ui=com.mordor.kmate` 在 `pom.xml` 已配置;不要删
- Test runner: `./mvnw test` (默认排除 `*IT`,但当前只有 `ImClientIT` 一个 IT,可用 `-Dtest='!ImClientIT'` 跳过)
- emoji 走 Twemoji PNG;Windows 上 `EmojiImages.view` 用 try/catch 兜底,headless test 也能跑
- 现有 `MessageBubbleTest` (仅静态方法) 不能被破坏
- 现有 `EmojiImagesTest` (4 个用例) 不能被破坏
- 每次 commit 提交一个任务,message 简短(`feat:` / `ref:` / `test:` / `docs:` 前缀)

---

## File Structure

**新建:**
- `src/test/java/com/mordor/kmate/ui/chat/SelectableTextFlowTest.java` — 覆盖新 API

**修改:**
- `src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java` — 完整重写为 `TextFlow` 子类,新增工厂方法
- `src/main/java/com/mordor/kmate/ui/chat/MessageBubble.java` — `buildBubble` 改用 `SelectableTextFlow.forText`
- `src/main/java/com/mordor/kmate/kelsy/ui/markdown/MarkdownView.java` — 每个 block 渲染为 `SelectableTextFlow`
- `src/main/java/com/mordor/kmate/kelsy/ui/ToolCallCard.java` — label → `SelectableTextFlow.forText`
- `src/main/java/com/mordor/kmate/kelsy/ui/AssistantBubble.java` — plain Label / 思考块 / 待办块改用 `SelectableTextFlow`,ReminderCard 跳转改 Hyperlink
- `src/main/resources/com/mordor/kmate/ui/chat/chat.css` — 加 `.selectable-text-flow { -fx-selection-fill: #90CAF9; }`

---

## Task 1: `SelectableTextFlow` 升级为 `TextFlow` 子类 + `forText(String)` 工厂

**Files:**
- Modify: `src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java` (完整重写)
- Create: `src/test/java/com/mordor/kmate/ui/chat/SelectableTextFlowTest.java`

**Interfaces:**
- Consumes: `EmojiImages.flowWithMap(String) -> EmojiImages.FlowParts(TextFlow flow, int[] charOffsets)`
- Produces: `public class SelectableTextFlow extends javafx.scene.text.TextFlow`, static `forText(String text) -> SelectableTextFlow`, package-private constructor `SelectableTextFlow(List<Node> children, int[] charOffsets, String raw)`, instance `currentRawSubstring() -> String` (null if no selection)

---

- [ ] **Step 1.1: 写 failing test**

创建 `src/test/java/com/mordor/kmate/ui/chat/SelectableTextFlowTest.java`:

```java
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
        text.selectRange(0, 5);
        assertEquals("hello", flow.currentRawSubstring());
    }

    @Test
    void currentRawSubstring_acrossEmojiImageView() {
        SelectableTextFlow flow = SelectableTextFlow.forText("a🍕b");
        // Text("a") 在 idx 0, ImageView 在 idx 1 (不参与), Text("b") 在 idx 2
        // 选 "a" + 整段 + "b" → raw[0..4) = "a🍕b"
        Text a = (Text) flow.getChildren().get(0);
        Text b = (Text) flow.getChildren().get(2);
        a.selectRange(0, 1);
        b.selectRange(0, 1);
        assertEquals("a🍕b", flow.currentRawSubstring());
    }
}
```

- [ ] **Step 1.2: 跑测试,确认 fail**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test -Dtest=SelectableTextFlowTest 2>&1 | tail -30
```

期望:编译失败,`SelectableTextFlow` 类不存在或 API 不匹配。

- [ ] **Step 1.3: 实现 `SelectableTextFlow`**

完整重写 `src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java`:

```java
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
import java.util.Arrays;
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
                final int idx = i;
                t.selectionStartProperty().addListener((obs, ov, nv) -> updateSelection(idx, true));
                t.selectionEndProperty().addListener((obs, ov, nv) -> updateSelection(idx, false));
            }
        }
    }

    private void updateSelection(int nodeIdx, boolean isStart) {
        if (nodeIdx < getChildren().size()
                && getChildren().get(nodeIdx) instanceof Text t) {
            int ts = t.getSelectionStart();
            int te = t.getSelectionEnd();
            if (ts >= 0 && te > ts) {
                int rawStart = charOffsets[nodeIdx] + ts;
                int rawEnd = charOffsets[nodeIdx] + te;
                int[] cur = currentSelection.get();
                int mergedStart = cur[0];
                int mergedEnd = cur[1];
                if (rawStart < mergedStart || mergedStart == 0) mergedStart = rawStart;
                if (rawEnd > mergedEnd) mergedEnd = rawEnd;
                currentSelection.set(new int[]{mergedStart, mergedEnd});
                return;
            }
        }
        recomputeSelection();
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
```

- [ ] **Step 1.4: 跑测试,确认 pass**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test -Dtest=SelectableTextFlowTest 2>&1 | tail -15
```

期望:`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`,BUILD SUCCESS。

- [ ] **Step 1.5: 跑回归,确认现有测试不破坏**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test -Dtest='EmojiImagesTest,MessageBubbleTest' 2>&1 | tail -15
```

期望:`Tests run: 13` (6 + 4 + 2 + 1 AwtSupportTest...实际看输出),BUILD SUCCESS。

- [ ] **Step 1.6: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate && git add src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java src/test/java/com/mordor/kmate/ui/chat/SelectableTextFlowTest.java && git commit -m "ref: extend SelectableTextFlow to TextFlow subclass with forText factory"
```

---

## Task 2: `Segment` sealed 接口 + `forSegments(...)` 工厂

**Files:**
- Modify: `src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java` (新增 nested types + 工厂方法)
- Modify: `src/test/java/com/mordor/kmate/ui/chat/SelectableTextFlowTest.java` (新增测试)

**Interfaces:**
- Consumes: 外部传入的 `List<Segment>` 列表
- Produces: `SelectableTextFlow.Segment` sealed interface, 子 record `Text` / `Emoji` / `Code` / `Link`, 静态工厂 `forSegments(List<Segment>, String raw) -> SelectableTextFlow`

---

- [ ] **Step 2.1: 写 failing test**

在 `SelectableTextFlowTest.java` 末尾追加:

```java
    @Test
    void forSegments_textAndCodeRenderAsTextNodes() {
        var segments = List.of(
                new SelectableTextFlow.Segment.Text("你好 "),
                new SelectableTextFlow.Segment.Code("println()"),
                new SelectableTextFlow.Segment.Text(" 世界"));
        SelectableTextFlow flow = SelectableTextFlow.forSegments(segments, "你好 println() 世界");
        // 全部 Text 节点
        assertEquals(3, flow.getChildren().size());
        assertInstanceOf(Text.class, flow.getChildren().get(0));
        assertInstanceOf(Text.class, flow.getChildren().get(1));
        assertInstanceOf(Text.class, flow.getChildren().get(2));
        // Code 节点带 md-inline-code 样式
        Text codeText = (Text) flow.getChildren().get(1);
        assertTrue(codeText.getStyleClass().contains("md-inline-code"));
    }

    @Test
    void forSegments_linkHasClickHandler() {
        var clickedDest = new String[]{null};
        var segments = List.of(
                new SelectableTextFlow.Segment.Link("点我", "https://example.com"));
        SelectableTextFlow flow = SelectableTextFlow.forSegments(
                segments, "点我", dest -> clickedDest[0] = dest);
        Text linkText = (Text) flow.getChildren().get(0);
        assertNotNull(linkText.getOnMouseClicked(), "Link 必须注册点击回调");
        // 模拟点击
        linkText.getOnMouseClicked().handle(null);
        assertEquals("https://example.com", clickedDest[0]);
    }
```

注:`Link` 的点击回调注册到 `Text` 节点本身,在 `forSegments` 里实现 — 工厂需要拿到 "点击时调用的回调"。设计选择:

- 方案 A: `forSegments` 接受 `Consumer<String> onLinkClick` 参数,所有 Link 触发它
- 方案 B: `Segment.Link` 自带回调

选方案 A(更简单,跟 MarkdownView 现有 `onWorkspaceLink` Consumer 模式一致)。

- [ ] **Step 2.2: 跑测试,确认 fail**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test -Dtest=SelectableTextFlowTest 2>&1 | tail -20
```

期望:编译失败,`Segment` / `Code` / `Link` 不存在。

- [ ] **Step 2.3: 实现 `Segment` 接口和 `forSegments` 工厂**

修改 `src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java`,在 `private static final KeyCombination COPY_WIN` 之前插入:

```java
    /** 富文本片段,可由 MarkdownView 等富文本渲染器构造。 */
    public sealed interface Segment {
        record Text(String value) implements Segment {}
        record Emoji(String codepoint) implements Segment {}
        record Code(String value) implements Segment {}
        record Link(String text, String dest) implements Segment {}
    }
```

在 `forText(String)` 方法之后添加:

```java
    public static SelectableTextFlow forSegments(List<Segment> segments, String raw) {
        return forSegments(segments, raw, dest -> { /* no-op */ });
    }

    public static SelectableTextFlow forSegments(List<Segment> segments, String raw, Consumer<String> onLinkClick) {
        List<Node> children = new ArrayList<>();
        int[] offsets = computeOffsetsForSegments(segments, raw);
        for (Segment seg : segments) {
            if (seg instanceof Segment.Text t) {
                children.add(new Text(t.value()));
            } else if (seg instanceof Segment.Emoji e) {
                children.add(EmojiImages.view(e.codepoint(), 16));
            } else if (seg instanceof Segment.Code c) {
                Text codeText = new Text(c.value());
                codeText.getStyleClass().add("md-inline-code");
                children.add(codeText);
            } else if (seg instanceof Segment.Link l) {
                Text linkText = new Text(l.text());
                linkText.getStyleClass().add("md-link");
                linkText.setOnMouseClicked(ev -> onLinkClick.accept(l.dest()));
                children.add(linkText);
            }
        }
        return new SelectableTextFlow(children, offsets, raw);
    }

    private static int[] computeOffsetsForSegments(List<Segment> segments, String raw) {
        int[] offsets = new int[segments.size()];
        int cursor = 0;
        for (int i = 0; i < segments.size(); i++) {
            offsets[i] = cursor;
            Segment seg = segments.get(i);
            int len = switch (seg) {
                case Segment.Text t -> t.value().length();
                case Segment.Emoji e -> e.codepoint().length();
                case Segment.Code c -> c.value().length();
                case Segment.Link l -> l.text().length();
            };
            cursor += len;
        }
        return offsets;
    }
```

并在文件顶部 import 区域添加:

```java
import java.util.ArrayList;
import java.util.function.Consumer;
```

- [ ] **Step 2.4: 跑测试,确认 pass**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test -Dtest=SelectableTextFlowTest 2>&1 | tail -15
```

期望:`Tests run: 9`,BUILD SUCCESS。

- [ ] **Step 2.5: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate && git add src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java src/test/java/com/mordor/kmate/ui/chat/SelectableTextFlowTest.java && git commit -m "feat: add Segment + forSegments factory to SelectableTextFlow"
```

---

## Task 3: `textProperty` 支持流式文本更新

**Files:**
- Modify: `src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java`
- Modify: `src/test/java/com/mordor/kmate/ui/chat/SelectableTextFlowTest.java`

**Interfaces:**
- Consumes: 外部 JavaFX binding
- Produces: instance `textProperty() -> StringProperty`, `getText()`, `setText(String)`

---

- [ ] **Step 3.1: 写 failing test**

在 `SelectableTextFlowTest.java` 末尾追加:

```java
    @Test
    void setText_rebuildsChildren() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        assertEquals(1, flow.getChildren().size());
        flow.setText("world");
        // Text 节点数变化(emoji 拆分可能不同,这里两个都是纯文本 → 都是 1)
        assertEquals(1, flow.getChildren().size());
        assertEquals("world", ((Text) flow.getChildren().get(0)).getText());
    }

    @Test
    void setText_toEmpty_clearsChildren() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        flow.setText("");
        assertEquals(0, flow.getChildren().size());
    }

    @Test
    void setText_replacesRaw() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        flow.setText("world");
        // 验证 raw 已更新: 选 "world" 后复制应得 "world"
        Text text = (Text) flow.getChildren().get(0);
        text.selectRange(0, 5);
        assertEquals("world", flow.currentRawSubstring());
    }

    @Test
    void textProperty_bindingUpdatesText() {
        var prop = new javafx.beans.property.SimpleStringProperty("foo");
        SelectableTextFlow flow = SelectableTextFlow.forText("foo");
        flow.textProperty().bind(prop);
        prop.set("bar");
        assertEquals("bar", ((Text) flow.getChildren().get(0)).getText());
    }
```

- [ ] **Step 3.2: 跑测试,确认 fail**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test -Dtest=SelectableTextFlowTest 2>&1 | tail -20
```

期望:编译失败,`setText` / `textProperty` 不存在。

- [ ] **Step 3.3: 实现 `textProperty`**

修改 `SelectableTextFlow.java`:

1. 在类顶部 import 区域添加:

```java
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
```

2. 在 `private boolean handlerInstalled = false;` 后添加:

```java
    private final StringProperty text = new SimpleStringProperty();

    public final StringProperty textProperty() { return text; }

    public final String getText() { return text.get(); }

    public final void setText(String value) {
        text.set(value);
        rebuildFromText(value);
    }

    private void rebuildFromText(String value) {
        getChildren().clear();
        this.raw = value == null ? "" : value;
        if (value == null || value.isEmpty()) {
            Arrays.fill(this.charOffsets, 0);
            currentSelection.set(new int[]{0, 0});
            return;
        }
        var parts = EmojiImages.flowWithMap(value);
        getChildren().addAll(parts.flow().getChildren());
        // charOffsets 是 final 引用但数组内容可变;新数组可能比旧数组长,Arrays.fill 防旧值残留
        Arrays.fill(this.charOffsets, 0);
        System.arraycopy(parts.charOffsets(), 0, this.charOffsets, 0,
                Math.min(this.charOffsets.length, parts.charOffsets().length));
        attachTextListeners();
        currentSelection.set(new int[]{0, 0});
    }
```

3. **注意** `charOffsets` 字段是 `final int[]`(引用不可变,内容可变)。`rebuildFromText` 用 `Arrays.fill` 清零再 `System.arraycopy` 写入,避免新数组比旧数组短时尾部残留旧值导致 selection 错位。

4. 在构造函数末尾调用 `setText(raw)`:

```java
    SelectableTextFlow(List<Node> children, int[] charOffsets, String raw) {
        this.charOffsets = charOffsets;
        this.raw = raw == null ? "" : raw;
        text.set(this.raw);
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
```

5. **修复 `forSegments` 让它也走 `setText` 路径**(保证 raw 一致):

```java
    public static SelectableTextFlow forSegments(List<Segment> segments, String raw, Consumer<String> onLinkClick) {
        List<Node> children = new ArrayList<>();
        for (Segment seg : segments) {
            if (seg instanceof Segment.Text t) {
                children.add(new Text(t.value()));
            } else if (seg instanceof Segment.Emoji e) {
                children.add(EmojiImages.view(e.codepoint(), 16));
            } else if (seg instanceof Segment.Code c) {
                Text codeText = new Text(c.value());
                codeText.getStyleClass().add("md-inline-code");
                children.add(codeText);
            } else if (seg instanceof Segment.Link l) {
                Text linkText = new Text(l.text());
                linkText.getStyleClass().add("md-link");
                linkText.setOnMouseClicked(ev -> onLinkClick.accept(l.dest()));
                children.add(linkText);
            }
        }
        int[] offsets = computeOffsetsForSegments(segments, raw);
        SelectableTextFlow flow = new SelectableTextFlow(children, offsets, raw);
        return flow;
    }
```

- [ ] **Step 3.4: 跑测试,确认 pass**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test -Dtest=SelectableTextFlowTest 2>&1 | tail -15
```

期望:`Tests run: 13`,BUILD SUCCESS。

- [ ] **Step 3.5: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate && git add src/main/java/com/mordor/kmate/ui/chat/SelectableTextFlow.java src/test/java/com/mordor/kmate/ui/chat/SelectableTextFlowTest.java && git commit -m "feat: add textProperty to SelectableTextFlow for streaming updates"
```

---

## Task 4: 重构 `MessageBubble` 使用新 API,验证 focus bug 修复

**Files:**
- Modify: `src/main/java/com/mordor/kmate/ui/chat/MessageBubble.java` (`buildBubble` 方法)

**Interfaces:**
- Consumes: `SelectableTextFlow.forText(String) -> SelectableTextFlow`
- Produces: `buildBubble` 返回 `SelectableTextFlow` (替代原 `TextFlow`)

---

- [ ] **Step 4.1: 修改 `buildBubble` 方法**

在 `src/main/java/com/mordor/kmate/ui/chat/MessageBubble.java` 中,替换 `buildBubble` 方法 (line 82-97):

**原代码:**

```java
    private TextFlow buildBubble(Message msg, String bubbleStyle) {
        var parts = EmojiImages.flowWithMap(msg.content());
        TextFlow bubble = parts.flow();
        bindBubbleWidth(bubble);
        bubble.getStyleClass().add(bubbleStyle);
        bubble.getStyleClass().add(STYLE_SELECTABLE);
        SelectableTextFlow selectable = new SelectableTextFlow(bubble, parts.charOffsets(), msg.content());
        // 第一次挂载 Scene 时安装监听器
        bubble.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) selectable.installCopyHandler(newScene);
        });
        if (bubble.getScene() != null) {
            selectable.installCopyHandler(bubble.getScene());
        }
        return bubble;
    }
```

**新代码:**

```java
    private SelectableTextFlow buildBubble(Message msg, String bubbleStyle) {
        SelectableTextFlow bubble = SelectableTextFlow.forText(msg.content());
        bindBubbleWidth(bubble);
        bubble.getStyleClass().add(bubbleStyle);
        bubble.getStyleClass().add(STYLE_SELECTABLE);
        return bubble;
    }
```

- [ ] **Step 4.2: 删除未使用的 import**

在 `MessageBubble.java` 顶部,如果 `TextFlow` 不再被其他方法直接用到,删除该 import。当前 `TextFlow` 只在 `buildBubble` 用到,可删除:

```java
import javafx.scene.text.TextFlow;
```

(如果其他方法如 `renderSide` / `renderSystem` 用到 `TextFlow`,保留。检查后只有 `buildBubble` 用到,所以删除。)

- [ ] **Step 4.3: 跑现有测试,确认不破坏**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test -Dtest='MessageBubbleTest,EmojiImagesTest,SelectableTextFlowTest' 2>&1 | tail -15
```

期望:BUILD SUCCESS。

- [ ] **Step 4.4: 编译验证**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw compile 2>&1 | tail -10
```

期望:BUILD SUCCESS。

- [ ] **Step 4.5: 手动启动应用,验证 MessageBubble 可选中**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw javafx:run &
```

进入 chat 后:
1. 发一条含 emoji 的消息 (例如 "你好 🍕 世界")
2. 拖动选区 → 应该看到浅蓝色 `#90CAF9` 高亮
3. Cmd+C → 粘贴到文本编辑器 → 应该得 "你好 🍕 世界" (emoji 完整)

如果**不能选中**,实施 spec §MessageBubble bug 修复 里的 fallback:

**Fallback A — TextFlow mouse pressed 主动 requestFocus:**

在 `SelectableTextFlow` 构造函数末尾追加:

```java
        setOnMousePressed(e -> {
            if (!isFocused()) requestFocus();
        });
```

**Fallback B — 检查 ScrollPane / MessageListView 是否拦截 mouse:**

在 `MessageListView.java:42-44` 检查 `setFitToWidth` / mouseTransparent 设置;如有,改用 `setPannable(false)` 或在 ScrollPane 上拦截 pan。

- [ ] **Step 4.6: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate && git add src/main/java/com/mordor/kmate/ui/chat/MessageBubble.java && git commit -m "ref: use SelectableTextFlow.forText in MessageBubble"
```

---

## Task 5: 重构 `MarkdownView` 每个 block 渲染为 `SelectableTextFlow`

**Files:**
- Modify: `src/main/java/com/mordor/kmate/kelsy/ui/markdown/MarkdownView.java`

**Interfaces:**
- Consumes: `SelectableTextFlow.forSegments(...)`, `MdSpan` / `MdNode` (现有 model)
- Produces: 每个 block 节点是一个 `SelectableTextFlow` 而非裸 `TextFlow`;`Hyperlink` 控件被 `Segment.Link` (styled Text + click) 替代

---

- [ ] **Step 5.1: 替换 `flow(spans)` 方法**

在 `MarkdownView.java` 中,替换 `private TextFlow flow(List<MdSpan> spans)` (line 177-184):

**原代码:**

```java
    private TextFlow flow(List<MdSpan> spans) {
        TextFlow flow = new TextFlow();
        flow.setMinWidth(0);
        for (MdSpan span : spans) {
            addSpan(flow, span, false, false);
        }
        return flow;
    }
```

**新代码:**

```java
    private SelectableTextFlow flow(List<MdSpan> spans) {
        List<SelectableTextFlow.Segment> segments = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        for (MdSpan span : spans) {
            collectSegments(span, segments, raw, false, false);
        }
        SelectableTextFlow sel = SelectableTextFlow.forSegments(segments, raw.toString(), onWorkspaceLink);
        sel.setMinWidth(0);
        return sel;
    }

    private void collectSegments(MdSpan span, List<SelectableTextFlow.Segment> out, StringBuilder raw,
                                  boolean bold, boolean italic) {
        switch (span) {
            case MdSpan.Text t -> {
                out.add(new SelectableTextFlow.Segment.Text(t.value()));
                raw.append(t.value());
            }
            case MdSpan.Strong s -> {
                for (MdSpan child : s.children()) collectSegments(child, out, raw, true, italic);
            }
            case MdSpan.Emphasis e -> {
                for (MdSpan child : e.children()) collectSegments(child, out, raw, bold, true);
            }
            case MdSpan.Code c -> {
                out.add(new SelectableTextFlow.Segment.Code(c.value()));
                raw.append(c.value());
            }
            case MdSpan.Link l -> {
                String text = linkText(l);
                out.add(new SelectableTextFlow.Segment.Link(text, l.dest()));
                raw.append(text);
            }
        }
    }
```

- [ ] **Step 5.2: 删除/重构 `addSpan` 方法**

`addSpan` 方法 (line 186-222) 现在不再被调用,删除它 (从 `private void addSpan(...)` 到方法结束)。

但要保留 `linkText(MdSpan.Link)` 静态方法 (line 224-231),因为 `collectSegments` 还在用。

- [ ] **Step 5.3: 删除未使用的 import**

```java
import javafx.scene.text.Text;
```

`Text` 不再被直接构造 (改用 `Segment.Text` 间接构造),可删除。

验证 import 列表里没有其他地方用 `Text` / `Hyperlink`:
- `Hyperlink` import 可删除 — 现在 link 由 `Segment.Link` 渲染

```java
import javafx.scene.control.Hyperlink;
```

删除。

- [ ] **Step 5.4: 添加缺失的 import**

`SelectableTextFlow` 在 `com.mordor.kmate.ui.chat` 包,而 `MarkdownView` 在 `com.mordor.kmate.kelsy.ui.markdown` 包,需要 import:

```java
import com.mordor.kmate.ui.chat.SelectableTextFlow;
import java.util.ArrayList;
```

- [ ] **Step 5.5: 跑测试**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test 2>&1 | tail -10
```

期望:BUILD SUCCESS,所有测试通过。

- [ ] **Step 5.6: 启动应用,验证知识库 Markdown 可复制**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw javafx:run &
```

进入 chat → 点「知识库」按钮 → 打开一篇知识库文件 → 拖动选区 → 应该看到浅蓝色高亮 → Cmd+C → 粘贴得 raw 文本。

助手气泡的 Markdown 块 (`AssistantBubble` 内的 `MarkdownView` 实例) 同样验证。

- [ ] **Step 5.7: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate && git add src/main/java/com/mordor/kmate/kelsy/ui/markdown/MarkdownView.java && git commit -m "ref: render each markdown block as SelectableTextFlow"
```

---

## Task 6: 重构 `ToolCallCard` 使用 `SelectableTextFlow`

**Files:**
- Modify: `src/main/java/com/mordor/kmate/kelsy/ui/ToolCallCard.java`

---

- [ ] **Step 6.1: 修改 `ToolCallCard` 构造**

替换 `src/main/java/com/mordor/kmate/kelsy/ui/ToolCallCard.java` 整个文件:

```java
package com.mordor.kmate.kelsy.ui;

import com.mordor.kmate.ui.chat.SelectableTextFlow;

import javafx.scene.control.Hyperlink;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/** 助手气泡里的工具调用条,可点「打开」跳到知识库文件。 */
public final class ToolCallCard extends HBox {

    public ToolCallCard(String name, String openPath, Consumer<String> onOpen) {
        getStyleClass().add("tool-card");
        setSpacing(8);
        SelectableTextFlow label = SelectableTextFlow.forText("调用：" + (name == null ? "" : name));
        getChildren().add(label);
        if (openPath != null && !openPath.isBlank()) {
            Hyperlink open = new Hyperlink("打开");
            open.setOnAction(e -> {
                if (onOpen != null) {
                    onOpen.accept(openPath);
                }
            });
            getChildren().add(open);
        }
    }
}
```

- [ ] **Step 6.2: 跑测试**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test 2>&1 | tail -10
```

期望:BUILD SUCCESS。

- [ ] **Step 6.3: 启动应用,验证 ToolCallCard「调用:xxx」可选**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw javafx:run &
```

让秘书调用一个工具(比如 `memory_search`)→ 看到 ToolCallCard → 拖动选区选 "调用:memory_search" → Cmd+C → 粘贴得完整文本。「打开」Hyperlink 仍可点击。

- [ ] **Step 6.4: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate && git add src/main/java/com/mordor/kmate/kelsy/ui/ToolCallCard.java && git commit -m "ref: use SelectableTextFlow in ToolCallCard label"
```

---

## Task 7: 重构 `AssistantBubble` plain Label / 思考块 / 待办块 + ReminderCard 跳转改 Hyperlink

**Files:**
- Modify: `src/main/java/com/mordor/kmate/kelsy/ui/AssistantBubble.java`

---

- [ ] **Step 7.1: 修改 `textLabelFor` 方法**

替换 `AssistantBubble.java:169-177`:

**原代码:**

```java
    private Label textLabelFor(MessageBlock block) {
        Label label = new Label();
        label.setWrapText(true);
        label.textProperty().bind(Bindings.createStringBinding(
                () -> block.content().isEmpty() && block.streamingProperty().get() ? "…" : block.content(),
                block.contentProperty(), block.streamingProperty()));
        bindBubbleWidth(label);
        return label;
    }
```

**新代码:**

```java
    private Region textLabelFor(MessageBlock block) {
        SelectableTextFlow sel = SelectableTextFlow.forText(initialContent(block));
        sel.textProperty().bind(Bindings.createStringBinding(
                () -> initialContent(block),
                block.contentProperty(), block.streamingProperty()));
        bindBubbleWidth(sel);
        return styled(sel);
    }

    private static String initialContent(MessageBlock block) {
        return block.content().isEmpty() && block.streamingProperty().get() ? "…" : block.content();
    }
```

- [ ] **Step 7.2: 修改 `textLabel` 方法**

替换 `AssistantBubble.java:179-184`:

**原代码:**

```java
    private Label textLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        bindBubbleWidth(label);
        return label;
    }
```

**新代码:**

```java
    private Region textLabel(String text) {
        SelectableTextFlow sel = SelectableTextFlow.forText(text);
        bindBubbleWidth(sel);
        return styled(sel);
    }
```

- [ ] **Step 7.3: 修改 `thinkingBox` 方法**

替换 `AssistantBubble.java:148-167`:

**原代码:**

```java
    private Region thinkingBox(MessageBlock block) {
        Label title = new Label("思考过程");
        title.getStyleClass().add("thinking-title");

        Label text = new Label();
        text.setWrapText(true);
        text.getStyleClass().add("thinking-body");
        text.textProperty().bind(Bindings.createStringBinding(
                () -> block.content().isEmpty() && block.streamingProperty().get() ? "…" : block.content(),
                block.contentProperty(), block.streamingProperty()));

        VBox box = new VBox(4, title, text);
        box.getStyleClass().add("thinking-block");
        if (thinkingVisible != null) {
            box.visibleProperty().bind(thinkingVisible);
            box.managedProperty().bind(thinkingVisible);
        }
        bindBubbleWidth(box);
        return box;
    }
```

**新代码:**

```java
    private Region thinkingBox(MessageBlock block) {
        Label title = new Label("思考过程");
        title.getStyleClass().add("thinking-title");

        SelectableTextFlow text = SelectableTextFlow.forText(initialContent(block));
        text.getStyleClass().add("thinking-body");
        text.textProperty().bind(Bindings.createStringBinding(
                () -> initialContent(block),
                block.contentProperty(), block.streamingProperty()));

        VBox box = new VBox(4, title, text);
        box.getStyleClass().add("thinking-block");
        if (thinkingVisible != null) {
            box.visibleProperty().bind(thinkingVisible);
            box.managedProperty().bind(thinkingVisible);
        }
        bindBubbleWidth(box);
        return box;
    }
```

- [ ] **Step 7.4: 修改 `reminderBox` 方法 (含 ReminderCard 跳转改造)**

替换 `AssistantBubble.java:126-146`:

**原代码:**

```java
    private Region reminderBox(List<ReminderItem> items) {
        Label summary = new Label("还有 " + items.size() + " 条待办待处理");
        summary.setWrapText(true);
        VBox box = new VBox(6, summary);
        for (ReminderItem item : items) {
            Label title = new Label(item.title());
            title.setWrapText(true);
            String due = "截止 " + item.due();
            if (item.overdue()) {
                due += "  已逾期";
            }
            Label meta = new Label(due);
            meta.getStyleClass().add("todo-reminder-due");
            VBox card = new VBox(2, title, meta);
            card.getStyleClass().add("todo-reminder-item");
            card.setOnMouseClicked(e -> onWorkspaceLink.accept(item.relativePath()));
            box.getChildren().add(card);
        }
        box.getStyleClass().add("todo-reminder");
        return box;
    }
```

**新代码:**

```java
    private Region reminderBox(List<ReminderItem> items) {
        Label summary = new Label("还有 " + items.size() + " 条待办待处理");
        summary.setWrapText(true);
        VBox box = new VBox(6, summary);
        for (ReminderItem item : items) {
            Label title = new Label(item.title());
            title.setWrapText(true);
            String due = "截止 " + item.due();
            if (item.overdue()) {
                due += "  已逾期";
            }
            Label meta = new Label(due);
            meta.getStyleClass().add("todo-reminder-due");
            Hyperlink open = new Hyperlink("打开");
            open.setOnAction(e -> onWorkspaceLink.accept(item.relativePath()));
            // 保留 VBox 让 todo-reminder-item CSS 仍生效;title + open 同行,meta 在下
            HBox titleRow = new HBox(8, title, open);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            VBox card = new VBox(2, titleRow, meta);
            card.getStyleClass().add("todo-reminder-item");
            box.getChildren().add(card);
        }
        box.getStyleClass().add("todo-reminder");
        return box;
    }
```

- [ ] **Step 7.5: 添加 import**

```java
import com.mordor.kmate.ui.chat.SelectableTextFlow;
import javafx.scene.control.Hyperlink;
```

如果 `Label` 不再被其他方法直接使用,保留 import (title 和 summary 还是 Label)。

- [ ] **Step 7.6: 跑测试**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test 2>&1 | tail -10
```

期望:BUILD SUCCESS。

- [ ] **Step 7.7: 启动应用,验证 AssistantBubble 各区域可选**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw javafx:run &
```

- 让秘书回答 → 助手气泡 plain text / Markdown 拖动选区 → Cmd+C
- 让秘书触发思考块 → 拖动思考块文本 → Cmd+C
- 让秘书调用工具 → ToolCallCard「调用:xxx」可选
- 让秘书提到待办 → 拖动待办标题 → Cmd+C;点「打开」跳转(点空白不跳转)

- [ ] **Step 7.8: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate && git add src/main/java/com/mordor/kmate/kelsy/ui/AssistantBubble.java && git commit -m "ref: use SelectableTextFlow in AssistantBubble text/thinking/reminder"
```

---

## Task 8: CSS 更新 + 视觉回归收尾

**Files:**
- Modify: `src/main/resources/com/mordor/kmate/ui/chat/chat.css`

---

- [ ] **Step 8.1: 添加 `.selectable-text-flow` 选区色规则**

打开 `src/main/resources/com/mordor/kmate/ui/chat/chat.css`,找到现有的 `.bubble-text-selectable` 块 (line 347-349):

```css
.bubble-text-selectable {
    -fx-selection-fill: #90CAF9;
}
```

在其上方添加:

```css
/* 统一选中色: 所有 SelectableTextFlow 实例 */
.selectable-text-flow {
    -fx-selection-fill: #90CAF9;
}

/* Markdown 链接: 视觉模拟原 Hyperlink 样式 (蓝色 + 下划线) */
.selectable-text-flow .md-link {
    -fx-fill: #1565C0;
    -fx-underline: true;
    -fx-cursor: hand;
}

/* Markdown 行内 code: 等宽 + 灰底 */
.selectable-text-flow .md-inline-code {
    -fx-font-family: "Menlo", "Consolas", monospace;
    -fx-background-color: rgba(0, 0, 0, 0.06);
}
```

`.bubble-text-selectable` 保留兼容。

- [ ] **Step 8.2: 跑测试 + 编译**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test 2>&1 | tail -10
```

期望:BUILD SUCCESS。

- [ ] **Step 8.3: macOS 视觉回归**

启动应用,逐项检查:

| 区域 | 检查项 | 期望 |
|---|---|---|
| 普通消息气泡 | 拖动选区 | 浅蓝色 `#90CAF9` 高亮 |
| 助手气泡 plain text | 拖动选区 | 同上 |
| 助手气泡 Markdown 段落 | 拖动选区 | 同上 |
| 助手气泡 Markdown 链接 | 视觉 | 蓝色下划线,鼠标变手型 |
| 助手气泡 Markdown 行内 code | 视觉 | 等宽字体 + 浅灰背景 |
| 助手气泡思考块 | 拖动选区 | 同上 |
| ToolCallCard | 「调用:xxx」拖动选区 | 浅蓝色高亮 |
| ToolCallCard | 「打开」点击 | 仍可跳转 |
| ReminderCard | 标题拖动选区 | 浅蓝色高亮 |
| ReminderCard | 「打开」点击 | 仍可跳转 |
| ReminderCard | 点空白 | 不再跳转(预期变化) |
| 知识库 Markdown | 拖动选区 | 浅蓝色高亮 |
| 任意区域 | Cmd+C | 粘贴得 raw 文本,emoji 完整 |

任何视觉异常,记录在 issue 后回头修。

- [ ] **Step 8.4: 文档:Windows 验证步骤**

在 `docs/superpowers/specs/2026-09-08-extend-selectable-copy-to-assistant-and-knowledge-design.md` 末尾追加 "Windows 验证清单" section (或在 PR description 里给):

```markdown
## Windows 验证清单 (用户自验)

在 Windows 上跑 `./mvnw javafx:run`,逐项检查 (与 macOS 表格一致):

- [ ] 普通消息气泡: 拖动选区 → Ctrl+C → 粘贴得 raw (emoji 完整)
- [ ] 助手气泡 plain text: 同上
- [ ] 助手气泡 Markdown 段落: 同上
- [ ] 助手气泡思考块: 同上
- [ ] ToolCallCard「调用:xxx」可选中 + 复制
- [ ] ReminderCard 标题可选中 + 复制;点空白不跳转;「打开」可跳转
- [ ] 知识库 Markdown 段落可选中 + 复制
- [ ] 选区色 `#90CAF9` 在 Windows 渲染正常

如有视觉异常,截图反馈。
```

- [ ] **Step 8.5: 全量测试最后跑一遍**

```bash
cd /Users/ksw/workspace/repository/kmate && ./mvnw test 2>&1 | tail -15
```

期望:BUILD SUCCESS,180+ tests passing。

- [ ] **Step 8.6: Commit**

```bash
cd /Users/ksw/workspace/repository/kmate && git add src/main/resources/com/mordor/kmate/ui/chat/chat.css docs/superpowers/specs/2026-09-08-extend-selectable-copy-to-assistant-and-knowledge-design.md && git commit -m "feat: add .selectable-text-flow CSS rule + Windows verification checklist"
```

---

## Self-Review Checklist

- **Spec coverage:** 每个 spec 章节都有对应任务 — SelectableTextFlow 升级 (Task 1-3), MessageBubble 重构 (Task 4), MarkdownView 重构 (Task 5), ToolCallCard 重构 (Task 6), AssistantBubble 重构 (Task 7), CSS 更新 (Task 8)
- **No placeholders:** 每个 step 含具体代码/命令;无 "TBD" / "implement later"
- **Type consistency:** `forText(String) -> SelectableTextFlow` 在 Task 1 定义,Task 4-7 复用;`Segment` 在 Task 2 定义,Task 5 使用;`textProperty` 在 Task 3 定义,Task 7 使用
- **Cross-task references:** Task 4 引用 Task 1 API;Task 5 引用 Task 2 API;Task 7 引用 Task 1+3 API
