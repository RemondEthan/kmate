# Kmate — Replicating SiMate Chat UI in JavaFX

Date: 2026-08-24
Status: Approved (brainstorm sections 1–4)
Project: `com.glodon.mordor:kmate`

## Goal

Replicate the user interface of `simate/client` (a Tauri + React + MUI LAN chat
client) as a JavaFX desktop application inside the existing `kmate` Maven
project. The replication covers two screens (LoginDialog and ChatWindow), the
visual styling, and the inter-screen flow.

## Out of scope (explicit)

- Real WebSocket networking to the simate server.
- AES-GCM encryption / decryption of message bodies.
- SQLite message history persistence.
- System tray integration.
- File transfer implementation.
- Multi-language / i18n beyond the existing Chinese strings carried over from
  simate.
- Cross-platform packaging (the existing `mac` / `win` / `linux-deb` profiles
  are untouched by this design).

## Reference

Visual source: `/Users/ksw/workspace/repository/simate/client/src-ui/src/`

- `App.tsx` — top-level swap between LoginDialog and ChatWindow.
- `components/LoginDialog.tsx` — 5-field form (serverIp, serverPort, imCode,
  password, username) with validation.
- `components/ChatWindow.tsx` — header (title + status + peer name), message
  list (self/peer/system bubbles with avatar and timestamp), input bar (file
  attach + text + emoji + send).

Reference window: 660×510, resizable.

## Architecture

Single window, single scene, in-scene view swap. A `StackPane` root holds both
the `LoginPane` and the `ChatPane`; the active one is brought to front by
toggling `managed` / `visible`. No second `Stage`, no separate scenes.

```
Stage (660×510, title "Kmate")
└── Scene
    └── StackPane (root)
        ├── LoginPane       (visible before login)
        └── ChatPane        (visible after login)
```

A shared `AppState` object (plain record or holder class) is created by
`Mate4K.start()` and passed into both panes. `AppState` carries the username
typed at login and the peer name displayed in the chat header.

## Package layout

All under `com.glodon.mordor.kmate`:

| File | Kind | Responsibility |
|---|---|---|
| `Mate4K.java` | Existing, modified | Application entry. Loads StackPane, wires both panes, sets window title and size. |
| `AppState.java` | New (record) | Holds `username` and `peerName`. |
| `Message.java` | New (record + Sender enum) | One chat message. |
| `LoginPane.java` | New (Java code, no FXML) | 5-field form + connect button + lightweight validation. Emits an `onConnect(AppState)` callback. |
| `ChatPane.java` | New (Java code) | VBox composing Header + List + InputBar. Receives `AppState` via constructor. |
| `ChatHeader.java` | New (Java code) | Title + status emoji + `我 ↔ 对方`. |
| `MessageListView.java` | New (Java code) | ScrollPane + VBox of `MessageBubble`; auto-scroll on add. |
| `InputBar.java` | New (Java code) | Attach + text + emoji + send; emits `onSend(String)`, `onAttach()`, `onEmoji()`. |
| `MessageBubble.java` | New (Java code) | One row: avatar + sender name + bubble + timestamp. Style varies by `Sender`. |
| `EmojiPopover.java` | New (Java code) | 6×6 grid of common emojis in a `Popup`. Click inserts at text-field caret. |
| `styles.css` | New (resource) | Color tokens, font stack, bubble shapes, button styling. |

Existing `MateController.java`, `hello-view.fxml` are deleted.

## Data model

```java
public record Message(
    String id,
    Sender sender,           // SELF, PEER, SYSTEM
    String content,
    LocalDateTime timestamp
) {}

public enum Sender { SELF, PEER, SYSTEM }
```

## Data flow

```
Mate4K.start()
  │
  ├─ build AppState (username="我", peerName="Alice")
  │
  ├─ build LoginPane, defaultConfig:
  │     serverIp="127.0.0.1", serverPort="3000",
  │     imCode="ABC123", password="demo", username="我"
  │     onConnect(state) → validate → swap to ChatPane
  │
  ├─ build ChatPane(state)
  │     prefill(sampleMessages)
  │
  └─ StackPane { loginPane, chatPane }
       chatPane.setVisible(false); chatPane.setManaged(false);
       root.getChildren().addAll(loginPane, chatPane);
```

`onConnect` validation rules (mirrors `LoginDialog.tsx`):

- All five fields non-blank.
- `serverPort` matches `^\d+$`.
- On failure: show inline `Alert` styled as error (no submit, no scene swap).
- On success: populate `AppState.username` from input, then show ChatPane.

## Sample messages

Hardcoded inside `ChatPane` (or a `SampleData` helper), six messages:

```java
List.of(
    new Message("1", SYSTEM, "[系统] 对方已连接", now),
    new Message("2", PEER,   "你好 👋",                 now.plusSeconds(5)),
    new Message("3", SELF,   "你好，请问是张三吗？",   now.plusSeconds(12)),
    new Message("4", PEER,   "对的，我是",              now.plusSeconds(20)),
    new Message("5", SELF,   "[文件] design.pdf",       now.plusSeconds(45)),
    new Message("6", PEER,   "收到，谢谢！",            now.plusSeconds(52)),
)
```

`now` is captured once when the chat pane is constructed so the timestamps are
internally consistent.

## Styling

### Color tokens (top of `styles.css`)

```css
:root {
    -primary:       #1976D2;
    -primary-soft:  #BBDEFB;
    -peer-bubble:   #F5F5F5;
    -bg-app:        #FAFAFA;
    -text-primary:  #212121;
    -text-secondary:#757575;
    -system-bubble: #FFF3E0;
}
```

### Font

```css
.root { -fx-font-family: "SF Pro Text", "PingFang SC", "Microsoft YaHei", sans-serif; }
```

System-default emoji rendering on macOS (Apple Color Emoji) and Windows
(Segoe UI Emoji) is acceptable; no font embedding.

### Bubble shapes

Three rounded corners plus one flat corner per sender to mimic a chat tail:

```css
.bubble-self { -fx-background-radius: 12 12 0 12; }
.bubble-peer { -fx-background-radius: 12 12 12 0; }
```

`-fx-background-radius` order in JavaFX is `topLeft, topRight, bottomRight,
bottomLeft`.

### Avatars

`StackPane(Circle r=11, Label first-letter)` colored `primary` for SELF and
`#9E9E9E` for PEER. SYSTEM messages have no avatar.

### Iconography

`org.kordamp.ikonli:ikonli-materialdesign2-pack` added to pom. Used for:

- `MaterialDesignS.PAPERCLIP` — attach button (16 px)
- `MaterialDesignS.SEND` — send button (16 px)

Emoji button uses a literal `"😊"` glyph as a label (JavaFX renders system
emoji).

## Component behavior

### LoginPane

- Five `TextField` (one for password uses `setPromptText` only — not a real
  `PasswordField`, since this is a UI demo and we want the prefill visible).
- Single "连接" `Button` (`ButtonType.OK`-style, full width).
- Inline error `Label` for validation messages (red background, top of form;
  NOT a JavaFX `Alert` dialog — that one is always modal and we want the
  message inside the form like the original MUI design).
- Pre-fills all defaults so a single click is enough to reach the chat.

### ChatHeader

- `HBox`: `Label("SiMate 🟢")` (left, `HBox.setHgrow(..., ALWAYS)`), spacer
  via `Region`, `Label("我 ↔ Alice")` (right).
- Status emoji is hardcoded 🟢 for demo (no real connection).
- Background `bg-app`, slight bottom shadow.

### MessageListView

- `ScrollPane(fitToWidth=true)` containing a `VBox(spacing=4)`.
- Each `MessageBubble` is added bottom-up; the VBox `fillWidth=true`.
- Auto-scroll: a `ChangeListener<ObservableList<Node>>` on the VBox's
  children scrolls the ScrollPane to bottom on add.

### MessageBubble

- Outer `HBox(alignment)` = `CENTER_RIGHT` for SELF, `CENTER_LEFT` for PEER,
  `CENTER` for SYSTEM.
- Inside (SELF/PEER): `HBox(spacing=4)` with avatar (left or right) + inner
  `VBox(spacing=2)` containing name `Label`, bubble `StackPane` (background
  + content `Label(wrapText=true)`), timestamp `Label`.
- SYSTEM: single `Label` with background `system-bubble`, no avatar, no
  timestamp.

### InputBar

- `HBox(spacing=4)` with: paperclip `Button`, `TextField` (`Hgrow=ALWAYS`),
  emoji `Button`, send `Button`.
- Send button `disabled` while `textField.getText().isBlank()`.
- `onSend` appends a new SELF `Message` to the list with current timestamp
  and clears the text field.
- Attach button triggers `Alert.show("文件传输未实现（demo 模式）")`.
- Emoji button toggles the `EmojiPopover` anchored at the button.

### EmojiPopover

- `Popup` containing a 6×6 `GridPane` with 36 common emojis (handles,
  smileys, hearts, common objects).
- Each cell is a `Button` with the emoji glyph; on click, insert the
  character at the caret position of the associated `TextField` and hide
  the popover.
- Width ~ 240 px, height ~ 200 px.

## Edge cases

| Situation | Behavior |
|---|---|
| Empty input | Send disabled |
| Whitespace-only input | Send disabled (`isBlank`) |
| Long line | Bubble `Label` wraps via `wrapText=true` |
| Multi-line paste | Same wrap behavior |
| Window resize | Header height fixed, InputBar height fixed, MessageList fills remainder |
| Username contains emoji | Stored as-is; avatar falls back to first char, or "我" if empty |
| System messages | Centered, system-bubble color, no avatar, no timestamp |

## Acceptance / verification

No JUnit tests for this UI shell — the value-to-effort ratio is poor given the
absence of business logic.

Manual checklist (run `mvn -Pmac clean package` then open the resulting DMG, or
`mvn clean javafx:run` for dev mode):

1. LoginDialog appears 660×510, title "SiMate / 局域网加密聊天", all five
   fields pre-filled.
2. Clicking "连接" swaps to ChatWindow; LoginDialog disappears.
3. Header reads `SiMate 🟢` on the left and `我 ↔ Alice` on the right.
4. Six sample messages render: SELF right (blue bubble + blue avatar), PEER
   left (grey bubble + grey avatar), SYSTEM centered (orange).
5. Bubble corners: SELF has bottom-right flat; PEER has bottom-left flat.
6. Timestamps appear below each bubble in `HH:mm` format.
7. Empty input → send button disabled.
8. Type text + send → new SELF bubble appears at bottom with current time.
9. Attach button → Alert: "文件传输未实现（demo 模式）".
10. Emoji button → 6×6 popover; click any emoji inserts at caret.
11. Resize window: header and input bar height unchanged; list fills the
    remaining space and auto-scrolls to the latest message.
12. Mixed Chinese/English text wraps cleanly inside bubbles.

Visual diff against `simate/client` after build is acceptable to differ on:
font (SF Pro vs Segoe UI), scrollbar chrome, emoji rendering fidelity. The
layout, color, and shape must match.

## Open questions

None. All brainstorming answers locked:

- Scope: UI shell only with sample data.
- Styling: custom CSS + Ikonli.
- Connect behavior: button jumps directly to chat with prefilled defaults.
- Architecture: FXML-free Java code (LoginPane, ChatPane, etc. all Java).