# Meeting / Decision Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a meeting, `/note` or “记一下” goes through a slot-clarification gate, then writes a Markdown card plus alias-bearing pointers so later questions like “客户交付 licence 的会，结论是什么？” can be answered from disk.

**Architecture:** No new store. Java only changes `/note` rewrite, seeder directories, and product-owned skill files (overwrite on seed). The agent writes `knowledge/meetings|decisions/*.md` with filesystem tools and uses `memory_save` only for `MEMORY.md` + daily one-liners that include aliases and the card path.

**Tech Stack:** Java 21, JUnit 5, existing AgentScope `memory_save` / `memory_search` / `read_file`. No FTS, no vectors, no new Maven deps.

**Spec:** `docs/superpowers/specs/2026-09-04-meeting-decision-cards-design.md`

---

## File map

**Modify:**

- `src/main/java/com/glodon/mordor/kmate/kelsy/service/SlashCommands.java` — `/note` and `/tidy` rewrite text
- `src/test/java/com/glodon/mordor/kmate/kelsy/service/SlashCommandsTest.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeeder.java` — `meetings` / `decisions` dirs; overwrite skill files
- `src/test/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeederTest.java`
- `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/KNOWLEDGE.md` — catalog lines
- `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/AGENTS.md` — one sentence
- `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/skills/kelsy-knowledge/SKILL.md` — card gate + recall
- `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/skills/kelsy-knowledge/references/examples.md`
- `src/test/java/com/glodon/mordor/kmate/kelsy/service/KnowledgeStoreSearchTest.java` — alias hits on a meeting card

**Do not modify:** `KnowledgeStore.search` (already walks `knowledge/**`), `FindQuery`, `LocalAssistantService`, server, login.

---

### Task 1: `/note` clarification rewrite

**Files:**
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/service/SlashCommandsTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/service/SlashCommands.java`

- [ ] **Step 1: Change the `/note` assertions so the old one-shot text fails**

In `SlashCommandsTest.noteRewrites`, keep `send`, `memory_save`, and the user text, and require the new gate wording:

```java
    @Test
    void noteRewrites() {
        var r = SlashCommands.parse("/note 今天和张三敲定了方案");
        assertTrue(r.send());
        assertTrue(r.outgoing().contains("memory_save"));
        assertTrue(r.outgoing().contains("今天和张三敲定了方案"));
        assertTrue(r.outgoing().contains("卡片"));
        assertTrue(r.outgoing().contains("澄清"));
        assertTrue(r.outgoing().contains("knowledge/meetings"));
    }
```

In `tidyRewrites`, keep `MEMORY.md` and `不要 write_file`, add that tidy must not delete cards:

```java
    @Test
    void tidyRewrites() {
        var r = SlashCommands.parse("/tidy");
        assertTrue(r.send());
        assertTrue(r.outgoing().contains("MEMORY.md"));
        assertTrue(r.outgoing().contains("不要 write_file"));
        assertTrue(r.outgoing().contains("meetings"));
    }
```

Leave `noteWithoutBodyRejected` unchanged (`请写上要记的内容`).

- [ ] **Step 2: Run the two tests and confirm they fail**

Run: `./mvnw -q test -Dtest=SlashCommandsTest#noteRewrites,SlashCommandsTest#tidyRewrites`

Expected: FAIL — outgoing still says `请将以下内容用 memory_save 归档到知识库` and tidy does not mention `meetings`.

- [ ] **Step 3: Replace the `/note` and `/tidy` strings**

In `SlashCommands.parse`, replace only those two `Result.send(...)` arms:

```java
            case "/note" -> rest.isEmpty()
                    ? Result.reject("请写上要记的内容")
                    : Result.send("请将以下内容按会议/决定卡片归档。先抽槽（类型、谁、日期、主题、结论、待办）。"
                            + "不齐先澄清，不要 memory_save、不要写卡片、不要说已经记下。"
                            + "齐了再：用文件系统工具写 knowledge/meetings/ 或 knowledge/decisions/ 卡片（含别名字段），"
                            + "KNOWLEDGE.md 加一行，再用 memory_save 写日记和 MEMORY.md 指针（必须带谁、主题词、别名、卡片路径）。"
                            + "不是会议/决定则仍按一条一事 memory_save。原文：\n" + rest);
            case "/today" -> Result.send(
                    "请用 memory_search / memory_get 汇总今天已归档的工作，列出条目并注明来源路径。");
            case "/tidy" -> Result.send(
                    "MEMORY.md 可能过长。请按 AGENTS.md：把流水迁到卡片或专题页的短指针，"
                            + "指针须带谁、主题词、别名和路径。不删除 knowledge/meetings 或 decisions 里的卡片。"
                            + "只许用 memory_save，不要 write_file。");
```

Do not change `/find`, reject messages, or `default`.

- [ ] **Step 4: Re-run SlashCommandsTest**

Run: `./mvnw -q test -Dtest=SlashCommandsTest`

Expected: PASS (all methods in the class).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/service/SlashCommands.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/service/SlashCommandsTest.java
git commit -m "$(cat <<'EOF'
feat: rewrite /note into a card clarification gate

EOF
)"
```

---

### Task 2: Seed `meetings` / `decisions` and overwrite the skill

**Files:**
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeederTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeeder.java`
- Modify: `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/KNOWLEDGE.md`
- Modify: `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/AGENTS.md`

- [ ] **Step 1: Failing seeder tests**

In `WorkspaceSeederTest.writesMissingFilesOnce`, after the inbox assertion, add:

```java
        assertTrue(Files.isDirectory(dir.resolve("knowledge/meetings")));
        assertTrue(Files.isDirectory(dir.resolve("knowledge/decisions")));
```

Add a second test in the same class:

```java
    @Test
    void overwritesProductSkillButKeepsAgents() throws Exception {
        WorkspaceSeeder.seed(dir);
        Path skill = dir.resolve("skills/kelsy-knowledge/SKILL.md");
        Path examples = dir.resolve("skills/kelsy-knowledge/references/examples.md");
        Files.writeString(skill, "old-skill");
        Files.writeString(examples, "old-examples");
        Files.writeString(dir.resolve("AGENTS.md"), "keep-me");
        WorkspaceSeeder.seed(dir);
        assertEquals("keep-me", Files.readString(dir.resolve("AGENTS.md")));
        assertFalse(Files.readString(skill).equals("old-skill"));
        assertFalse(Files.readString(examples).equals("old-examples"));
        assertTrue(Files.readString(skill).contains("kelsy-knowledge"));
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertFalse;` if missing.

- [ ] **Step 2: Run seeder tests and confirm they fail**

Run: `./mvnw -q test -Dtest=WorkspaceSeederTest`

Expected: FAIL — `knowledge/meetings` missing; skill still `old-skill` after second seed.

- [ ] **Step 3: Create dirs; overwrite skill files; update seed Markdown**

In `WorkspaceSeeder.seed`, change the folder loop and skill writes:

```java
            for (String folder : List.of("people", "projects", "playbooks", "inbox", "meetings", "decisions")) {
                Files.createDirectories(knowledge.resolve(folder));
            }
            writeAlways(
                    workspace.resolve("skills/kelsy-knowledge/SKILL.md"),
                    read("skills/kelsy-knowledge/SKILL.md"));
            writeAlways(
                    workspace.resolve("skills/kelsy-knowledge/references/examples.md"),
                    read("skills/kelsy-knowledge/references/examples.md"));
```

Keep `writeIfAbsent` for `AGENTS.md`, `MEMORY.md`, `knowledge/KNOWLEDGE.md`.

Add:

```java
    private static void writeAlways(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
```

Replace `KNOWLEDGE.md` resource with:

```markdown
# Knowledge

目录，每条一行：路径 + 一句话。正文放在对应文件里，不要写进本页。

- knowledge/people/ — 人
- knowledge/projects/ — 项目
- knowledge/playbooks/ — 流程
- knowledge/inbox/ — 尚未归类
- knowledge/meetings/ — 会议卡片
- knowledge/decisions/ — 决定卡片
```

Replace `AGENTS.md` resource with:

```markdown
# Kelsy

你是用户的个人工作助理。回答简洁、直接，使用中文。

归档、回忆、问到过去的事（含半年前）、或收到 /note /today /tidy 的改写指令时：
先 load_skill_through_path(skillId="kelsy-knowledge", path="SKILL.md")，然后只按该 skill 执行。

会议或决定：槽位不齐先澄清，齐了再写 knowledge/meetings 或 knowledge/decisions 卡片，memory_save 只写带别名的短指针。

禁止 write_file / edit_file 改 MEMORY.md 或 memory/。/find 不会发给你。
```

Existing user `AGENTS.md` / `KNOWLEDGE.md` stay untouched (`writeIfAbsent`). New workspaces get the extra lines.

- [ ] **Step 4: Re-run seeder tests**

Run: `./mvnw -q test -Dtest=WorkspaceSeederTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeeder.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeederTest.java \
        src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/KNOWLEDGE.md \
        src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/AGENTS.md
git commit -m "$(cat <<'EOF'
feat: seed meeting card folders and refresh product skill

EOF
)"
```

---

### Task 3: Card-gate skill

**Files:**
- Modify: `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/skills/kelsy-knowledge/SKILL.md`
- Test: `src/test/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeederTest.java` (already asserts overwrite contains `kelsy-knowledge`; after this write, seed must still pass)

- [ ] **Step 1: Replace `SKILL.md` with the following file in full**

Do not leave old “怎么写一条 / 立刻 memory_save” as the meeting path. Front matter `name` and `description` must remain; harness fails to load the skill if either is missing.

```markdown
---
name: kelsy-knowledge
description: >
  用户个人知识库的归档与回忆。在以下情况必须先加载本 skill 再行动：
  用户提到决定、会议、纪要、结论、偏好、人名、日期、进展、待办；要求记住或记下；
  问到过去的事（昨天、上周、上月、半年前、去年、某年某月）；
  问「当时怎么定的」「当时结论是什么」「张三是谁」「项目进展」；
  客户端改写后的 /note、/today、/tidy 指令出现在对话里。
  覆盖会议/决定卡片、澄清门闩、memory_save / memory_search / memory_get，
  以及 knowledge/ 专题页的读写。
  不要用本 skill 回答与知识库无关的闲聊。
---

# Kelsy 知识库

加载后严格按本文操作。磁盘上的 Markdown 是唯一真相，你的记忆不是。

## 库长什么样

| 路径 | 角色 | 你怎么碰 |
|---|---|---|
| `MEMORY.md` | 短索引，每轮已在上下文里。只留「现在仍为真」 | 只用 `memory_save` |
| `memory/YYYY-MM-DD.md` | 当日流水 | 只用 `memory_save` |
| `knowledge/KNOWLEDGE.md` | 目录（路径 + 一句话） | 文件系统工具 |
| `knowledge/meetings/` `knowledge/decisions/` | 会议/决定卡片正文 | 文件系统工具 |
| `knowledge/people/` `projects/` `playbooks/` `inbox/` | 常青正文 | 文件系统工具 |
| `AGENTS.md` | 人设摘要 | 不要改 |
| `sessions/` | 原始聊天 | 不要当已归档事实 |

`memory_save` **只写** `MEMORY.md` 和当天日记，**不写** `knowledge/`。

禁止 `write_file` / `edit_file` 改 `MEMORY.md` 或 `memory/`。

## 何时走卡片

同时满足才进澄清门闩：

- 用户有归档意图：`/note` 改写指令，或明确「记下 / 记一下 / 归档」；以及
- 内容是会议或决定：出现「会议 / 会 / 纪要 / 结论 / 决定」，或抽槽后类型为 `会议` / `决定`。

「记住我喜欢深色主题」等：不进门闩，按文末「普通一条」`memory_save`。

闲聊、情绪、寒暄：不存。

## 卡片字段

一张卡片一条事件。类型只写 `会议` 或 `决定`。

| 字段 | 规则 |
|---|---|
| 类型 | `会议` / `决定` |
| 谁 | 客户、项目或人名 |
| 日期 | `YYYY-MM-DD` |
| 主题 | 一句话，含以后会搜的词 |
| 结论 | 必填；没有不许落盘 |
| 待办 | 可写「无」 |
| 别名 | 独立一行；你在澄清结束后自动补，用户不必手写 |

别名是检索用词，不是第二条结论。至少补上主题/结论里外语词的中文和常见拼写。固定对照（出现一侧就写全）：`licence` / `license` / `许可证`；`评审` / `review`。可再补明显同指。

模板：

```
# 会议 · 客户XX · 交付 licence

- 日期：2026-09-04
- 类型：会议
- 谁：客户XX
- 主题：交付是否需要 licence
- 结论：先申请再发货
- 待办：无
- 别名：licence, license, 许可证, 交付许可
```

路径（在当前用户知识根下）：

- 会议：`knowledge/meetings/YYYY-MM-DD-<谁>-<主题短slug>.md`
- 决定：`knowledge/decisions/YYYY-MM-DD-<谁>-<主题短slug>.md`

文件名去掉 `/ \ : * ? " < > |`，空白改 `-`，过长截断。已存在则 `-2`、`-3`，不覆盖。

## 澄清（未齐不许落盘）

只根据**本轮归档对话**（含后续澄清）抽槽。不编造，不把旧会话里的名字当成本次已确认。

1. 缺什么问什么。一次只问未决项；多项都空可以并列问。
2. 日期没给：用当天，写明「按 YYYY-MM-DD 记，对吗？」用户改了就改；接着补别的槽且不反对日期，视为接受。
3. 待办没提：问一句；用户说没有 → 「无」。
4. 未齐：禁止 `memory_save`、禁止写卡片、禁止说「已经记下了」。
5. 取消：用户说「先不记了」、结束会话、明显改聊且不再答槽、或又来一条新的 `/note`。盘上不留半张卡，不改索引和日记。再归档当作新的一次。

## 齐了怎么落盘

同一轮内按顺序：

1. 文件系统工具写卡片全文（含别名）。
2. `knowledge/KNOWLEDGE.md` 加一行：`路径 — 谁 + 主题 + 结论缩写`。
3. `memory_save` 写两行指针（都必须含谁、主题词、**全部别名**、卡片相对路径、结论缩写）：
   - `memory/YYYY-MM-DD.md`
   - `MEMORY.md`

回复必须出现实际路径（卡片 + `MEMORY.md` 和/或当日日记）。

同一「谁 + 主题」第三次归档：用文件系统工具写或更新 `knowledge/people/` 或 `projects/` 专题页（结论时间线 + 链回各卡片）；`KNOWLEDGE.md` 加/改一行；`MEMORY.md` 改指专题页；旧卡片保留。

## 普通一条（非会议/决定）

`memory_save` 自含、一条一事：ISO 日期、专名、不写「如上」「他」。

好：`- 2026-03-15 与张三敲定评审方案，周五评审`

坏：`- 敲定了方案`

`MEMORY.md` 目标 2–4KB。`/tidy` 只收瘦索引：过时流水改成指向卡片或专题页的短指针（仍须带谁、主题词、别名、路径）。不删 `knowledge/meetings/` 或 `decisions/` 里的文件。不是清空。

## 怎么查（会议结论 / 半年前）

半年前的日记和卡片正文**不在**本轮自动注入里。只看 `MEMORY.md` 会漏。

1. 抽出可搜词（类型、谁、主题、licence/许可证 等），不要整句当一个关键词。
2. 有相对时间再换成日期窗口（以用户说的「今天」为准，没有则用系统日期）：
   - 昨天 → 当天；上周 → 过去 7 天；上月/本月 → 日历月
   - 半年前 → `(今天 − 6 个月)` 所在月，再各扩一个月（2026-09-02 → 2026-02-01～2026-04-30）
   - 「2026年3月」→ 2026-03-01～2026-03-31
3. **没给日期：不限制日记窗口。**
4. 有窗口时先 `memory_get` 窗口内日记（文件存在才读）。
5. `memory_search` 抽出的词（指针行里应有别名）。
6. 命中指针后 `read_file` **卡片全文**。「结论」以卡片字段为准，不用索引缩写顶替。
7. 问法像会议/结论/决定时，再看已注入的 `KNOWLEDGE.md` / `knowledge/meetings/` / `decisions/` 清单，对得上的再读。
8. 已升格：先读专题页时间线；用户要「当时那一次」再链回旧卡片。
9. 禁止把整个 `knowledge/` 读进回复。

## 怎么答

- 只用刚读到的原文，改写可以，事实不能加。
- 每条关键事实写 **`来源：相对路径`**（卡片用 `来源：knowledge/meetings/…`）。
- 多张卡都像，或结论打架：按日期列出谁 / 主题 / 结论，让用户挑，不要私自合并。
- 用户明确说「更正」才改那张卡，并在卡上加一行：日期 + 新结论，旧结论仍可见。
- 零命中：**只说**没有归档，不许编，不许用会话印象。
- `sessions/` 里有、库里没有：当作没归档。

## 客户端斜杠（你看到的是改写后的中文指令）

| 用户打的 | 你收到的意图 | 你做 |
|---|---|---|
| `/note …` | 强制归档；会议/决定先澄清 | 按本文门闩；齐了才落盘；回复写路径 |
| `/today` | 汇总今天已归档 | search/get **今天**的日记、索引、卡片指针，列表 + 来源 |
| `/tidy` | 收瘦索引 | 只许 `memory_save` 改指针；不删卡片 |
| `/find …` | **不会发给你** | 本地验收。你不必执行 `/find` |

## 对照例子

见同目录 `references/examples.md`（需要时再 `load_skill_through_path`，path 填 `references/examples.md`）。
```

- [ ] **Step 2: Prove seed still loads the new skill**

Run: `./mvnw -q test -Dtest=WorkspaceSeederTest`

Expected: PASS. `overwritesProductSkillButKeepsAgents` still sees `kelsy-knowledge` in the overwritten file.

Optional check after seed in a throwaway dir: the file contains `澄清` and `knowledge/meetings`. If you add that assertion, add it in `overwritesProductSkillButKeepsAgents`:

```java
        assertTrue(Files.readString(skill).contains("澄清"));
        assertTrue(Files.readString(skill).contains("knowledge/meetings"));
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/skills/kelsy-knowledge/SKILL.md \
        src/test/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeederTest.java
git commit -m "$(cat <<'EOF'
feat: teach knowledge skill the meeting card gate

EOF
)"
```

---

### Task 4: Skill examples for the licence meeting

**Files:**
- Modify: `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/skills/kelsy-knowledge/references/examples.md`

- [ ] **Step 1: Replace `examples.md` in full**

```markdown
# 归档与回忆例子

用 `load_skill_through_path` 加载本文件。不要把本页写进 MEMORY.md。

## 会议卡片：槽位不齐

用户 `/note 会上讲了交付`（你收到的是改写后的澄清指令）。

缺谁、结论、待办。只问缺的。此时不要 `memory_save`，不要写 `knowledge/meetings/`。

## 会议卡片：齐了再落盘

用户补全：客户 XX，结论「先申请再发货」，待办无。日期按当天 2026-09-04。

写卡片 `knowledge/meetings/2026-09-04-客户XX-交付licence.md`，正文含：

```
- 结论：先申请再发货
- 别名：licence, license, 许可证, 交付许可
```

`KNOWLEDGE.md` 加一行。`memory_save` 日记和 `MEMORY.md` 各一行，必须出现 `许可证`、`licence` 和卡片路径。

回复点名这些路径。不要说「已经记下了」却不写路径。

## 回忆：客户交付 licence 的会

用户：「有一个关于客户交付 licence 的会议，当时结论是什么？」

不要整句当一个词。search：`会议`、`交付`、`licence`、`许可证`。没给日期，不限制窗口。

命中指针后 `read_file` 卡片。答：「先申请再发货」+ `来源：knowledge/meetings/2026-09-04-客户XX-交付licence.md`。

另有一张不同客户的类似卡：两张都列出，让用户挑。

search 与目录都空：答「知识库没有这场会议的归档。」不要补一段像真的。

## 普通一条（非会议）

用户：「今天下午和张三把评审定在周五了。」

不是会议门闩。`memory_save`：

```
- 2026-09-02 与张三敲定评审方案，周五评审
```

不要收成：`- 定了评审`。

## 取消

澄清中用户说「先不记了」：不写任何文件。

## `/note` 回显

会议卡片落盘后，回复例如：「已写入 `knowledge/meetings/2026-09-04-客户XX-交付licence.md`、`MEMORY.md` 与 `memory/2026-09-04.md`。」
```

- [ ] **Step 2: Seeder still overwrites examples**

Run: `./mvnw -q test -Dtest=WorkspaceSeederTest`

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/skills/kelsy-knowledge/references/examples.md
git commit -m "$(cat <<'EOF'
docs: add meeting-card examples for licence recall

EOF
)"
```

---

### Task 5: `/find` hits aliases on a meeting card

**Files:**
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/service/KnowledgeStoreSearchTest.java`

`KnowledgeStore.search` already `walk`s `knowledge/**`. Do **not** change `KnowledgeStore.java` unless this test fails for a real path bug.

- [ ] **Step 1: Add the failing-if-broken regression**

Append to `KnowledgeStoreSearchTest`:

```java
    @Test
    void hitsMeetingCardByLicenceAlias() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Files.writeString(dir.resolve("MEMORY.md"),
                "- 2026-09-04 客户XX 交付 licence 许可证 → knowledge/meetings/2026-09-04-客户XX-交付licence.md\n");
        Files.writeString(
                dir.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md"),
                """
                # 会议 · 客户XX · 交付 licence
                - 结论：先申请再发货
                - 别名：licence, license, 许可证, 交付许可
                """);
        var store = new KnowledgeStore(dir);
        var byLicense = store.search(FindQuery.parse("许可证", LocalDate.of(2026, 9, 4)));
        var byLicence = store.search(FindQuery.parse("licence", LocalDate.of(2026, 9, 4)));
        assertTrue(byLicense.stream().anyMatch(h ->
                h.relativePath().equals("knowledge/meetings/2026-09-04-客户XX-交付licence.md")));
        assertTrue(byLicence.stream().anyMatch(h ->
                h.relativePath().equals("knowledge/meetings/2026-09-04-客户XX-交付licence.md")));
    }
```

- [ ] **Step 2: Run the new test**

Run: `./mvnw -q test -Dtest=KnowledgeStoreSearchTest#hitsMeetingCardByLicenceAlias`

Expected: PASS without changing production search. If FAIL because `walk` skips the file, fix `KnowledgeStore.search` so `knowledge/**/*.md` is included; do not add a special `meetings` branch.

- [ ] **Step 3: Run the full related suite**

Run: `./mvnw -q test -Dtest=SlashCommandsTest,WorkspaceSeederTest,KnowledgeStoreSearchTest,KnowledgeStoreTest,KnowledgeStoreUserRootTest`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/glodon/mordor/kmate/kelsy/service/KnowledgeStoreSearchTest.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/service/KnowledgeStore.java
git commit -m "$(cat <<'EOF'
test: find licence aliases on meeting cards

EOF
)"
```

If `KnowledgeStore.java` was not modified, omit it from `git add`.

---

## Manual check (after all tasks; no JavaFX automation)

Use a disposable workspace. Existing installs pick up the new skill on next `WorkspaceSeeder.seed` (skill files are overwritten).

1. `/note 会上讲了交付` → asks 谁/结论; `/find licence` is 0.
2. Finish slots (客户 XX, 结论「先申请再发货」, 待办无) → card opens in the knowledge tree.
3. `/find 许可证` and `/find licence` → same path.
4. Ask「客户交付 licence 的会，当时结论是什么？」→ 先申请再发货 + `来源：knowledge/meetings/…`.
5. Archive a second customer’s similar meeting; ask the same sentence → two rows, not merged.
6. Mid-clarify「先不记了」→ no new card, no new `MEMORY.md` line.

---

## Self-review vs spec

| Spec section | Task |
|---|---|
| Card fields, aliases, paths, `-2` collision | Task 3 skill |
| Three writes + `memory_save` pointers with aliases | Task 3 + Task 1 `/note` text |
| Clarification / cancel / default date | Task 3 |
| Non-meeting still old `memory_save` | Task 3 + Task 4 |
| Recall: split terms, no date window, read card, cite, multi-card | Task 3 + Task 4 |
| `/find` unchanged, walks `knowledge/**` | Task 5 |
| `/tidy` keeps cards | Task 1 + Task 3 |
| Seeder dirs; overwrite skill; do not overwrite AGENTS | Task 2 |
| `KNOWLEDGE.md` / `AGENTS.md` seed lines | Task 2 |
| Promotion 3rd time, 更正 | Task 3 |
| Java does not write memory files | no task adds that |
| No vector / FTS | no task adds that |
