# 登录卡密码可见切换 + 消息气泡文本复制 — Design Spec

Date: 2026-09-07
Status: Approved (brainstorming)

## 背景

kmate 的登录卡现在用 `PasswordField` 罩着「初始口令」输入，**没有查看明文的入口**。当口令是用户临时设的、对方用 IM 通讯告知时，靠手敲一次看不到自己输的对不对，容易配对失败。

聊天气泡 (`MessageBubble`) 当前是 `TextFlow`，里面是 `Text` 节点 + emoji `ImageView`。JavaFX `Text` 默认 selectable，用户**理论上可以拖选文字 + Ctrl/Cmd+C**，但有两个问题：

1. 没有视觉提示（用户不知道能选中复制）。
2. 选区跨 emoji 时，`ImageView` 不参与选择，复制出来的字符串会**丢掉 emoji**。

`AssistantBubble`（秘书气泡）走 `Label` + `MarkdownView` 路径，与 `MessageBubble` 是独立渲染，本次**不在范围内**。

## 目标

### Feature 1 — 密码可见切换
- 登录卡的「初始口令」框**内嵌**一个 👁 图标按钮（右侧）。
- 默认掩码显示（●●●●）；点击 👁 切换为明文，再次点击切回。
- 脱机模式下按钮与密码框同步 disable。
- 切换瞬间**输入内容不丢**；光标切到末尾。
- 切换时**不主动转移焦点**——点击 👁 按钮本身会让输入框失焦（JavaFX 默认行为），符合用户预期。
- 校验逻辑不变（`LoginController.validate` 仍读 `password.getValue()`；该方法返回当前可见字段的 `getText()`）。

### Feature 2 — 消息气泡文本选择复制
- 普通消息气泡（自己 / 对方 / 系统）的 `TextFlow` 支持鼠标拖选 + Ctrl/Cmd+C 系统复制。
- 选区跨 emoji 时，剪贴板里是**还原后的 Unicode 字符串**（如 "我吃了🍕饭"），不是丢失 emoji 的 "我吃了饭"。
- 选中样式用 `#90CAF9`（贴合现有蓝色主题）。
- 不影响现有 emoji 渲染、点击行为、布局、自动滚动。

## 不在范围内

- 秘书气泡 (`AssistantBubble`) 的 `Label` / `MarkdownView` 复制。
- 右键菜单复制 / 长按复制 / 工具栏复制按钮。
- 复制 Markdown 源（仅复制纯文本 + emoji）。
- 跨会话跨设备的复制历史同步。
- `ModelConfigDialog` 内 API Key 字段的可见性切换按钮（与本 spec 重复，保持 YAGNI）。
- 自动测试 GUI（项目无 TestFX）；手工验证 + 两个轻量级 `EmojiImages` 单测。
- IME 选词期间的处理（KeyEvent 不被消费时仍按 JavaFX 默认）。

## 组件

### 新增

| 文件 | 职责 |
|---|---|
| `ui/login/PasswordVisibilityField.java` | 单一职责：装一个明文 `TextField` + 一个掩码 `PasswordField` + 一个 👁 `Button`，叠在 `StackPane`；暴露 `getValue()` / `setDisable(boolean)` / `setDisableEye(boolean)` |
| `ui/chat/SelectableTextFlow.java` | 接受 `TextFlow` + `int[] charOffsets` + `String raw`；提供 `installCopyHandler(Scene)`，把 JavaFX 默认的 Ctrl/Cmd+C 复制改写成 `raw.substring(start, end)`，保证 emoji 不丢 |

### 修改

| 文件 | 改动 |
|---|---|
| `ui/login/LoginPane.java` | `private final PasswordField password` 改为 `private final PasswordVisibilityField password`；构造时设置其 placeholder；`applyOffline(on)` 改为调 `password.setDisable(on)`；`handleConnect` 构造 `Input` 时取 `password.getValue()` |
| `ui/login/LoginController.java` | `Input.password` 字段不变（仍是 `String`）；调用者从 `PasswordField.getText()` 改为 `PasswordVisibilityField.getValue()`（在 `LoginPane.handleConnect`）；`Input` record / `validate` / `save` 内部代码不动 |
| `resources/.../ui/login/login.css` | 新增 `.login-password-stack`（含 padding-right 给按钮腾位置）、`.login-eye-button`（透明、hover 加 `#F2F6FA`、pressed 加 `#E4E7ED`、focused 蓝边）、`.login-eye-shown`（眼睛字符）、`.login-eye-hidden`（闭眼字符，可选） |
| `ui/chat/EmojiImages.java` | `flow(String)` 保留（向后兼容）；新增 `flowWithMap(String)` 返回 `record FlowParts(TextFlow flow, int[] charOffsets)`；`flow` 内部同步维护 `charOffsets` 数组（每个 `Text` / `ImageView` 的 raw 起始偏移） |
| `ui/chat/MessageBubble.java` | `renderSide` 和 `renderSystem` 改用 `EmojiImages.flowWithMap(msg.content())`，把产物 TextFlow 同时挂两个 styleClass（既有 bubble 样式 `bubble-self` / `bubble-peer` / `bubble-system` + 新增 `bubble-text-selectable`），包成 `SelectableTextFlow` 并 `installCopyHandler` |
| `resources/.../ui/chat/chat.css` | 新增 `.bubble-text-selectable`，给 TextFlow 设 `-fx-selection-fill: #90CAF9`；保留现有 `.bubble-self .text` / `.bubble-peer .text` / `.bubble-system .text` 的 fill 样式 |

## UI 行为

### Feature 1 — 密码可见切换

**初始状态**
- `passwordMasked`（`PasswordField`）可见 / managed、`passwordVisible`（`TextField`）不可见 / unmanaged
- 👁 按钮显示「眼睛」字符（Unicode `U+1F441`）
- 按钮 focusTraversable 默认 false（避免抢焦点）

**点击 👁（当前掩码）**
1. `String s = passwordMasked.getText()`
2. `passwordVisible.setText(s)`
3. `passwordVisible.positionCaret(s.length())`
4. `passwordVisible.setVisible(true)`、`passwordVisible.setManaged(true)`
5. `passwordMasked.setVisible(false)`、`passwordMasked.setManaged(false)`
6. 👁 按钮切到「闭眼」字符（Unicode `U+1F648`）

**点击 👁（当前明文）**
- 上述反向

**`getValue()` 语义**
- 返回**当前可见那个字段**的 `getText()`
- `validate` / `save` 调用者不需要知道当前是掩码还是明文

**脱机模式 `applyOffline(on)`**
- `password.setDisable(on)` —— 内部同时 disable 两个输入框 + 👁 按钮

**焦点跟随**
- 切换瞬间**不**主动 `requestFocus()`；用户光标位置由自己下次点击决定
- 但 positionCaret 保留到末尾（防止切回时光标跳到开头）

### Feature 2 — 消息气泡复制

**初始状态**
- `TextFlow` 内的所有 `Text` 节点保持 `selectable=true`（JavaFX 默认）
- `ImageView` 节点不可选（与现有 `EmojiImages.flow` 一致）
- 选中时背景由 TextFlow 的 `-fx-selection-fill` 控制 → CSS 给 `.bubble-text-selectable` 设 `#90CAF9`

**Ctrl/Cmd+C 处理**
- 在 Scene 级注册 `KeyEvent.KEY_PRESSED` 监听
- 仅消费 `Ctrl+C` (Win/Linux) 或 `Cmd+C` (Mac)
- 从 `e.getTarget()` 向上查找最近的 `Text` 节点（在 `SelectableTextFlow` 注册的 `WeakHashMap<Text, SelectableBubble>` 里查）
- 命中 → 计算 raw 子串 → 写 `Clipboard` → `e.consume()`
- 未命中（用户没选中文本）→ 不消费，放过 JavaFX 默认行为

**charOffsets 数据结构**

```
raw = "我吃了🍕饭"
Text("我吃了")    → [0, 3)
ImageView(🍕)     → [3, 4)         // 跳过 4 chars (1 emoji code point)
Text("饭")        → [4, 5)
charOffsets = [0, 3, 4]            // 每个 Text/ImageView 在 raw 中的起始偏移
```

**rawOffset 还原算法（拖选 [startNode, endNode]）**
```
rawStart = charOffsets[startNode] + (textStart - 0)
rawEnd   = charOffsets[endNode]   + textEnd
// 其中 textStart / textEnd 是 Text.getSelection() 在节点内的偏移
```

### 测试

**手工验证 — Feature 1**
1. 进入页面 → 密码框 ●●●● + 👁「眼睛」
2. 输入 6 位口令 → 点 👁 → 明文 + 👁「闭眼」
3. 在明文状态再点 👁 → 回到 ●●●●
4. 来回切 5 次 → 最终值不变、光标在末尾
5. 密文复制粘贴得 ●●●●；明文复制粘贴得原文
6. 「脱机登录」→ 密码框 + 👁 同时灰
7. 切回「在线」→ 全部恢复
8. 窗口最大化 / 缩小 → 按钮始终在框内右侧、不溢出
9. macOS / Windows / Linux 三平台 👁 emoji 都能渲染（最小验证：截图）

**手工验证 — Feature 2**
1. 输入 "你好👋世界" → 气泡 "你好[PNG]世界"
2. 拖选 "你好" → Ctrl/Cmd+C → 粘贴得 "你好"
3. 拖选 "👋世"（跨 emoji）→ Ctrl/Cmd+C → 粘贴得 "👋世"
4. 拖选全段 → Ctrl/Cmd+C → 粘贴得 "你好👋世界"
5. SYSTEM 消息气泡也可拖选复制
6. SELF（bubble-self）+ PEER（bubble-peer）均通过
7. 选中背景为 `#90CAF9` 浅蓝高亮

**单测（轻量级，不依赖 JavaFX）**

新增 `EmojiImagesTest`：
- `testFlowWithMap_offsetsAlignWithRaw`：输入 "a🍕b" → 验证 `charOffsets` + `raw` 子串还原
- `testFlowWithMap_empty`：空字符串 → 空 TextFlow + 空数组
- `testFlowWithMap_consecutiveEmoji`：输入 "🍕🍕" → 两个 ImageView 占两位
- `testFlowWithMap_textOnly`：纯文本 "hello" → 单个 Text + charOffsets = [0]

`./mvnw test` 必须全绿。

## 数据流

### Feature 1

```
启动 LoginPane
  → 构造 PasswordVisibilityField（含 👁 按钮）
  → applyOffline(false)
       → password.setDisable(false)   ← 内部联动

用户输入
  → masked.setText(...)              ← 默认状态
  → 点 👁 → applyMask(false)
       → 同步 visible.setText / positionCaret / 切换 visibility

用户点「连接」/「进入」
  → LoginPane.handleConnect()
       → Input(..., password.getValue(), ...)
       → LoginController.validate(input)
       → LoginController.save(input)
```

### Feature 2

```
MessageListView.newBubble(m)
  → MessageBubble(m, ...)
       → EmojiImages.flowWithMap(m.content())
            → TextFlow flow + int[] charOffsets
       → new SelectableTextFlow(flow, charOffsets, m.content())
            → 把每个 Text 节点 register 进 WeakHashMap
            → installCopyHandler(scene)

用户拖选
  → JavaFX 默认绘制选区（CSS 给 .bubble-text-selectable 加 selectionFill）
  → Ctrl/Cmd+C
       → Scene 监听器消费
            → 从 e.getTarget() 向上找 Text 节点
            → 查 SelectableBubble
            → raw.substring(rawStart, rawEnd) → Clipboard
       → e.consume()

助理气泡 / 工具调用卡片 / Markdown 渲染
  → 路径不经过 SelectableTextFlow → 行为不变（Label 默认不可选，不在本次范围）

MessageBubble 的 SYSTEM 系统消息分支
  → 同样走 SelectableTextFlow → 与 SELF/PEER 一致支持复制
```

## 错误处理

| 阶段 | 失败情况 | 用户感知 |
|---|---|---|
| PasswordVisibilityField 切换 | setText 抛异常（理论上不会，PasswordField/TextField 不会拒绝字符串） | 静默保留旧值；日志一行 warn |
| PasswordVisibilityField 脱机 | setDisable 同步失败 | UI 可能错位；最低限度保证密码框灰，按钮不灰是已知退化（手动测试覆盖） |
| SelectableTextFlow 安装 | Scene 为 null（极端情况下 MessageBubble 还未挂到 Scene） | 监听器不安装；复制走 JavaFX 默认（emoji 会丢）；不阻塞 UI |
| SelectableTextFlow 选区为空 | 用户没选中就按 Ctrl/Cmd+C | 不消费 KeyEvent，让 JavaFX 默认行为执行（无选区时也无副作用） |
| 切换脱机但密码框已 disabled | masked.isDisabled() → visible 也要 isDisabled() | `PasswordVisibilityField.setDisable` 内部统一处理 |
| 跨平台 emoji 渲染 | 👁 / 🙈 Unicode emoji 在某平台不显示 | 按钮位置正常；图标显示为 □ 或占位符；功能不挂 |

## Out of scope 重复确认

- 不动 `AssistantBubble` / `MarkdownView` / `ToolCallCard`。
- 不动 `ModelConfigDialog` 的 API Key 字段可见性。
- 不新增右键菜单。
- 不改消息持久化格式。
- 不改 WebSocket 协议。
- 不改聊天历史 (`ChatHistory`) 编码。