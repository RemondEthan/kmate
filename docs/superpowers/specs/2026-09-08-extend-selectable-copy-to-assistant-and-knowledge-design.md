# 扩展文本选中复制到助手气泡、知识库、工具调用 — Design Spec

Date: 2026-09-08
Status: Draft (brainstorming approved, pending self-review)
Supersedes: [2026-09-07-password-reveal-and-message-copy-design.md](2026-09-07-password-reveal-and-message-copy-design.md) 中 §Feature 2 的范围限定

## 背景

[`2026-09-07-password-reveal-and-message-copy-design.md`](2026-09-07-password-reveal-and-message-copy-design.md) 给普通消息气泡 (`MessageBubble`) 加了 `SelectableTextFlow` + Scene 级 Ctrl/Cmd+C 复制,但**明确把以下区域排除在外**:

> `AssistantBubble`（秘书气泡）走 `Label` + `MarkdownView` 路径，与 `MessageBubble` 是独立渲染，本次**不在范围内**。

实际使用中用户反馈:

- 普通消息气泡虽已实现机制,但实际**仍无法选中文本**(根因调查待实施阶段)
- 助手气泡 (`AssistantBubble`) 的 plain `Label` / `MarkdownView` / 思考块 / 待办块完全不可选中
- 工具调用卡片 (`ToolCallCard`) 的 `Label("调用：" + name)` 完全不可选中
- 右侧知识库 (`KnowledgePane`) 的 `MarkdownView` 完全不可选中
- 顶部 sources 列表是 `Hyperlink`,文本不可拖选(只可点击)

`SelectableTextFlow` 已存在 (`ui/chat/SelectableTextFlow.java`)、`EmojiImages.flowWithMap` 已暴露 charOffsets、`chat.css` 已设选区色 (`#90CAF9`),扩展只是把已有的"可复制容器"模式铺到其他渲染路径。

## 目标

- 助手气泡的所有可读区域 (plain text、Markdown、思考块、待办标题) **可选中 + Ctrl/Cmd+C 复制**,还原 raw 字符串(emoji 保留 Unicode)
- 工具调用卡片「调用:xxx」文本可选中 + 复制;`打开` Hyperlink 保留点击
- 右侧知识库 `MarkdownView` 全文可选中 + 复制
- 普通消息气泡的可选中行为**实际生效**(当前 bug 修复)
- 选中样式统一 `#90CAF9`
- 所有改造**不破坏**现有 emoji 渲染、布局、自动滚动、点击行为

### 不在范围内

- 顶部 sources 列表 (`KnowledgePane` 顶部) `Hyperlink` 文本可选复制
- 跨段落/跨气泡选区(段落级粒度)
- 复制 Markdown 源(纯文本 + emoji Unicode)
- 右键菜单 / 长按手势 / 工具栏复制按钮
- 跨设备同步复制历史

## 组件

### 新增

| 文件 | 职责 |
|---|---|
| `ui/chat/SelectableTextFlow.java` (扩展) | 新增 `forText(String)` / `forSegments(List<Segment>, String)` 工厂方法,内部自动 `setFocusTraversable(true)` + 自动 `installCopyHandler(Scene)`。新增 `textProperty`(StringProperty),流式更新文本时重建子节点。新增 `Segment` sealed 接口(`Text` / `Emoji` / `Code` / `Link`) |
| `ui/chat/SelectableTextFlowTest.java` (新增) | 覆盖新工厂方法、focusTraversable、textProperty 重建、跨节点 selection 合并、多 TextFlow 隔离、Link 点击回调 |

### 修改

| 文件 | 改动 |
|---|---|
| `ui/chat/SelectableTextFlow.java` | 见上 |
| `ui/chat/MessageBubble.java` | `buildBubble` 改用 `SelectableTextFlow.forText(msg.content())`,移除手动 `installCopyHandler` 与 `sceneProperty` 监听;**移除现有 `(TextFlow, int[], String)` 构造的引用**(已无 caller) |
| `kelsy/ui/AssistantBubble.java` | `textLabelFor`、`textLabel`、`reminderBox` 内的 title/meta Label、`thinkingBox` 内的 text Label 全部替换为 `SelectableTextFlow.forText(...)`;ReminderCard 的 `setOnMouseClicked` 跳转移除,改为 title 旁加 `Hyperlink("打开")` |
| `kelsy/ui/markdown/MarkdownView.java` | 重构渲染路径:每个 block (Heading/Paragraph/ListItem/QuoteChildren/TableCell) 渲染成一个 `SelectableTextFlow`,内部把 `MdSpan` 转成 `Segment` 列表;`Hyperlink` 控件替换为 `Segment.Link`(styled Text + 点击回调) |
| `kelsy/ui/ToolCallCard.java` | `Label("调用：" + name)` → `SelectableTextFlow.forText(...)`;`打开` Hyperlink 保留 |
| `kelsy/ui/knowledge/KnowledgePane.java` | 无逻辑改动;由 `MarkdownView` 内部升级带来可复制能力 |
| `resources/com/mordor/kmate/ui/chat/chat.css` | 新增 `.selectable-text-flow { -fx-selection-fill: #90CAF9; }`;`.bubble-text-selectable` 保留兼容 |

## 行为

### SelectableTextFlow 升级

**工厂方法:**

```java
public static SelectableTextFlow forText(String text)
public static SelectableTextFlow forSegments(List<Segment> segments, String raw)
```

**`forText(String)`:** 内部调用 `EmojiImages.flowWithMap(text)` 拆分 emoji,自动:
- `setFocusTraversable(true)`(修复 MessageBubble "不能选中" bug)
- `getStyleClass().add("selectable-text-flow")`
- `sceneProperty` 监听:Scene 挂载时自动 `addEventFilter(KEY_PRESSED, globalCopyHandler)`

**`forSegments(List<Segment>, String)`:** 自定义段落:
- `Segment.Text(String)` → `Text` 节点
- `Segment.Emoji(String codepoint)` → `EmojiImages.view(codepoint, 16)`
- `Segment.Code(String)` → `Text` 节点,styleClass `md-inline-code`
- `Segment.Link(String text, String dest)` → styled Text(下划线 + #1565C0),`setOnMouseClicked` 触发外部 `Consumer<String>`

`raw` 是复制到剪贴板的纯文本,emoji 保留 Unicode codepoint。

**textProperty:**
```java
StringProperty textProperty()           // get
void setText(String)                    // 重建子节点,重新 attach selection 监听
```
用于 AssistantBubble 流式文本更新。

### Scene-level 复制 handler

**当前实现:** 每个 `MessageBubble.buildBubble` 在 `sceneProperty` 监听里手动 `installCopyHandler(scene)`。`SelectableTextFlow` 持有自己的 `currentSelection` 字段,handler 闭包引用它。

**新实现:** Scene 上**一次性**装 `addEventFilter(KEY_PRESSED, GLOBAL_COPY)`,handler 维护 `WeakReference<SelectableTextFlow> focused`:

```java
private static final EventHandler<KeyEvent> GLOBAL_COPY = e -> {
    if (!COPY_WIN.match(e) && !COPY_MAC.match(e)) return;
    SelectableTextFlow sel = currentFocused();
    if (sel == null || sel.currentSelectionStart() == sel.currentSelectionEnd()) return;
    ClipboardContent content = new ClipboardContent();
    content.putString(sel.currentRawSubstring());
    Clipboard.getSystemClipboard().setContent(content);
    e.consume();
};
```

**focus 跟踪:** 每个 `SelectableTextFlow` 在自己的 `Text` 节点 selection 变化时,把 `this` 设为 `currentFocused`(**per-Scene**,装 handler 时通过闭包引用,不强求 static)。Mouse pressed 在 Text 上时也设为 focused。如果同一 Scene 多次 `installCopyHandler` (实现不强制幂等),只装一次(由 `SelectableTextFlow` 内部 flag 控制)。

### MarkdownView 重构

每个 block (Heading / Paragraph / BulletList item / OrderedList item / Quote child / TableCell) 渲染成一个 `SelectableTextFlow`:

```java
private Region styledFlow(List<MdSpan> spans, String styleClass) {
    List<Segment> segments = spans.stream().map(this::toSegment).toList();
    String raw = spanToRaw(spans);
    SelectableTextFlow sel = SelectableTextFlow.forSegments(segments, raw);
    if (styleClass != null) sel.getStyleClass().add(styleClass);
    return sel;
}

private Segment toSegment(MdSpan span) {
    return switch (span) {
        case MdSpan.Text t -> new Segment.Text(t.value());
        case MdSpan.Strong s -> new Segment.Text(s.toRaw(), boldStyle=true);
        case MdSpan.Emphasis e -> new Segment.Text(e.toRaw(), italicStyle=true);
        case MdSpan.Code c -> new Segment.Code(c.value());
        case MdSpan.Link l -> new Segment.Link(linkText(l), l.dest());
    };
}
```

**`Strong` / `Emphasis` 处理:** 内部 spans 被 flat-mapped 成多个 `Segment.Text`(每个含对应 style 标记),不嵌套 `SelectableTextFlow`。Bold/italic 通过 `text.getStyleClass().add("md-bold")` / `text.setStyle("-fx-font-style: italic;")` 在单个 Text 节点上表达。复制时这些 Segment 全部合并到 raw 字符串,不丢样式语义。

`Link` 渲染为 styled Text + `setOnMouseClicked`,点击时调 `openLink(dest)`(由 `MarkdownView` 构造函数传入的 `Consumer<String> onWorkspaceLink` 兜底,链接 http/https 走 `Desktop.browse`)。

**FencedCode 多行代码块** 用 `Label`(不强制可选 — 多行代码 block-select 行为诡异),保持现状。

### AssistantBubble 改造

```java
// 旧:
private Label textLabelFor(MessageBlock block) { Label label = new Label(); ... }
// 新:
private Region textLabelFor(MessageBlock block) {
    SelectableTextFlow sel = SelectableTextFlow.forText(initialContent(block));
    sel.textProperty().bind(Bindings.createStringBinding(...));
    bindBubbleWidth(sel);
    return styled(sel);
}
```

**Reminder card 跳转改造:**

旧:
```java
VBox card = new VBox(2, title, meta);
card.setOnMouseClicked(e -> onWorkspaceLink.accept(item.relativePath()));
```

新:
```java
Hyperlink open = new Hyperlink("打开");
open.setOnAction(e -> onWorkspaceLink.accept(item.relativePath()));
HBox card = new HBox(8, title, open);
```

(整体点击跳转 → 显式 Hyperlink;避免 click 事件与文本 selection 冲突)

### ToolCallCard 改造

```java
SelectableTextFlow sel = SelectableTextFlow.forText("调用：" + (name == null ? "" : name));
getChildren().add(sel);
if (openPath != null) {
    Hyperlink open = new Hyperlink("打开");
    open.setOnAction(e -> onOpen.accept(openPath));
    getChildren().add(open);
}
```

### MessageBubble bug 修复

`MessageBubble.buildBubble` 改用 `SelectableTextFlow.forText(msg.content())`,依赖新接口的自动 `setFocusTraversable(true)` + 自动 Scene handler 安装。**不**单独调查原 TextFlow focus 根因 — 新抽象里隐式修复。

**实施阶段验证点:** 如果 `setFocusTraversable(true)` 后用户仍无法选中文本,根因可能是 (a) ScrollPane 拦截 mouse press 给 pan,需要 `setOnMousePressed(e -> requestFocus())` 在 TextFlow 上补一个;或 (b) TextFlow 上层有 mouseTransparent/mouseTransparent 链断。两种 fallback 都在 implementation plan 里列出。

### CSS

```css
.selectable-text-flow { -fx-selection-fill: #90CAF9; }
.bubble-text-selectable { -fx-selection-fill: #90CAF9; }  /* 保留兼容 */
.md-view > .text, .md-view .text { -fx-fill: #212121; }   /* 保留 */
```

## 测试

### 新增 `SelectableTextFlowTest.java`

```java
@Test void forText_returnsFocusableTextFlow()              // focusTraversable==true
@Test void forText_emojiSplitIntoImageView()                // 同 EmojiImages 路径
@Test void forText_empty_returnsEmptyFlow()                 // ""
@Test void forSegments_textAndCodeRenderAsTextNodes()       // Code 渲染含 styleClass
@Test void forSegments_linkHasClickHandler()                // Link 点击触发回调
@Test void textProperty_rebuildsNodesOnChange()             // setText 后子节点数变更
@Test void copyAcrossMultipleTextFlows_isolated()           // 多 TextFlow 时只复制 focused
@Test void crossEmojiSelection_mergesCharRangesCorrectly()  // 跨 ImageView 边界 → raw 还原
@Test void globalCopyHandler_writesRawSubstringToClipboard()// Scene-level Ctrl+C 写 raw
@Test void globalCopyHandler_noFocused_doesNotConsume()     // 无 focused → 不消费
```

### 更新 `EmojiImagesTest.java`

无改动(已覆盖基础 emoji 拆分)。

### 视觉回归(手工)

- macOS: `./mvnw javafx:run`,进入 chat
  - 普通消息: 拖动选区蓝色高亮 `#90CAF9`,Cmd+C 粘贴得 raw
  - 助手气泡 plain text / Markdown / 思考块: 同样
  - ReminderCard: 标题可选中,点「打开」跳转,点空白不再跳转(预期变化)
  - ToolCallCard:「调用:xxx」可选中,「打开」可点
  - 知识库 Markdown: 段落可选中复制
  - emoji 跨节点 selection 不丢
- Windows: 同上由用户在 Windows 跑一次

## 数据流

### 选中复制

```
用户拖动 Text 节点
  → Text.selectionStart/End 变化
  → SelectableTextFlow.updateSelection(nodeIdx)
  → currentFocused = this           (static weak ref)
  → rawOffset = charOffsets[idx] + Text.selection*

用户按 Ctrl/Cmd+C
  → Scene KEY_PRESSED filter
  → GLOBAL_COPY handler
  → 取 currentFocused
  → raw.substring(start, end) → ClipboardContent.putString
  → Clipboard.getSystemClipboard().setContent
  → e.consume()

无 focused / 无 selection
  → handler 不消费,JavaFX 默认行为执行
```

### AssistantBubble 流式更新

```
MessageBlock.streamingProperty 变化
  → fillBody(body, msg) 重跑
  → 创建新 SelectableTextFlow
  → 旧 SelectableTextFlow 从 VBox 移除 → GC
  → 新 SelectableTextFlow.textProperty().bind(block.contentProperty())
  → 文本变更 → setText 重建子节点
```

## 错误处理

| 阶段 | 失败情况 | 处理 |
|---|---|---|
| `forText` 解析 emoji | emoji codepoint 不在 CATALOG | 现有 `EmojiImages.view` 返回空 ImageView,文本正常显示 |
| `setText` 重建 | 旧 selection 丢失 | 接受;流式更新时通常无 selection |
| Scene 监听 Scene 引用 | `getScene() == null` | `sceneProperty` 监听挂载后自动装;Scene null 时不装,handler 不响应,降级到 JavaFX 默认 |
| Click 事件冲突 | card click 触发文本 selection 完成 | ReminderCard 已改为 Hyperlink「打开」,无冲突 |
| `Segment.Link` 点击 | `openLink` 抛异常 | try/catch 静默,与现有 `Desktop.browse` 行为一致 |
| 跨平台 emoji 字体 | Windows JavaFX 不渲染彩色 emoji | emoji 走 PNG (现有 `EmojiImages`),不受影响 |
| `Segment.Link` 点击 | `openLink` 抛异常 | try/catch 静默,与现有 `Desktop.browse` 行为一致 |
| `MarkdownView` 内的 `Link` 点击 | 跨平台桌面跳转失败 | 接受;现有 `Desktop.getDesktop().browse` 已有 try/catch |
| `Strong`/`Emphasis` 嵌套 | 当前实现只 flat-map 一层 | 接受;深层嵌套的 markdown 罕见,视觉降级可接受(纯文本不丢) |

## 与 9/7 spec 的关系

| 区域 | 9/7 spec | 9/08 spec |
|---|---|---|
| `MessageBubble` 普通消息 | ✅ 实施完成,实际不可选中(bug) | 🔧 重构为新抽象,隐式修复 |
| `AssistantBubble` plain Label | ❌ 排除 | ✅ 改造 |
| `AssistantBubble` MarkdownView | ❌ 排除 | ✅ 改造 |
| `AssistantBubble` 思考块 | ❌ 排除 | ✅ 改造 |
| `AssistantBubble` ReminderCard | ❌ 排除 | ✅ 改造 |
| `ToolCallCard` | ❌ 排除 | ✅ 改造 |
| `KnowledgePane` MarkdownView | ❌ 排除 | ✅ 改造 |
| `KnowledgePane` 顶部 sources | ❌ 排除 | ❌ 排除(本期范围外) |

## Out of scope 重复确认

- 不改 `MessageBubble` 的 emoji 渲染 / 自动滚动 / bubble 布局
- 不改 WebSocket 协议
- 不改聊天历史 (`ChatHistory`) 编码
- 不改 `MarkdownRenderer`(只改 `MarkdownView` 的渲染输出形式)
- 不动 `KnowledgePane` 顶部 sources 列表
- 不跨段落/跨气泡选区
- 不复制 Markdown 源
- 不加右键菜单
- 不动登录页 / `Mate4K` / `QuitManager`
