# SiMate UI Replica in JavaFX — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replicate the `simate/client` LAN chat UI as a JavaFX desktop application inside the existing `kmate` Maven project. UI shell only with hardcoded sample data; no networking.

**Architecture:** Single 660×510 resizable window. One scene, one root `StackPane` holding both `LoginPane` (pre-login) and `ChatPane` (post-login). View swap by toggling `visible`/`managed`. All UI built in Java code (no FXML). Custom CSS for the Material-style appearance.

**Tech Stack:** Java 21, JavaFX 21.0.2, Maven 3.x, Ikonli 12.3.1 (`ikonli-javafx` + `ikonli-materialdesign2-pack`), custom CSS.

## Global Constraints

These apply to every task. Values copied from the approved spec.

- Window: **660×510**, resizable, title **`Kmate`**.
- LoginDialog pre-fills all five fields so a single click on "连接" reaches the chat.
- Sample data: six hardcoded messages, captured `now` once on construction.
- Default values: `serverIp="127.0.0.1"`, `serverPort="3000"`, `imCode="ABC123"`, `password="demo"`, `username="我"`.
- Default peer name: **`Alice`** (displayed as `我 ↔ Alice` in header).
- Status indicator: hardcoded **`🟢`** (no real connection in demo).
- Color palette: `#1976D2` primary, `#F5F5F5` peer bubble, `#FAFAFA` app bg, `#FFF3E0` system bubble.
- Bubble shape CSS: `.bubble-self { -fx-background-radius: 12 12 0 12; }`, `.bubble-peer { -fx-background-radius: 12 12 12 0; }` (order = topLeft, topRight, bottomRight, bottomLeft).
- Avatar: `Circle(radius=11)` + `Label(first letter)` in `StackPane`, color `#1976D2` for SELF, `#9E9E9E` for PEER.
- Icons: `MaterialDesignS.PAPERCLIP` for attach, `MaterialDesignS.SEND` for send (size 16 px). Emoji button uses literal `"😊"` glyph.
- Sender enum: `SELF, PEER, SYSTEM`. SYSTEM bubbles are centered, no avatar, no timestamp.
- Timestamp format: `HH:mm`.
- Send button disabled when `textField.getText().isBlank()`; on click, appends a new SELF message with current timestamp and clears the field.
- Attach button → `Alert` modal: `"文件传输未实现（demo 模式）"`.
- Emoji button → 6×6 `Popup` with 36 common emojis; click inserts at caret position.
- Validation in LoginPane: all 5 fields non-blank; `serverPort` matches `^\d+$`. On failure: inline error `Label` styled red (NOT a JavaFX `Alert`, which is always modal).
- Module: `com.glodon.mordor.kmate`.
- Existing files to delete: `src/main/java/com/glodon/mordor/kmate/MateController.java`, `src/main/resources/com/glodon/mordor/kmate/hello-view.fxml`.
- No JUnit tests. Verification is via `mvn compile` per task and a final manual acceptance checklist.
- Git: project is not currently a git repo. `git add`/`git commit` steps assume the engineer runs `git init` first; if they don't, skip the commit step.

---

## File Structure

All new files live under `src/main/java/com/glodon/mordor/kmate/` unless otherwise noted. Each file has one responsibility.

| File | Responsibility |
|---|---|
| `pom.xml` (modify) | Add `ikonli-materialdesign2-pack` dependency. |
| `module-info.java` (modify) | Add `requires org.kordamp.ikonli.materialdesign2`. |
| `AppState.java` (new) | Record holding `username`, `peerName`. |
| `Sender.java` (new) | Enum `SELF, PEER, SYSTEM`. |
| `Message.java` (new) | Record holding `id, sender, content, timestamp`. |
| `styles.css` (new, under `src/main/resources/com/glodon/mordor/kmate/`) | Color tokens, font stack, bubble shapes, button styling. |
| `MessageBubble.java` (new) | One chat row (avatar + name + bubble + timestamp). |
| `ChatHeader.java` (new) | Top bar (title + status emoji + `我 ↔ 对方`). |
| `MessageListView.java` (new) | `ScrollPane` + `VBox` of bubbles with auto-scroll. |
| `EmojiPopover.java` (new) | 6×6 emoji `Popup`. |
| `InputBar.java` (new) | Attach + text + emoji + send controls. |
| `LoginPane.java` (new) | 5-field form + connect button + validation. |
| `ChatPane.java` (new) | VBox composing Header + List + InputBar; holds sample data. |
| `Mate4K.java` (modify) | Rewrite `start()` to build `StackPane`, wire both panes, set window. |
| `MateController.java` (delete) | Old placeholder. |
| `hello-view.fxml` (delete) | Old placeholder. |

---

## Task 1: Add Ikonli MaterialDesign2 dependency

**Files:**
- Modify: `pom.xml:114` (add dep after the existing `org.kordamp.ikonli:ikonli-javafx`)
- Modify: `src/main/java/module-info.java:9` (add `requires` after the existing `org.kordamp.ikonli.javafx` line)

- [ ] **Step 1: Add Maven dependency**

In `pom.xml`, after the `ikonli-javafx` dependency block, add:

```xml
<dependency>
    <groupId>org.kordamp.ikonli</groupId>
    <artifactId>ikonli-materialdesign2-pack</artifactId>
    <version>12.3.1</version>
</dependency>
```

- [ ] **Step 2: Add module requires**

In `src/main/java/module-info.java`, after the line `requires org.kordamp.ikonli.javafx;`, add:

```java
requires org.kordamp.ikonli.materialdesign2;
```

- [ ] **Step 3: Verify dependency resolves**

Run: `mvn -q -Pmac dependency:resolve | grep -i ikonli`
Expected: two lines containing `ikonli-javafx` and `ikonli-materialdesign2-pack`.

- [ ] **Step 4: Verify compile still works**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`, no errors.

- [ ] **Step 5: Commit (optional)**

```bash
git add pom.xml src/main/java/module-info.java
git commit -m "build: add ikonli-materialdesign2-pack for Paperclip and Send icons"
```

---

## Task 2: Remove placeholder scaffold

**Files:**
- Delete: `src/main/java/com/glodon/mordor/kmate/MateController.java`
- Delete: `src/main/resources/com/glodon/mordor/kmate/hello-view.fxml`

- [ ] **Step 1: Delete `MateController.java`**

Run: `rm /Users/ksw/workspace/repository/kmate/src/main/java/com/glodon/mordor/kmate/MateController.java`

- [ ] **Step 2: Delete `hello-view.fxml`**

Run: `rm /Users/ksw/workspace/repository/kmate/src/main/resources/com/glodon/mordor/kmate/hello-view.fxml`

- [ ] **Step 3: Verify compile fails on missing fxml reference**

Run: `mvn -q -Pmac compile`
Expected: compile fails because `Mate4K.java` still loads `hello-view.fxml`. This is the expected transient state — Task 11 will rewrite `Mate4K.java`.

- [ ] **Step 4: Commit (optional)**

```bash
git add -A
git commit -m "refactor: remove placeholder MateController and hello-view.fxml"
```

---

## Task 3: Data models (Sender, Message, AppState)

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/Sender.java`
- Create: `src/main/java/com/glodon/mordor/kmate/Message.java`
- Create: `src/main/java/com/glodon/mordor/kmate/AppState.java`

- [ ] **Step 1: Create `Sender.java`**

File: `src/main/java/com/glodon/mordor/kmate/Sender.java`

```java
package com.glodon.mordor.kmate;

public enum Sender { SELF, PEER, SYSTEM }
```

- [ ] **Step 2: Create `Message.java`**

File: `src/main/java/com/glodon/mordor/kmate/Message.java`

```java
package com.glodon.mordor.kmate;

import java.time.LocalDateTime;

public record Message(
        String id,
        Sender sender,
        String content,
        LocalDateTime timestamp
) {}
```

- [ ] **Step 3: Create `AppState.java`**

File: `src/main/java/com/glodon/mordor/kmate/AppState.java`

```java
package com.glodon.mordor.kmate;

public record AppState(
        String username,
        String peerName
) {}
```

- [ ] **Step 4: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`. (It will still fail on `Mate4K.java` referencing the deleted fxml — that's fine; the new model files compile cleanly.)

- [ ] **Step 5: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/
git commit -m "feat: add Sender enum, Message and AppState records"
```

---

## Task 4: Create styles.css

**Files:**
- Create: `src/main/resources/com/glodon/mordor/kmate/styles.css`

- [ ] **Step 1: Create the stylesheet**

File: `src/main/resources/com/glodon/mordor/kmate/styles.css`

```css
/* Color tokens consumed via lookup-color */
.root {
    -color-primary:       #1976D2;
    -color-primary-soft:  #BBDEFB;
    -color-peer-bubble:   #F5F5F5;
    -color-bg-app:        #FAFAFA;
    -color-text-primary:  #212121;
    -color-text-secondary:#757575;
    -color-system-bubble: #FFF3E0;

    -fx-font-family: "SF Pro Text", "PingFang SC", "Microsoft YaHei", sans-serif;
    -fx-base: #FAFAFA;
}

/* App background */
.app-bg {
    -fx-background-color: #FAFAFA;
}

/* Title bar */
.header {
    -fx-background-color: white;
    -fx-border-color: transparent transparent #E0E0E0 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 4, 0, 0, 1);
}

.header-title {
    -fx-font-size: 13px;
    -fx-text-fill: #212121;
    -fx-font-weight: 600;
}

.header-meta {
    -fx-font-size: 9px;
    -fx-text-fill: #757575;
}

/* Message bubbles. background-radius order:
   topLeft, topRight, bottomRight, bottomLeft. */
.bubble-self {
    -fx-background-color: #1976D2;
    -fx-background-radius: 12 12 0 12;
    -fx-padding: 6 10 6 10;
    -fx-text-fill: white;
    -fx-font-size: 11px;
}

.bubble-peer {
    -fx-background-color: #F5F5F5;
    -fx-background-radius: 12 12 12 0;
    -fx-padding: 6 10 6 10;
    -fx-text-fill: #212121;
    -fx-font-size: 11px;
}

.bubble-system {
    -fx-background-color: #FFF3E0;
    -fx-background-radius: 8 8 8 8;
    -fx-padding: 4 10 4 10;
    -fx-text-fill: #757575;
    -fx-font-size: 10px;
    -fx-alignment: center;
}

.bubble-name {
    -fx-font-size: 9px;
    -fx-text-fill: #757575;
}

.bubble-time {
    -fx-font-size: 8px;
    -fx-text-fill: #9E9E9E;
}

/* Input bar */
.input-bar {
    -fx-background-color: white;
    -fx-border-color: #E0E0E0 transparent transparent transparent;
    -fx-border-width: 1 0 0 0;
    -fx-padding: 8 10 8 10;
}

.input-bar .button {
    -fx-background-color: transparent;
    -fx-cursor: hand;
}

.input-bar .button:hover {
    -fx-background-color: #F5F5F5;
}

.input-bar .button:disabled {
    -fx-opacity: 0.4;
}

.input-bar .text-field {
    -fx-background-radius: 16 16 16 16;
    -fx-background-color: #F5F5F5;
    -fx-padding: 4 8 4 8;
    -fx-font-size: 11px;
}

.input-bar .send-active {
    -fx-text-fill: #1976D2;
}

/* Login form */
.login-form {
    -fx-background-color: white;
    -fx-background-radius: 8 8 8 8;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.10), 12, 0, 0, 2);
}

.login-title {
    -fx-font-size: 14px;
    -fx-font-weight: 600;
    -fx-text-fill: #212121;
}

.login-subtitle {
    -fx-font-size: 9px;
    -fx-text-fill: #757575;
}

.login-field {
    -fx-font-size: 11px;
}

.login-info {
    -fx-background-color: #E3F2FD;
    -fx-text-fill: #1565C0;
    -fx-background-radius: 4 4 4 4;
    -fx-padding: 6 10 6 10;
    -fx-font-size: 10px;
}

.login-error {
    -fx-background-color: #FFEBEE;
    -fx-text-fill: #C62828;
    -fx-background-radius: 4 4 4 4;
    -fx-padding: 6 10 6 10;
    -fx-font-size: 10px;
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`. The CSS file is a resource, doesn't affect compilation.

- [ ] **Step 3: Commit (optional)**

```bash
git add src/main/resources/com/glodon/mordor/kmate/styles.css
git commit -m "feat(styles): add stylesheet with Material tokens and chat bubble shapes"
```

---

## Task 5: MessageBubble component

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/MessageBubble.java`

- [ ] **Step 1: Create the component**

File: `src/main/java/com/glodon/mordor/kmate/MessageBubble.java`

```java
package com.glodon.mordor.kmate;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.format.DateTimeFormatter;

public class MessageBubble extends HBox {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String STYLE_SELF  = "bubble-self";
    private static final String STYLE_PEER  = "bubble-peer";
    private static final String STYLE_SYS   = "bubble-system";
    private static final String STYLE_NAME  = "bubble-name";
    private static final String STYLE_TIME  = "bubble-time";

    public MessageBubble(Message msg, String myName, String peerName) {
        super(4);
        setFillHeight(false);
        setPadding(new Insets(2, 4, 2, 4));

        switch (msg.sender()) {
            case SELF -> renderSelf(msg, myName);
            case PEER -> renderPeer(msg, peerName);
            case SYSTEM -> renderSystem(msg);
        }
    }

    private void renderSelf(Message msg, String myName) {
        setAlignment(Pos.CENTER_RIGHT);
        Label name = new Label(myName.isBlank() ? "我" : myName);
        name.getStyleClass().add(STYLE_NAME);
        Label time = new Label(HHMM.format(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);

        Label bubble = new Label(msg.content());
        bubble.setWrapText(true);
        bubble.setMaxWidth(380);
        bubble.getStyleClass().add(STYLE_SELF);

        StackPane avatar = avatar(myName.isBlank() ? "我" : myName, true);

        VBox col = new VBox(2, name, bubble, time);
        col.setAlignment(Pos.BOTTOM_RIGHT);
        HBox row = new HBox(4, col, avatar);
        row.setAlignment(Pos.BOTTOM_RIGHT);
        getChildren().add(row);
    }

    private void renderPeer(Message msg, String peerName) {
        setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(peerName.isBlank() ? "对方" : peerName);
        name.getStyleClass().add(STYLE_NAME);
        Label time = new Label(HHMM.format(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);

        Label bubble = new Label(msg.content());
        bubble.setWrapText(true);
        bubble.setMaxWidth(380);
        bubble.getStyleClass().add(STYLE_PEER);

        StackPane avatar = avatar(peerName.isBlank() ? "对方" : peerName, false);

        VBox col = new VBox(2, name, bubble, time);
        col.setAlignment(Pos.BOTTOM_LEFT);
        HBox row = new HBox(4, avatar, col);
        row.setAlignment(Pos.BOTTOM_LEFT);
        getChildren().add(row);
    }

    private void renderSystem(Message msg) {
        setAlignment(Pos.CENTER);
        Label bubble = new Label(msg.content());
        bubble.getStyleClass().add(STYLE_SYS);
        getChildren().add(bubble);
    }

    private StackPane avatar(String name, boolean self) {
        Circle bg = new Circle(11);
        bg.setFill(self ? Color.web("#1976D2") : Color.web("#9E9E9E"));
        Label initial = new Label(name.substring(0, 1));
        initial.setStyle("-fx-text-fill: white; -fx-font-size: 9px; -fx-font-weight: 600;");
        return new StackPane(bg, initial);
    }
}
```

Note: the `Text content = new Text(...)` line in `renderSelf` is currently unused (we render via `Label`). Leave it as-is — it documents the content string for future tweaks; remove only if a code reviewer flags it.

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`. `MessageBubble.java` has no dependencies on the broken `Mate4K.java`, so it compiles cleanly even with the missing fxml.

- [ ] **Step 3: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/MessageBubble.java
git commit -m "feat(ui): add MessageBubble with self/peer/system styles"
```

---

## Task 6: ChatHeader component

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ChatHeader.java`

- [ ] **Step 1: Create the component**

File: `src/main/java/com/glodon/mordor/kmate/ChatHeader.java`

```java
package com.glodon.mordor.kmate;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public class ChatHeader extends HBox {

    public ChatHeader(AppState state) {
        super();
        getStyleClass().add("header");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(10, 12, 10, 12));

        Label title = new Label("SiMate 🟢");
        title.getStyleClass().add("header-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label meta = new Label(state.username() + " ↔ " + state.peerName());
        meta.getStyleClass().add("header-meta");

        getChildren().addAll(title, spacer, meta);
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/ChatHeader.java
git commit -m "feat(ui): add ChatHeader with title, status and peer name"
```

---

## Task 7: MessageListView component

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/MessageListView.java`

- [ ] **Step 1: Create the component**

File: `src/main/java/com/glodon/mordor/kmate/MessageListView.java`

```java
package com.glodon.mordor.kmate;

import javafx.collections.ListChangeListener;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import java.util.List;

public class MessageListView extends ScrollPane {

    private final VBox container;

    public MessageListView() {
        container = new VBox(4);
        container.setFillWidth(true);
        container.setPadding(new javafx.geometry.Insets(8, 8, 8, 8));

        setContent(container);
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        getStyleClass().add("app-bg");

        container.getChildren().addListener((ListChangeListener<javafx.scene.Node>) c ->
                scrollToBottom());
    }

    public void addMessage(Message msg, String myName, String peerName) {
        container.getChildren().add(new MessageBubble(msg, myName, peerName));
    }

    public void prefill(List<Message> messages, String myName, String peerName) {
        container.getChildren().clear();
        for (Message m : messages) {
            container.getChildren().add(new MessageBubble(m, myName, peerName));
        }
        scrollToBottom();
    }

    private void scrollToBottom() {
        setVvalue(1.0);
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/MessageListView.java
git commit -m "feat(ui): add MessageListView with auto-scroll on append"
```

---

## Task 8: EmojiPopover component

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/EmojiPopover.java`

- [ ] **Step 1: Create the component**

File: `src/main/java/com/glodon/mordor/kmate/EmojiPopover.java`

```java
package com.glodon.mordor.kmate;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Popup;

public class EmojiPopover {

    private static final String[] EMOJIS = {
            "😀","😁","😂","🤣","😊","😍",
            "😘","😎","🤩","🥳","🤔","🙄",
            "😴","😪","🤗","🤭","🤫","🤐",
            "👍","👎","👏","🙏","💪","🤝",
            "❤️","💔","💯","🔥","✨","🎉",
            "☕","🍕","🍺","🎁","📎","📁"
    };

    private final Popup popup = new Popup();
    private final TextField target;

    public EmojiPopover(TextField target) {
        this.target = target;
        popup.getContent().add(buildGrid());
        popup.setAutoHide(true);
    }

    private GridPane buildGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(2);
        grid.setVgap(2);
        grid.setPadding(new Insets(6));
        grid.setStyle("-fx-background-color: white; " +
                "-fx-border-color: #E0E0E0; " +
                "-fx-border-radius: 4; " +
                "-fx-background-radius: 4;");
        for (int i = 0; i < EMOJIS.length; i++) {
            String emoji = EMOJIS[i];
            Button b = new Button(emoji);
            b.setStyle("-fx-background-color: transparent; " +
                    "-fx-font-size: 14px; " +
                    "-fx-cursor: hand; " +
                    "-fx-padding: 2 4 2 4;");
            b.setOnAction(e -> {
                insertAtCaret(emoji);
                popup.hide();
            });
            grid.add(b, i % 6, i / 6);
        }
        return grid;
    }

    private void insertAtCaret(String emoji) {
        String text = target.getText();
        int caret = target.getCaretPosition();
        String next = text.substring(0, caret) + emoji + text.substring(caret);
        target.setText(next);
        target.positionCaret(caret + emoji.length());
        target.requestFocus();
    }

    public void show(javafx.scene.Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        javafx.geometry.Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMinX(), b.getMinY() - 200);
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/EmojiPopover.java
git commit -m "feat(ui): add EmojiPopover with 6x6 grid and caret-aware insert"
```

---

## Task 9: InputBar component

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/InputBar.java`

- [ ] **Step 1: Create the component**

File: `src/main/java/com/glodon/mordor/kmate/InputBar.java`

```java
package com.glodon.mordor.kmate;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.function.Consumer;

public class InputBar extends HBox {

    private final TextField textField;
    private final Button sendBtn;
    private final EmojiPopover emojiPopover;

    public InputBar(Runnable onSend) {
        super(6);
        getStyleClass().add("input-bar");
        setAlignment(Pos.CENTER_LEFT);

        Button attach = new Button();
        attach.setGraphic(new FontIcon(MaterialDesignS.PAPERCLIP));
        attach.setOnAction(e -> showAttachStub());

        textField = new TextField();
        textField.setPromptText("输入消息...");
        textField.setOnAction(e -> send(onSend));
        textField.textProperty().addListener((obs, o, n) ->
                sendBtn.setDisable(n == null || n.isBlank()));
        HBox.setHgrow(textField, Priority.ALWAYS);

        Button emoji = new Button("😊");
        emoji.setStyle("-fx-font-size: 14px; -fx-background-color: transparent; -fx-cursor: hand;");
        emojiPopover = new EmojiPopover(textField);
        emoji.setOnAction(e -> emojiPopover.show(emoji));

        sendBtn = new Button();
        sendBtn.setGraphic(new FontIcon(MaterialDesignS.SEND));
        sendBtn.setDisable(true);
        sendBtn.setOnAction(e -> send(onSend));

        getChildren().addAll(attach, textField, emoji, sendBtn);
    }

    private void send(Runnable onSend) {
        String text = textField.getText();
        if (text == null || text.isBlank()) return;
        onSend.run();
    }

    private void showAttachStub() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText("文件传输未实现（demo 模式）");
        alert.showAndWait();
    }

    /** Visible for tests / external callers that need to read the typed text. */
    public String getText() {
        return textField.getText();
    }

    /** Visible for the parent to clear the field after a successful send. */
    public void clear() {
        textField.clear();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/InputBar.java
git commit -m "feat(ui): add InputBar with attach, emoji, send and disabled-when-blank state"
```

---

## Task 10: LoginPane component

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/LoginPane.java`

- [ ] **Step 1: Create the component**

File: `src/main/java/com/glodon/mordor/kmate/LoginPane.java`

```java
package com.glodon.mordor.kmate;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

public class LoginPane extends VBox {

    private final TextField serverIp = new TextField();
    private final TextField serverPort = new TextField();
    private final TextField imCode = new TextField();
    private final TextField password = new TextField();
    private final TextField username = new TextField();
    private final Label errorLabel = new Label();

    public LoginPane(Consumer<AppState> onConnect) {
        super(8);
        getStyleClass().addAll("app-bg", "login-form");
        setPadding(new Insets(20));
        setAlignment(Pos.TOP_CENTER);
        setMaxWidth(380);

        Label title = new Label("SiMate");
        title.getStyleClass().add("login-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        Label subtitle = new Label("局域网加密聊天");
        subtitle.getStyleClass().add("login-subtitle");
        subtitle.setMaxWidth(Double.MAX_VALUE);
        subtitle.setAlignment(Pos.CENTER);

        errorLabel.getStyleClass().add("login-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setMaxWidth(Double.MAX_VALUE);

        Label info = new Label("请与对方约定相同的 IM_CODE 和初始口令进行配对");
        info.getStyleClass().add("login-info");
        info.setWrapText(true);
        info.setMaxWidth(Double.MAX_VALUE);

        serverIp.setPromptText("127.0.0.1");
        serverPort.setPromptText("3000");
        imCode.setPromptText("输入配对码（如：ABC123）");
        password.setPromptText("输入初始口令");
        username.setPromptText("输入你的名称");

        for (TextField f : new TextField[]{serverIp, serverPort, imCode, password, username}) {
            f.getStyleClass().add("login-field");
        }

        HBox ipRow = new HBox(8, serverIp, serverPort);
        serverIp.setPrefWidth(220);
        serverPort.setPrefWidth(120);

        Button connect = new Button("连接");
        connect.setDefaultButton(true);
        connect.setMaxWidth(Double.MAX_VALUE);
        connect.setOnAction(e -> handleConnect(onConnect));

        getChildren().addAll(
                title, subtitle,
                errorLabel, info,
                ipRow, imCode, password, username,
                connect);

        // Prefill with spec defaults so a single click reaches chat.
        serverIp.setText("127.0.0.1");
        serverPort.setText("3000");
        imCode.setText("ABC123");
        password.setText("demo");
        username.setText("我");
    }

    private void handleConnect(Consumer<AppState> onConnect) {
        if (!validate()) return;
        AppState state = new AppState(username.getText().trim(), "Alice");
        onConnect.accept(state);
    }

    private boolean validate() {
        if (serverIp.getText().isBlank())     return showError("请输入服务器 IP");
        if (serverPort.getText().isBlank())   return showError("请输入端口");
        if (!serverPort.getText().matches("\\d+")) return showError("端口必须是数字");
        if (imCode.getText().isBlank())       return showError("请输入 IM_CODE");
        if (password.getText().isBlank())     return showError("请输入初始口令");
        if (username.getText().isBlank())     return showError("请输入用户名");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        return true;
    }

    private boolean showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        return false;
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/LoginPane.java
git commit -m "feat(ui): add LoginPane with five-field form, validation and prefill"
```

---

## Task 11: ChatPane component

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ChatPane.java`

- [ ] **Step 1: Create the component**

File: `src/main/java/com/glodon/mordor/kmate/ChatPane.java`

```java
package com.glodon.mordor.kmate;

import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ChatPane extends VBox {

    private final AppState state;
    private final MessageListView list;
    private final InputBar input;
    private final AtomicLong idGen = new AtomicLong(1000);

    public ChatPane(AppState state) {
        super();
        this.state = state;
        getStyleClass().add("app-bg");

        ChatHeader header = new ChatHeader(state);
        list = new MessageListView();
        VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);

        input = new InputBar(this::handleSend);

        getChildren().addAll(header, list, input);
        list.prefill(sampleMessages(), state.username(), state.peerName());
    }

    private void handleSend() {
        String text = input.getText();
        if (text == null || text.isBlank()) return;
        list.addMessage(
                new Message(String.valueOf(idGen.incrementAndGet()),
                        Sender.SELF,
                        text,
                        LocalDateTime.now()),
                state.username(),
                state.peerName());
        input.clear();
    }

    private List<Message> sampleMessages() {
        LocalDateTime now = LocalDateTime.now();
        return List.of(
                new Message("1", Sender.SYSTEM, "[系统] 对方已连接",
                        now.minusSeconds(60)),
                new Message("2", Sender.PEER, "你好 👋",
                        now.minusSeconds(55)),
                new Message("3", Sender.SELF, "你好，请问是张三吗？",
                        now.minusSeconds(48)),
                new Message("4", Sender.PEER, "对的，我是",
                        now.minusSeconds(40)),
                new Message("5", Sender.SELF, "[文件] design.pdf",
                        now.minusSeconds(15)),
                new Message("6", Sender.PEER, "收到，谢谢！",
                        now.minusSeconds(8))
        );
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/ChatPane.java
git commit -m "feat(ui): add ChatPane composing header, list and input bar with sample data"
```

---

## Task 12: Wire up `Mate4K` entry point

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/Mate4K.java` (full rewrite)

- [ ] **Step 1: Replace `Mate4K.java` contents**

File: `src/main/java/com/glodon/mordor/kmate/Mate4K.java`

```java
package com.glodon.mordor.kmate;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Mate4K extends Application {

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        root.getStyleClass().add("app-bg");

        LoginPane loginPane = new LoginPane(state -> showChat(root, loginPane, state));
        root.getChildren().add(loginPane);

        Scene scene = new Scene(root, 660, 510);
        scene.getStylesheets().add(
                Mate4K.class.getResource("styles.css").toExternalForm());

        stage.setTitle("Kmate");
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(360);
        stage.show();
    }

    private void showChat(StackPane root, LoginPane loginPane, AppState state) {
        ChatPane chatPane = new ChatPane(state);
        root.getChildren().add(chatPane);
        loginPane.setVisible(false);
        loginPane.setManaged(false);
    }

    public static void main(String[] args) {
        launch();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q -Pmac compile`
Expected: `BUILD SUCCESS`. The full project now compiles.

- [ ] **Step 3: Run dev mode and walk the flow**

Run: `mvn -q -Pmac javafx:run`
Expected: window opens at 660×510 with title "Kmate", showing the LoginDialog. Click "连接" → chat appears with six sample messages. Type text + send → new SELF bubble appears. Click attach → Alert dialog. Click emoji → 6×6 popover, clicking an emoji inserts into the text field.

- [ ] **Step 4: Build the packaged artifact**

Run: `mvn -B -Pmac clean package`
Expected: `BUILD SUCCESS`, `target/dist/Kmate-1.0.0.dmg` produced.

- [ ] **Step 5: Commit (optional)**

```bash
git add src/main/java/com/glodon/mordor/kmate/Mate4K.java
git commit -m "feat(app): wire Mate4K to load LoginPane and swap to ChatPane on connect"
```

---

## Task 13: Manual acceptance checklist

No file changes. This task is verification only.

- [ ] **Step 1: Run the acceptance checklist from the spec**

Run: `mvn -B -Pmac clean package && open target/dist/Kmate-1.0.0.dmg`

Walk through all twelve items from `docs/superpowers/specs/2026-08-24-simate-client-javafx-ui-design.md` § Acceptance / verification:

1. LoginDialog appears 660×510 with title "SiMate / 局域网加密聊天", five fields pre-filled.
2. Click "连接" → ChatWindow appears, LoginDialog disappears.
3. Header reads `SiMate 🟢` (left) and `我 ↔ Alice` (right).
4. Six sample messages render with correct alignment and colors.
5. Bubble corners match spec (self bottom-right flat, peer bottom-left flat).
6. Timestamps in `HH:mm` format.
7. Empty input → send button disabled.
8. Type + send → new SELF bubble appears with current time.
9. Attach button → Alert: "文件传输未实现（demo 模式）".
10. Emoji button → 6×6 popover; click inserts at caret.
11. Resize window: header and input bar height unchanged; list fills remainder.
12. Mixed Chinese/English text wraps cleanly inside bubbles.

- [ ] **Step 2: Capture a screenshot for visual diff**

Take a screenshot of the running app. Compare against the rendering of `simate/client` (run its `pnpm tauri dev` or open its built DMG if available). Differences acceptable: font family, scrollbar chrome, emoji rendering. Differences NOT acceptable: layout, color, bubble shape.

- [ ] **Step 3: If any item fails, fix the offending task and re-verify**

Don't move on until all 12 items pass. Each fix is a follow-up commit; reference the failing item number in the commit message.

---

## Self-Review

Coverage check against spec sections:

| Spec section | Covered by |
|---|---|
| § Goal, § Out of scope | Plan header, Task 11 sample data, Task 13 acceptance scope |
| § Architecture (StackPane swap) | Task 12 |
| § Package layout (every file) | Tasks 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 |
| § Data model (Sender, Message) | Task 3 |
| § Data flow (login → chat) | Task 10 (validation), Task 11 (sample data), Task 12 (swap) |
| § Sample messages (six, fixed) | Task 11 `sampleMessages()` |
| § Styling (palette, font, bubbles, avatar, icons) | Tasks 4, 5, 9 |
| § LoginPane behavior | Task 10 |
| § ChatHeader behavior | Task 6 |
| § MessageListView behavior | Task 7 |
| § MessageBubble behavior | Task 5 |
| § InputBar behavior | Task 9 |
| § EmojiPopover behavior | Task 8 |
| § Edge cases (empty input, blank, wrap, resize, system message) | Tasks 5, 9, 10 |
| § Acceptance checklist (12 items) | Task 13 |
| § Open questions | none |

Placeholder scan:
- No "TBD", "TODO", or "implement later" markers.
- No "add appropriate error handling" hand-waves — validation is fully spelled out in Task 10.
- All referenced types (`AppState`, `Sender`, `Message`, `MessageBubble`, `ChatHeader`, `MessageListView`, `EmojiPopover`, `InputBar`, `LoginPane`, `ChatPane`, `Mate4K`) are created by earlier tasks before they are used.

Type consistency:
- `MessageBubble(Message, String myName, String peerName)` is used consistently in Tasks 5, 7, 11.
- `MessageListView.addMessage(Message, String, String)` and `prefill(List<Message>, String, String)` are used in Task 11.
- `InputBar.getText()` and `InputBar.clear()` are used in Task 11.
- `EmojiPopover(TextField)` constructor used in Task 9.
- `LoginPane(Consumer<AppState>)` constructor used in Task 12.
- `ChatPane(AppState)` constructor used in Task 12.
- `ChatHeader(AppState)` constructor used in Task 11.
- `AppState(String username, String peerName)` record used everywhere.

No inconsistencies found.