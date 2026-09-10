# Persistent User ID, Secretary Nickname, and ID-Keyed Avatars

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Human `user_id`s persist across reconnects (client remembers, server issues from 200000 and stores the high-water mark); avatars key by id so leave keeps timeline photos; secretary mention uses a per-room nickname (id `100778`).

**Architecture:** Server `IdAllocator` owns `id_counter` on disk and `assign(claimed, in_use)`. Client `SavedUserIdService` stores one int per `imCode+username` and `register` sends it. `peerAvatars` / `ImClient.avatars` become `Map<Integer, …>`; `PeerLeft` drops members only. `KelsyMention` / `KelsyRoomSettings` take a nickname; secretary avatars never resolve by display name.

**Tech Stack:** Java 21, JavaFX 21, JUnit 5, C++17 (kserver). No new Maven dependencies. C++ test is a small assert binary (no Catch2).

**Spec:** `docs/superpowers/specs/2026-09-03-persistent-id-nickname-avatars-design.md`

---

## File map

**Create:**

- `src/main/java/com/glodon/mordor/kmate/service/SavedUserIdService.java` — Preferences `uid.<sha256(imCode + "\n" + username)>` (single hash: Java Preferences keys max 80 chars; spec isolation is the same)
- `src/test/java/com/glodon/mordor/kmate/service/SavedUserIdServiceTest.java`
- `server/include/kserver/id_allocator.hpp`
- `server/src/id_allocator.cpp`
- `server/tests/id_allocator_test.cpp`

**Modify:**

- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyMention.java` — nickname argument
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyMentionTest.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettings.java` — `nick.<hash>`
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettingsTest.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsySendRouter.java` — `route(..., nickname)`
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsySendRouterTest.java`
- `src/main/java/com/glodon/mordor/kmate/model/RoomMember.java` — `KELSY_ID = 100778`, `kelsy(String nick)`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java` — nickname, avatars by id, `lastSeenIds`, keep on leave
- `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java` — 「添加秘书」, nick dialog, insert `@nick `
- `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java` — mention check uses current nick
- `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java` — pass nick supplier into `InputBar`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java` — secretary avatar by id
- `src/main/java/com/glodon/mordor/kmate/service/Protocol.java` — optional `user_id` on register
- `src/test/java/com/glodon/mordor/kmate/service/ProtocolTest.java`
- `src/main/java/com/glodon/mordor/kmate/service/ImClient.java` — send/save id; avatars keyed by `userId`; no remove on leave
- `server/include/kserver/message.hpp` / `server/src/message.cpp` — `RegisterMessage.user_id`
- `server/include/kserver/room.hpp` / `server/src/room.cpp` — `join` uses caller-supplied id
- `server/include/kserver/server.hpp` / `server/src/server.cpp` — load allocator, `allocate_id` / `release_id`
- `server/include/kserver/session.hpp` / `server/src/session.cpp` — register claimed id
- `server/src/main.cpp` — pass `argv[0]` parent / `id_counter`
- `server/CMakeLists.txt` — compile allocator + test target
- `server/design.md` — protocol + id rules
- `docs/superpowers/specs/2026-09-03-persistent-id-nickname-avatars-design.md` — already Approved

Do **not** add `user_id` on chat text frames. Do **not** persist peer avatars to disk.

---

### Task 1: Nickname settings and mention parser

**Files:**
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyMentionTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyMention.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettingsTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettings.java`

- [ ] **Step 1: Rewrite `KelsyMentionTest` for a nickname argument**

Replace the class body so every call passes a nick. Keep default-`tars` cases and add custom / `kelsy` nick:

```java
package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.model.RoomMember;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyMentionTest {

    @Test
    void defaultTarsPrefix() {
        assertTrue(KelsyMention.isMention("@tars 你好", RoomMember.SECRETARY_NAME));
        assertTrue(KelsyMention.isMention("  @TARS 帮我记 ", "tars"));
        assertEquals("你好", KelsyMention.strip("@tars 你好", "tars"));
        assertEquals("帮我记", KelsyMention.strip("  @TARS 帮我记 ", "tars"));
        assertEquals("@tars ", KelsyMention.insert("tars"));
    }

    @Test
    void customNickOnly() {
        assertTrue(KelsyMention.isMention("@Ada 你好", "Ada"));
        assertTrue(KelsyMention.isMention("  @ada 帮我 ", "Ada"));
        assertEquals("你好", KelsyMention.strip("@Ada 你好", "Ada"));
        assertFalse(KelsyMention.isMention("@tars 你好", "Ada"));
        assertFalse(KelsyMention.isMention("@kelsy 你好", "Ada"));
        assertFalse(KelsyMention.isMention("请 @Ada 看看", "Ada"));
        assertFalse(KelsyMention.isMention("@Adafoo 嗨", "Ada"));
        assertEquals("@Ada ", KelsyMention.insert("Ada"));
    }

    @Test
    void kelsyNickIsMentionOnlyWhenChosen() {
        assertFalse(KelsyMention.isMention("@kelsy 你好", "tars"));
        assertTrue(KelsyMention.isMention("@kelsy 你好", "kelsy"));
        assertEquals("你好", KelsyMention.strip("@kelsy 你好", "kelsy"));
    }

    @Test
    void mentionAloneIsEmptyBody() {
        assertTrue(KelsyMention.isMention("@tars", "tars"));
        assertEquals("", KelsyMention.strip("@tars", "tars"));
        assertFalse(KelsyMention.isMention(null, "tars"));
        assertFalse(KelsyMention.isMention("hello", "tars"));
    }
}
```

- [ ] **Step 2: Run mention tests — expect FAIL (wrong arity)**

Run: `./mvnw -q test -Dtest=KelsyMentionTest`

Expected: FAIL compile — `isMention` / `strip` still one-arg; `insert` missing.

- [ ] **Step 3: Implement nickname-aware `KelsyMention`**

Replace `KelsyMention.java`:

```java
package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.model.RoomMember;

public final class KelsyMention {

    private KelsyMention() {
    }

    public static String insert(String nickname) {
        return "@" + normalize(nickname) + " ";
    }

    public static boolean isMention(String text, String nickname) {
        String at = "@" + normalize(nickname);
        String body = text == null ? "" : text.strip();
        int n = at.length();
        if (body.length() < n || !body.regionMatches(true, 0, at, 0, n)) {
            return false;
        }
        return body.length() == n || Character.isWhitespace(body.charAt(n));
    }

    public static String strip(String text, String nickname) {
        if (!isMention(text, nickname)) {
            return text == null ? "" : text.strip();
        }
        String body = text.strip();
        int n = ("@" + normalize(nickname)).length();
        return body.length() == n ? "" : body.substring(n).strip();
    }

    private static String normalize(String nickname) {
        String n = nickname == null ? "" : nickname.strip();
        return n.isEmpty() ? RoomMember.SECRETARY_NAME : n;
    }
}
```

Delete `NAME`, `AT`, `INSERT`. Call sites still using them will break until later tasks — that is OK if this step only runs `KelsyMentionTest`. If `./mvnw test -Dtest=KelsyMentionTest` still compiles the rest of main sources, **temporarily keep**:

```java
    public static final String NAME = RoomMember.SECRETARY_NAME;
    public static final String INSERT = insert(NAME);

    public static boolean isMention(String text) {
        return isMention(text, NAME);
    }

    public static String strip(String text) {
        return strip(text, NAME);
    }
```

so the tree still compiles. Remove these shims in Task 2/3 once every caller passes a nick.

- [ ] **Step 4: Extend `KelsyRoomSettingsTest`**

Replace `roomsAreIsolatedAndRemovable` and add nick cases. `enable` becomes 3-arg:

```java
    @Test
    void roomsAreIsolatedAndRemovable() {
        MemoryPrefs prefs = new MemoryPrefs();
        KelsyRoomSettings settings = new KelsyRoomSettings(prefs);
        assertFalse(settings.enabled("ROOM-A"));
        settings.enable("ROOM-A", "/tmp/a.png", "Ada");
        settings.enable("ROOM-B", "/tmp/b.png", "tars");
        assertTrue(settings.enabled("ROOM-A"));
        assertEquals("/tmp/a.png", settings.avatarPath("ROOM-A"));
        assertEquals("Ada", settings.nickname("ROOM-A"));
        assertEquals("tars", settings.nickname("ROOM-B"));
        settings.disable("ROOM-A");
        assertFalse(settings.enabled("ROOM-A"));
        assertTrue(settings.enabled("ROOM-B"));
        assertEquals("", settings.avatarPath("ROOM-A"));
        assertEquals("tars", settings.nickname("ROOM-A"));
    }

    @Test
    void blankNicknameFallsBackToTars() {
        MemoryPrefs prefs = new MemoryPrefs();
        KelsyRoomSettings settings = new KelsyRoomSettings(prefs);
        settings.enable("ROOM", "/tmp/x.png", "   ");
        assertEquals("tars", settings.nickname("ROOM"));
        assertEquals("tars", settings.nickname("MISSING"));
    }
```

- [ ] **Step 5: Implement settings nick key**

In `KelsyRoomSettings.java`:

```java
    public String nickname(String imCode) {
        String n = prefs.get(nickerKey(imCode), "");
        return n == null || n.isBlank() ? "tars" : n;
    }

    public void enable(String imCode, String avatarPath, String nickname) {
        prefs.putBoolean(onKey(imCode), true);
        prefs.put(avatarKey(imCode), avatarPath == null ? "" : avatarPath);
        String n = nickname == null ? "" : nickname.strip();
        prefs.put(nickerKey(imCode), n.isEmpty() ? "tars" : n);
    }

    public void disable(String imCode) {
        prefs.putBoolean(onKey(imCode), false);
        prefs.remove(avatarKey(imCode));
        prefs.remove(nickerKey(imCode));
    }

    private static String nickerKey(String imCode) {
        return "nick." + ChatHistory.sha256Hex(imCode);
    }
```

Delete the old 2-arg `enable`. Fix the two production/test call sites so the project compiles:

- `ChatController.enableKelsy`: `settings.enable(imCode, avatarPath, RoomMember.SECRETARY_NAME);`
- `ChatControllerKelsyTest`: `settings.enable(thatImCode, "", RoomMember.SECRETARY_NAME);`

- [ ] **Step 6: Run settings + mention tests**

Run: `./mvnw -q test -Dtest=KelsyMentionTest,KelsyRoomSettingsTest`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/KelsyMention.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsyMentionTest.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettings.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettingsTest.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java \
        src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java
git commit -m "$(cat <<'EOF'
feat: store per-room secretary nickname and parse @nick mentions

EOF
)"
```

---

### Task 2: Route and controller use the room nickname

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/KelsySendRouter.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/kelsy/KelsySendRouterTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/model/RoomMember.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java`

- [ ] **Step 1: Fail router tests for 5-arg `route` + custom nick**

Change signature to:

```java
    public static Result route(boolean enabled, boolean busy, boolean configured,
                               String text, String nickname)
```

Update every existing `KelsySendRouterTest` call: add `"tars"` as the last argument.

Add:

```java
    @Test
    void customNickAskAndDefaultTarsIsPeer() {
        var ask = KelsySendRouter.route(true, false, true, "@Ada 你好", "Ada");
        assertEquals(KelsySendRouter.Kind.ASK, ask.kind());
        assertEquals("你好", ask.outgoing());
        var peer = KelsySendRouter.route(true, false, true, "@tars 你好", "Ada");
        assertEquals(KelsySendRouter.Kind.PEER, peer.kind());
    }
```

Inside `route`, replace `KelsyMention.isMention(raw)` / `strip(raw)` with the two-arg forms using `nickname`.

- [ ] **Step 2: Run router tests**

Run: `./mvnw -q test -Dtest=KelsySendRouterTest`

Expected: FAIL compile until `ChatController.send` is updated (main sources compile with tests). Fix `ChatController.send` in the same step:

```java
        var route = KelsySendRouter.route(
                enabled, kelsyBusy.get(), configured, content, settings.nickname(imCode));
```

Then re-run. Expected: PASS.

- [ ] **Step 3: `RoomMember` id + factory**

```java
public record RoomMember(int userId, String username, boolean self) {
    public static final int SELF_ID = -1;
    public static final int KELSY_ID = 100778;
    /** 未设置昵称时的默认显示名，不是提及硬编码。 */
    public static final String SECRETARY_NAME = "tars";

    public static RoomMember kelsy() {
        return kelsy(SECRETARY_NAME);
    }

    public static RoomMember kelsy(String nickname) {
        String n = nickname == null || nickname.isBlank() ? SECRETARY_NAME : nickname.strip();
        return new RoomMember(KELSY_ID, n, false);
    }

    public boolean isKelsy() {
        return userId == KELSY_ID;
    }
}
```

- [ ] **Step 4: Controller nickname API and persist `from`**

In `ChatController`:

```java
    public String secretaryNickname() {
        return settings.nickname(imCode);
    }

    public void enableKelsy(String avatarPath) {
        enableKelsy(avatarPath, RoomMember.SECRETARY_NAME);
    }

    public void enableKelsy(String avatarPath, String nickname) {
        settings.enable(imCode, avatarPath, nickname);
        if (runtime == null) {
            runtime = KelsyRuntime.shared(username);
        }
        kelsyEnabled.set(true);
        refreshPeers();
    }
```

`refreshPeers` secretary row:

```java
            next.add(1, RoomMember.kelsy(settings.nickname(imCode)));
```

`refreshPeers` self row:

```java
        next.add(new RoomMember(RoomMember.SELF_ID, username, true));
```

`persistAssistant`:

```java
                settings.nickname(imCode));
```

Add a test in `ChatControllerKelsyTest`:

```java
    @Test
    void customNickMentionAsksAndTarsGoesPeer() throws Exception {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, false, true);
        c.enableKelsy("", "Ada");
        assertEquals("Ada", c.secretaryNickname());
        assertTrue(c.getMembers().stream().anyMatch(m -> m.isKelsy() && "Ada".equals(m.username())));
        assertTrue(c.send("@Ada 你好").accepted());
        assertEquals(List.of("你好"), asked);
        assertTrue(peer.isEmpty());
        asked.clear();
        assertTrue(c.send("@tars 你好").accepted());
        assertEquals(List.of("@tars 你好"), peer);
    }
```

- [ ] **Step 5: Run controller + router tests**

Run: `./mvnw -q test -Dtest=KelsySendRouterTest,ChatControllerKelsyTest`

Expected: PASS (`enableAddsKelsyWithoutChangingHumanCount` still uses `enableKelsy("")` → nick `tars`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/KelsySendRouter.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsySendRouterTest.java \
        src/main/java/com/glodon/mordor/kmate/model/RoomMember.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java \
        src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java
git commit -m "$(cat <<'EOF'
feat: route @mention and member label by secretary nickname

EOF
)"
```

---

### Task 3: Add-secretary UI (nick dialog + insert)

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java`

No JavaFX test. Keep changes mechanical.

- [ ] **Step 1: `InputBar` takes current nick**

Add `java.util.function.Supplier`.

```java
    private final Supplier<String> secretaryNickname;

    public InputBar(Function<String, ChatController.SendResult> onSend,
                    Supplier<String> secretaryNickname) {
        super(6);
        this.secretaryNickname = secretaryNickname == null
                ? () -> RoomMember.SECRETARY_NAME : secretaryNickname;
        // ... existing body unchanged ...
    }
```

Need import `RoomMember`.

`insertMention`:

```java
        if (KelsyMention.isMention(cur, secretaryNickname.get())) {
```

Comment: `在输入框开头插入当前秘书昵称；若已是提及则仅聚焦。`

- [ ] **Step 2: `ChatPane` supplies the nick**

```java
        InputBar input = new InputBar(controller::send, controller::secretaryNickname);
```

- [ ] **Step 3: `RoomMemberList` add + click**

- Button text: `new Button("添加秘书")`
- Click secretary row: `onMention.accept(KelsyMention.insert(controller.secretaryNickname()));`
- `pickKelsyAvatar`:

```java
    private void pickKelsyAvatar() {
        AvatarService.chooseAndStoreKelsy(
                        getScene() == null ? null : getScene().getWindow(),
                        controller.imCode())
                .ifPresent(path -> {
                    TextInputDialog dialog = new TextInputDialog(RoomMember.SECRETARY_NAME);
                    dialog.setTitle("秘书昵称");
                    dialog.setHeaderText(null);
                    dialog.setContentText("秘书昵称");
                    dialog.showAndWait().ifPresentOrElse(
                            nick -> controller.enableKelsy(path, nick),
                            () -> { /* 取消则不 enable，settings 不落盘 */ });
                });
    }
```

Imports: `TextInputDialog`, `RoomMember` (already).

Cancel = do not call `enableKelsy`. Copied avatar file may remain; do not delete (YAGNI).

- [ ] **Step 4: `MessageListView` secretary label**

```java
        return new AssistantBubble(
                msg,
                controller.secretaryNickname(),
                bubbleMaxWidth(),
                controller.thinkingVisibleProperty(),
                controller.avatarOfSecretary(),
                controller::openKnowledge);
```

`avatarOfSecretary` does not exist yet — add a temporary method on `ChatController` that is the current secretary branch of `avatarOf`:

```java
    public Image avatarOfSecretary() {
        return AvatarService.load(settings.avatarPath(imCode)).orElse(null);
    }
```

Do **not** look up `peerAvatars` by name here. (Task 4 will keep this and stop `avatarOf(String)` from special-casing `tars`.)

Until Task 4, leave `avatarOf(String)` as-is so existing tests still pass.

- [ ] **Step 5: Compile tests that still run headless**

Run: `./mvnw -q test -Dtest=ChatControllerKelsyTest,KelsyMentionTest,KelsySendRouterTest`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java
git commit -m "$(cat <<'EOF'
feat: prompt for secretary nickname and insert @nick

EOF
)"
```

---

### Task 4: Avatars keyed by userId; leave keeps cache

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/service/ImClient.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java`

- [ ] **Step 1: Write failing controller tests for leave + name collision**

Add to `ChatControllerKelsyTest` (same package → can call package-visible `onEvent`):

```java
    @Test
    void peerLeftRemovesMemberKeepsRememberedId() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), false, true);
        c.onEvent(new ImClient.Event.PeerJoined(200001, "Bob"));
        assertTrue(c.getMembers().stream().anyMatch(m -> m.userId() == 200001));
        assertEquals(Integer.valueOf(200001), c.rememberedUserId("Bob"));
        c.onEvent(new ImClient.Event.PeerLeft(200001, "Bob"));
        assertTrue(c.getMembers().stream().noneMatch(m -> m.userId() == 200001));
        assertEquals(Integer.valueOf(200001), c.rememberedUserId("Bob"));
        assertTrue(c.peerAvatars().containsKey(200001) || c.peerAvatars().get(200001) == null);
    }

    @Test
    void secretaryAvatarIgnoresPeerNamedTars() throws Exception {
        ChatController c = controller(new ArrayList<>(), new ArrayList<>(), true, true);
        c.onEvent(new ImClient.Event.PeerJoined(200002, "tars"));
        assertNull(c.avatarOf("tars"));
        assertNull(c.avatarOfSecretary());
        assertEquals(Integer.valueOf(200002), c.rememberedUserId("tars"));
    }
```

`containsKey` after leave: implementation **must** put the id into `peerAvatars` on join (value may be null) **or** the test should only assert `rememberedUserId`. Prefer: on `PeerJoined`, `peerAvatars.putIfAbsent(userId, null)` is ugly. Instead change the last assert to only `rememberedUserId`, and add a third test that puts a key:

```java
        c.peerAvatars().put(200001, null);
        c.onEvent(new ImClient.Event.PeerLeft(200001, "Bob"));
        assertTrue(c.peerAvatars().containsKey(200001));
```

Use that in `peerLeftRemovesMemberKeepsRememberedId` (put after join, before leave).

- [ ] **Step 2: Run — expect FAIL**

Run: `./mvnw -q test -Dtest=ChatControllerKelsyTest#peerLeftRemovesMemberKeepsRememberedId`

Expected: FAIL compile (`onEvent` private, `rememberedUserId` missing, `peerAvatars` still `String` keys).

- [ ] **Step 3: Retype maps and fix events**

`ChatController` fields:

```java
    private final ObservableMap<Integer, Image> peerAvatars = FXCollections.observableHashMap();
    private final Map<Integer, String> peers = new LinkedHashMap<>();
    private final Map<String, Integer> lastSeenIds = new LinkedHashMap<>();
```

Ctor hydration (production):

```java
            state.client().avatars().forEach((id, png) ->
                    AvatarService.fromPngBytes(png).ifPresent(img -> peerAvatars.put(id, img)));
            state.client().roster().forEach((id, name) -> lastSeenIds.put(name, id));
```

`peerAvatars()` return type: `ObservableMap<Integer, Image>`.

```java
    public Integer rememberedUserId(String username) {
        return username == null ? null : lastSeenIds.get(username);
    }

    public Image avatarOf(String username) {
        if (state != null && username != null && username.equals(state.username())) {
            return state.avatar();
        }
        if (username != null && username.equals(this.username)) {
            return state != null ? state.avatar() : null;
        }
        Integer id = lastSeenIds.get(username);
        return id == null ? null : peerAvatars.get(id);
    }

    public Image avatarOfSecretary() {
        return AvatarService.load(settings.avatarPath(imCode)).orElse(null);
    }
```

`onEvent` → package-visible (`void onEvent(...)`).

`PeerJoined`: `lastSeenIds.put(username, userId);` then existing `peers.put` / refresh.

`PeerLeft`: `peers.remove(userId);` **do not** `peerAvatars.remove` / `lastSeenIds.remove`.

`PeerAvatar`:

```java
            case ImClient.Event.PeerAvatar(int userId, String username, byte[] png) -> {
                lastSeenIds.put(username, userId);
                Optional<Image> img = AvatarService.fromPngBytes(png);
                if (img.isPresent()) {
                    peerAvatars.put(userId, img.get());
                } else {
                    Diag.warn("chat", "peer avatar decode failed user=%s id=%d bytes=%d",
                            username, userId, png == null ? 0 : png.length);
                }
            }
```

`ImClient`:

```java
    private final ConcurrentHashMap<Integer, byte[]> avatars = new ConcurrentHashMap<>();

    public Map<Integer, byte[]> avatars() {
        return Map.copyOf(avatars);
    }
```

On avatar receive: `avatars.put(msg.userId(), png);` (keep username in the log).

On `peer_disconnected`: `roster.remove(msg.userId());` **do not** `avatars.remove`.

`MessageListView` / `RoomMemberList` listeners:

```java
        controller.peerAvatars().addListener((MapChangeListener<Integer, Image>) c -> rebuild());
```

`RoomMemberList` photo:

```java
        Image photo = member.isKelsy()
                ? controller.avatarOfSecretary()
                : controller.avatarOf(name);
```

Remove leftover `avatarOf(SECRETARY_NAME)` special case (already unused if Step 3 Task 3 landed).

- [ ] **Step 4: Run controller tests**

Run: `./mvnw -q test -Dtest=ChatControllerKelsyTest`

Expected: PASS. Also run `./mvnw -q test` if time — fix any `ObservableMap<String, Image>` leftovers (`grep peerAvatars`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java \
        src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java \
        src/main/java/com/glodon/mordor/kmate/service/ImClient.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java
git commit -m "$(cat <<'EOF'
fix: key avatars by user id and keep them after peer leave

EOF
)"
```

---

### Task 5: Client remembers `user_id` and sends it on register

**Files:**
- Create: `src/test/java/com/glodon/mordor/kmate/service/SavedUserIdServiceTest.java`
- Create: `src/main/java/com/glodon/mordor/kmate/service/SavedUserIdService.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/service/ProtocolTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/service/Protocol.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/service/ImClient.java`

- [ ] **Step 1: Failing `SavedUserIdServiceTest` + `ProtocolTest`**

`SavedUserIdServiceTest.java` (reuse `KelsyRoomSettingsTest.MemoryPrefs`):

```java
package com.glodon.mordor.kmate.service;

import com.glodon.mordor.kmate.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SavedUserIdServiceTest {

    @Test
    void isolatesByImCodeAndUsernameAndOverwrites() {
        MemoryPrefs prefs = new MemoryPrefs();
        SavedUserIdService ids = new SavedUserIdService(prefs);
        assertEquals(0, ids.get("ROOM", "Ada"));
        ids.put("ROOM", "Ada", 200003);
        ids.put("ROOM", "Bob", 200004);
        ids.put("OTHER", "Ada", 200010);
        assertEquals(200003, ids.get("ROOM", "Ada"));
        assertEquals(200004, ids.get("ROOM", "Bob"));
        assertEquals(200010, ids.get("OTHER", "Ada"));
        ids.put("ROOM", "Ada", 200099);
        assertEquals(200099, ids.get("ROOM", "Ada"));
    }
}
```

`ProtocolTest` add:

```java
    @Test
    void registerOmitsUserIdWhenUnset() {
        String json = Protocol.register("OFFICE2024", "Alice");
        assertTrue(json.contains("\"im_code\":\"OFFICE2024\""));
        assertTrue(json.contains("\"username\":\"Alice\""));
        assertTrue(!json.contains("user_id"));
    }

    @Test
    void registerIncludesUserIdWhenPositive() {
        String json = Protocol.register("OFFICE2024", "Alice", 200003);
        assertTrue(json.contains("\"user_id\":200003"));
        Protocol.Incoming incoming = Protocol.parse(json);
        assertEquals("register", incoming.type());
        assertEquals(200003, incoming.userId());
    }
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./mvnw -q test -Dtest=SavedUserIdServiceTest,ProtocolTest`

Expected: FAIL compile (`SavedUserIdService` missing; `register` 3-arg missing).

- [ ] **Step 3: Implement service + protocol**

`SavedUserIdService.java`:

```java
package com.glodon.mordor.kmate.service;

import java.util.prefs.Preferences;

/** 本机记住的 server user_id。不含口令。键是 imCode+用户名的哈希（Preferences 键最长 80）。 */
public final class SavedUserIdService {

    private final Preferences prefs;

    public SavedUserIdService() {
        this(Preferences.userNodeForPackage(SavedUserIdService.class));
    }

    public SavedUserIdService(Preferences prefs) {
        this.prefs = prefs;
    }

    public int get(String imCode, String username) {
        return prefs.getInt(key(imCode, username), 0);
    }

    public void put(String imCode, String username, int userId) {
        prefs.putInt(key(imCode, username), userId);
    }

    private static String key(String imCode, String username) {
        return "uid." + ChatHistory.sha256Hex(
                (imCode == null ? "" : imCode) + "\n" + (username == null ? "" : username));
    }
}
```

`Protocol.register`:

```java
    public static String register(String imCode, String username) {
        return register(imCode, username, 0);
    }

    public static String register(String imCode, String username, int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"register\",\"data\":{\"im_code\":")
                .append(quote(imCode))
                .append(",\"username\":")
                .append(quote(username));
        if (userId > 0) {
            sb.append(",\"user_id\":").append(userId);
        }
        sb.append("}}");
        return sb.toString();
    }
```

- [ ] **Step 4: `ImClient` uses the store**

Field + ctors:

```java
    private final SavedUserIdService savedUserIds;

    public ImClient() {
        this(new SavedUserIdService());
    }

    ImClient(SavedUserIdService savedUserIds) {
        this.savedUserIds = savedUserIds;
    }
```

In `connect`, replace `Protocol.register(imCode, username)` with:

```java
                    int claimed = savedUserIds.get(imCode, username);
                    enqueueSend(Protocol.register(imCode, username, claimed), false);
```

In `registered` handler, after `registered = true`:

```java
                savedUserIds.put(this.imCode, this.username, msg.userId());
```

Do **not** create `ImClient` on offline login (already true).

- [ ] **Step 5: Run tests**

Run: `./mvnw -q test -Dtest=SavedUserIdServiceTest,ProtocolTest,ChatControllerKelsyTest`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/service/SavedUserIdService.java \
        src/test/java/com/glodon/mordor/kmate/service/SavedUserIdServiceTest.java \
        src/main/java/com/glodon/mordor/kmate/service/Protocol.java \
        src/test/java/com/glodon/mordor/kmate/service/ProtocolTest.java \
        src/main/java/com/glodon/mordor/kmate/service/ImClient.java
git commit -m "$(cat <<'EOF'
feat: persist assigned user id and send it on register

EOF
)"
```

---

### Task 6: Server `IdAllocator` (C++ unit binary)

**Files:**
- Create: `server/include/kserver/id_allocator.hpp`
- Create: `server/src/id_allocator.cpp`
- Create: `server/tests/id_allocator_test.cpp`
- Modify: `server/CMakeLists.txt`

- [ ] **Step 1: Write the allocator header + test source first**

`server/include/kserver/id_allocator.hpp`:

```cpp
#pragma once

#include <filesystem>
#include <functional>
#include <string>

namespace kserver {

class IdAllocator {
public:
    static constexpr int kFirstHuman = 200000;
    static constexpr int kSecretaryReserved = 100778;

    explicit IdAllocator(std::filesystem::path file);

    int assign(int claimed, const std::function<bool(int)>& in_use);
    int next() const { return next_; }

private:
    void load();
    void persist() const;

    std::filesystem::path file_;
    int next_ = kFirstHuman;
};

} // namespace kserver
```

`server/tests/id_allocator_test.cpp` — assert and `return 1` on failure:

```cpp
#include <kserver/id_allocator.hpp>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

namespace fs = std::filesystem;

static int fails = 0;

static void expect(bool cond, const char* msg) {
    if (!cond) {
        std::cerr << "FAIL: " << msg << std::endl;
        fails++;
    }
}

int main() {
    fs::path dir = fs::temp_directory_path() / ("kserver-id-" + std::to_string(std::time(nullptr)));
    fs::create_directories(dir);
    fs::path file = dir / "id_counter";

    {
        kserver::IdAllocator a(file);
        expect(a.next() == 200000, "missing file starts at 200000");
        int id = a.assign(0, [](int) { return false; });
        expect(id == 200000, "first assign is 200000");
        expect(a.next() == 200001, "next after first");
    }
    {
        std::ifstream in(file);
        int stored = 0;
        in >> stored;
        expect(stored == 200001, "file stores next");
    }
    {
        kserver::IdAllocator a(file);
        expect(a.next() == 200001, "reload next");
        int id = a.assign(200005, [](int) { return false; });
        expect(id == 200005, "claim unused 200005");
        expect(a.next() == 200006, "watermark raised");
    }
    {
        kserver::IdAllocator a(file);
        int bad1 = a.assign(100778, [](int) { return false; });
        expect(bad1 == 200006, "reject secretary id");
        int bad2 = a.assign(7, [](int) { return false; });
        expect(bad2 == 200007, "reject old small id");
        int bad3 = a.assign(-1, [](int) { return false; });
        expect(bad3 == 200008, "reject negative");
        int taken = a.assign(200005, [](int id) { return id == 200005; });
        expect(taken == 200008 || taken == 200009, "in-use claim gets a new id");
        expect(taken != 200005, "must not reuse live id");
    }
    {
        fs::path oldf = dir / "old";
        std::ofstream(oldf) << "42\n";
        kserver::IdAllocator a(oldf);
        expect(a.next() == 200000, "legacy counter 42 becomes 200000");
        expect(a.assign(0, [](int) { return false; }) == 200000, "first after legacy");
    }

    fs::remove_all(dir);
    if (fails) {
        std::cerr << fails << " assertion(s) failed\n";
        return 1;
    }
    std::cout << "id_allocator_test OK\n";
    return 0;
}
```

Add `#include <ctime>` for `std::time`.

- [ ] **Step 2: Implement `id_allocator.cpp`**

```cpp
#include <kserver/id_allocator.hpp>
#include <fstream>
#include <iostream>
#include <sstream>

namespace kserver {

IdAllocator::IdAllocator(std::filesystem::path file)
    : file_(std::move(file))
{
    load();
}

void IdAllocator::load() {
    next_ = kFirstHuman;
    std::ifstream in(file_);
    if (!in) {
        return;
    }
    int value = 0;
    if (!(in >> value)) {
        return;
    }
    if (value > kFirstHuman) {
        next_ = value;
    }
}

void IdAllocator::persist() const {
    try {
        if (!file_.parent_path().empty()) {
            std::filesystem::create_directories(file_.parent_path());
        }
        auto tmp = file_;
        tmp += ".tmp";
        {
            std::ofstream out(tmp, std::ios::trunc);
            if (!out) {
                std::cerr << "id_counter write open failed: " << tmp << std::endl;
                return;
            }
            out << next_ << '\n';
        }
        std::filesystem::rename(tmp, file_);
    } catch (const std::exception& e) {
        std::cerr << "id_counter persist failed: " << e.what() << std::endl;
    }
}

int IdAllocator::assign(int claimed, const std::function<bool(int)>& in_use) {
    const bool ok = claimed >= kFirstHuman
            && claimed != kSecretaryReserved
            && !(in_use && in_use(claimed));
    if (ok) {
        if (claimed >= next_) {
            next_ = claimed + 1;
            persist();
        }
        return claimed;
    }
    int id = next_++;
    persist();
    return id;
}

} // namespace kserver
```

`load`: spec says read value `< 200000` → use `200000`. `value > kFirstHuman` keeps a higher watermark. If file is exactly `200000`, next stays `200000`. Good.

- [ ] **Step 3: CMake**

Add `src/id_allocator.cpp` to `kserver` sources.

After the main target:

```cmake
add_executable(id_allocator_test
    src/id_allocator.cpp
    tests/id_allocator_test.cpp
)
target_include_directories(id_allocator_test PRIVATE include)
```

- [ ] **Step 4: Build and run the test**

```bash
cmake -S server -B server/build
cmake --build server/build --target id_allocator_test
./server/build/id_allocator_test
```

Expected: `id_allocator_test OK` and exit 0.

If `in-use claim gets a new id` is brittle (`200008` vs `200009` depending on how many rejects incremented), keep the `!= 200005` check and print `taken` on failure.

- [ ] **Step 5: Commit**

```bash
git add server/include/kserver/id_allocator.hpp \
        server/src/id_allocator.cpp \
        server/tests/id_allocator_test.cpp \
        server/CMakeLists.txt
git commit -m "$(cat <<'EOF'
feat: persist kserver user-id watermark from 200000

EOF
)"
```

---

### Task 7: Wire allocator into register / join

**Files:**
- Modify: `server/include/kserver/message.hpp`
- Modify: `server/src/message.cpp`
- Modify: `server/include/kserver/room.hpp`
- Modify: `server/src/room.cpp`
- Modify: `server/include/kserver/server.hpp`
- Modify: `server/src/server.cpp`
- Modify: `server/include/kserver/session.hpp`
- Modify: `server/src/session.cpp`
- Modify: `server/src/main.cpp`
- Modify: `server/design.md`

- [ ] **Step 1: Parse optional `user_id` on register**

`RegisterMessage` add `int user_id = 0;`

In `message.cpp` register branch after username:

```cpp
            msg.user_id = data.value("user_id", 0);
```

`std::visit` in `session.cpp`:

```cpp
            handle_register(msg.im_code, msg.username, msg.user_id);
```

- [ ] **Step 2: `Room::join` no longer increments**

Constructor: `explicit Room(const std::string& im_code);` — drop `id_counter` member and the `atomic` include if unused.

```cpp
bool Room::join(std::shared_ptr<Session> session, int user_id, std::string& padding) {
    // same as now, but use the incoming user_id; do not increment
    user_id = user_id; // the out-param in the old signature was `int&`
```

Change signature to **input** id (not out-param):

```cpp
    bool join(std::shared_ptr<Session> session, int user_id, std::string& padding);
```

Inside the lock: do **not** assign `user_id = id_counter_++`. Use the argument for `peer_connected` notifications.

`get_or_create_room`: `std::make_shared<Room>(im_code);`

- [ ] **Step 3: `Server` owns allocator + live ids**

Ctor: `Server(unsigned short port, std::filesystem::path id_file);`

Members:

```cpp
    IdAllocator id_allocator_;
    std::unordered_set<int> live_ids_;
```

Init: `id_allocator_(std::move(id_file))`, remove `id_counter_(1)`.

Public:

```cpp
    int allocate_id(int claimed);
    void release_id(int id);
```

```cpp
int Server::allocate_id(int claimed) {
    std::lock_guard<std::mutex> lock(rooms_mutex_);
    int id = id_allocator_.assign(claimed, [this](int x) {
        return live_ids_.count(x) > 0;
    });
    live_ids_.insert(id);
    return id;
}

void Server::release_id(int id) {
    std::lock_guard<std::mutex> lock(rooms_mutex_);
    live_ids_.erase(id);
}
```

Include `id_allocator.hpp` and `<unordered_set>` / `<filesystem>`.

`main.cpp`:

```cpp
        std::filesystem::path exe = argc > 0 ? argv[0] : "kserver";
        std::filesystem::path dir = std::filesystem::absolute(exe).parent_path();
        if (dir.empty()) {
            dir = ".";
        }
        auto server = std::make_shared<kserver::Server>(opt.port, dir / "id_counter");
```

- [ ] **Step 4: `Session::handle_register`**

```cpp
void Session::handle_register(const std::string& im_code,
                              const std::string& username,
                              int claimed_user_id) {
    // existing validation ...
    auto server = server_.lock();
    // ...
    room->evict_username(username);

    user_id_ = server->allocate_id(claimed_user_id);
    std::string padding;
    if (!room->join(shared_from_this(), user_id_, padding)) {
        server->release_id(user_id_);
        user_id_ = 0;
        room_.reset();
        send(MessageParser::error("Room is full (max 10 users)"));
        // existing close flags
        return;
    }
    registered_ = true;
    send(MessageParser::registered(user_id_, padding));
    room->replay_avatars(shared_from_this());
    // existing log
}
```

In `do_close`, after `room->leave(...)`:

```cpp
        if (auto server = server_.lock()) {
            server->release_id(user_id_);
        }
        user_id_ = 0;
```

`evict` → `leave` + `close` → `do_close` releases once. Do **not** also release inside `Room::leave`.

- [ ] **Step 5: Rebuild kserver**

```bash
cmake --build server/build --target kserver id_allocator_test
./server/build/id_allocator_test
```

Expected: both succeed. (No full WS integration test in this task.)

- [ ] **Step 6: Update `server/design.md`**

Register table: optional `user_id` (integer, omit or `0` = first login).

`registered.user_id`: 真人从 200000 起；`100778` 保留给客户端秘书；声明合法则沿用，否则新号。水位文件：可执行文件同目录 `id_counter`。

Replace「从 1 开始自增」.

JSON example `user_id` in the handshake sequence: use `200000` / `200001` instead of `1` / `2`.

- [ ] **Step 7: Commit**

```bash
git add server/include/kserver/message.hpp server/src/message.cpp \
        server/include/kserver/room.hpp server/src/room.cpp \
        server/include/kserver/server.hpp server/src/server.cpp \
        server/include/kserver/session.hpp server/src/session.cpp \
        server/src/main.cpp server/design.md
git commit -m "$(cat <<'EOF'
feat: honor claimed user_id on register and persist issuance

EOF
)"
```

---

### Task 8: Full verification + shim cleanup

**Files:** any leftover `KelsyMention.INSERT` / one-arg `isMention` / `enable(` 2-arg

- [ ] **Step 1: Grep leftovers**

```bash
rg -n "KelsyMention\.(INSERT|NAME|isMention\(|strip\()" src --glob '*.java'
rg -n "enable\([^,]+,[^,\)]+\)" src --glob '*.java'
rg -n "ObservableMap<String, Image>" src --glob '*.java'
rg -n "KELSY_ID = -2|id_counter_\(1\)" src server --glob '*.{java,cpp,hpp,md}'
```

Remove Task 1 shims if they are still in `KelsyMention`. Every remaining caller must pass a nickname.

- [ ] **Step 2: Full Java tests**

Run: `./mvnw -q test`

Expected: PASS

- [ ] **Step 3: C++ test again**

Run: `./server/build/id_allocator_test`

Expected: OK

- [ ] **Step 4: Commit leftover fixes only if the tree changed**

If grep/fix produced diffs:

```bash
git add -u
git commit -m "$(cat <<'EOF'
chore: drop mention shims and finish id/nickname wiring

EOF
)"
```

If clean, skip.

---

## Spec coverage

| Spec section | Task |
|---|---|
| ID space `-1` / `100778` / `≥200000` | 2, 6, 7 |
| Optional `register.user_id`; save `registered` | 5, 7 |
| `id_counter` file, legacy `<200000`, claim rules | 6, 7 |
| Client Preferences per imCode+username | 5 (hashed together) |
| Avatars by id; secretary never by name | 4 |
| Leave: drop member, keep cache | 4 |
| Nickname settings / mention / UI | 1, 2, 3 |
| Offline: no uid write; same settings | 5 (no ImClient); 1 settings already keyed by `__offline__` |
| `server/design.md` | 7 |
| Tests listed in spec | 1–6, 8 |

## Placeholder / consistency review

- No TBD. `IdAllocator.assign` + `Server.live_ids_` is the only “in use” mechanism (no username map).
- `enable` is 3-arg everywhere after Task 1.
- `KelsySendRouter.route` is 5-arg after Task 2.
- `peerAvatars` keys are `Integer` after Task 4.
- `Room::join` takes an input id after Task 7; `Room` ctor loses `id_counter`.
- Preferences key is `uid.<sha256(imCode+"\n"+username)>` — same isolation as the spec, legal key length.

## Manual check (not automated)

1. Two clients, same room: first login ids `200000`, `200001`; quit and rejoin — same ids in member list / `registered` logs.
2. Restart kserver — `id_counter` next to the binary still `≥ 200002`; new third user gets that number, not `200000`.
3. Leave: member row gone; old bubbles keep the photo.
4. Add secretary, nick `Ada`, `@Ada` asks, `@tars` is a normal message; peer named `tars` keeps their own avatar.
