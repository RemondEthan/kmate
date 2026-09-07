# Mention Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Typing `@` in the chat box opens a WeChat-style member picker (others + secretary, prefix filter) and inserts `@昵称 ` at the caret.

**Architecture:** Pure `MentionQuery` decides token + candidates + replacement string. `MentionPopover` is a `Popup` list like `EmojiPopover`. `InputBar` listens to text/caret and wires members/avatars from `ChatPane`. `KelsyMention` routing is unchanged (leading `@秘书` only).

**Tech Stack:** Java 21, JavaFX 21, JUnit 5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-04-mention-picker-design.md`

---

## File map

**Create:**

- `src/main/java/com/glodon/mordor/kmate/ui/chat/MentionQuery.java` — parse / candidates / apply
- `src/test/java/com/glodon/mordor/kmate/ui/chat/MentionQueryTest.java`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/MentionPopover.java` — Popup UI

**Modify:**

- `src/main/java/com/glodon/mordor/kmate/ui/chat/EmojiPopover.java` — public `hide()`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java` — listen + show popover
- `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java` — pass members and avatars
- `src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css` — `.mention-popup` rows
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyMentionTest.java` — insert-then-mention still true

**Do not modify:** `KelsyMention` logic, `KelsySendRouter`, `ImClient`, message bubbles.

---

### Task 1: `MentionQuery.parse`

**Files:**
- Create: `MentionQueryTest.java`, `MentionQuery.java`

- [ ] **Step 1: Write failing parse tests**

Create `src/test/java/com/glodon/mordor/kmate/ui/chat/MentionQueryTest.java`:

```java
package com.glodon.mordor.kmate.ui.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionQueryTest {

    @Test
    void parseAtStartAndAfterSpace() {
        var start = MentionQuery.parse("@", 1).orElseThrow();
        assertEquals(0, start.atIndex());
        assertEquals("", start.query());

        var mid = MentionQuery.parse("hi @张", 5).orElseThrow();
        assertEquals(3, mid.atIndex());
        assertEquals("张", mid.query());
    }

    @Test
    void parseRejectsEmailAndFinishedMention() {
        assertTrue(MentionQuery.parse("a@b", 3).isEmpty());
        assertTrue(MentionQuery.parse("@张三 你好", 8).isEmpty());
        assertTrue(MentionQuery.parse("hello", 5).isEmpty());
    }

    @Test
    void parseQueryIsPrefixBeforeCaret() {
        var t = MentionQuery.parse("@张三", 2).orElseThrow();
        assertEquals(0, t.atIndex());
        assertEquals("张", t.query());
    }
}
```

- [ ] **Step 2: Run tests — expect compile/fail**

Run: `./mvnw -q test -Dtest=MentionQueryTest`

Expected: FAIL compile (`MentionQuery` missing).

- [ ] **Step 3: Implement parse only**

Create `src/main/java/com/glodon/mordor/kmate/ui/chat/MentionQuery.java`:

```java
package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.RoomMember;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MentionQuery {

    public record Token(int atIndex, String query) {
    }

    public record Applied(String text, int caret) {
    }

    private MentionQuery() {
    }

    public static Optional<Token> parse(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        int pos = Math.max(0, Math.min(caret, text.length()));
        int i = pos - 1;
        while (i >= 0 && !Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        int wordStart = i + 1;
        if (wordStart >= pos || text.charAt(wordStart) != '@') {
            return Optional.empty();
        }
        return Optional.of(new Token(wordStart, text.substring(wordStart + 1, pos)));
    }

    public static List<RoomMember> candidates(List<RoomMember> members, String query) {
        return List.of();
    }

    public static Applied apply(String text, int atIndex, int caret, String nickname) {
        return new Applied(text == null ? "" : text, caret);
    }
}
```

`candidates` / `apply` stubs are filled in Task 2. User-visible comments stay Chinese if you add any.

- [ ] **Step 4: Re-run parse tests**

Run: `./mvnw -q test -Dtest=MentionQueryTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/MentionQuery.java \
        src/test/java/com/glodon/mordor/kmate/ui/chat/MentionQueryTest.java
git commit -m "$(cat <<'EOF'
feat: parse in-progress @ mention tokens

EOF
)"
```

---

### Task 2: candidates and apply

**Files:**
- Modify: `MentionQueryTest.java`, `MentionQuery.java`
- Modify: `KelsyMentionTest.java`

- [ ] **Step 1: Failing candidate/apply tests**

Append to `MentionQueryTest`:

```java
    @Test
    void candidatesDropSelfSecretaryFirstPrefix() {
        var self = new RoomMember(RoomMember.SELF_ID, "我", true);
        var ada = new RoomMember(200001, "Ada", false);
        var bob = new RoomMember(200002, "Bob", false);
        var tars = RoomMember.kelsy("tars");
        var all = MentionQuery.candidates(List.of(self, ada, tars, bob), "");
        assertEquals(List.of("tars", "Ada", "Bob"),
                all.stream().map(RoomMember::username).toList());

        var filtered = MentionQuery.candidates(List.of(self, ada, tars, bob), "a");
        assertEquals(List.of("Ada"),
                filtered.stream().map(RoomMember::username).toList());
    }

    @Test
    void applyReplacesTokenAtCaret() {
        var applied = MentionQuery.apply("hello @张", 6, 8, "张三");
        assertEquals("hello @张三 ", applied.text());
        assertEquals("hello @张三 ".length(), applied.caret());
    }
```

Add imports: `RoomMember`, `List`.

Append to `KelsyMentionTest`:

```java
    @Test
    void appliedLeadingSecretaryStillMentions() {
        var applied = com.glodon.mordor.kmate.ui.chat.MentionQuery.apply("@", 0, 1, "Ada");
        assertTrue(KelsyMention.isMention(applied.text(), "Ada"));
    }
```

- [ ] **Step 2: Run — expect fail**

Run: `./mvnw -q test -Dtest=MentionQueryTest#candidatesDropSelfSecretaryFirstPrefix,MentionQueryTest#applyReplacesTokenAtCaret`

Expected: FAIL (stubs return empty / unchanged text).

- [ ] **Step 3: Implement candidates and apply**

Replace the stubs in `MentionQuery`:

```java
    public static List<RoomMember> candidates(List<RoomMember> members, String query) {
        String q = query == null ? "" : query;
        List<RoomMember> out = new ArrayList<>();
        if (members != null) {
            for (RoomMember m : members) {
                if (m == null || m.self()) {
                    continue;
                }
                String name = m.username() == null ? "" : m.username();
                if (!q.isEmpty() && !name.regionMatches(true, 0, q, 0, q.length())) {
                    continue;
                }
                out.add(m);
            }
        }
        out.sort((a, b) -> Boolean.compare(b.isKelsy(), a.isKelsy()));
        return List.copyOf(out);
    }

    public static Applied apply(String text, int atIndex, int caret, String nickname) {
        String src = text == null ? "" : text;
        int at = Math.max(0, Math.min(atIndex, src.length()));
        int pos = Math.max(at, Math.min(caret, src.length()));
        String repl = KelsyMention.insert(nickname);
        String next = src.substring(0, at) + repl + src.substring(pos);
        return new Applied(next, at + repl.length());
    }
```

Add `import com.glodon.mordor.kmate.kelsy.KelsyMention;`.

Stable secretary-first: `Boolean.compare(b.isKelsy(), a.isKelsy())` is 0 among non-kelsy so original relative order of Ada/Bob is kept if the sort is stable (`List.sort` is TimSort, stable).

- [ ] **Step 4: Re-run**

Run: `./mvnw -q test -Dtest=MentionQueryTest,KelsyMentionTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/MentionQuery.java \
        src/test/java/com/glodon/mordor/kmate/ui/chat/MentionQueryTest.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsyMentionTest.java
git commit -m "$(cat <<'EOF'
feat: filter mention candidates and apply @name

EOF
)"
```

---

### Task 3: `MentionPopover` and CSS

**Files:**
- Create: `MentionPopover.java`
- Modify: `chat.css`, `EmojiPopover.java`

No JavaFX Stage test. Keep the class small: list of names, highlight index, onPick callback, show/hide.

- [ ] **Step 1: Add `EmojiPopover.hide()`**

In `EmojiPopover.java` add:

```java
    public void hide() {
        popup.hide();
    }
```

- [ ] **Step 2: Add CSS**

Append to `src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css`:

```css
.mention-popup {
    -fx-background-color: #FFFFFF;
    -fx-border-color: #E4E7ED;
    -fx-border-width: 1;
    -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.12), 8, 0, 0, 2);
    -fx-padding: 4 0 4 0;
}

.mention-row {
    -fx-padding: 6 10 6 10;
    -fx-alignment: CENTER_LEFT;
}

.mention-row-active {
    -fx-background-color: #F2F6FA;
}

.mention-name {
    -fx-font-size: 12px;
    -fx-text-fill: #1F2329;
}
```

- [ ] **Step 3: Implement MentionPopover**

Create `src/main/java/com/glodon/mordor/kmate/ui/chat/MentionPopover.java`:

```java
package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.RoomMember;
import com.glodon.mordor.kmate.ui.AvatarView;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public final class MentionPopover {

    private static final int MAX_VISIBLE = 6;
    private static final double ROW_H = 40;

    private final Popup popup = new Popup();
    private final VBox list = new VBox();
    private final ScrollPane scroller = new ScrollPane(list);
    private final BiFunction<RoomMember, String, Image> avatarOf;
    private final Consumer<RoomMember> onPick;

    private final List<RoomMember> items = new ArrayList<>();
    private int active;

    public MentionPopover(BiFunction<RoomMember, String, Image> avatarOf,
                          Consumer<RoomMember> onPick) {
        this.avatarOf = avatarOf;
        this.onPick = onPick;
        list.getStyleClass().add("mention-popup");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setMaxHeight(MAX_VISIBLE * ROW_H);
        scroller.setPrefWidth(220);
        popup.getContent().add(scroller);
        popup.setAutoHide(true);
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    public void hide() {
        popup.hide();
        items.clear();
    }

    public void show(Node anchor, List<RoomMember> candidates) {
        items.clear();
        if (candidates == null || candidates.isEmpty()) {
            hide();
            return;
        }
        items.addAll(candidates);
        active = 0;
        rebuild();
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b == null) {
            return;
        }
        scroller.applyCss();
        scroller.autosize();
        double h = Math.min(items.size(), MAX_VISIBLE) * ROW_H + 8;
        popup.show(anchor, b.getMinX(), b.getMinY() - h);
    }

    public boolean handleKey(KeyEvent e) {
        if (!popup.isShowing() || items.isEmpty()) {
            return false;
        }
        if (e.getCode() == KeyCode.UP) {
            active = (active - 1 + items.size()) % items.size();
            rebuild();
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.DOWN) {
            active = (active + 1) % items.size();
            rebuild();
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.ENTER) {
            pick(items.get(active));
            e.consume();
            return true;
        }
        if (e.getCode() == KeyCode.ESCAPE) {
            hide();
            e.consume();
            return true;
        }
        return false;
    }

    private void rebuild() {
        list.getChildren().clear();
        for (int i = 0; i < items.size(); i++) {
            RoomMember m = items.get(i);
            String name = m.username() == null || m.username().isBlank() ? "?" : m.username();
            Image photo = avatarOf.apply(m, name);
            HBox row = new HBox(8);
            row.getStyleClass().add("mention-row");
            if (i == active) {
                row.getStyleClass().add("mention-row-active");
            }
            row.getChildren().add(new AvatarView(name, photo, false, 28));
            Label label = new Label(name);
            label.getStyleClass().add("mention-name");
            row.getChildren().add(label);
            final int index = i;
            row.setOnMouseEntered(ev -> {
                active = index;
                rebuild();
            });
            row.addEventHandler(MouseEvent.MOUSE_CLICKED, ev -> pick(m));
            list.getChildren().add(row);
        }
    }

    private void pick(RoomMember member) {
        hide();
        onPick.accept(member);
    }
}
```

Do not add a test class that starts a Stage.

- [ ] **Step 4: Compile check**

Run: `./mvnw -q test -Dtest=MentionQueryTest`

Expected: PASS (popover compiles with the module).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/MentionPopover.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/EmojiPopover.java \
        src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css
git commit -m "$(cat <<'EOF'
feat: add mention member popup

EOF
)"
```

---

### Task 4: Wire `InputBar` and `ChatPane`

**Files:**
- Modify: `InputBar.java`, `ChatPane.java`

`InputBar` today:

```java
    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname)
```

`ChatPane` today: `new InputBar(controller::send, controller::secretaryNickname)`.

- [ ] **Step 1: Extend InputBar constructor**

Add an overload used by ChatPane; keep the two-arg constructor delegating with empty members and null avatars so existing call sites compile if any tests use it.

```java
    private final ObservableList<RoomMember> members;
    private final MentionPopover mentionPopover;

    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname) {
        this(onSend, secretaryNickname, FXCollections.observableArrayList(), (m, n) -> null);
    }

    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname,
                    ObservableList<RoomMember> members,
                    BiFunction<RoomMember, String, Image> avatarOf) {
```

Inside the 4-arg constructor, after `textField` exists:

```java
        this.members = members == null ? FXCollections.observableArrayList() : members;
        mentionPopover = new MentionPopover(avatarOf == null ? (m, n) -> null : avatarOf, this::pickMember);
        textField.textProperty().addListener((obs, o, n) -> refreshMention());
        textField.caretPositionProperty().addListener((obs, o, n) -> refreshMention());
        textField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (mentionPopover.handleKey(e)) {
                e.consume();
            }
        });
```

Add imports: `ObservableList`, `FXCollections`, `RoomMember`, `Image`, `BiFunction`, `KeyEvent`.

Add methods:

```java
    private void refreshMention() {
        if (mentionPopover == null) {
            return;
        }
        var token = MentionQuery.parse(textField.getText(), textField.getCaretPosition());
        if (token.isEmpty()) {
            mentionPopover.hide();
            return;
        }
        var found = MentionQuery.candidates(members, token.get().query());
        if (found.isEmpty()) {
            mentionPopover.hide();
            return;
        }
        emojiPopover.hide();
        mentionPopover.show(textField, found);
    }

    private void pickMember(RoomMember member) {
        var token = MentionQuery.parse(textField.getText(), textField.getCaretPosition());
        if (token.isEmpty()) {
            return;
        }
        var applied = MentionQuery.apply(
                textField.getText(), token.get().atIndex(), textField.getCaretPosition(), member.username());
        textField.setText(applied.text());
        textField.positionCaret(applied.caret());
        textField.requestFocus();
    }
```

In `send(...)`, after a successful send (existing `clear()`), also `mentionPopover.hide()`.

When constructing `EmojiPopover` and showing emoji, hide mention:

```java
        emoji.setOnAction(e -> {
            mentionPopover.hide();
            emojiPopover.show(emoji);
        });
```

`mentionPopover` must be assigned before this `setOnAction`. Order: create textField → create mentionPopover → create emoji button action.

- [ ] **Step 2: ChatPane wiring**

Replace InputBar construction:

```java
        InputBar input = new InputBar(
                controller::send,
                controller::secretaryNickname,
                controller.getMembers(),
                (member, name) -> member.isKelsy()
                        ? controller.avatarOfSecretary()
                        : controller.avatarOf(name));
```

- [ ] **Step 3: Compile + unit tests**

Run: `./mvnw -q test -Dtest=MentionQueryTest,KelsyMentionTest,KelsySendRouterTest`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java
git commit -m "$(cat <<'EOF'
feat: open member picker when typing @

EOF
)"
```

---

## Manual check (after all tasks)

In a room with self, one peer, and secretary added:

1. Type `@` → list: secretary first, then peer; you are absent.
2. Type `@张` (peer 张三) → only 张三; Enter → ` @张三 ` at caret.
3. `a@b` → no popup.
4. `@秘书昵称 问一下` at start still goes to secretary; `@同事 你好` in the middle is a normal peer message.
5. Esc leaves `@张` in the field; emoji button closes the list; list open + Enter does not send.

---

## Self-review vs spec

| Spec | Task |
|---|---|
| Trigger `@` at start / after space; reject email / finished mention | Task 1 |
| Candidates: no self, secretary first, prefix | Task 2 |
| Insert `@昵称 ` at caret | Task 2 + 4 |
| Popup UI, keys, mutex with emoji | Task 3 + 4 |
| Do not change KelsyMention routing | Task 2 extra test + no edits to mention/router |
| ChatPane wires members/avatars | Task 4 |
