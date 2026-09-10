# Todo Reminders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scan `knowledge/todos/` cards and, from 3 days before due until closed, post one annotated secretary bubble at login / 10:00 / 14:00 (with catch-up), and open those cards on the right pane.

**Architecture:** Java parses todo Markdown, applies a date window, and a per-day slot ledger. `TodoReminderService.evaluate(now)` is a pure decision; `ChatController` posts one `ASSISTANT` message, updates `CitationTurn.shown`, and schedules the next 10:00/14:00. The secretary skill writes todo cards (including meeting todos); Java never flips `closed`.

**Tech Stack:** Java 21, JavaFX 21, JUnit 5. No new dependencies. User-visible strings and new comments in Chinese.

**Spec:** `docs/superpowers/specs/2026-09-04-todo-reminders-design.md`

---

## File map

**Create:**

- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoStatus.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderSlot.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoCard.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoCardParser.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoReminderPolicy.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderLedger.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderFormat.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoScanner.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderBatch.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoReminderService.java`
- matching tests under `src/test/java/com/glodon/mordor/kmate/kelsy/todo/`

**Modify:**

- `CitationTurn.java` + `CitationTurnTest.java` — `replaceShown` / `firstShown`
- `ChatController.java` + `ChatControllerKelsyTest.java` — fire, defer pane, schedule
- `ChatPane.java` / `Mate4K.java` — start/stop reminders
- `AssistantBubble.java` + `chat.css` — darker annotation blocks
- `WorkspaceSeeder.java` + `WorkspaceSeederTest.java` — `knowledge/todos/`
- `skills/kelsy-knowledge/SKILL.md` + `references/examples.md`

**Do not modify:** WebSocket protocol, `HistoryCodec` fields, `KnowledgePathExtractor` regex (already matches `knowledge/**.md`).

---

### Task 1: `TodoCardParser`

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoStatus.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoCard.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoCardParser.java`
- Test: `src/test/java/com/glodon/mordor/kmate/kelsy/todo/TodoCardParserTest.java`

- [ ] **Step 1: Failing tests**

```java
package com.glodon.mordor.kmate.kelsy.todo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoCardParserTest {

    @Test
    void parsesRequiredFields() {
        var card = TodoCardParser.parse("""
                # 待办 · 申请 licence

                - 截止：2026-09-10
                - 状态：open
                - 标题：申请 licence
                """, "knowledge/todos/2026-09-10-申请-licence.md").orElseThrow();
        assertEquals("申请 licence", card.title());
        assertEquals(LocalDate.of(2026, 9, 10), card.due());
        assertEquals(TodoStatus.OPEN, card.status());
        assertEquals("knowledge/todos/2026-09-10-申请-licence.md", card.relativePath());
    }

    @Test
    void titleFallsBackToHeadingThenSlug() {
        var fromHeading = TodoCardParser.parse("""
                # 待办 · 发周报
                - 截止：2026-09-12
                - 状态：open
                """, "knowledge/todos/2026-09-12-发周报.md").orElseThrow();
        assertEquals("发周报", fromHeading.title());

        var fromFile = TodoCardParser.parse("""
                - 截止：2026-09-12
                - 状态：closed
                """, "knowledge/todos/2026-09-12-misc.md").orElseThrow();
        assertEquals("misc", fromFile.title());
        assertEquals(TodoStatus.CLOSED, fromFile.status());
    }

    @Test
    void skipsMissingDueOrBadDate() {
        assertTrue(TodoCardParser.parse("- 状态：open\n", "knowledge/todos/a.md").isEmpty());
        assertTrue(TodoCardParser.parse("- 截止：10月\n- 状态：open\n", "knowledge/todos/a.md").isEmpty());
        assertTrue(TodoCardParser.parse(null, "knowledge/todos/a.md").isEmpty());
    }
}
```

- [ ] **Step 2:** `./mvnw -q test -Dtest=TodoCardParserTest`

Expected: FAIL compile (`TodoCardParser` missing).

- [ ] **Step 3: Implement**

`TodoStatus`: `OPEN`, `CLOSED`.

`TodoCard` record: `String title, LocalDate due, TodoStatus status, String relativePath`.

`TodoCardParser.parse(String markdown, String relativePath)`:

- Blank markdown / blank path → empty.
- For each line, strip, match `- 截止：` / `- 状态：` / `- 标题：` (allow ASCII `:`).
- Due: `LocalDate.parse` `YYYY-MM-DD`; failure → empty.
- Status: `open` / `closed` case-insensitive; anything else → empty.
- Title: 标题 field, else first `# ` heading with optional `待办 · ` prefix stripped, else filename without `.md` after last `-` if present else whole stem (`misc` from `2026-09-12-misc.md`).
- Ignore `- 来源：` and other lines.

- [ ] **Step 4:** `./mvnw -q test -Dtest=TodoCardParserTest` — PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoStatus.java \
  src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoCard.java \
  src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoCardParser.java \
  src/test/java/com/glodon/mordor/kmate/kelsy/todo/TodoCardParserTest.java
git commit -m "$(cat <<'EOF'
feat: parse knowledge/todos Markdown cards

EOF
)"
```

---

### Task 2: Window policy

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoReminderPolicy.java`
- Test: `src/test/java/com/glodon/mordor/kmate/kelsy/todo/TodoReminderPolicyTest.java`

- [ ] **Step 1: Failing tests**

```java
package com.glodon.mordor.kmate.kelsy.todo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoReminderPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);

    @Test
    void windowStartsThreeDaysBeforeDue() {
        assertFalse(TodoReminderPolicy.inWindow(open("远", LocalDate.of(2026, 9, 8)), TODAY));
        assertTrue(TodoReminderPolicy.inWindow(open("临界", LocalDate.of(2026, 9, 7)), TODAY));
        assertTrue(TodoReminderPolicy.inWindow(open("当天", TODAY), TODAY));
        assertTrue(TodoReminderPolicy.inWindow(open("逾期", LocalDate.of(2026, 9, 1)), TODAY));
        assertFalse(TodoReminderPolicy.inWindow(
                new TodoCard("关了", TODAY, TodoStatus.CLOSED, "knowledge/todos/x.md"), TODAY));
    }

    @Test
    void sortsByDueThenTitle() {
        var a = open("周报", LocalDate.of(2026, 9, 12));
        var b = open("申请", LocalDate.of(2026, 9, 10));
        var c = open("补材料", LocalDate.of(2026, 9, 10));
        assertEquals(List.of(b, c, a), TodoReminderPolicy.sort(List.of(a, c, b)));
    }

    private static TodoCard open(String title, LocalDate due) {
        return new TodoCard(title, due, TodoStatus.OPEN, "knowledge/todos/" + title + ".md");
    }
}
```

- [ ] **Step 2:** `./mvnw -q test -Dtest=TodoReminderPolicyTest` — FAIL compile.

- [ ] **Step 3: Implement**

```java
public final class TodoReminderPolicy {
    public static final int LEAD_DAYS = 3;

    private TodoReminderPolicy() {}

    public static boolean inWindow(TodoCard card, LocalDate today) {
        if (card == null || today == null || card.status() != TodoStatus.OPEN) {
            return false;
        }
        return !today.isBefore(card.due().minusDays(LEAD_DAYS));
    }

    public static List<TodoCard> sort(List<TodoCard> cards) {
        return cards.stream()
                .sorted(Comparator.comparing(TodoCard::due).thenComparing(TodoCard::title))
                .toList();
    }

    public static boolean overdue(TodoCard card, LocalDate today) {
        return card != null && today != null && today.isAfter(card.due());
    }
}
```

- [ ] **Step 4:** `./mvnw -q test -Dtest=TodoReminderPolicyTest` — PASS

- [ ] **Step 5: Commit** `feat: define todo reminder date window`

---

### Task 3: `ReminderLedger`

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderSlot.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderLedger.java`
- Test: `src/test/java/com/glodon/mordor/kmate/kelsy/todo/ReminderLedgerTest.java`

- [ ] **Step 1: Failing tests**

```java
package com.glodon.mordor.kmate.kelsy.todo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderLedgerTest {

    @TempDir Path dir;

    @Test
    void marksAndPersistsSameDay() {
        Path file = dir.resolve(".todo-reminder-ledger");
        ReminderLedger a = ReminderLedger.open(file);
        LocalDate day = LocalDate.of(2026, 9, 4);
        assertFalse(a.fired(day, ReminderSlot.LOGIN));
        a.mark(day, EnumSet.of(ReminderSlot.LOGIN, ReminderSlot.TEN));
        assertTrue(a.fired(day, ReminderSlot.LOGIN));
        assertTrue(a.fired(day, ReminderSlot.TEN));
        assertFalse(a.fired(day, ReminderSlot.FOURTEEN));
        assertFalse(a.fired(day.plusDays(1), ReminderSlot.LOGIN));

        ReminderLedger b = ReminderLedger.open(file);
        assertTrue(b.fired(day, ReminderSlot.TEN));
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void corruptFileMeansUnfired() throws Exception {
        Path file = dir.resolve(".todo-reminder-ledger");
        Files.writeString(file, "not-a-ledger");
        ReminderLedger ledger = ReminderLedger.open(file);
        assertFalse(ledger.fired(LocalDate.of(2026, 9, 4), ReminderSlot.LOGIN));
    }
}
```

- [ ] **Step 2:** FAIL compile.

- [ ] **Step 3: Implement**

`ReminderSlot`: `LOGIN`, `TEN`, `FOURTEEN`.

File format (no JSON library), one line:

`2026-09-04 LOGIN TEN`

`ReminderLedger.open(Path file)`:

- Missing / unreadable / unparseable → empty in-memory (no slots fired).
- `fired(date, slot)` true only if stored date equals `date` and slot listed.
- `mark(date, slots)`: if stored date != date, replace; else union. Write `date` + slot names space-separated. Create parent dirs. IO failure: keep memory, do not throw to caller (log via `Diag.warn`).

Path used later: `knowledgeRoot.resolve(".todo-reminder-ledger")` — not under `knowledge/`.

- [ ] **Step 4:** PASS `ReminderLedgerTest`

- [ ] **Step 5: Commit** `feat: persist todo reminder slots per day`

---

### Task 4: Reminder archive text

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderItem.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderFormat.java`
- Test: `src/test/java/com/glodon/mordor/kmate/kelsy/todo/ReminderFormatTest.java`

- [ ] **Step 1: Failing tests**

```java
package com.glodon.mordor.kmate.kelsy.todo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderFormatTest {

    @Test
    void roundTripKeepsPathAndOverdue() {
        LocalDate today = LocalDate.of(2026, 9, 11);
        var cards = List.of(
                new TodoCard("申请 licence", LocalDate.of(2026, 9, 10),
                        TodoStatus.OPEN, "knowledge/todos/2026-09-10-申请-licence.md"),
                new TodoCard("发周报", LocalDate.of(2026, 9, 12),
                        TodoStatus.OPEN, "knowledge/todos/2026-09-12-发周报.md"));
        String text = ReminderFormat.encode(cards, today);
        assertTrue(text.startsWith("还有 2 条待办待处理"));
        var items = ReminderFormat.parse(text).orElseThrow();
        assertEquals(2, items.size());
        assertEquals("knowledge/todos/2026-09-10-申请-licence.md", items.get(0).relativePath());
        assertTrue(items.get(0).overdue());
        assertEquals("发周报", items.get(1).title());
        assertEquals(LocalDate.of(2026, 9, 12), items.get(1).due());
        assertEquals(false, items.get(1).overdue());
    }

    @Test
    void parseRejectsPlainChat() {
        assertTrue(ReminderFormat.parse("今天下午有会").isEmpty());
        assertTrue(ReminderFormat.parse(null).isEmpty());
    }
}
```

- [ ] **Step 2:** FAIL compile.

- [ ] **Step 3: Implement**

`ReminderItem` record: `String title, LocalDate due, boolean overdue, String relativePath`.

`encode`: first line `还有 N 条待办待处理`. Each card: if overdue (`today.isAfter(due)`) then

`- {title} | 截止 {due} | 已逾期 | {path}`

else

`- {title} | 截止 {due} | {path}`

`parse`: first line must start with `还有 ` and contain `条待办待处理`. Subsequent `- ` lines split on ` | `. Last field is path (must start with `knowledge/todos/`). `截止 YYYY-MM-DD` required. Optional `已逾期`. Empty → empty optional.

- [ ] **Step 4:** PASS

- [ ] **Step 5: Commit** `feat: encode todo reminders for chat history`

---

### Task 5: Scan directory + evaluate slots

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoScanner.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/ReminderBatch.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/todo/TodoReminderService.java`
- Test: `src/test/java/com/glodon/mordor/kmate/kelsy/todo/TodoReminderServiceTest.java`

- [ ] **Step 1: Failing tests**

```java
package com.glodon.mordor.kmate.kelsy.todo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoReminderServiceTest {

    @TempDir Path root;

    @Test
    void loginAt1001FiresLoginAndTenOnce() throws Exception {
        writeOpen("knowledge/todos/2026-09-07-临界.md", "临界", "2026-09-07");
        TodoReminderService svc = new TodoReminderService(root);
        var now = LocalDateTime.of(2026, 9, 4, 10, 1);
        var batch = svc.evaluate(now).orElseThrow();
        assertEquals(1, batch.todos().size());
        assertEquals(EnumSet.of(ReminderSlot.LOGIN, ReminderSlot.TEN), batch.slots());
        svc.commit(batch, now.toLocalDate());
        assertTrue(svc.evaluate(now).isEmpty());
    }

    @Test
    void loginAt0900OnlyLoginThenTenFiresLater() throws Exception {
        writeOpen("knowledge/todos/2026-09-04-当天.md", "当天", "2026-09-04");
        TodoReminderService svc = new TodoReminderService(root);
        var morning = LocalDateTime.of(2026, 9, 4, 9, 0);
        var first = svc.evaluate(morning).orElseThrow();
        assertEquals(EnumSet.of(ReminderSlot.LOGIN), first.slots());
        svc.commit(first, morning.toLocalDate());
        assertTrue(svc.evaluate(morning).isEmpty());
        var ten = LocalDateTime.of(2026, 9, 4, 10, 0);
        var second = svc.evaluate(ten).orElseThrow();
        assertEquals(EnumSet.of(ReminderSlot.TEN), second.slots());
    }

    @Test
    void emptyWindowDoesNotMark() throws Exception {
        writeOpen("knowledge/todos/2026-09-08-远.md", "远", "2026-09-08");
        TodoReminderService svc = new TodoReminderService(root);
        var now = LocalDateTime.of(2026, 9, 4, 10, 1);
        assertTrue(svc.evaluate(now).isEmpty());
        writeOpen("knowledge/todos/2026-09-07-临界.md", "临界", "2026-09-07");
        var later = svc.evaluate(now).orElseThrow();
        assertEquals(EnumSet.of(ReminderSlot.LOGIN, ReminderSlot.TEN), later.slots());
    }

    @Test
    void skipsClosedAndBadFiles() throws Exception {
        write("knowledge/todos/bad.md", "- 状态：open\n");
        write("knowledge/todos/2026-09-04-关.md", """
                - 截止：2026-09-04
                - 状态：closed
                - 标题：关
                """);
        TodoReminderService svc = new TodoReminderService(root);
        assertTrue(svc.evaluate(LocalDateTime.of(2026, 9, 4, 15, 0)).isEmpty());
    }

    @Test
    void nextClock() {
        assertEquals(LocalDateTime.of(2026, 9, 4, 10, 0),
                TodoReminderService.nextClock(LocalDateTime.of(2026, 9, 4, 9, 0)));
        assertEquals(LocalDateTime.of(2026, 9, 4, 14, 0),
                TodoReminderService.nextClock(LocalDateTime.of(2026, 9, 4, 10, 0)));
        assertEquals(LocalDateTime.of(2026, 9, 5, 10, 0),
                TodoReminderService.nextClock(LocalDateTime.of(2026, 9, 4, 14, 0)));
    }

    private void writeOpen(String rel, String title, String due) throws Exception {
        write(rel, "- 截止：" + due + "\n- 状态：open\n- 标题：" + title + "\n");
    }

    private void write(String rel, String body) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }
}
```

- [ ] **Step 2:** FAIL compile.

- [ ] **Step 3: Implement**

`ReminderBatch` record: `List<TodoCard> todos, EnumSet<ReminderSlot> slots`.

`TodoScanner.list(Path knowledgeRoot)`: if `knowledge/todos` missing → empty. List `*.md` in that directory only (not recursive). Parse each; skip failures (`Diag.warn`). Paths stored as `knowledge/todos/<file>` with `/`.

`TodoReminderService(Path knowledgeRoot)` opens `ReminderLedger` at `knowledgeRoot.resolve(".todo-reminder-ledger")`.

`evaluate(LocalDateTime now)`:

1. Scan, filter `inWindow`, `sort`.
2. `slotsDue(now, ledger)`:
   - if !fired(LOGIN) add LOGIN
   - if `now.toLocalTime() >= 10:00` && !fired(TEN) add TEN
   - if `now.toLocalTime() >= 14:00` && !fired(FOURTEEN) add FOURTEEN
3. if todos empty OR slots empty → `Optional.empty()` (do not mark).
4. else `ReminderBatch`.

`commit(batch, date)` → `ledger.mark(date, batch.slots())`.

`nextClock(now)`: if time `< 10:00` → today 10:00; else if `< 14:00` → today 14:00; else tomorrow 10:00. Use `LocalTime.of(10, 0)` / `14, 0`. At exactly 10:00, next is 14:00 (the 10:00 fire is `evaluate`, not `nextClock`).

- [ ] **Step 4:** PASS `TodoReminderServiceTest`

- [ ] **Step 5: Commit** `feat: evaluate todo reminder catch-up slots`

---

### Task 6: `CitationTurn` first path + replace

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/service/CitationTurn.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/service/CitationTurnTest.java`

- [ ] **Step 1: Failing test** (append)

```java
    @Test
    void replaceShownOpensFirst() {
        CitationTurn t = new CitationTurn();
        t.addRetrievalText("knowledge/meetings/old.md");
        t.commitIfRetrieved();
        t.replaceShown(List.of(
                "knowledge/todos/2026-09-10-申请-licence.md",
                "knowledge/todos/2026-09-12-发周报.md"));
        assertEquals(
                List.of("knowledge/todos/2026-09-10-申请-licence.md",
                        "knowledge/todos/2026-09-12-发周报.md"),
                t.shown());
        assertEquals("knowledge/todos/2026-09-10-申请-licence.md", t.firstShown());
        assertEquals("knowledge/todos/2026-09-12-发周报.md", t.lastShown());
    }
```

- [ ] **Step 2:** `./mvnw -q test -Dtest=CitationTurnTest#replaceShownOpensFirst` — FAIL (`replaceShown` missing).

- [ ] **Step 3: Implement**

```java
    public void replaceShown(List<String> paths) {
        shown.clear();
        addAllShown(paths);
    }

    public String firstShown() {
        return shown.isEmpty() ? null : shown.get(0);
    }

    private void addAllShown(List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            if (p != null && !p.isBlank()) {
                shown.add(p);
            }
        }
    }
```

`replaceShown` does not touch `pending`.

- [ ] **Step 4:** `./mvnw -q test -Dtest=CitationTurnTest` — PASS

- [ ] **Step 5: Commit** `feat: replace citation shown list for todo reminders`

---

### Task 7: Post reminder and defer pane

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java`

Keep scheduling out of this task. Add package-visible hooks only.

- [ ] **Step 1: Failing tests** (append to `ChatControllerKelsyTest`)

Use the existing `controller(...)` helper. After constructing, call the new methods:

```java
    @Test
    void reminderPostsAssistantAndOpensFirstTodo() throws Exception {
        List<List<String>> sources = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        c.setOnCitationSources(sources::add);
        c.setOnOpenKnowledge(opened::add);
        var batch = new ReminderBatch(
                List.of(
                        new TodoCard("申请", LocalDate.of(2026, 9, 10), TodoStatus.OPEN,
                                "knowledge/todos/2026-09-10-申请.md"),
                        new TodoCard("周报", LocalDate.of(2026, 9, 12), TodoStatus.OPEN,
                                "knowledge/todos/2026-09-12-周报.md")),
                EnumSet.of(ReminderSlot.LOGIN));
        c.applyReminder(batch, LocalDate.of(2026, 9, 4));
        Message last = c.getMessages().getLast();
        assertEquals(Sender.ASSISTANT, last.sender());
        assertTrue(last.content().startsWith("还有 2 条待办待处理"));
        assertEquals(List.of(
                "knowledge/todos/2026-09-10-申请.md",
                "knowledge/todos/2026-09-12-周报.md"), sources.getLast());
        assertEquals("knowledge/todos/2026-09-10-申请.md", opened.getLast());
        assertTrue(c.knowledgeVisibleProperty().get());
    }

    @Test
    void reminderDefersPaneWhileAskingThenAppliesIfNoRetrieval() throws Exception {
        List<List<String>> sources = new ArrayList<>();
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        c.setOnCitationSources(sources::add);
        c.setAsking(true);
        var batch = new ReminderBatch(
                List.of(new TodoCard("申请", LocalDate.of(2026, 9, 10), TodoStatus.OPEN,
                        "knowledge/todos/2026-09-10-申请.md")),
                EnumSet.of(ReminderSlot.TEN));
        c.applyReminder(batch, LocalDate.of(2026, 9, 4));
        assertEquals(Sender.ASSISTANT, c.getMessages().getLast().sender());
        assertTrue(sources.isEmpty());
        c.finishAskWithoutRetrieval();
        assertEquals(List.of("knowledge/todos/2026-09-10-申请.md"), sources.getLast());
    }
```

Add imports: `EnumSet`, `LocalDate`, `Message`, `ReminderBatch`, `ReminderSlot`, `TodoCard`, `TodoStatus`.

- [ ] **Step 2:** FAIL compile.

- [ ] **Step 3: Implement on `ChatController`**

Fields:

```java
    private List<String> deferredTodoPaths;
```

```java
    void setAsking(boolean asking) {
        kelsyBusy.set(asking);
    }

    void applyReminder(ReminderBatch batch, LocalDate today) {
        if (batch == null || batch.todos().isEmpty()) {
            return;
        }
        persistAssistant(ReminderFormat.encode(batch.todos(), today));
        List<String> paths = batch.todos().stream().map(TodoCard::relativePath).toList();
        if (kelsyBusy.get()) {
            deferredTodoPaths = paths;
            return;
        }
        showTodoSources(paths);
    }

    void finishAskWithoutRetrieval() {
        kelsyBusy.set(false);
        applyDeferredTodosIfNeeded(false);
    }

    private void showTodoSources(List<String> paths) {
        citations.replaceShown(paths);
        knowledgeVisible.set(true);
        onCitationSources.accept(citations.shown());
        openKnowledge(citations.firstShown());
    }

    private void applyDeferredTodosIfNeeded(boolean retrieved) {
        List<String> deferred = deferredTodoPaths;
        deferredTodoPaths = null;
        if (!retrieved && deferred != null && !deferred.isEmpty()) {
            showTodoSources(deferred);
        }
    }
```

In existing `onComplete`, after `kelsyBusy.set(false)`:

```java
                    boolean retrieved = citations.commitIfRetrieved();
                    if (retrieved) {
                        knowledgeVisible.set(true);
                        onCitationSources.accept(citations.shown());
                        openKnowledge(citations.lastShown());
                    }
                    applyDeferredTodosIfNeeded(retrieved);
                    refreshKnowledge();
```

If `retrieved` is true, `applyDeferredTodosIfNeeded` must drop deferred without showing (retrieval wins). The `false` branch only runs when `!retrieved`.

- [ ] **Step 4:** `./mvnw -q test -Dtest=ChatControllerKelsyTest` — PASS (existing + new).

- [ ] **Step 5: Commit** `feat: post todo reminder bubbles and defer the pane`

---

### Task 8: Start on enter chat, clock, stop on leave

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`
- Test: append clock-fire test that injects a service, or unit-test only `nextClock` (already Task 5). Add `ChatControllerKelsyTest#startRemindersUsesStore` only if cheap; otherwise test `fireRemindersAt` package method.

- [ ] **Step 1: Failing test**

```java
    @Test
    void fireRemindersAtUsesLedgerCatchUp() throws Exception {
        Path userRoot = runtime.store("alice").workspace();
        Files.createDirectories(userRoot.resolve("knowledge/todos"));
        Files.writeString(userRoot.resolve("knowledge/todos/2026-09-04-当天.md"),
                "- 截止：2026-09-04\n- 状态：open\n- 标题：当天\n");
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        c.startReminders(new TodoReminderService(userRoot));
        c.fireRemindersAt(LocalDateTime.of(2026, 9, 4, 10, 1));
        assertEquals(Sender.ASSISTANT, c.getMessages().getLast().sender());
        c.fireRemindersAt(LocalDateTime.of(2026, 9, 4, 10, 2));
        long assistant = c.getMessages().stream().filter(m -> m.sender() == Sender.ASSISTANT).count();
        assertEquals(1, assistant);
    }
```

The existing `controller` helper already builds `runtime` for user `alice` when enabled. Reuse that username / store. If the helper uses a different username, match `userRoot` to `c.knowledgeStore().workspace()`.

Preferred form if username is not `alice`:

```java
        ChatController c = controller(...);
        Path userRoot = c.knowledgeStore().workspace();
        Files.createDirectories(userRoot.resolve("knowledge/todos"));
        Files.writeString(...);
        c.startReminders(new TodoReminderService(userRoot));
        c.fireRemindersAt(LocalDateTime.of(2026, 9, 4, 10, 1));
```

Read `ChatControllerKelsyTest.controller` and use the same username it passes to `KelsyRuntime`.

- [ ] **Step 2:** FAIL (`startReminders` missing).

- [ ] **Step 3: Implement**

```java
    private TodoReminderService reminders;
    private ScheduledExecutorService reminderClock;
    private ScheduledFuture<?> reminderTick;

    void startReminders(TodoReminderService service) {
        stopReminders();
        this.reminders = service;
        fireRemindersAt(LocalDateTime.now());
        reminderClock = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kmate-todo-reminder");
            t.setDaemon(true);
            return t;
        });
        scheduleNext(LocalDateTime.now());
    }

    void fireRemindersAt(LocalDateTime now) {
        if (reminders == null) {
            return;
        }
        reminders.evaluate(now).ifPresent(batch -> {
            reminders.commit(batch, now.toLocalDate());
            applyReminder(batch, now.toLocalDate());
        });
    }

    public void stopReminders() {
        if (reminderTick != null) {
            reminderTick.cancel(false);
            reminderTick = null;
        }
        if (reminderClock != null) {
            reminderClock.shutdownNow();
            reminderClock = null;
        }
        reminders = null;
    }

    private void scheduleNext(LocalDateTime now) {
        if (reminderClock == null) {
            return;
        }
        LocalDateTime next = TodoReminderService.nextClock(now);
        long delay = Duration.between(now, next).toMillis();
        reminderTick = reminderClock.schedule(() -> onFx(() -> {
            LocalDateTime t = LocalDateTime.now();
            fireRemindersAt(t);
            scheduleNext(t);
        }), Math.max(delay, 0), TimeUnit.MILLISECONDS);
    }
```

`ChatPane` constructor end, after listeners:

```java
        if (controller.kelsyEnabled() && controller.knowledgeStore() != null) {
            controller.startReminders(new TodoReminderService(controller.knowledgeStore().workspace()));
        }
```

Add `ChatPane.close()` → `controller.stopReminders()`.

`Mate4K`: keep `private ChatPane chatPane;`. `enterChat`: `closeSession()` then `chatPane = new ChatPane(state); root.setAll(chatPane);`. `closeSession`: if `chatPane != null` call `chatPane.close(); chatPane = null;` then existing unread/client/runtime shutdown.

- [ ] **Step 4:** `./mvnw -q test -Dtest=ChatControllerKelsyTest` — PASS

- [ ] **Step 5: Commit** `feat: schedule todo reminders on login and at 10:00/14:00`

---

### Task 9: Darker annotation blocks

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/ui/AssistantBubble.java`
- Modify: `src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css`
- Test: `src/test/java/com/glodon/mordor/kmate/kelsy/todo/ReminderFormatTest.java` already covers parse. UI is CSS + click → `onWorkspaceLink`. No Stage test.

- [ ] **Step 1:** In `fillBody`, when block is TEXT and not streaming, try `ReminderFormat.parse(block.content())`. If present, add reminder node instead of markdown.

```java
            if (!streaming && msg.sender() != Sender.SYSTEM) {
                var reminder = ReminderFormat.parse(block.content());
                if (reminder.isPresent()) {
                    body.getChildren().add(styled(reminderBox(reminder.get())));
                    continue;
                }
                body.getChildren().add(styled(markdownOrPlain(block.content())));
                continue;
            }
```

Keep the existing streaming / SYSTEM branch.

`reminderBox`:

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

CSS (darker than `.bubble-peer` `#E8F4FF`):

```css
.todo-reminder-item {
    -fx-background-color: #9FC4E8;
    -fx-background-radius: 8;
    -fx-padding: 6 8 6 8;
    -fx-cursor: hand;
}
.todo-reminder-due {
    -fx-font-size: 10px;
    -fx-text-fill: #3D4F66;
}
```

History reload already uses `AssistantMessage.of(ASSISTANT, content)` → same parse path.

- [ ] **Step 2:** `./mvnw -q test -Dtest=ReminderFormatTest,ChatControllerKelsyTest` — PASS

- [ ] **Step 3: Commit** `feat: render todo reminder items as darker annotations`

---

### Task 10: Skill — standalone todos and meeting todos

**Files:**
- Modify: `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/skills/kelsy-knowledge/SKILL.md`
- Modify: `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/skills/kelsy-knowledge/references/examples.md`
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeeder.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/service/WorkspaceSeederTest.java`

- [ ] **Step 1: Failing seeder assertion**

In `writesMissingFilesOnce` add:

```java
        assertTrue(Files.isDirectory(dir.resolve("knowledge/todos")));
```

In `overwritesProductSkillButKeepsAgents` add:

```java
        assertTrue(Files.readString(skill).contains("knowledge/todos"));
        assertTrue(Files.readString(skill).contains("状态：open"));
```

- [ ] **Step 2:** `./mvnw -q test -Dtest=WorkspaceSeederTest` — FAIL (no `todos` dir / strings).

- [ ] **Step 3: Seeder + skill text**

In `WorkspaceSeeder.seed`, add `"todos"` to the folder list:

```java
            for (String folder : List.of(
                    "people", "projects", "playbooks", "inbox", "meetings", "decisions", "todos")) {
```

In `SKILL.md` library table add:

`| `knowledge/todos/` | 带截止日的待办卡（提醒只扫这里） | 文件系统工具 |`

After 卡片字段 / 路径, add sections (Chinese, same tone as the rest):

**单独待办：** 用户说记住/记下待办（不是会议门闩里的「待办：」槽）→ 必须问截止日 `YYYY-MM-DD`。不齐不写。齐了写：

```
# 待办 · <标题>
- 截止：YYYY-MM-DD
- 状态：open
- 标题：<标题>
```

路径：`knowledge/todos/YYYY-MM-DD-<标题短slug>.md`（日期是截止日）。`KNOWLEDGE.md` 加一行；`memory_save` 指针带路径。用户说关了 / close / 做完：只把对应卡 `状态` 改成 `closed`，不删文件。

**会议/决定 → todo：** 「待办」不是「无」时，每条必须有截止日，未问清则整张会议/决定卡也不许落盘。一段话多件就拆多张 todo 卡。齐了：先写会议/决定卡（「待办：」保留纪要），再为每条写 `knowledge/todos/`（`状态=open`，可选 `- 来源：knowledge/meetings/…`）。用户说没有待办：只写会议卡。不对旧会议做回填。

Update 澄清 item 3: 待办没提就问；不是「无」则继续问每条截止日。

Update 「齐了怎么落盘」step 1: 写会议/决定卡之后立刻写 todo 卡（若有）。

In `examples.md` add two examples:

1. 会议待办两条：缺截止 → 不写任何文件；补截止后会议卡 + `knowledge/todos/2026-09-10-申请-licence.md` + `knowledge/todos/2026-09-12-发周报.md`。
2. 单独「记一条待办：周五交周报」→ 问截止 → 只写 todo 卡。

- [ ] **Step 4:** `./mvnw -q test -Dtest=WorkspaceSeederTest` — PASS

- [ ] **Step 5: Commit** `feat: archive meeting todos into knowledge/todos`

---

### Task 11: Full test + spec already on disk

- [ ] **Step 1:** `./mvnw test`

Expected: all non-IT tests PASS.

- [ ] **Step 2: Commit docs if not already committed**

```bash
git add docs/superpowers/specs/2026-09-04-todo-reminders-design.md \
  docs/superpowers/plans/2026-09-04-todo-reminders.md
git commit -m "$(cat <<'EOF'
docs: add todo reminder spec and plan

EOF
)"
```

Manual (not automated): two open cards (one in window, one due in 4 days) → login shows one annotation + right pane; close the file → next fire omits it; `/note` a meeting with two todos → secretary asks dues → two todo files appear.

---

## Self-review

| Spec | Task |
|---|---|
| `knowledge/todos/` fields, skip bad files | 1, 5 |
| Window ≥ due−3, overdue until closed | 2, 5 |
| LOGIN / 10:00 / 14:00 + catch-up merge + empty does not mark | 3, 5, 8 |
| One ASSISTANT bubble, darker items, history text | 4, 7, 9 |
| Pane = those paths, open first; defer if asking; retrieval wins | 6, 7 |
| Schedule next clock, stop on leave | 8 |
| Meeting todos → todo cards; no Java scan of meeting line; no backfill | 10 |
| Java does not close todos | all (no writer) |
