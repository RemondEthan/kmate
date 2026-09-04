# Knowledge Citation Pane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After the secretary finishes a turn that actually retrieved knowledge files, show those originals on the right; sending a new `@秘书` must not clear or hide the current pane.

**Architecture:** `KnowledgePathExtractor.all` collects every path. `CitationTurn` holds `shown` vs `pending`. Controller writes retrievals into `pending` during tools; `onComplete` / `/find` commit only if `pending` is non-empty. `KnowledgePane` drops the tree and shows a source list + existing Markdown preview.

**Tech Stack:** Java 21, JavaFX 21, JUnit 5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-04-knowledge-citation-pane-design.md`

---

## File map

**Create:**

- `src/main/java/com/glodon/mordor/kmate/kelsy/service/CitationTurn.java`
- `src/test/java/com/glodon/mordor/kmate/kelsy/service/CitationTurnTest.java`

**Modify:**

- `KnowledgePathExtractor.java` + `KnowledgePathExtractorTest.java`
- `KnowledgePane.java` — list + preview, no tree
- `chat.css` — replace tree styles with citation chips
- `ChatController.java` — pending/shown, commit on complete
- `ChatPane.java` — `setSources` callback; `openKnowledge` also shows the pane

**Do not modify:** `KelsyMention`, `KelsySendRouter`, protocol, AgentScope tools.

---

### Task 1: `KnowledgePathExtractor.all`

**Files:**
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/service/KnowledgePathExtractorTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/service/KnowledgePathExtractor.java`

- [ ] **Step 1: Failing tests**

Append:

```java
    @Test
    void allKeepsOrderAndDedups() {
        var paths = KnowledgePathExtractor.all(
                "见 knowledge/meetings/a.md 和 memory/2026-09-04.md 以及 knowledge/meetings/a.md");
        assertEquals(
                List.of("knowledge/meetings/a.md", "memory/2026-09-04.md"),
                paths);
    }

    @Test
    void allEmptyWhenNone() {
        assertTrue(KnowledgePathExtractor.all("no files").isEmpty());
        assertTrue(KnowledgePathExtractor.all(null).isEmpty());
    }
```

Add `import java.util.List;`

- [ ] **Step 2:** `./mvnw -q test -Dtest=KnowledgePathExtractorTest#allKeepsOrderAndDedups`

Expected: FAIL compile (`all` missing).

- [ ] **Step 3: Implement `all`**

In `KnowledgePathExtractor`:

```java
    public static List<String> all(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = PATH.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return List.copyOf(out);
    }
```

Keep `first` as `all(text).stream().findFirst()`. Add imports `List`, `LinkedHashSet`.

- [ ] **Step 4:** `./mvnw -q test -Dtest=KnowledgePathExtractorTest`

Expected: PASS (old `first` tests still pass).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/service/KnowledgePathExtractor.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/service/KnowledgePathExtractorTest.java
git commit -m "$(cat <<'EOF'
feat: extract all knowledge paths from tool text

EOF
)"
```

---

### Task 2: `CitationTurn`

**Files:**
- Create: `CitationTurn.java`, `CitationTurnTest.java`

- [ ] **Step 1: Failing tests**

Create `src/test/java/com/glodon/mordor/kmate/kelsy/service/CitationTurnTest.java`:

```java
package com.glodon.mordor.kmate.kelsy.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationTurnTest {

    @Test
    void beginAskDoesNotClearShown() {
        CitationTurn t = new CitationTurn();
        t.addRetrievalText("read knowledge/meetings/a.md");
        assertTrue(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/a.md"), t.shown());
        t.beginAsk();
        assertEquals(List.of("knowledge/meetings/a.md"), t.shown());
        assertFalse(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/a.md"), t.shown());
    }

    @Test
    void commitReplacesShownWhenPendingNonEmpty() {
        CitationTurn t = new CitationTurn();
        t.addRetrievalText("knowledge/meetings/old.md");
        t.commitIfRetrieved();
        t.beginAsk();
        t.addRetrievalText("见 knowledge/meetings/new.md 和 memory/2026-09-04.md");
        assertTrue(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/new.md", "memory/2026-09-04.md"), t.shown());
        assertEquals("memory/2026-09-04.md", t.lastShown());
    }

    @Test
    void writesAreNotRetrievals() {
        assertFalse(CitationTurn.isRetrievalTool("memory_save"));
        assertFalse(CitationTurn.isRetrievalTool("write_file"));
        assertFalse(CitationTurn.isRetrievalTool("edit_file"));
        assertTrue(CitationTurn.isRetrievalTool("memory_get"));
        assertTrue(CitationTurn.isRetrievalTool("memory_search"));
        assertTrue(CitationTurn.isRetrievalTool("read_file"));
        CitationTurn t = new CitationTurn();
        t.addRetrievalText("knowledge/meetings/a.md");
        t.commitIfRetrieved();
        t.beginAsk();
        t.addPaths(List.of("knowledge/meetings/written.md")); // only used for /find
        // simulate write-only: do not call addRetrievalText
        assertFalse(t.commitIfRetrieved());
        assertEquals(List.of("knowledge/meetings/a.md"), t.shown());
    }
}
```

- [ ] **Step 2:** `./mvnw -q test -Dtest=CitationTurnTest` — FAIL compile.

- [ ] **Step 3: Implement**

Create `src/main/java/com/glodon/mordor/kmate/kelsy/service/CitationTurn.java`:

```java
package com.glodon.mordor.kmate.kelsy.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CitationTurn {

    private static final Set<String> RETRIEVAL = Set.of(
            "memory_get", "memory_search", "read_file");

    private final List<String> shown = new ArrayList<>();
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();

    public static boolean isRetrievalTool(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return RETRIEVAL.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    public void beginAsk() {
        pending.clear();
    }

    public void addRetrievalText(String text) {
        pending.addAll(KnowledgePathExtractor.all(text));
    }

    public void addPaths(List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            if (p != null && !p.isBlank()) {
                pending.add(p);
            }
        }
    }

    /** @return true if shown was replaced */
    public boolean commitIfRetrieved() {
        if (pending.isEmpty()) {
            return false;
        }
        shown.clear();
        shown.addAll(pending);
        pending.clear();
        return true;
    }

    public List<String> shown() {
        return List.copyOf(shown);
    }

    public String lastShown() {
        return shown.isEmpty() ? null : shown.get(shown.size() - 1);
    }
}
```

- [ ] **Step 4:** `./mvnw -q test -Dtest=CitationTurnTest,KnowledgePathExtractorTest` — PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/service/CitationTurn.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/service/CitationTurnTest.java
git commit -m "$(cat <<'EOF'
feat: stage citation paths until a retrieved turn completes

EOF
)"
```

---

### Task 3: `KnowledgePane` source list

**Files:**
- Modify: `KnowledgePane.java`, `chat.css`

No Stage test. Keep `open` / `refresh` (refresh re-reads `currentPath` only; do **not** rebuild a file tree).

- [ ] **Step 1: Replace the pane body**

`KnowledgePane` fields: drop `TreeView` and `SplitPane`. Use:

```java
    private final VBox sources = new VBox(4);
    private final ScrollPane host = new ScrollPane();
    private List<String> sourcePaths = List.of();
```

Constructor: `BorderPane` top = `sources` (style `knowledge-sources`), center = `host`. Call `showEmpty()` initially. **Do not** `open(store.defaultPath())`.

Add:

```java
    public void setSources(List<String> paths) {
        sourcePaths = paths == null ? List.of() : List.copyOf(paths);
        sources.getChildren().clear();
        if (sourcePaths.isEmpty()) {
            showEmpty();
            return;
        }
        for (String path : sourcePaths) {
            Hyperlink link = new Hyperlink(shortName(path));
            link.getStyleClass().add("knowledge-source");
            if (path.equals(currentPath)) {
                link.getStyleClass().add("knowledge-source-active");
            }
            link.setOnAction(e -> open(path));
            sources.getChildren().add(link);
        }
        memoryWarn.set(store.memoryBytes() > KnowledgeStore.MEMORY_WARN_BYTES);
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
```

`open` stays the same switch on `store.read`, then call `setSources(sourcePaths)` again so the active chip updates (or only toggle style classes).

`refresh`: if `currentPath != null` then `open(currentPath)`, else update `memoryWarn` only.

`showEmpty`: `currentPath = null`; `host` label「本轮没有引用原文」.

Delete `toItem` / `expand` / tree listeners.

- [ ] **Step 2: CSS**

In `chat.css` replace `.knowledge-tree` / `.knowledge-tree .tree-cell` with:

```css
.knowledge-sources {
    -fx-padding: 8 8 4 8;
}

.knowledge-source {
    -fx-font-size: 11px;
    -fx-text-fill: #1565C0;
}

.knowledge-source-active {
    -fx-font-weight: bold;
    -fx-text-fill: #0D47A1;
}
```

Keep `.knowledge-pane` and `.knowledge-host`.

- [ ] **Step 3:** `./mvnw -q test -Dtest=CitationTurnTest` — PASS (compiles with ChatPane still calling `open`/`refresh`).

`ChatPane` currently `knowledge::open` / `knowledge::refresh` — still valid.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/ui/knowledge/KnowledgePane.java \
        src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css
git commit -m "$(cat <<'EOF'
feat: show citation list instead of knowledge tree

EOF
)"
```

---

### Task 4: Wire controller and ChatPane

**Files:**
- Modify: `ChatController.java`, `ChatPane.java`

- [ ] **Step 1: ChatController fields and helpers**

Add:

```java
    private final CitationTurn citations = new CitationTurn();
    private Consumer<List<String>> onCitationSources = paths -> {
    };

    public void setOnCitationSources(Consumer<List<String>> onCitationSources) {
        this.onCitationSources = onCitationSources == null ? paths -> {
        } : onCitationSources;
    }
```

Import `CitationTurn`, `List`.

`startAsk` **first line inside the method** (before busy): `citations.beginAsk();`  
Do **not** set `knowledgeVisible` false here.

`onToolCall`: keep adding the tool card.

`onToolResult` replace with:

```java
            public void onToolResult(String name, String summary) {
                onFx(() -> {
                    String args = "";
                    for (MessageBlock b : reply.blocks()) {
                        if (b.kind() == MessageBlock.Kind.TOOL && name.equals(b.toolName())) {
                            args = b.toolArgs() == null ? "" : b.toolArgs();
                            break;
                        }
                    }
                    if (CitationTurn.isRetrievalTool(name)) {
                        citations.addRetrievalText(args + "\n" + (summary == null ? "" : summary));
                    }
                    KnowledgePathExtractor.first(
                            (args + " " + (summary == null ? "" : summary)))
                            .ifPresent(path -> {
                                for (MessageBlock b : reply.blocks()) {
                                    if (b.kind() == MessageBlock.Kind.TOOL
                                            && name.equals(b.toolName())) {
                                        b.openPathProperty().set(path);
                                    }
                                }
                            });
                });
            }
```

Check `MessageBlock` for the args getter name (`toolArgs` vs `args` vs preview). Use the existing field the card already stores from `addTool`. If the getter has another name, use that — do not invent a new field.

`onComplete` after `kelsyBusy.set(false)`:

```java
                    if (citations.commitIfRetrieved()) {
                        knowledgeVisible.set(true);
                        onCitationSources.accept(citations.shown());
                        openKnowledge(citations.lastShown());
                    }
                    refreshKnowledge();
```

`onError`: do **not** commit (leave shown as-is). Still `refreshKnowledge` optional skip.

`runFind` after computing hits:

```java
        citations.beginAsk();
        citations.addPaths(hits.stream().map(KnowledgeStore.Hit::relativePath).toList());
        if (citations.commitIfRetrieved()) {
            knowledgeVisible.set(true);
            onCitationSources.accept(citations.shown());
            openKnowledge(citations.lastShown());
        }
        addSystem(formatFind(hits));
```

`openKnowledge`:

```java
    public void openKnowledge(String path) {
        knowledgeVisible.set(true);
        onOpenKnowledge.accept(path);
    }
```

`disableKelsy` still `knowledgeVisible.set(false)` only; do not clear `shown` (pane is torn down anyway).

- [ ] **Step 2: ChatPane**

Where `knowledge` is created:

```java
            knowledge = new KnowledgePane(store, controller.memoryWarnProperty());
            controller.setOnOpenKnowledge(path -> {
                controller.knowledgeVisibleProperty().set(true);
                applyCenter();
                knowledge.open(path);
            });
            controller.setOnRefreshKnowledge(knowledge::refresh);
            controller.setOnCitationSources(paths -> {
                knowledge.setSources(paths);
            });
```

Careful: `openKnowledge` already sets `knowledgeVisible`. The listener on `knowledgeVisible` already calls `applyCenter`. Avoid double-apply loops: if `setOnOpenKnowledge` also sets visible, `openKnowledge` would set it twice. Prefer:

- `openKnowledge` in controller only `knowledgeVisible.set(true)` + `onOpenKnowledge.accept(path)`
- ChatPane `setOnOpenKnowledge` = `knowledge::open` (unchanged)
- `knowledgeVisible` listener already `applyCenter`

If the pane was hidden, setting visible true rebuilds SplitPane then `open` must happen **after** applyCenter. Order in controller:

```java
    public void openKnowledge(String path) {
        boolean was = knowledgeVisible.get();
        knowledgeVisible.set(true);
        onOpenKnowledge.accept(path);
    }
```

If `was` is false, the listener runs `applyCenter` synchronously (JavaFX property), then `open` runs on the new pane — OK if `knowledge` instance is kept (it is).

`setOnCitationSources` should run **before** `open` on commit so chips exist. In `onComplete`:

```java
                        onCitationSources.accept(citations.shown());
                        openKnowledge(citations.lastShown());
```

- [ ] **Step 3:** `./mvnw -q test -Dtest=CitationTurnTest,KnowledgePathExtractorTest,ChatControllerKelsyTest,KelsySendRouterTest`

Expected: PASS. Then `./mvnw -q test`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java
git commit -m "$(cat <<'EOF'
feat: commit retrieved citations after the secretary finishes

EOF
)"
```

---

## Manual check (do not automate)

1. Ask `@秘书` about an archived meeting → after the full reply, right pane shows that card.
2. Send another `@秘书` idle chat with no retrieval → pane **stays** on the previous card (no flicker, no close).
3. Ask about a different meeting that retrieves a new file → after reply, list and preview replace the old card.
4. `/find` with hits replaces; `/find` with zero hits leaves the pane.
5. Header「知识库」toggles visibility of the same `shown` list.

---

## Self-review vs spec

| Spec | Task |
|---|---|
| `all` paths, dedup, order | Task 1 |
| shown/pending; beginAsk does not clear shown | Task 2 |
| commit only if pending non-empty | Task 2 + 4 |
| retrieval tools only | Task 2 |
| `/find` same commit rule | Task 4 |
| Pane: chips + Markdown, no tree | Task 3 |
| Render only after complete | Task 4 |
| Header toggle only | already `knowledgeVisible` |
| Tool card open | `openKnowledge` sets visible |

---

**Stop here.** Do not start Task 1 or open a worktree until the user explicitly says to implement.
