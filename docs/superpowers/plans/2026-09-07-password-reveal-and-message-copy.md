# Password Reveal + Message Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在登录卡加 👁 密码可见切换，在聊天气泡加鼠标拖选 + Ctrl/Cmd+C 复制（emoji 还原 Unicode）。

**Architecture:** 双 feature 串行实现。Feature 1 用「PasswordField + TextField 叠放在 StackPane」做掩码切换，独立组件 `PasswordVisibilityField`；Feature 2 在 `EmojiImages` 加 `charOffsets` 跟踪，独立 `SelectableTextFlow` 接管 Ctrl/Cmd+C，按 `raw.substring` 写剪贴板，避开 ImageView 丢失 emoji 的问题。

**Tech Stack:** Java 21、JavaFX 21（`Text` / `TextFlow` / `PasswordField` / `TextField` / `Clipboard`）、JUnit 5、JavaFX Scene `KeyEvent`。

## Global Constraints

- Java source/target = 21
- JavaFX 21；`module-info.java` 当前已导出所有目标包，新组件包路径无需新增 exports
- 不引入新依赖（JUnit 5 已在 `pom.xml`）
- `./mvnw test` 必须全绿
- `./mvnw -Pmac clean package` 至少跑通一次（mac 平台）
- 不改 `server/` 子项目
- 不改 `module-info.java`
- 不动 `AssistantBubble` / `MarkdownView`（明确 out of scope）
- EmojiImages.flow(String) 签名不变，向后兼容
- LoginController.Input record / validate / save 内部代码不动；仅调用方从 `PasswordField.getText()` 改为 `PasswordVisibilityField.getValue()`
- 提交粒度：每个 task 一次 commit；message 用 `feat:` / `test:` / `docs:` / `chore:` 前缀
- 跨平台：Ctrl+C (Windows/Linux) + Cmd+C (Mac) 都消费；其它键不消费

---

## 文件总览

### 新增
| 文件 | 职责 |
|---|---|
| `src/main/java/com/glodon/mordor/kmate/ui/login/PasswordVisibilityField.java` | 叠放 PasswordField + TextField + 👁 按钮；暴露 getValue() / setDisable(boolean) |
| `src/main/java/com/glodon/mordor/kmate/ui/chat/SelectableTextFlow.java` | 接 EmojiImages.flowWithMap 产物；接管 Ctrl/Cmd+C 把 raw 子串写剪贴板 |

### 修改
| 文件 | 改动 |
|---|---|
| `src/main/java/com/glodon/mordor/kmate/ui/chat/EmojiImages.java` | 保留 `flow(String)`；新增 `flowWithMap(String)` 返回 `record FlowParts(TextFlow flow, int[] charOffsets)` |
| `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageBubble.java` | 改用 `flowWithMap`；产物 TextFlow 挂 `bubble-text-selectable`；包成 `SelectableTextFlow` 并 `installCopyHandler` |
| `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java` | 把 `PasswordField password` 换成 `PasswordVisibilityField password`；applyOffline 改调 setDisable；handleConnect 改读 getValue() |
| `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css` | 新增 `.login-password-stack` / `.login-eye-button` / `.login-eye-shown` / `.login-eye-hidden` |
| `src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css` | 新增 `.bubble-text-selectable` |

### 测试新增
| 文件 | 改动 |
|---|---|
| `src/test/java/com/glodon/mordor/kmate/ui/chat/EmojiImagesTest.java` | 新增 4 个 `flowWithMap` 用例 |

---

## Task 1: EmojiImages.flowWithMap + 单测（TDD）

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/EmojiImages.java`
- Create: `src/test/java/com/glodon/mordor/kmate/ui/chat/EmojiImagesTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `EmojiImages.FlowParts(TextFlow flow, int[] charOffsets)`；`EmojiImages.flowWithMap(String text)` 静态方法；`EmojiImages.flow(String text)` 签名不变（向后兼容）

- [ ] **Step 1: 写失败的单测**

创建 `src/test/java/com/glodon/mordor/kmate/ui/chat/EmojiImagesTest.java`：

```java
package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.ui.chat.EmojiImages.FlowParts;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiImagesTest {

    @Test
    void flowWithMap_textOnly_returnsSingleTextOffsetZero() {
        FlowParts parts = EmojiImages.flowWithMap("hello");
        assertEquals(1, parts.flow().getChildren().size());
        assertEquals(0, parts.charOffsets()[0]);
        assertEquals("hello", ((Text) parts.flow().getChildren().get(0)).getText());
    }

    @Test
    void flowWithMap_empty_returnsEmptyFlowAndEmptyOffsets() {
        FlowParts parts = EmojiImages.flowWithMap("");
        assertEquals(0, parts.flow().getChildren().size());
        assertEquals(0, parts.charOffsets().length);
    }

    @Test
    void flowWithMap_offsetsAlignWithRaw() {
        // "a🍕b" — 1 char "a" + 1 emoji (2 UTF-16 code units) + 1 char "b"
        // raw 长度 4；emoji 在 raw offset 1（code unit 偏移）
        String raw = "a🍕b";
        FlowParts parts = EmojiImages.flowWithMap(raw);
        // 子节点：Text("a") + ImageView(🍕) + Text("b")
        // charOffsets：每个子节点对应 raw 中的起始偏移（UTF-16 code unit）
        assertEquals(3, parts.flow().getChildren().size());
        assertEquals(0, parts.charOffsets()[0]);
        assertEquals(1, parts.charOffsets()[1]); // emoji 占 [1, 3)
        assertEquals(3, parts.charOffsets()[2]);
        // 用 charOffsets + raw 重构"a🍕" = raw.substring(0, 3)
        assertEquals("a🍕", raw.substring(parts.charOffsets()[0], parts.charOffsets()[1]));
        // 还原 "🍕b" = raw.substring(1, 4)
        assertEquals("🍕b", raw.substring(parts.charOffsets()[1], raw.length()));
    }

    @Test
    void flowWithMap_consecutiveEmoji_eachTakesOneSlot() {
        String raw = "🍕🍕";
        FlowParts parts = EmojiImages.flowWithMap(raw);
        // 两个 ImageView
        assertTrue(parts.flow().getChildren().get(0) != null);
        assertEquals(0, parts.charOffsets()[0]);
        assertEquals(2, parts.charOffsets()[1]); // 每个 emoji 占 2 code units
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./mvnw test -pl . -Dtest=EmojiImagesTest`
Expected: 编译失败（`EmojiImages.flowWithMap` / `FlowParts` 还不存在）

- [ ] **Step 3: 在 `EmojiImages` 加 `FlowParts` record + `flowWithMap` 方法**

修改 `src/main/java/com/glodon/mordor/kmate/ui/chat/EmojiImages.java`：

1. 在类顶部加 record（紧跟 `EmojiImages() {}` 之后）：

```java
public record FlowParts(TextFlow flow, int[] charOffsets) {}
```

2. 修改 `flow(String)` 私有化其遍历逻辑，新增一个 `flushTextWithOffset` 帮手：

把现有的 `flow` 拆成两半：

```java
public static TextFlow flow(String text) {
    return flowWithMap(text).flow();
}

public static FlowParts flowWithMap(String text) {
    TextFlow flow = new TextFlow();
    if (text == null || text.isEmpty()) {
        return new FlowParts(flow, new int[0]);
    }
    int[] offsets = new int[countChildren(text)];
    int childIdx = 0;
    int i = 0;
    int rawOffset = 0;
    StringBuilder buf = new StringBuilder();
    while (i < text.length()) {
        String match = matchAt(text, i);
        if (match != null && image(match) != null) {
            if (buf.length() > 0) {
                offsets[childIdx] = rawOffset;
                flow.getChildren().add(new Text(buf.toString()));
                rawOffset += buf.length();
                buf.setLength(0);
                childIdx++;
            }
            offsets[childIdx] = rawOffset;
            flow.getChildren().add(view(match, 16));
            rawOffset += match.length();
            i += match.length();
            childIdx++;
        } else {
            buf.append(text.charAt(i));
            i++;
        }
    }
    if (buf.length() > 0) {
        offsets[childIdx] = rawOffset;
        flow.getChildren().add(new Text(buf.toString()));
    }
    return new FlowParts(flow, offsets);
}

private static int countChildren(String text) {
    if (text == null || text.isEmpty()) return 0;
    int count = 0;
    int i = 0;
    while (i < text.length()) {
        String match = matchAt(text, i);
        if (match != null && image(match) != null) {
            count++;
            i += match.length();
        } else {
            // 找下一段连续非 emoji 字符
            int j = i;
            while (j < text.length()) {
                String m = matchAt(text, j);
                if (m != null && image(m) != null) break;
                j++;
            }
            if (j > i) count++; // 一段 Text
            i = j;
        }
    }
    return count;
}
```

注意：原 `flow(String)` 删掉；`flushText` 私有方法也可以删（已被新算法内联）。

- [ ] **Step 4: 跑测试，确认通过**

Run: `./mvnw test -Dtest=EmojiImagesTest`
Expected: PASS（4 tests）

- [ ] **Step 5: 跑全量测试，确认未破坏现有用例**

Run: `./mvnw test`
Expected: PASS（所有测试，含 `EmojiImagesTest` 已有用例如有）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/EmojiImages.java src/test/java/com/glodon/mordor/kmate/ui/chat/EmojiImagesTest.java
git commit -m "feat: add EmojiImages.flowWithMap with charOffsets for raw substring recovery"
```

---

## Task 2: SelectableTextFlow 组件

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ui/chat/SelectableTextFlow.java`

**Interfaces:**
- Consumes: `TextFlow flow + int[] charOffsets + String raw`（来自 Task 1 的 `FlowParts`）
- Produces: `SelectableTextFlow` 实例，方法 `installCopyHandler(Scene scene)`；选区变化时自动维护 raw 子串对应的 Clipboard

- [ ] **Step 1: 创建 `SelectableTextFlow.java`**

```java
package com.glodon.mordor.kmate.ui.chat;

import javafx.scene.Scene;
import javafx.scene.control.TextFieldSkin;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.HashSet;
import java.util.Set;
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
```

注：`TextFieldSkin` 是误加的 import，要删掉。实际代码：

```java
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
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/SelectableTextFlow.java
git commit -m "feat: add SelectableTextFlow for emoji-aware clipboard copy"
```

---

## Task 3: MessageBubble 接入 SelectableTextFlow

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageBubble.java`

**Interfaces:**
- Consumes: `EmojiImages.flowWithMap(String)` (Task 1)、`SelectableTextFlow(TextFlow, int[], String)` (Task 2)
- Produces: `MessageBubble` 内的 TextFlow 同时挂 `bubble-{self|peer|system}` 和 `bubble-text-selectable` 两个 styleClass

- [ ] **Step 1: 修改 `renderSide` / `renderSystem`**

修改 `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageBubble.java`：

1. 把类顶部第 33-34 行 `maxBubbleWidth` 字段下加：

```java
private final Message msg;  // 用于 SelectableTextFlow 的 raw 字符串
```

并在构造器（第 36-47 行）加：

```java
this.msg = msg;
```

实际更简单：直接 `EmojiImages.flowWithMap(msg.content())` 然后 `new SelectableTextFlow(...)`，不存 msg 字段——SelectableTextFlow 内部已经持有 raw。修改如下：

替换 `renderSide` 中的：

```java
TextFlow bubble = EmojiImages.flow(msg.content());
bindBubbleWidth(bubble);
bubble.getStyleClass().add(self ? STYLE_SELF : STYLE_PEER);
```

为：

```java
var parts = EmojiImages.flowWithMap(msg.content());
TextFlow bubble = parts.flow();
bindBubbleWidth(bubble);
bubble.getStyleClass().add(self ? STYLE_SELF : STYLE_PEER);
bubble.getStyleClass().add("bubble-text-selectable");
SelectableTextFlow selectable = new SelectableTextFlow(bubble, parts.charOffsets(), msg.content());
selectable.installCopyHandler(getScene());
```

同样的修改应用在 `renderSystem`：

```java
private void renderSystem(Message msg) {
    setAlignment(Pos.CENTER);
    var parts = EmojiImages.flowWithMap(msg.content());
    TextFlow bubble = parts.flow();
    bindBubbleWidth(bubble);
    bubble.getStyleClass().add(STYLE_SYS);
    bubble.getStyleClass().add("bubble-text-selectable");
    SelectableTextFlow selectable = new SelectableTextFlow(bubble, parts.charOffsets(), msg.content());
    selectable.installCopyHandler(getScene());
    getChildren().add(bubble);
}
```

注意：`getScene()` 在 MessageBubble 还没挂到 Scene 时返回 null。`installCopyHandler` 内部对 null 已经做了防护（`flow.getScene() == null` 时不安装）。但 MessageBubble 一旦挂到 Scene，应该再调一次——为此加个 listener：

在构造器（`super(4)` 之后）加：

```java
sceneProperty().addListener((obs, oldScene, newScene) -> {
    if (newScene != null) {
        selectable.installCopyHandler(newScene);
    }
});
```

但 selectable 是 renderSide 内的局部变量。重构方法：把 SelectableTextFlow 提升到字段。

替换文件：建议直接重写整个 `MessageBubble.java`：

```java
package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.ui.AvatarView;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MessageBubble extends HBox {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String STYLE_SELF = "bubble-self";
    private static final String STYLE_PEER = "bubble-peer";
    private static final String STYLE_SYS = "bubble-system";
    private static final String STYLE_NAME = "bubble-name";
    private static final String STYLE_TIME = "bubble-time";
    private static final String STYLE_SELECTABLE = "bubble-text-selectable";

    private final ObservableValue<? extends Number> maxBubbleWidth;

    public MessageBubble(Message msg, String myName, String peerName, Image peerAvatar, Image myAvatar,
                         ObservableValue<? extends Number> maxBubbleWidth) {
        super(4);
        this.maxBubbleWidth = maxBubbleWidth;
        setFillHeight(false);
        setPadding(new Insets(2, 4, 2, 4));

        switch (msg.sender()) {
            case SELF -> renderSide(msg, displayName(myName, "我"), myAvatar, true);
            case PEER, ASSISTANT -> renderSide(msg, displayName(peerName, "对方"), peerAvatar, false);
            case SYSTEM -> renderSystem(msg);
        }
    }

    private void renderSide(Message msg, String name, Image photo, boolean self) {
        setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add(STYLE_NAME);
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setAlignment(sideMetaAlignment(self));
        nameLabel.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(80, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));

        AvatarView avatar = new AvatarView(name, photo, self, 28);

        Label time = new Label(formatTime(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);
        time.setAlignment(sideMetaAlignment(self));
        time.maxWidthProperty().bind(nameLabel.maxWidthProperty());

        TextFlow bubble = buildBubble(msg, self ? STYLE_SELF : STYLE_PEER);

        VBox col = new VBox(2, nameLabel, bubble, time);
        col.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        HBox row = self ? new HBox(6, col, avatar) : new HBox(6, avatar, col);
        row.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        getChildren().add(row);
    }

    private void renderSystem(Message msg) {
        setAlignment(Pos.CENTER);
        TextFlow bubble = buildBubble(msg, STYLE_SYS);
        getChildren().add(bubble);
    }

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

    private void bindBubbleWidth(Region bubble) {
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }

    static String formatTime(LocalDateTime timestamp) {
        return DATE_TIME.format(timestamp);
    }

    static Pos sideMetaAlignment(boolean self) {
        return self ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT;
    }

    private static String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 跑全量测试**

Run: `./mvnw test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/MessageBubble.java
git commit -m "feat: wire MessageBubble through SelectableTextFlow"
```

---

## Task 4: chat.css 加 `.bubble-text-selectable` 选中样式

**Files:**
- Modify: `src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css`

- [ ] **Step 1: 在文件末尾追加**

```css
.bubble-text-selectable {
    -fx-selection-fill: #90CAF9;
}
```

- [ ] **Step 2: 验证语法**

Run: `./mvnw -DskipTests compile`
Expected: BUILD SUCCESS（CSS 编译不报错；运行时由 JavaFX 加载）

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css
git commit -m "feat: add #90CAF9 selection fill for selectable message bubbles"
```

---

## Task 5: PasswordVisibilityField 组件

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ui/login/PasswordVisibilityField.java`

**Interfaces:**
- Consumes: 无
- Produces: `PasswordVisibilityField extends StackPane`；方法 `getValue()` 返回当前可见字段文本；`setDisable(boolean)` 同步 disable 两个输入框和 👁 按钮；`setPromptText(String)`

- [ ] **Step 1: 创建 `PasswordVisibilityField.java`**

```java
package com.glodon.mordor.kmate.ui.login;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * 密码可见切换控件：PasswordField + TextField 叠放在 StackPane 内，
 * 右侧嵌一个 👁 按钮切换掩码 / 明文。
 *
 * getValue() 永远返回当前可见那个字段的 getText()；外部调用者
 * （LoginController 校验/落盘）不需要关心当前状态。
 */
public class PasswordVisibilityField extends StackPane {

    private static final String EYE_OPEN = "👁";   // 👁
    private static final String EYE_CLOSED = "🙈"; // 🙈

    private final PasswordField masked = new PasswordField();
    private final TextField visible = new TextField();
    private final Button eye = new Button(EYE_OPEN);
    private boolean showingVisible;

    public PasswordVisibilityField() {
        getStyleClass().add("login-password-stack");

        visible.setVisible(false);
        visible.setManaged(false);
        masked.setVisible(true);
        masked.setManaged(true);

        eye.getStyleClass().addAll("login-eye-button", "login-eye-shown");
        eye.setFocusTraversable(false);
        eye.setCursor(javafx.scene.Cursor.HAND);
        eye.setOnAction(e -> toggle());

        StackPane.setAlignment(eye, Pos.CENTER_RIGHT);
        StackPane.setMargin(eye, new javafx.geometry.Insets(0, 6, 0, 0));

        getChildren().addAll(masked, visible, eye);

        // placeholder 在两个字段间同步
        masked.promptTextProperty().addListener((obs, o, n) -> {
            if (visible.getPromptText() == null || visible.getPromptText().isEmpty()) {
                visible.setPromptText(n);
            }
        });
        visible.promptTextProperty().addListener((obs, o, n) -> masked.setPromptText(n));
    }

    public String getValue() {
        return showingVisible ? visible.getText() : masked.getText();
    }

    public void setPromptText(String text) {
        masked.setPromptText(text);
        visible.setPromptText(text);
    }

    @Override
    public void setDisable(boolean disabled) {
        super.setDisable(disabled);
        masked.setDisable(disabled);
        visible.setDisable(disabled);
        eye.setDisable(disabled);
    }

    private void toggle() {
        if (showingVisible) {
            String s = visible.getText();
            masked.setText(s);
            masked.positionCaret(s.length());
            visible.setVisible(false);
            visible.setManaged(false);
            masked.setVisible(true);
            masked.setManaged(true);
            eye.setText(EYE_OPEN);
            eye.getStyleClass().remove("login-eye-hidden");
            eye.getStyleClass().add("login-eye-shown");
            showingVisible = false;
        } else {
            String s = masked.getText();
            visible.setText(s);
            visible.positionCaret(s.length());
            masked.setVisible(false);
            masked.setManaged(false);
            visible.setVisible(true);
            visible.setManaged(true);
            eye.setText(EYE_CLOSED);
            eye.getStyleClass().remove("login-eye-shown");
            eye.getStyleClass().add("login-eye-hidden");
            showingVisible = true;
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/PasswordVisibilityField.java
git commit -m "feat: add PasswordVisibilityField with eye toggle"
```

---

## Task 6: LoginPane 接入 PasswordVisibilityField

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java`

- [ ] **Step 1: 修改字段声明 + 构造器**

把第 45 行：

```java
private final PasswordField password = new PasswordField();
```

改为：

```java
private final PasswordVisibilityField password = new PasswordVisibilityField();
```

把第 93 行：

```java
VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");
```

改为：

```java
VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");
password.setPromptText("输入初始口令");
```

`fieldBox` 接受 `Control`；`PasswordVisibilityField` 继承 `StackPane extends Pane extends Region extends Control`？让我查一下——`StackPane` 是 `Pane` 的子类，`Pane` 是 `Region` 的子类，`Region` 是 `Control` 的子类吗？**不是**。`Control` 是 `Region` 的兄弟（都继承 `Parent`）。

所以 `fieldBox` 的参数 `Control field` 不能直接接 `StackPane`。两种方案：

A. 修改 `fieldBox` 签名为 `fieldBox(String, Region, String)`，覆盖 TextField/PasswordField/StackPane 三类输入控件。

B. 把 `PasswordVisibilityField` 继承 `Region`，让 fieldBox 改签名。

选 A：在 `fieldBox` 方法签名加一个分支：

```java
private VBox fieldBox(String labelText, Region field, String placeholder) {
    Label l = new Label(labelText);
    l.getStyleClass().add("login-field-label");
    l.setMaxWidth(Double.MAX_VALUE);

    if (field instanceof TextField tf) {
        tf.setPromptText(placeholder);
        tf.getStyleClass().add("login-field");
    } else if (field instanceof PasswordField pf) {
        pf.setPromptText(placeholder);
        pf.getStyleClass().add("login-field");
    } else if (field instanceof PasswordVisibilityField pvf) {
        pvf.setPromptText(placeholder);
        // 内部已经包含 styled children，无需外加 class
    }
    field.setMaxWidth(Double.MAX_VALUE);

    VBox box = new VBox(3, l, field);
    box.setMaxWidth(Double.MAX_VALUE);
    box.setFillWidth(true);
    return box;
}
```

把原 `fieldBox` 替换为上述。

- [ ] **Step 2: 修改 `applyOffline`**

第 180 行：

```java
password.setDisable(on);
```

保持不变（`PasswordVisibilityField.setDisable` 内部已经联动了 masked / visible / eye）。

- [ ] **Step 3: 修改 `handleConnect`**

第 249 行：

```java
password.getText(),
```

改为：

```java
password.getValue(),
```

- [ ] **Step 4: 编译验证**

Run: `./mvnw -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 跑全量测试**

Run: `./mvnw test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java
git commit -m "feat: wire LoginPane to PasswordVisibilityField"
```

---

## Task 7: login.css 密码框 + 👁 按钮样式

**Files:**
- Modify: `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css`

- [ ] **Step 1: 在文件末尾追加**

```css
/* 密码可见切换控件 */
.login-password-stack {
    -fx-padding: 0 30 0 0; /* 给右侧 👁 按钮让位 */
}

/* 👁 按钮：透明、hover/pressed 浅灰底、focused 蓝边 */
.login-eye-button {
    -fx-background-color: transparent;
    -fx-text-fill: #5C6370;
    -fx-font-size: 13px;
    -fx-padding: 0 4 0 4;
    -fx-cursor: hand;
    -fx-background-radius: 3;
}

.login-eye-button:hover {
    -fx-background-color: #F2F6FA;
}

.login-eye-button:pressed {
    -fx-background-color: #E4E7ED;
}

.login-eye-button:focused {
    -fx-border-color: #1E6FFF;
    -fx-border-width: 1;
    -fx-border-radius: 3;
}

.login-eye-button:disabled {
    -fx-opacity: 0.55;
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/com/glodon/mordor/kmate/ui/login/login.css
git commit -m "feat: add eye button styles for password visibility toggle"
```

---

## Task 8: 手工验证 + 最终验收

**Files:**
- 无

**参考文档：**
- `docs/superpowers/specs/2026-09-07-password-reveal-and-message-copy-design.md` §测试

- [ ] **Step 1: Feature 1 手工验证（9 项）**

启动应用：

```bash
./mvnw javafx:run
```

逐项验证（参见 spec）：
1. 进入页面 → 密码框 ●●●● + 👁「眼睛」
2. 输入 6 位口令 → 点 👁 → 明文 + 👁「闭眼」
3. 在明文状态再点 👁 → 回到 ●●●●
4. 来回切 5 次 → 最终值不变、光标在末尾
5. 密文复制粘贴得 ●●●●；明文复制粘贴得原文
6. 「脱机登录」→ 密码框 + 👁 同时灰
7. 切回「在线」→ 全部恢复
8. 窗口最大化 / 缩小 → 按钮始终在框内右侧、不溢出
9. 👁 emoji 渲染（截图留档）

记录在 PR description 里。

- [ ] **Step 2: Feature 2 手工验证（7 项）**

启动应用，进入聊天后：

1. 发送 "你好👋世界" → 气泡显示 "你好[PNG]世界"
2. 拖选 "你好" → Ctrl/Cmd+C → 粘贴得 "你好"
3. 拖选 "👋世"（跨 emoji）→ Ctrl/Cmd+C → 粘贴得 "👋世"
4. 拖选全段 → Ctrl/Cmd+C → 粘贴得 "你好👋世界"
5. SYSTEM 系统消息也可拖选复制
6. SELF + PEER 气泡均通过
7. 选中背景为 `#90CAF9` 浅蓝高亮

记录在 PR description 里。

- [ ] **Step 3: 最终 ./mvnw test**

Run: `./mvnw test`
Expected: PASS

- [ ] **Step 4: 打包验证（仅 mac 平台）**

Run: `./mvnw -Pmac clean package -DskipTests`
Expected: BUILD SUCCESS（产物在 `target/dist/`）

- [ ] **Step 5: 最终 commit（如有手动修复）**

如果手工验证发现 bug，修复后 commit：

```bash
git add -A
git commit -m "fix: address findings from manual verification"
```

---

## 自审检查

**1. Spec 覆盖**：
- Feature 1 密码可见切换（👁 内嵌、默认隐藏、脱机联动、切换不丢值）→ Task 5/6/7 ✓
- Feature 2 消息气泡复制（拖选、跨 emoji 还原 Unicode、系统消息支持）→ Task 1/2/3/4 ✓
- 范围外（AssistantBubble、ModelConfigDialog、右键菜单）→ 不在 plan 中 ✓
- 单测（4 个 EmojiImagesTest 用例）→ Task 1 ✓
- 手工验证清单 → Task 8 ✓
- 错误处理（选区为空不消费、Scene null 不安装监听器）→ Task 2 ✓

**2. 占位符扫描**：无 TBD / TODO；所有 step 含完整代码或命令。

**3. 类型一致性**：
- `EmojiImages.FlowParts` 在 Task 1 定义，Task 3 使用 ✓
- `SelectableTextFlow(flow, charOffsets, raw)` 构造签名在 Task 2 定义，Task 3 使用 ✓
- `PasswordVisibilityField.getValue()` 在 Task 5 定义，Task 6 使用 ✓
- `PasswordVisibilityField.setDisable(boolean)` 在 Task 5 定义，Task 6 使用 ✓

无发现需修复的 bug。