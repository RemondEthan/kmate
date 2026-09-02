# 群聊内嵌 Kelsy 秘书 — Design Spec

Date: 2026-09-02
Status: Approved (brainstorming)

## 背景

同级目录的 `kelsy` 是个人智能秘书（JavaFX + 内嵌 AgentScope，知识库在本地 Markdown workspace）。kmate 是端到端加密群聊。本 spec 把 kelsy 的秘书逻辑**迁入 kmate 进程**，在群聊里按房间手动添加后，用 `@kelsy` 与秘书对话；不经过 server，也不转发给对方。

独立 kelsy 工程本轮不改。用户若要在独立应用里接着用同一份知识库，自行把 workspace 指到 `~/.kmate/kelsy/workspace/`。

## 目标

- 未添加 kelsy 的房间与当前 kmate 行为完全一致：无秘书成员、无知识库栏、`@kelsy` 当普通消息转发。
- 房间内可手动添加 / 移除 kelsy，并配置头像；状态按 `imCode` 存在本机，对方不可见。
- 已添加后，以 `@kelsy` 开头的消息走本地秘书；同一条时间线展示（流式、思考、工具卡片、完成后 Markdown）。
- 秘书忙碌时普通群聊仍可发送；再发 `@kelsy` 只提示、不发送。
- 秘书数据与独立 kelsy 隔离：配置 `~/.kmate/kelsy/config.json`，知识库 `~/.kmate/kelsy/workspace/`。全客户端共用一份秘书（跨房间同一记忆）。
- `@kelsy` 对话写入该房间现有加密 `ChatHistory`，重进同一房间可见最终正文。

## 不在范围内

- 改独立 kelsy 仓库、抽出共享 Maven 模块、秘书独立进程 / IPC。
- 服务端感知 kelsy、对方客户端显示 kelsy 成员。
- 多房间同时在线、房间内切换。
- 历史恢复思考区与工具卡片。
- 向量检索、改模型供应商 UI（沿用 kelsy 的 MiniMax + `config.json`）。
- 秘书并行多会话（仍是单 Agent 单会话）。

## 架构

```
kmate 进程
├── 群聊（现有）
│   ImClient → server 转发 → 对方
│   ChatHistory（按 imCode 加密）
└── 秘书（从 kelsy 迁入 com.glodon.mordor.kmate.kelsy）
    AssistantService / LocalAssistantService
    KnowledgeStore / KnowledgePane
    ~/.kmate/kelsy/config.json
    ~/.kmate/kelsy/workspace/
    房间启用状态（按 imCode，仅本机）
```

界面（仅当本房间已添加 kelsy）：

```
[ 成员列表 | 消息时间线 | 知识库 ]
[           输入栏              ]
```

未添加时布局与现在相同：左侧成员 + 中间时间线 + 底栏，无右侧栏。

代码落在 `com.glodon.mordor.kmate.kelsy`，与现有 `ui.chat` / `service` 分开。`pom.xml` 引入 kelsy 同款依赖（AgentScope、Jackson、commonmark 等）。jpackage 改为「jlink 运行时镜像 + 额外 classpath」，不把 AgentScope 打进模块镜像。

## 房间启用

每个 `imCode` 一份本机记录：是否已添加、头像文件路径。存 Java `Preferences`（与 `SaveLastLoginService` 相同机制），key 用 `sha256(imCode)` 的 hex，避免明文配对码进偏好存储。

头像文件：`~/.kmate/avatars/kelsy-<sha256(imCode) hex>.<ext>`，挑选与拷贝复用 `AvatarService` 的流程。

左侧成员列表：

- 未添加：现有「自己 + 对端」，另有入口「添加 kelsy」。点开后选头像、确认。
- 已添加：自己后面固定一位本地成员，`username=kelsy`，`userId=-2`（自己仍是 `-1`），`self=false`。不占 server 房间席位，不出现在 `ImClient.roster()`。点该行往输入框插入 `@kelsy `（末尾空格）。提供「移除 kelsy」。
- 成员人数统计只计真实进出房间的人，不含 kelsy。

移除后立即回到未集成：去掉成员、收起并卸掉知识库栏、之后 `@kelsy` 当普通消息转发。若当时秘书正在回复，允许当前一轮说完，再 `busy=false`；新的 `@kelsy` 已按未添加处理。

换房间必须在新房间再添加。再进已添加过的同一 `imCode` 会恢复成员与头像。

## 提及与发送分流

判定「秘书对话」当且仅当同时成立：

1. 本房间已添加 kelsy；
2. `text.strip()` 以 `@kelsy` 开头（大小写不敏感）；
3. 前缀后是结尾或空白（`@kelsyfoo` 不是提及，走群聊转发）。

点成员插入的文本是 `@kelsy `，满足上述规则。

`ChatController.send`：

| 条件 | 行为 |
|------|------|
| 未添加，任意文本 | 现有路径：`sendChat` + SELF 气泡 + 房间历史 |
| 已添加，非提及 | 同上 |
| 已添加，提及，且 `busy` | 不发送、不清输入框、不写历史；输入栏旁提示「秘书还在回复」（约 3 秒后消失，不入时间线） |
| 已添加，提及，未配置 API key | 不 `sendChat`；时间线加一条 SYSTEM，指出 `~/.kmate/kelsy/config.json` |
| 已添加，提及，空正文（只有 `@kelsy`） | 不调用 Agent；SYSTEM「请输入要问秘书的内容」 |
| 已添加，提及，斜杠命令 | 不 `sendChat`；SELF 气泡保留原文；按 kelsy `SlashCommands` 处理去掉前缀后的正文 |
| 已添加，提及，普通问句 | 不 `sendChat`；SELF 原文入时间线与历史；去掉前缀后交给 `AssistantService.chat` |

输入栏**不**因 `busy` 禁用发送按钮。`send` 返回是否已消费：拦截时返回 false，输入框保留原文；其余返回 true 后清空。

斜杠命令（`/note`、`/today`、`/tidy`、`/find`）只有写成 `@kelsy /find …` 才本地执行。单独 `/find` 转发给对方。

## 时间线与渲染

`Sender` 增加 `ASSISTANT`。

- 用户的 `@kelsy …`：`SELF`，`from` 为当前用户名，`content` 为原文（含 `@kelsy`）。
- 秘书回复：`ASSISTANT`，`from=kelsy`。进行中用迁入的可变气泡（思考 / 工具 / 文本块，流式追加，完成后 Markdown）。
- 普通群聊气泡不变。

`MessageListView`：`ASSISTANT` 或带块的进行中回复走 kelsy 气泡；其余走现有 `MessageBubble`。

## 历史

沿用该房间 `ChatHistory`（`~/.kmate/history/<sha256(imCode)>/messages.log`）。

- 追加 SELF（`@kelsy` 原文）与 ASSISTANT（**最终正文**，`content()` 拼接文本块）。
- `HistoryCodec` 已按 `Sender.name()` 编解码，枚举增加 `ASSISTANT` 即可；旧行不受影响。
- 重进房间：秘书气泡按最终正文做 Markdown 渲染。思考区、工具卡片不从历史恢复。
- 换房间看不到这些气泡；秘书记忆仍在全局 workspace。

忙碌拦截的提示、以及「请输入内容」类短提示：前者不写历史；SYSTEM 配置/空正文提示写入时间线（可进历史，便于知道当时为什么没问成）。

## 配置、workspace、Agent 生命周期

路径：

| 用途 | 路径 |
|------|------|
| 模型配置 | `~/.kmate/kelsy/config.json` |
| 知识库 | `~/.kmate/kelsy/workspace/` |
| 房间启用 | Preferences，key 含 `sha256(imCode)` |
| kelsy 头像 | `~/.kmate/avatars/kelsy-<hash>.<ext>` |

首次需要配置文件时：若存在 `~/.kelsy/config.json` 则复制为模板，否则按 kelsy 现有 `ConfigLoader` 写空模板（POSIX `600`）。不修改 `~/.kelsy/`。

Agent **延迟创建**：本进程第一次在某个房间点「添加 kelsy」时播种 workspace、创建 `LocalAssistantService`。之后各房间共用这一实例（全局一份秘书）。从未添加过则不创建 Agent、不写 workspace。

`Mate4K` 退出 / `QuitManager` 时 `assistant.close()`，避免 AgentScope 非守护线程拖住 JVM。

缺 `model.apiKey`：仍允许添加 kelsy 和进房；第一次 `@kelsy` 出 SYSTEM 提示，不转发。配好 key 后同一会话再次 `@kelsy` 即走 Agent（若创建失败则 SYSTEM 报错并解除 busy）。

## 知识库栏

仅本房间已添加 kelsy 时出现。默认收起。展开/收起绑定 `knowledgeVisible`（与 kelsy 的 `KnowledgePane` 相同：目录树 + Markdown 预览）。展开时 `SplitPane` 初始分隔约 0.70。收起后中间时间线占满右侧。

`/find` 命中后打开对应文件；Agent 完成后刷新树。行为与独立 kelsy 一致。

## 失败

| 情况 | 表现 |
|------|------|
| 缺 API key / 配置无效 | 第一次 `@kelsy`：SYSTEM 指出配置路径；不转发 |
| Agent `onError` | 写在当前秘书气泡末尾；`busy=false`；群聊发送不受影响 |
| Agent 创建失败 | SYSTEM 提示原因；`busy=false` |
| 历史写入失败 | 与现有群聊历史相同，不阻断本条发送 |
| 移除时正在流式 | 本轮说完；之后按未添加分流 |

## 组件职责

| 单元 | 职责 |
|------|------|
| `kelsy.KelsyMention` | 解析是否提及、去掉前缀；不依赖 UI |
| `kelsy.KelsyRoomSettings` | 按 imCode 读写启用与头像路径 |
| `kelsy.KelsyRuntime` | 配置、workspace、单例 Agent、KnowledgeStore；可查询是否已配置 key |
| `ChatController` | 分流发送、busy、成员列表插入本地 kelsy、历史追加 ASSISTANT |
| `InputBar` | 发送是否清空；忙碌提示；提供 `insertMention` |
| `RoomMemberList` | 添加/移除入口、点击插入提及 |
| `ChatPane` | 按启用状态装/卸右侧知识库 |
| 迁入的 `AssistantService` 等 | 与独立 kelsy 同契约；回调仍在后台线程，UI 侧 `Platform.runLater` |

独立 kelsy 的 `ChatController.send` 整栏 busy 禁用**不**迁入输入栏。只迁入 Agent、知识库、斜杠命令、富文本气泡。

## 测试

不打真实模型。用可注入的 `AssistantService` 假实现。

- `KelsyMention`：前缀、大小写、`@kelsyfoo`、未 strip 空白、去掉前缀后的正文。
- 路由：未添加任意文本会调用 `sendChat`；已添加 + 提及不调用；已添加非提及会调用；`busy` 时提及不发送、假服务不被调用。
- 斜杠：`@kelsy /find` 走本地检索；`/find` 单独发送走 `sendChat`。
- `KelsyRoomSettings`：不同 `imCode` 互不影响；移除后启用为 false。
- `HistoryCodec`：编解码 `ASSISTANT`；旧 `SELF/PEER/SYSTEM` 行仍能解。
- 现有 `ChatController` / `ChatHistory` / `InputBar` 在未启用 kelsy 时行为不变。

## 成功标准

1. 新房间不添加 kelsy：界面与发送路径与改前 kmate 一致。
2. 添加后 `@kelsy 你好` 不出现在对方客户端；本机时间线有 SELF + 流式秘书回复。
3. 秘书回复未结束时，普通消息能发出并被对方收到；再发 `@kelsy …` 被拦住并提示。
4. 重进同一房间：仍有 kelsy 成员与头像；历史中可见此前 `@kelsy` 原文与秘书最终正文。
5. 移除后 `@kelsy` 再次被对方收到。
