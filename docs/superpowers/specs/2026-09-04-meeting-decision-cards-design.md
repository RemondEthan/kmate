# 会议/决定卡片与澄清归档 — Design Spec

Date: 2026-09-04
Status: Approved

## 背景

现有知识库是三层 Markdown：`MEMORY.md` 每轮注入、日记按日、`knowledge/` 专题按需读。检索是字面匹配。长期问「有一个关于客户交付 licence 的会议，当时结论是什么？」会漏：写入不自含、没有别名、模型只看索引、半年前日记不在上下文里。

用户会后主动 `/note` 或口述归档；信息按理完整（谁、哪天、结论、待办），不够则先澄清再落盘。目标是这类转述问法在个人规模上可追溯，不靠向量，也不让 `MEMORY.md` 膨胀拖垮每轮上下文。

## 目标

- 会议/决定做成**一张卡片一条事件**，固定字段 + 独立「别名」行。
- 槽位不齐先澄清，齐了才落盘；未完成不留半成品。
- 索引只留短指针（含可搜词和路径）；正文只在卡片里。
- 事后按主题词回忆：读卡片「结论」，注明来源路径；多张卡并列，不私自合并。
- `/find` 仍不经模型，用来验收盘上能否搜到（含 `许可证` / `licence` 同一张卡）。

## 不在范围内

- 向量、FTS、SQLite、自动从群聊偷听存卡。
- Java 直写 `MEMORY.md` / `memory/` / 卡片文件。
- 改 AgentScope 会话模型、上下文窗口、`maxIters`。
- 改独立 kelsy 仓库（若两边 skill 要同步，另开）。
- 手改 Markdown 后自动补别名。
- 配置页、导入纪要/录音。

## 何时走卡片（其余仍走旧归档）

同时满足才进澄清门闩：

- 用户有归档意图：`/note`，或明确「记下 / 记一下 / 归档」；以及
- 内容是会议或决定：用户说了「会议 / 会 / 纪要 / 结论 / 决定」，或抽槽后类型为 `会议` / `决定`。

「记住我喜欢用深色主题」等仍按现有 `memory_save` 一条一事，不追问客户名。闲聊无归档意图：不进门闩。

## 卡片

路径在**当前用户知识根**下（与 `KnowledgeStore.forUser(workspace, username)` 相同，不是 workspace 根上的种子树）。

| 字段 | 规则 |
|---|---|
| 类型 | `会议` / `决定` / `其他`（本门闩只写前两种） |
| 谁 | 客户、项目或人名，以后能用来问 |
| 日期 | `YYYY-MM-DD` |
| 主题 | 一句话，含以后会搜的词 |
| 结论 | 必填；没有不许落盘 |
| 待办 | 可写「无」 |
| 别名 | 独立字段；澄清结束后由模型根据主题/结论自动补，用户不必手写 |

模板：

```markdown
# 会议 · 客户XX · 交付 licence

- 日期：2026-09-04
- 类型：会议
- 谁：客户XX
- 主题：交付是否需要 licence
- 结论：先申请再发货
- 待办：无
- 别名：licence, license, 许可证, 交付许可
```

文件：

- 会议：`knowledge/meetings/YYYY-MM-DD-<谁>-<主题短slug>.md`
- 决定：`knowledge/decisions/YYYY-MM-DD-<谁>-<主题短slug>.md`

文件名去掉 `/ \ : * ? " < > |`，空白改 `-`，过长截断。同一天同一谁+主题已存在：加 `-2`、`-3`，不覆盖。

别名作用：给字面检索用，不是第二条结论。`licence` / `license` / `许可证` 这类同指必须写进别名（skill 里给一小份常见对照，模型可再补）。

## 三处落盘（正文不抄三遍）

齐了之后同一轮内：

1. 文件系统工具写卡片全文。
2. `knowledge/KNOWLEDGE.md` 加一行：`路径 — 谁 + 主题 + 结论缩写`。
3. `memory_save` **只**写：
   - `memory/YYYY-MM-DD.md` 一行流水：谁、主题词、别名、卡片相对路径；
   - `MEMORY.md` 一行仍为真的指针：同样带谁、主题词、别名、路径、结论缩写。

禁止 `write_file` / `edit_file` 改 `MEMORY.md` 或 `memory/`（与现规则相同）。

同一「谁 + 主题」第三次归档：用文件系统工具写入或更新 `knowledge/people/` 或 `projects/` 专题页（结论时间线 + 链回各卡片）；`MEMORY.md` 改指专题页；旧卡片文件保留。

## 写入与澄清

`/note` 改写（`SlashCommands`）从「马上 memory_save」改为：按会议/决定卡片归档；先抽槽，不齐先澄清，齐了再按「三处落盘」。口述「帮我记一下会上…」不经斜杠，靠 skill 同一套门闩。

顺序：

1. 只根据**本轮归档对话**抽槽（含后续澄清）。不编造，不把旧会话里的客户名当成本次已确认。
2. 缺什么问什么。一次只问当前未决项；多项都空可以并列问。
3. 日期没给：用当天，回复写明「按 YYYY-MM-DD 记，对吗？」用户改了就改；用户接着补别的槽且不反对，视为接受默认日期。
4. 待办没提：问一句；用户说没有 → 「无」。
5. 槽位未齐：禁止 `memory_save`、禁止写卡片、禁止说「已经记下了」。
6. 齐了：落盘，回复必须出现实际路径（卡片 + `MEMORY.md` 和/或当日日记）。
7. 取消：用户说「先不记了」、结束会话、或明显改聊且不再回答槽位、或又发了一条新的 `/note`。盘上不留半张卡、不改索引和日记。未完成的澄清不写进知识库。再归档同一件事当作新的一次。

澄清在现有 `sessionId=main` 里完成。

## 回忆

问「有一个关于客户交付 licence 的会议，当时结论是什么？」时：

1. 先 `load_skill_through_path` `kelsy-knowledge`。
2. 抽出可搜词（类型、谁、主题、别名候选如 licence/许可证）。有相对时间再换成日期窗口；**没给日期则不限制日记窗口**。
3. 不要把整句当一个关键词。对抽出的词做 `memory_search`（指针行里必须已有别名，否则 search 扫不到 `knowledge/meetings/`）。
4. 命中指针后 `read_file` 卡片；结论以卡片「结论」字段为准，不用索引缩写顶替。
5. 问法像会议/结论时，再看已注入的 `KNOWLEDGE.md` / `knowledge/meetings/`（或 `decisions/`）清单，对得上的再读。
6. 回答只用刚读到的原文；写 `来源：knowledge/meetings/…`。多张都像：按日期列出谁 / 主题 / 结论，让用户挑。零命中：只说没有归档，不许用聊天印象补。
7. 已升格：`MEMORY.md` 指专题页则先读时间线；用户要「当时那一次」再链回旧卡片。

`/find` 不发给模型。`KnowledgeStore.search` 已 `walk` `knowledge/**`，会扫到 meetings/decisions。规则不变：空格分词 AND、日记可加日期窗口、仍扫 `MEMORY.md` 与全部知识页、50 条、256KB 跳过。

## `/tidy` 与手改

`/tidy` 只收瘦 `MEMORY.md`：过时流水改成指向卡片或专题页的短指针（仍须带别名和路径）。不删卡片文件。索引超过约 8KB 仍提示 `/tidy`。

手改 Markdown 合法，下一轮以盘为准。手改缺别名或指针时检索可变弱；不自动补。

卡片与后来口述结论打架：都保留、标日期、让用户看。用户明确说「更正」才改那张卡，并在卡上加一行更正说明（日期 + 新结论 + 旧结论仍可见）。

## 种子

`WorkspaceSeeder`：

- 每次 `createDirectories`：`knowledge/meetings`、`knowledge/decisions`（以及现有 people/projects/playbooks/inbox）。
- 新工作区 `KNOWLEDGE.md` 目录增加 meetings / decisions 两行说明。
- **覆盖写入** `skills/kelsy-knowledge/SKILL.md` 与 `references/examples.md`（属产品规程，不是用户文档）。
- `AGENTS.md` / `MEMORY.md` / 已有 `KNOWLEDGE.md` **不覆盖**。种子 AGENTS 加一句：会议/决定先澄清再写卡片。已有工作区靠更新后的 skill `description` 触发加载。

## 数据流

```
/note 或「记一下会上…」
  → 会议/决定？否 → 旧 memory_save
  → 是 → 抽槽 → 不齐则只问缺的（不落盘）
  → 齐 → 写卡片 + KNOWLEDGE.md 一行
       → memory_save：日记一行 + MEMORY.md 指针（含别名和路径）

事后提问
  → load skill → 抽词 → memory_search → read 卡片
  → 答结论 + 来源；多卡列出

/find 许可证
  → KnowledgeStore.search（不经模型）
```

## 测试

不启 JavaFX、不打真实模型：

- `SlashCommands`：`/note` 改写含澄清/卡片，不再是「立刻 memory_save」单句；无正文仍拒绝。
- `WorkspaceSeeder`：知识根下存在 `knowledge/meetings` 与 `knowledge/decisions`。
- `KnowledgeStore.search`：卡片在 `knowledge/meetings/`、别名含 `许可证` 时，`/find 许可证` 与 `licence` 都能命中该路径（回归）。

手工（实现后按此验收，不自动化）：

1. `/note` 只写「会上讲了交付」→ 先问谁/结论；此时 `/find licence` 为 0。
2. 澄清补全后知识树能打开卡片；结论为约定原文。
3. `/find 许可证` 与 `/find licence` 同一路径。
4. 自然语言问「客户交付 licence 的会，当时结论是什么？」→ 含结论原文和 `来源：knowledge/meetings/…`。
5. 再记另一客户的类似会，再问同一句 → 列出两次，不合成一条。
6. 澄清中说「先不记了」→ 盘上无新卡、索引无新行。

## 风险（接受）

字面检索：问法与卡片、别名、指针行完全没有共同词时仍会漏。不在本期用向量补。准确率靠澄清、别名、指针行带词，不靠把日记整段注入上下文。
