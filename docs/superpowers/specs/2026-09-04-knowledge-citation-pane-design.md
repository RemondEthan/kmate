# 右侧知识页改为本轮检索原文 — Design Spec

Date: 2026-09-04
Status: Approved

## 背景

右侧 `KnowledgePane` 是目录树 + Markdown 预览，默认关着，打开后停在 `KNOWLEDGE.md`。秘书检索时 Java 只从工具回传抠第一路径挂在工具卡上，**不自动打开、不跳到原文**。回合结束只 `refresh` 树。提问和证据对不上，页卡显得鸡肋。

## 目标

- 问秘书并**整轮答完**后，若确认有检索到的知识文件，再打开或**覆盖**右侧，展示这一问读到的原文。
- 上沿是当前已展示的来源短列表，下面是现有 `MarkdownView`；默认打开列表中最后一篇。
- `@秘书` 发出时**不清空、不关掉**已在右侧的页卡。进行中也不改右侧。
- 只有「本问确实检索到了新原文，并且秘书已经答完」才用新列表覆盖旧内容。本问没有检索到：右侧保持原样（继续显示上一问，或保持关着）。
- 顶栏「知识库」只切换显示，不改已展示的列表。

## 不在范围内

- 把目录树当主界面、向量、改协议。
- 进行中每读一篇就拉开（会闪）。
- 纯 `/note` / `memory_save` / `write_file` 写入不算「检索到」，不因此打开。
- 气泡里贴全文。Java 直写记忆文件。
- 独立 kelsy 仓库。

## 两份列表

- **已展示** `shown`：右侧正在看的来源。只在成功覆盖时替换。
- **本问暂存** `pending`：这一轮检索到、但还没渲染的路径。

`@秘书` 发出（`startAsk`）：只 `pending.clear()`，**不动** `shown`、**不改** `knowledgeVisible`、不 `open`。

本地 `/find`：命中写入 `pending`；有命中才用 `pending` 覆盖 `shown` 并打开；零命中不动右侧。

## 收集路径

只认知识库相对路径，沿用并保持 `KnowledgePathExtractor` 能匹配：

- `MEMORY.md`、`AGENTS.md`
- `memory/*.md`
- `knowledge/**.md`（含 `meetings/`、`decisions/`）

不收录 `sessions/`、`skills/`。

**检索类（计入「有检索」、可打开右侧）：**

- 工具名 `memory_get` / `memory_search` / `read_file`：从**参数预览和回传摘要**里抽取全部匹配路径
- `/find`：`KnowledgeStore.Hit.relativePath()`

**写入类（不单独打开右侧）：** `memory_save` / `write_file` / `edit_file` 抽到的路径**不进本轮列表**。同一轮里若已有检索路径，列表仍只含检索到的文件。

去重，保持**首次出现**顺序。一篇工具结果可多条。抽不到则忽略。

## 何时渲染

进行中**不改** `knowledgeVisible`、不 `open`、不改 `shown`。

仅在以下时刻，且 **`pending` 非空**，才覆盖：

1. 秘书 `onComplete`（整轮答完）
2. `/find` 得到结果且有命中

覆盖：`shown = pending`，`pending.clear()`，`knowledgeVisible = true`，`open` **最后一条**，上沿换成新列表。

`pending` 为空（闲聊、检索失败、工具没带回路径）：**什么都不动**——开着的页卡继续显示上一问，关着的保持关。

顶栏「知识库」：只翻转 `knowledgeVisible`。再打开时仍是 `shown`。`shown` 仍空时点开：只显示「本轮没有引用原文」，**不**回到目录树或默认 `KNOWLEDGE.md`。

## 右侧 UI

`KnowledgePane` **去掉** `TreeView` 和垂直 Split。改为：

- 上沿：本轮来源（短文件名，如 `2026-09-04-客户XX-交付licence.md`），可点选
- 下方：现有 `MarkdownView`（`KnowledgeStore.read` + `MarkdownRenderer`）

点某一条即 `open` 该相对路径。文件缺失/过大/拒绝的文案与现在相同。

工具卡上已有 `openPath` 时：点击则打开右侧并选中该路径（若在本轮列表中）。不在气泡里再渲染全文。

答完后对当前预览再 `read` 一次（避免盘上刚变）。来源列表只以本轮抽取为准，不扫整库。

## 代码拆分

| 单元 | 职责 |
|---|---|
| `KnowledgePathExtractor` | `first` 保留；增加 `all(String)` 返回去重保序的全部路径 |
| `CitationTurn`（或 `ChatController` 内） | `shown` + `pending`；`beginAsk` 只清 pending；`commitIfRetrieved` 非空才覆盖 shown |
| `ChatController` | `startAsk` 不清 shown；工具回传写入 pending；`onComplete` / `runFind` 仅在 pending 非空时覆盖并打开 |
| `KnowledgePane` | 来源列表 + 预览；`setSources(List<String>)`；去掉树 |
| `ChatPane` / 顶栏 | 仍用 `knowledgeVisible`；不改路由 |

工具名用字符串常量比较（`memory_get` 等），与现有 `onToolCall` / `onToolResult` 的 `name` 对齐。`onToolResult` 今日只把 `first(summary)` 写进工具卡：改为对检索类工具把 `all(args+summary)` 写入本轮列表，工具卡仍可挂其中第一条以便点击。

## 数据流

```
startAsk → pending.clear()          // shown / 右侧不动
  → onToolResult(memory_get|search|read_file)
       → PathExtractor.all(args + summary) → pending.add
  → onComplete
       → pending 空? 不动 : shown=pending, show + open(last)

/find → pending = hit paths
     → pending 空? 不动 : shown=pending, show + open(last)
```

## 测试

不启 JavaFX Stage：

- `KnowledgePathExtractor.all`：一段文字里两条 `knowledge/…` 与 `memory/…`，去重保序；无路径为空
- 控制器（可抽纯函数测）：`beginAsk` 不改 shown；pending 空 commit → shown 不变、不关；pending 非空 → shown 换成 pending，默认最后一条
- `/find` 零命中 → 不改 shown；有命中 → 覆盖
- 写入类工具名不把路径加入 pending

手工：问已归档会议 → 答完右侧出现原文；再闲聊 `@秘书` → 右侧仍是上一篇，不闪关；再问另一场会 → 答完后列表和预览换成新原文；点顶栏可开关。

## 风险（接受）

工具回传不含路径时本轮列表会漏，右侧可能该开却不开。不在本期解析 AgentScope 私有工具 JSON 结构，只扫已有路径正则。
