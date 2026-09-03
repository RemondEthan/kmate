# Offline Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Login can skip the IM server and enter the existing chat UI, talking only to opt-in tars against a dedicated local history room.

**Architecture:** `AppState.offline` plus a nullable `ImClient`. `ChatController` uses fixed `__offline__` archive credentials and rejects `PEER` routes with a distinct hint. `send` returns `SendResult` so `InputBar` can show「脱机登录，消息无法发送」instead of the busy hint. Tars stays opt-in via「添加 tars」.

**Tech Stack:** Java 21, JavaFX 21, JUnit 5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-03-offline-login-design.md`

---

## File map

**Create:**

- `src/test/java/com/glodon/mordor/kmate/ui/login/LoginControllerTest.java`

**Modify:**

- `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java` — `SendResult`, offline constants, production ctor, `send`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java` — consume `SendResult`
- `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java` — `accepted()` / offline cases
- `src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java` — `Input.offline`, validate, save
- `src/main/java/com/glodon/mordor/kmate/model/AppState.java` — `offline`, factory
- `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java` — checkbox, disable fields, skip `ImClient`
- `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css` — checkbox style
- `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java` — null client
- `src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java` — no WS avatar publish when `client == null`
- `src/test/java/com/glodon/mordor/kmate/service/ChatHistoryTest.java` — `__offline__` round-trip

---

### Task 1: `SendResult` and InputBar hint

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java`

- [ ] **Step 1: Update `ChatControllerKelsyTest` to the new return type**

In `ChatController.java` add this public record **above** `send` (tests will not compile until `send` returns it):

```java
    public record SendResult(boolean accepted, String hint) {
        public static SendResult ok() {
            return new SendResult(true, null);
        }

        public static SendResult reject(String hint) {
            return new SendResult(false, hint);
        }
    }

    public static final String BUSY_HINT = "秘书还在回复";
    public static final String OFFLINE_REJECT_HINT = "脱机登录，消息无法发送";
```

Change `public boolean send(String content)` to `public SendResult send(String content)`.

Map each branch:

- blank → `return SendResult.reject(null);`
- `PEER` → `sendPeer` must return `SendResult.ok()` (keep calling `peerSender` for now)
- `BUSY` → `return SendResult.reject(BUSY_HINT);`
- `UNCONFIGURED` / `EMPTY_BODY` / `SLASH_ERROR` / `FIND` / `ASK` → existing side effects, `yield SendResult.ok();`

Update `sendPeer` to `private SendResult sendPeer(String content)` and `return SendResult.ok();` after the try/catch (still `return SendResult.ok()` on send failure after SYSTEM, matching current “cleared input” behavior).

In `ChatControllerKelsyTest` replace boolean asserts:

```java
        assertTrue(c.send("@tars 你好").accepted());
        // ...
        assertTrue(c.send("@tars 你好").accepted());
        // ...
        assertFalse(c.send("@tars 第二问").accepted());
        // ...
        assertTrue(c.send("普通").accepted());
```

Add after the busy second send:

```java
        assertEquals(ChatController.BUSY_HINT, c.send("@tars 第二问").hint());
```

Wait — that would send a third time. Instead on the existing second send:

```java
        ChatController.SendResult busy = c.send("@tars 第二问");
        assertFalse(busy.accepted());
        assertEquals(ChatController.BUSY_HINT, busy.hint());
```

- [ ] **Step 2: Run the kelsy controller tests**

Run: `./mvnw -q test -Dtest=ChatControllerKelsyTest`

Expected: PASS (online routing unchanged except return type).

- [ ] **Step 3: Point `InputBar` at `SendResult`**

Replace `Function<String, Boolean>` with `Function<String, ChatController.SendResult>`.

In `send(...)`:

```java
        ChatController.SendResult result = onSend.apply(text);
        if (result == null || !result.accepted()) {
            if (result != null && result.hint() != null && !result.hint().isBlank()) {
                hint.setText(result.hint());
                hint.setVisible(true);
                hint.setManaged(true);
                hideHint.stop();
                hideHint.playFromStart();
            }
            return;
        }
        clear();
```

`ChatPane` `new InputBar(controller::send)` keeps compiling.

- [ ] **Step 4: Compile chat UI**

Run: `./mvnw -q test -Dtest=ChatControllerKelsyTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java \
        src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java
git commit -m "feat: return send hint instead of boolean"
```

---

### Task 2: Offline login validation

**Files:**
- Create: `src/test/java/com/glodon/mordor/kmate/ui/login/LoginControllerTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java`

- [ ] **Step 1: Write the failing test**

```java
package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.service.SaveLastLoginService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginControllerTest {

    private final LoginController controller = new LoginController(new SaveLastLoginService());

    @Test
    void onlineStillRequiresServerFields() {
        LoginController.Input empty = new LoginController.Input("", "", "", "", "", false);
        assertInstanceOf(LoginController.Result.Invalid.class, controller.validate(empty));
        LoginController.Input noUser = new LoginController.Input(
                "127.0.0.1", "3000", "ABC", "pw", "", false);
        assertInstanceOf(LoginController.Result.Invalid.class, controller.validate(noUser));
    }

    @Test
    void offlineAcceptsUsernameOnly() {
        LoginController.Input input = new LoginController.Input("", "", "", "", "法内狂徒", true);
        assertInstanceOf(LoginController.Result.Ok.class, controller.validate(input));
    }

    @Test
    void offlineRejectsBlankUsername() {
        LoginController.Input input = new LoginController.Input("", "", "", "", "  ", true);
        var result = controller.validate(input);
        assertInstanceOf(LoginController.Result.Invalid.class, result);
        assertTrue(((LoginController.Result.Invalid) result).message().contains("用户名"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=LoginControllerTest`

Expected: FAIL — `Input` has no 6th component / offline path still requires IP.

- [ ] **Step 3: Minimal `LoginController` change**

```java
    public record Input(String ip, String port, String imCode,
                        String password, String username, boolean offline) {
        public Input(String ip, String port, String imCode, String password, String username) {
            this(ip, port, imCode, password, username, false);
        }
    }
```

At the top of `validate`:

```java
        if (input.offline()) {
            if (input.username() == null || input.username().isBlank()) {
                return new Result.Invalid("请输入用户名");
            }
            return new Result.Ok();
        }
```

Keep the existing online checks after this.

`save(Input input)` when offline must not persist empty server fields:

```java
    public void save(Input input) {
        if (input.offline()) {
            saveService.save(
                    saveService.getServerIp(),
                    saveService.getServerPort(),
                    saveService.getImCode(),
                    input.username(),
                    saveService.getPeerName());
            return;
        }
        saveService.save(input.ip(), input.port(), input.imCode(),
                input.username(), saveService.getPeerName());
    }
```

`LoginPane` still compiles with the 5-arg `Input` ctor until Task 4.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=LoginControllerTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java \
        src/test/java/com/glodon/mordor/kmate/ui/login/LoginControllerTest.java
git commit -m "feat: validate offline login with username only"
```

---

### Task 3: Offline `ChatController` send and archive identity

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/model/AppState.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java`

- [ ] **Step 1: Write failing offline send tests**

Add to `ChatController`:

```java
    public static final String OFFLINE_IM_CODE = "__offline__";
    public static final String OFFLINE_ARCHIVE_PASSWORD = "offline";
```

Extend the **test** constructor (do not break the 6-arg overload — add a 7th `boolean offline` and delegate):

```java
    ChatController(String imCode, String username, ChatHistory history,
                   PeerSender peerSender, KelsyRoomSettings settings, KelsyRuntime runtime) {
        this(imCode, username, history, peerSender, settings, runtime, false);
    }

    ChatController(String imCode, String username, ChatHistory history,
                   PeerSender peerSender, KelsyRoomSettings settings, KelsyRuntime runtime,
                   boolean offline) {
        this.state = null;
        this.imCode = imCode;
        this.username = username;
        this.history = history;
        this.peerSender = peerSender;
        this.settings = settings;
        this.runtime = runtime;
        this.offline = offline;
        refreshPeers();
    }
```

Add field `private final boolean offline;` set in the **production** ctor from `state.offline()` (Task 3 step 3). For the existing production ctor, temporarily `this.offline = false` so it compiles, then replace.

Add helper:

```java
    private boolean offline() {
        return state != null ? state.offline() : offline;
    }
```

In `ChatControllerKelsyTest` add a `boolean offline` parameter to the private `controller(...)` helper (default via overload). New tests:

```java
    @Test
    void offlinePeerIsRejectedWithoutCallingSender() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true, true);
        assertEquals(ChatController.OFFLINE_IM_CODE, c.imCode());
        ChatController.SendResult r = c.send("普通");
        assertFalse(r.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, r.hint());
        assertTrue(peer.isEmpty());
        assertTrue(c.getMessages().isEmpty());
    }

    @Test
    void offlineMentionWithoutTarsIsRejected() throws Exception {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true, true);
        ChatController.SendResult r = c.send("@tars 你好");
        assertFalse(r.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, r.hint());
        assertTrue(peer.isEmpty());
    }

    @Test
    void offlineAskDoesNotCallPeer() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true, true);
        assertTrue(c.send("@tars 你好").accepted());
        assertEquals(List.of("你好"), asked);
        assertTrue(peer.isEmpty());
        ChatController.SendResult plain = c.send("普通");
        assertFalse(plain.accepted());
        assertEquals(ChatController.OFFLINE_REJECT_HINT, plain.hint());
        assertTrue(peer.isEmpty());
    }
```

Helper signature:

```java
    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured) throws Exception {
        return controller(peer, asked, enabled, configured, false);
    }

    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured,
                                      boolean offline) throws Exception {
        // existing body, but:
        // imCode = offline ? ChatController.OFFLINE_IM_CODE : "ROOM"
        // settings.enable(thatImCode, "") when enabled
        // last args: settings, runtime, offline
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=ChatControllerKelsyTest`

Expected: FAIL — `PEER` still calls `peerSender` / `accepted==true`.

- [ ] **Step 3: Implement offline `PEER` reject and `AppState.offline`**

`AppState` add:

```java
    private final boolean offline;

    public AppState(String username, ImClient client, Image avatar) {
        this(username, client, avatar, false);
    }

    public AppState(String username, ImClient client, Image avatar, boolean offline) {
        this.username = username;
        this.client = client;
        this.avatar.set(avatar);
        this.offline = offline;
        if (offline) {
            this.online.set(false);
            this.peerDisplay.set("脱机");
        }
    }

    public static AppState offline(String username, Image avatar) {
        return new AppState(username, null, avatar, true);
    }

    public boolean offline() {
        return offline;
    }
```

Keep the 2-arg ctor delegating to 3-arg.

`ChatController` production ctor (`ChatController(AppState state, ChatHistory history)`):

```java
        this.state = state;
        this.offline = state.offline();
        this.username = state.username();
        this.history = history;
        this.settings = new KelsyRoomSettings();
        this.runtime = null;
        if (state.offline()) {
            this.imCode = OFFLINE_IM_CODE;
            this.peerSender = text -> {
                throw new IllegalStateException("offline");
            };
        } else {
            this.imCode = state.client().imCode();
            this.peerSender = state.client()::sendChat;
            state.client().addListener(event -> { /* existing */ });
            peers.putAll(state.client().roster());
            state.client().avatars().forEach((name, png) ->
                    AvatarService.fromPngBytes(png).ifPresent(img -> peerAvatars.put(name, img)));
        }
        if (this.settings.enabled(this.imCode)) {
            try {
                this.runtime = KelsyRuntime.shared(this.username);
            } catch (UnsupportedOperationException ignored) {
                this.runtime = null;
            }
        }
        refreshPeers();
        Diag.log("chat", "controller ready roster=%s header=%s offline=%s",
                peers.values(), state.peerDisplayProperty().get(), state.offline());
        history.loadInitialAsync(loaded -> Platform.runLater(() -> onHistoryLoaded(loaded)));
```

`createHistory`:

```java
    private static ChatHistory createHistory(AppState state) {
        if (state.offline()) {
            return new ChatHistory(
                    ChatHistory.defaultFile(OFFLINE_IM_CODE),
                    CryptoService.forArchive(OFFLINE_ARCHIVE_PASSWORD, OFFLINE_IM_CODE));
        }
        ImClient client = state.client();
        return new ChatHistory(
                ChatHistory.defaultFile(client.imCode()),
                CryptoService.forArchive(client.password(), client.imCode()));
    }
```

In `send`, `case PEER`:

```java
            case PEER -> {
                if (offline()) {
                    yield SendResult.reject(OFFLINE_REJECT_HINT);
                }
                yield sendPeer(content);
            }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=ChatControllerKelsyTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/model/AppState.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java \
        src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java
git commit -m "feat: reject peer sends when chat is offline"
```

---

### Task 4: Login checkbox, enter chat, null-safe client

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java`
- Modify: `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css`
- Modify: `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java`

No new automated UI test (spec: 不测真窗口).

- [ ] **Step 1: `LoginPane` checkbox and skip connect**

Add `import javafx.scene.control.CheckBox;`

Fields:

```java
    private final CheckBox offline = new CheckBox("脱机登录");
    private final Label info = new Label();
```

(Reuse the existing `info` label instead of a second one — move the current local `Label info` to a field.)

After building fields, before `connect`:

```java
        offline.getStyleClass().add("login-offline");
        offline.setSelected(false);
        offline.selectedProperty().addListener((obs, o, on) -> applyOffline(on));
```

`applyOffline(boolean on)`:

```java
    private void applyOffline(boolean on) {
        serverIp.setDisable(on);
        serverPort.setDisable(on);
        imCode.setDisable(on);
        password.setDisable(on);
        info.setText(on
                ? "脱机只和本机秘书对话，不会连接服务器"
                : "请与对方约定相同的 IM_CODE 和初始口令进行配对");
        connect.setText(on ? "进 入" : "连 接");
    }
```

Call `applyOffline(false)` once after prefill.

Insert `offline` into the card `VBox` **above** `connect` (e.g. `card = new VBox(10, cardTop, fields, offline, connect, cardBottom)`).

`handleConnect` build Input with the checkbox:

```java
        var input = new LoginController.Input(
                serverIp.getText().trim(),
                serverPort.getText().trim(),
                imCode.getText().trim(),
                password.getText(),
                username.getText().trim(),
                offline.isSelected());
```

After `validate` Ok, if `input.offline()`:

```java
        hideError();
        controller.save(input);
        onConnect.accept(AppState.offline(
                input.username(),
                AvatarService.load(avatarPath).orElse(null)));
        return;
```

Do **not** `new ImClient()` or `connect(...)`.

- [ ] **Step 2: CSS**

Append to `login.css`:

```css
.login-offline {
    -fx-font-size: 12px;
    -fx-text-fill: #5C6370;
}
```

- [ ] **Step 3: `Mate4K.enterChat` null client**

```java
    private void enterChat(AppState state) {
        closeSession();
        if (state.client() != null) {
            session = state.client();
            unreadAlert.watch(session);
        }
        stage.setTitle(state.username());
        root.getChildren().setAll(new ChatPane(state));
    }
```

- [ ] **Step 4: `RoomMemberList.pickSelfAvatar`**

```java
                    state.setAvatar(AvatarService.load(path).orElse(null));
                    if (state.client() != null) {
                        AvatarService.thumbnailBase64(path).ifPresent(state.client()::setAvatarPlaintext);
                    }
```

- [ ] **Step 5: Compile tests that still run headless**

Run: `./mvnw -q test -Dtest=LoginControllerTest,ChatControllerKelsyTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java \
        src/main/resources/com/glodon/mordor/kmate/ui/login/login.css \
        src/main/java/com/glodon/mordor/kmate/app/Mate4K.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java
git commit -m "feat: add offline login checkbox and skip IM connect"
```

---

### Task 5: Offline history round-trip

**Files:**
- Modify: `src/test/java/com/glodon/mordor/kmate/service/ChatHistoryTest.java`

- [ ] **Step 1: Write the failing test**

Add import: `com.glodon.mordor.kmate.ui.chat.ChatController`

```java
    @Test
    void offlineArchiveRoundTripSelfAndAssistant() throws Exception {
        Path file = ChatHistory.defaultFile(tmp, ChatController.OFFLINE_IM_CODE);
        ChatHistory writer = new ChatHistory(
                file,
                CryptoService.forArchive(
                        ChatController.OFFLINE_ARCHIVE_PASSWORD,
                        ChatController.OFFLINE_IM_CODE));
        assertTrue(writer.open());
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 3, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 3, 10, 0, 1);
        writer.append(new Message("s1", Sender.SELF, "@tars 你好", t1, "me"));
        writer.append(new Message("a1", Sender.ASSISTANT, "最终正文", t2, "tars"));
        writer.flush(2, TimeUnit.SECONDS);
        writer.close();

        ChatHistory reader = new ChatHistory(
                file,
                CryptoService.forArchive(
                        ChatController.OFFLINE_ARCHIVE_PASSWORD,
                        ChatController.OFFLINE_IM_CODE));
        assertTrue(reader.open());
        List<Message> loaded = reader.loadNewest(10);
        assertEquals(List.of("s1", "a1"), ids(loaded));
        assertEquals(Sender.SELF, loaded.get(0).sender());
        assertEquals(Sender.ASSISTANT, loaded.get(1).sender());
        assertEquals("最终正文", loaded.get(1).content());
        reader.close();
    }
```

This should already PASS once constants exist (Task 3). If `flush`/`close` signatures differ, match `ChatControllerKelsyTest` (`flush(2, TimeUnit.SECONDS)` + `close()`).

- [ ] **Step 2: Run the test**

Run: `./mvnw -q test -Dtest=ChatHistoryTest#offlineArchiveRoundTripSelfAndAssistant`

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/glodon/mordor/kmate/service/ChatHistoryTest.java
git commit -m "test: persist offline room self and assistant history"
```

---

### Task 6: Full unit tests

- [ ] **Step 1: Run all unit tests**

Run: `./mvnw -q test`

Expected: PASS (same suite as before plus `LoginControllerTest` and the new history / offline send cases).

- [ ] **Step 2: Manual checklist (do not automate)**

1. 不勾选脱机：缺 IM_CODE 仍报错；能连上 server 时与现在相同。
2. 勾选脱机：IP/口令灰掉；只填用户名点「进 入」立刻进房，日志无 `ws connect`。
3. 未添加 tars 发「你好」或 `@tars 你好`：提示「脱机登录，消息无法发送」，输入还在。
4. 添加 tars 后 `@tars 你好` 走秘书；普通消息仍是脱机提示。
5. 退出再脱机进入：时间线仍有上次 `@tars` 与秘书最终正文；线上房间历史不出现。
6. 取消勾选：字段恢复可填，按钮回到「连 接」。

---

## Spec coverage

| Spec | Task |
|---|---|
| 勾选「脱机登录」、不记住 | 4 |
| 禁用 IP/端口/IM_CODE/口令，改说明与按钮 | 4 |
| 只校验用户名 | 2 |
| 不创建 WebSocket | 4 |
| 保存不覆盖空服务器字段 | 2 |
| `AppState.offline`，`client==null`，顶栏「脱机」 | 3 |
| `Mate4K` 不 watch 空 client | 4 |
| `imCode=__offline__`，口令 `offline` | 3 |
| tars 不自动加，仍「添加 tars」 | 3（沿用 `refreshPeers`） |
| `SendResult` + 脱机 hint | 1, 3 |
| `PEER` 不写历史、不调 peerSender | 3 |
| ASK/FIND/BUSY/SYSTEM 与线上相同 | 1, 3 |
| 自己换头像不发 WS | 4 |
| 历史 SELF/ASSISTANT 回读 | 5 |
| 在线校验回归 | 2 |
| 未勾选行为不变 | 2, 4 |

## Type names (locked)

- `ChatController.SendResult(boolean accepted, String hint)` + `ok()` / `reject(String)`
- `ChatController.BUSY_HINT` / `OFFLINE_REJECT_HINT` / `OFFLINE_IM_CODE` / `OFFLINE_ARCHIVE_PASSWORD`
- `LoginController.Input(..., boolean offline)` + 5-arg ctor `offline=false`
- `AppState.offline()` / `AppState.offline(username, avatar)`
