# Kelsy in Group Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opt-in per-room Kelsy secretary in kmate: `@kelsy` stays local, peer chat never waits on the agent.

**Architecture:** New `com.glodon.mordor.kmate.kelsy` package holds mention parsing, room enablement, config/workspace, and the ported Agent/knowledge/UI. `ChatController.send` returns whether the input should clear; only `@kelsy` mentions (when the room enabled kelsy) skip `ImClient.sendChat`. Live streaming uses a ported `AssistantMessage` beside the existing `Message` list; completed replies persist as `Sender.ASSISTANT` in the room `ChatHistory`.

**Tech Stack:** Java 21, JavaFX 21, JUnit 5, AgentScope 2.0.1, Jackson 2.21.1, commonmark 0.24.0. Sibling source of truth for ports: `/Users/ksw/workspace/repository/kelsy`.

**Spec:** `docs/superpowers/specs/2026-09-02-kelsy-in-group-chat-design.md`

---

## File map

**Create (logic, no AgentScope):**

- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyMention.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsySendRouter.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettings.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyPaths.java`
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyMentionTest.java`
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsySendRouterTest.java`
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettingsTest.java`
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyPathsTest.java`
- `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java`

**Create (ported from kelsy, package → `com.glodon.mordor.kmate.kelsy.*`):**

- `kelsy/config/KelsyConfig.java`, `ConfigLoader.java`
- `kelsy/service/SlashCommands.java`, `FindQuery.java`, `KnowledgeStore.java`, `KnowledgePathExtractor.java`, `WorkspaceSeeder.java`, `AssistantService.java`, `ModelFactory.java`, `LocalAssistantService.java`
- `kelsy/model/AssistantMessage.java` (kelsy `Message.java` renamed), `MessageBlock.java`
- `kelsy/ui/knowledge/KnowledgePane.java`
- `kelsy/ui/markdown/MdSpan.java`, `MdNode.java`, `MarkdownRenderer.java`, `MarkdownView.java`
- `kelsy/ui/ToolCallCard.java`, `kelsy/ui/AssistantBubble.java` (kelsy `MessageBubble`)
- resources: `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/**` (from kelsy workspace resources)
- matching tests copied from kelsy (`SlashCommandsTest`, `KnowledgeStore*`, `FindQueryTest`, `MarkdownRendererTest`, `ConfigLoaderTest`) with package + path updates

**Create (runtime glue):**

- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRuntime.java`

**Modify:**

- `src/main/java/com/glodon/mordor/kmate/model/Sender.java` — add `ASSISTANT`
- `src/main/java/com/glodon/mordor/kmate/model/RoomMember.java` — `isKelsy()`
- `src/main/java/com/glodon/mordor/kmate/service/ChatHistory.java` — `public static sha256Hex`
- `src/main/java/com/glodon/mordor/kmate/service/AvatarService.java` — `copyLocalAs(File, String basename)`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java`
- `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java`
- `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`
- `src/main/java/module-info.java`
- `pom.xml`
- `src/test/java/com/glodon/mordor/kmate/service/HistoryCodecTest.java`

---

### Task 1: `@kelsy` mention parser

**Files:**
- Create: `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyMentionTest.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyMention.java`

- [ ] **Step 1: Write the failing test**

```java
package com.glodon.mordor.kmate.kelsy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyMentionTest {

    @Test
    void detectsPrefixCaseInsensitiveAndStrips() {
        assertTrue(KelsyMention.isMention("@kelsy 你好"));
        assertTrue(KelsyMention.isMention("  @KELSY 帮我记 "));
        assertEquals("你好", KelsyMention.strip("@kelsy 你好"));
        assertEquals("帮我记", KelsyMention.strip("  @KELSY 帮我记 "));
    }

    @Test
    void mentionAloneIsEmptyBody() {
        assertTrue(KelsyMention.isMention("@kelsy"));
        assertTrue(KelsyMention.isMention("@kelsy   "));
        assertEquals("", KelsyMention.strip("@kelsy"));
    }

    @Test
    void gluedNameIsNotMention() {
        assertFalse(KelsyMention.isMention("@kelsyfoo 嗨"));
        assertFalse(KelsyMention.isMention("请 @kelsy 看看"));
        assertFalse(KelsyMention.isMention("hello"));
        assertFalse(KelsyMention.isMention(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=KelsyMentionTest test`

Expected: FAIL — `KelsyMention` 不存在

- [ ] **Step 3: Write minimal implementation**

```java
package com.glodon.mordor.kmate.kelsy;

public final class KelsyMention {

    public static final String NAME = "kelsy";
    public static final String INSERT = "@kelsy ";

    private KelsyMention() {
    }

    public static boolean isMention(String text) {
        String body = text == null ? "" : text.strip();
        if (body.length() < 7 || !body.regionMatches(true, 0, "@kelsy", 0, 6)) {
            return false;
        }
        return body.length() == 6 || Character.isWhitespace(body.charAt(6));
    }

    public static String strip(String text) {
        if (!isMention(text)) {
            return text == null ? "" : text.strip();
        }
        String body = text.strip();
        return body.length() == 6 ? "" : body.substring(6).strip();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=KelsyMentionTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/glodon/mordor/kmate/kelsy/KelsyMentionTest.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/KelsyMention.java
git commit -m "feat: parse @kelsy mention prefix"
```

---

### Task 2: Port SlashCommands (needed by the router)

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/service/SlashCommands.java`
- Create: `src/test/java/com/glodon/mordor/kmate/kelsy/service/SlashCommandsTest.java`

- [ ] **Step 1: Copy tests and production from kelsy, change package only**

```bash
mkdir -p src/main/java/com/glodon/mordor/kmate/kelsy/service \
         src/test/java/com/glodon/mordor/kmate/kelsy/service
cp /Users/ksw/workspace/repository/kelsy/src/main/java/com/mordor/kelsy/service/SlashCommands.java \
   src/main/java/com/glodon/mordor/kmate/kelsy/service/SlashCommands.java
cp /Users/ksw/workspace/repository/kelsy/src/test/java/com/mordor/kelsy/service/SlashCommandsTest.java \
   src/test/java/com/glodon/mordor/kmate/kelsy/service/SlashCommandsTest.java
```

In both files replace `package com.mordor.kelsy.service;` with `package com.glodon.mordor.kmate.kelsy.service;`. Do not change command strings or `Result` fields (`send`, `find`, `outgoing`, `error`).

- [ ] **Step 2: Run tests**

Run: `./mvnw -q -Dtest=SlashCommandsTest test`

Expected: PASS (same cases as standalone kelsy)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/service/SlashCommands.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/service/SlashCommandsTest.java
git commit -m "feat: port kelsy slash commands"
```

---

### Task 3: Send router (enabled / busy / mention / slash)

**Files:**
- Create: `src/test/java/com/glodon/mordor/kmate/kelsy/KelsySendRouterTest.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/KelsySendRouter.java`

- [ ] **Step 1: Write the failing test**

```java
package com.glodon.mordor.kmate.kelsy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KelsySendRouterTest {

    @Test
    void disabledAlwaysPeerEvenWithMention() {
        var r = KelsySendRouter.route(false, false, true, "@kelsy 你好");
        assertEquals(KelsySendRouter.Kind.PEER, r.kind());
        assertEquals("@kelsy 你好", r.peerText());
    }

    @Test
    void enabledNonMentionIsPeer() {
        var r = KelsySendRouter.route(true, false, true, "/find 关键词");
        assertEquals(KelsySendRouter.Kind.PEER, r.kind());
        assertEquals("/find 关键词", r.peerText());
    }

    @Test
    void busyMentionIsBusy() {
        var r = KelsySendRouter.route(true, true, true, "@kelsy 第二问");
        assertEquals(KelsySendRouter.Kind.BUSY, r.kind());
        assertNull(r.peerText());
    }

    @Test
    void busyDoesNotBlockPeer() {
        var r = KelsySendRouter.route(true, true, true, "普通消息");
        assertEquals(KelsySendRouter.Kind.PEER, r.kind());
    }

    @Test
    void mentionWithoutKey() {
        var r = KelsySendRouter.route(true, false, false, "@kelsy 你好");
        assertEquals(KelsySendRouter.Kind.UNCONFIGURED, r.kind());
    }

    @Test
    void mentionEmptyBody() {
        var r = KelsySendRouter.route(true, false, true, "@kelsy");
        assertEquals(KelsySendRouter.Kind.EMPTY_BODY, r.kind());
    }

    @Test
    void mentionAskAndFind() {
        var ask = KelsySendRouter.route(true, false, true, "@kelsy 你好");
        assertEquals(KelsySendRouter.Kind.ASK, ask.kind());
        assertEquals("你好", ask.outgoing());

        var find = KelsySendRouter.route(true, false, true, "@kelsy /find 方案");
        assertEquals(KelsySendRouter.Kind.FIND, find.kind());
        assertEquals("方案", find.outgoing());
    }

    @Test
    void mentionSlashReject() {
        var r = KelsySendRouter.route(true, false, true, "@kelsy /note");
        assertEquals(KelsySendRouter.Kind.SLASH_ERROR, r.kind());
        assertEquals("请写上要记的内容", r.error());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=KelsySendRouterTest test`

Expected: FAIL — `KelsySendRouter` 不存在

- [ ] **Step 3: Write implementation**

```java
package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.kelsy.service.SlashCommands;

public final class KelsySendRouter {

    public enum Kind {
        PEER, BUSY, UNCONFIGURED, EMPTY_BODY, ASK, FIND, SLASH_ERROR
    }

    public record Result(Kind kind, String peerText, String outgoing, String error) {
        static Result peer(String text) {
            return new Result(Kind.PEER, text, null, null);
        }
    }

    private KelsySendRouter() {
    }

    public static Result route(boolean enabled, boolean busy, boolean configured, String text) {
        String raw = text == null ? "" : text;
        if (!enabled || !KelsyMention.isMention(raw)) {
            return Result.peer(raw);
        }
        if (busy) {
            return new Result(Kind.BUSY, null, null, "秘书还在回复");
        }
        if (!configured) {
            return new Result(Kind.UNCONFIGURED, null, null, null);
        }
        String body = KelsyMention.strip(raw);
        if (body.isEmpty()) {
            return new Result(Kind.EMPTY_BODY, null, null, "请输入要问秘书的内容");
        }
        SlashCommands.Result slash = SlashCommands.parse(body);
        if (slash.find()) {
            return new Result(Kind.FIND, null, slash.outgoing(), null);
        }
        if (!slash.send()) {
            return new Result(Kind.SLASH_ERROR, null, null, slash.error());
        }
        return new Result(Kind.ASK, null, slash.outgoing(), null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=KelsySendRouterTest,KelsyMentionTest,SlashCommandsTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/KelsySendRouter.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsySendRouterTest.java
git commit -m "feat: route @kelsy vs peer send"
```

---

### Task 4: `Sender.ASSISTANT` history round-trip

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/model/Sender.java`
- Modify: `src/test/java/com/glodon/mordor/kmate/service/HistoryCodecTest.java`

- [ ] **Step 1: Add failing test to `HistoryCodecTest`**

```java
@Test
void assistantMessageRoundTrip() {
    LocalDateTime at = LocalDateTime.of(2026, 9, 2, 18, 0, 0);
    Message original = new Message("a1", Sender.ASSISTANT, "最终正文", at, "kelsy");
    assertEquals(original, HistoryCodec.decode(HistoryCodec.encode(original)));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=HistoryCodecTest#assistantMessageRoundTrip test`

Expected: FAIL — `ASSISTANT` 不存在

- [ ] **Step 3: Add enum constant**

```java
package com.glodon.mordor.kmate.model;

public enum Sender { SELF, PEER, SYSTEM, ASSISTANT }
```

`HistoryCodec` already uses `Sender.name()` / `Sender.valueOf`; no codec change.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=HistoryCodecTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/model/Sender.java \
        src/test/java/com/glodon/mordor/kmate/service/HistoryCodecTest.java
git commit -m "feat: persist ASSISTANT sender in chat history"
```

---

### Task 5: Per-room enable settings

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/service/ChatHistory.java` — make `sha256Hex` public
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettings.java`
- Create: `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettingsTest.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/model/RoomMember.java`

- [ ] **Step 1: Write the failing test**

```java
package com.glodon.mordor.kmate.kelsy;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyRoomSettingsTest {

    @Test
    void roomsAreIsolatedAndRemovable() {
        MemoryPrefs prefs = new MemoryPrefs();
        KelsyRoomSettings settings = new KelsyRoomSettings(prefs);
        assertFalse(settings.enabled("ROOM-A"));
        settings.enable("ROOM-A", "/tmp/a.png");
        settings.enable("ROOM-B", "/tmp/b.png");
        assertTrue(settings.enabled("ROOM-A"));
        assertEquals("/tmp/a.png", settings.avatarPath("ROOM-A"));
        assertEquals("/tmp/b.png", settings.avatarPath("ROOM-B"));
        settings.disable("ROOM-A");
        assertFalse(settings.enabled("ROOM-A"));
        assertTrue(settings.enabled("ROOM-B"));
        assertEquals("", settings.avatarPath("ROOM-A"));
    }

    static final class MemoryPrefs extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();

        MemoryPrefs() {
            super(null, "");
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return this;
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=KelsyRoomSettingsTest test`

Expected: FAIL — class missing

- [ ] **Step 3: Implement settings + `RoomMember.isKelsy` + public hash**

In `ChatHistory.java` change `static String sha256Hex` to `public static String sha256Hex`.

```java
package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.service.ChatHistory;

import java.util.prefs.Preferences;

public final class KelsyRoomSettings {

    private final Preferences prefs;

    public KelsyRoomSettings() {
        this(Preferences.userNodeForPackage(KelsyRoomSettings.class));
    }

    public KelsyRoomSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    public boolean enabled(String imCode) {
        return prefs.getBoolean(onKey(imCode), false);
    }

    public String avatarPath(String imCode) {
        return prefs.get(avatarKey(imCode), "");
    }

    public void enable(String imCode, String avatarPath) {
        prefs.putBoolean(onKey(imCode), true);
        prefs.put(avatarKey(imCode), avatarPath == null ? "" : avatarPath);
    }

    public void disable(String imCode) {
        prefs.putBoolean(onKey(imCode), false);
        prefs.remove(avatarKey(imCode));
    }

    private static String onKey(String imCode) {
        return "on." + ChatHistory.sha256Hex(imCode);
    }

    private static String avatarKey(String imCode) {
        return "avatar." + ChatHistory.sha256Hex(imCode);
    }
}
```

```java
package com.glodon.mordor.kmate.model;

public record RoomMember(int userId, String username, boolean self) {
    public static final int KELSY_ID = -2;

    public static RoomMember kelsy() {
        return new RoomMember(KELSY_ID, "kelsy", false);
    }

    public boolean isKelsy() {
        return userId == KELSY_ID;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q -Dtest=KelsyRoomSettingsTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettings.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRoomSettingsTest.java \
        src/main/java/com/glodon/mordor/kmate/service/ChatHistory.java \
        src/main/java/com/glodon/mordor/kmate/model/RoomMember.java
git commit -m "feat: persist per-room kelsy enablement"
```

---

### Task 6: Isolated config paths + template copy

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyPaths.java`
- Create: `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyPathsTest.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/config/KelsyConfig.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/config/ConfigLoader.java`
- Create: `src/test/java/com/glodon/mordor/kmate/kelsy/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write path + loader tests (temp dirs, no real home)**

`KelsyPathsTest`:

```java
package com.glodon.mordor.kmate.kelsy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KelsyPathsTest {

    @Test
    void defaultsUnderKmateKelsy() {
        Path home = Path.of("/tmp/home");
        KelsyPaths paths = KelsyPaths.forHome(home);
        assertEquals(home.resolve(".kmate/kelsy/config.json"), paths.config());
        assertEquals(home.resolve(".kmate/kelsy/workspace"), paths.workspace());
        assertEquals(home.resolve(".kelsy/config.json"), paths.legacyConfig());
    }
}
```

`ConfigLoaderTest` (new, do not copy kelsy test verbatim — paths differ):

```java
package com.glodon.mordor.kmate.kelsy.config;

import com.glodon.mordor.kmate.kelsy.KelsyPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir Path tmp;

    @Test
    void copiesLegacyThenHasKey() throws Exception {
        Path home = tmp.resolve("home");
        KelsyPaths paths = KelsyPaths.forHome(home);
        Files.createDirectories(paths.legacyConfig().getParent());
        Files.writeString(paths.legacyConfig(), """
                {"model":{"apiKey":"sk-test","baseUrl":"https://api.minimaxi.com/v1","modelName":"MiniMax-M3"}}
                """);
        assertTrue(ConfigLoader.ensureAndHasApiKey(paths));
        assertTrue(Files.exists(paths.config()));
        assertTrue(Files.readString(paths.config()).contains("sk-test"));
    }

    @Test
    void writesEmptyTemplateWhenNoLegacy() {
        KelsyPaths paths = KelsyPaths.forHome(tmp.resolve("empty"));
        assertFalse(ConfigLoader.ensureAndHasApiKey(paths));
        assertTrue(Files.exists(paths.config()));
        assertTrue(Files.readString(paths.config()).contains("\"apiKey\": \"\"")
                || Files.readString(paths.config()).contains("\"apiKey\":\"\""));
    }
}
```

- [ ] **Step 2: Run tests, expect fail**

Run: `./mvnw -q -Dtest=KelsyPathsTest,com.glodon.mordor.kmate.kelsy.config.ConfigLoaderTest test`

Expected: FAIL — missing classes / Jackson not on classpath yet

- [ ] **Step 3: Add Jackson (and later AgentScope) to `pom.xml` `<dependencies>`**

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.21.1</version>
</dependency>
```

Copy `/Users/ksw/workspace/repository/kelsy/src/main/java/com/mordor/kelsy/config/KelsyConfig.java` to `src/main/java/com/glodon/mordor/kmate/kelsy/config/KelsyConfig.java`.

Change:

- `package com.glodon.mordor.kmate.kelsy.config;`
- `DEFAULT_WORKSPACE_DIR = "~/.kmate/kelsy/workspace"`

`KelsyPaths.java`:

```java
package com.glodon.mordor.kmate.kelsy;

import java.nio.file.Path;

public record KelsyPaths(Path config, Path workspace, Path legacyConfig) {

    public static KelsyPaths defaults() {
        return forHome(Path.of(System.getProperty("user.home")));
    }

    public static KelsyPaths forHome(Path home) {
        return new KelsyPaths(
                home.resolve(".kmate").resolve("kelsy").resolve("config.json"),
                home.resolve(".kmate").resolve("kelsy").resolve("workspace"),
                home.resolve(".kelsy").resolve("config.json"));
    }
}
```

Copy kelsy `ConfigLoader.java` then replace the class body so it **never throws on missing key**. Keep `TEMPLATE` but set `"workspaceDir": "~/.kmate/kelsy/workspace"`. Implementation:

```java
package com.glodon.mordor.kmate.kelsy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glodon.mordor.kmate.kelsy.KelsyPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

public final class ConfigLoader {

    private static final String TEMPLATE = """
            {
              "model": {
                "provider": "minimax",
                "apiKey": "",
                "baseUrl": "https://api.minimaxi.com/v1",
                "modelName": "MiniMax-M3"
              },
              "workspaceDir": "~/.kmate/kelsy/workspace",
              "lastUsername": "",
              "selfAvatarPath": "",
              "kelsyAvatarPath": ""
            }
            """;

    private ConfigLoader() {
    }

    public static boolean ensureAndHasApiKey(KelsyPaths paths) {
        try {
            Files.createDirectories(paths.config().getParent());
            if (!Files.exists(paths.config())) {
                if (Files.isRegularFile(paths.legacyConfig())) {
                    Files.copy(paths.legacyConfig(), paths.config(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.writeString(paths.config(), TEMPLATE);
                }
                restrictToOwner(paths.config());
            }
            KelsyConfig config = peek(paths);
            return config.model() != null
                    && config.model().apiKey() != null
                    && !config.model().apiKey().isBlank();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static KelsyConfig peek(KelsyPaths paths) {
        if (!Files.exists(paths.config())) {
            return new KelsyConfig(null, paths.workspace().toString(), "", "", "");
        }
        try {
            return new ObjectMapper().readValue(paths.config().toFile(), KelsyConfig.class);
        } catch (IOException e) {
            return new KelsyConfig(null, paths.workspace().toString(), "", "", "");
        }
    }

    public static KelsyConfig loadOrThrow(KelsyPaths paths) {
        if (!ensureAndHasApiKey(paths)) {
            throw new IllegalStateException("配置缺少 model.apiKey：" + paths.config());
        }
        return peek(paths);
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
```

`module-info.java` add: `requires com.fasterxml.jackson.databind;`

- [ ] **Step 4: Run tests**

Run: `./mvnw -q -Dtest=KelsyPathsTest,com.glodon.mordor.kmate.kelsy.config.ConfigLoaderTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/module-info.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/KelsyPaths.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/config \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsyPathsTest.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/config
git commit -m "feat: isolate kelsy config under ~/.kmate/kelsy"
```

---

### Task 7: Port knowledge store, find, seeder

**Files:**
- Create (copy + package rewrite): `FindQuery.java`, `KnowledgePathExtractor.java`, `KnowledgeStore.java`, `WorkspaceSeeder.java`
- Create resources under `src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/`
- Create corresponding tests from kelsy (`FindQueryTest`, `KnowledgeStoreTest`, `KnowledgeStoreSearchTest`, `KnowledgeStoreUserRootTest`, `KnowledgePathExtractorTest`, `WorkspaceSeederTest`)

- [ ] **Step 1: Copy sources and tests, rewrite packages**

```bash
KELSY=/Users/ksw/workspace/repository/kelsy
for f in FindQuery KnowledgePathExtractor KnowledgeStore WorkspaceSeeder; do
  cp $KELSY/src/main/java/com/mordor/kelsy/service/$f.java \
     src/main/java/com/glodon/mordor/kmate/kelsy/service/$f.java
done
mkdir -p src/main/resources/com/glodon/mordor/kmate/kelsy/workspace
cp -R $KELSY/src/main/resources/com/mordor/kelsy/workspace/. \
      src/main/resources/com/glodon/mordor/kmate/kelsy/workspace/
```

In every copied `.java` file:

- `package com.glodon.mordor.kmate.kelsy.service;`
- imports `com.mordor.kelsy` → `com.glodon.mordor.kmate.kelsy`

In `WorkspaceSeeder`, change resource prefix:

```java
private static final String PREFIX = "/com/glodon/mordor/kmate/kelsy/workspace/";
```

Copy the six kelsy tests listed above into `src/test/java/com/glodon/mordor/kmate/kelsy/service/` and apply the same package/import rewrite. Do not change assertions.

- [ ] **Step 2: Run ported tests**

Run: `./mvnw -q -Dtest=FindQueryTest,KnowledgeStoreTest,KnowledgeStoreSearchTest,KnowledgeStoreUserRootTest,KnowledgePathExtractorTest,WorkspaceSeederTest test`

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/service \
        src/main/resources/com/glodon/mordor/kmate/kelsy/workspace \
        src/test/java/com/glodon/mordor/kmate/kelsy/service
git commit -m "feat: port kelsy knowledge store and seeder"
```

---

### Task 8: Port AssistantService + AssistantMessage (no AgentScope yet)

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/service/AssistantService.java` (copy, package only)
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/model/MessageBlock.java` (copy, package only)
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/model/AssistantMessage.java` from kelsy `Message.java`
- Create: `src/test/java/com/glodon/mordor/kmate/model/MessageTest.java` is kmate's existing file — do **not** overwrite. Copy kelsy `MessageTest` to `src/test/java/com/glodon/mordor/kmate/kelsy/model/AssistantMessageTest.java`

- [ ] **Step 1: Copy `AssistantService` and `MessageBlock` with package `com.glodon.mordor.kmate.kelsy.service` / `.model`**

- [ ] **Step 2: Rename kelsy `Message` → `AssistantMessage`**

Copy `Message.java` to `AssistantMessage.java`, then:

- `package com.glodon.mordor.kmate.kelsy.model;`
- `public final class AssistantMessage`
- Replace kelsy `Sender` with `com.glodon.mordor.kmate.model.Sender` (`SELF` / `ASSISTANT` / `SYSTEM` only in this class)
- Keep `of`, `streaming`, `append`, `appendThinking`, `finish`, `blocks`, `content`, `contentProperty`, `streamingProperty`

Update the copied test: class name `AssistantMessageTest`, call `AssistantMessage.of` / `streaming`.

- [ ] **Step 3: Run**

Run: `./mvnw -q -Dtest=AssistantMessageTest test`

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/service/AssistantService.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/model \
        src/test/java/com/glodon/mordor/kmate/kelsy/model
git commit -m "feat: port assistant message model and service interface"
```

---

### Task 9: `KelsyRuntime` (lazy agent, injectable assistant)

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRuntime.java`
- Create: `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRuntimeTest.java`

Do **not** construct `LocalAssistantService` in this task. Runtime holds an optional `AssistantService` factory so controller tests stay fake.

- [ ] **Step 1: Failing test**

```java
package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.kelsy.service.AssistantService;
import com.glodon.mordor.kmate.kelsy.service.KnowledgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KelsyRuntimeTest {

    @TempDir Path tmp;

    @Test
    void seedsStoreAndReusesAssistant() {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        AtomicInteger builds = new AtomicInteger();
        AssistantService fake = new AssistantService() {
            @Override public void chat(String text, ReplyHandler handler) {}
            @Override public void close() {}
        };
        KelsyRuntime runtime = KelsyRuntime.open(paths, "alice", p -> {
            builds.incrementAndGet();
            return fake;
        });
        assertFalse(runtime.hasApiKey());
        assertNotNull(runtime.store("alice"));
        assertTrue(java.nio.file.Files.isDirectory(paths.workspace()));
        assertEquals(null, runtime.ensureAssistant());
        assertEquals(0, builds.get());

        Files.writeString(paths.config(), "{\"model\":{\"apiKey\":\"sk-test\"}}");
        assertTrue(runtime.hasApiKey());
        assertSame(fake, runtime.ensureAssistant());
        runtime.ensureAssistant();
        assertEquals(1, builds.get());
        runtime.close();
    }
}
```

Add `import java.nio.file.Files;` and `import static org.junit.jupiter.api.Assertions.assertEquals;`.

Contract:

- `open` seeds workspace via `WorkspaceSeeder.seed(paths.workspace())` and `WorkspaceSeeder.seed(KnowledgeStore.knowledgeRoot(paths.workspace(), username))`
- `hasApiKey()` = `ConfigLoader.ensureAndHasApiKey(paths)`
- `ensureAssistant()` is null and does not call the factory when there is no key
- factory invoked at most once after a key exists
- `close()` calls `assistant.close()` if present

- [ ] **Step 2: Implement `KelsyRuntime`**

```java
package com.glodon.mordor.kmate.kelsy;

import com.glodon.mordor.kmate.kelsy.config.ConfigLoader;
import com.glodon.mordor.kmate.kelsy.config.KelsyConfig;
import com.glodon.mordor.kmate.kelsy.service.AssistantService;
import com.glodon.mordor.kmate.kelsy.service.KnowledgeStore;
import com.glodon.mordor.kmate.kelsy.service.WorkspaceSeeder;

import java.util.function.Function;

public final class KelsyRuntime implements AutoCloseable {

    private static KelsyRuntime instance;

    private final KelsyPaths paths;
    private final Function<KelsyConfig, AssistantService> factory;
    private AssistantService assistant;
    private boolean closed;

    public static synchronized KelsyRuntime shared(String username) {
        if (instance == null) {
            instance = open(KelsyPaths.defaults(), username, KelsyRuntime::createLocal);
        }
        return instance;
    }

    public static synchronized void shutdown() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public static KelsyRuntime open(KelsyPaths paths, String username,
                                    Function<KelsyConfig, AssistantService> factory) {
        ConfigLoader.ensureAndHasApiKey(paths);
        WorkspaceSeeder.seed(paths.workspace());
        WorkspaceSeeder.seed(KnowledgeStore.knowledgeRoot(paths.workspace(), username));
        return new KelsyRuntime(paths, factory);
    }

    private KelsyRuntime(KelsyPaths paths, Function<KelsyConfig, AssistantService> factory) {
        this.paths = paths;
        this.factory = factory;
    }

    public boolean hasApiKey() {
        return ConfigLoader.ensureAndHasApiKey(paths);
    }

    public KnowledgeStore store(String username) {
        return KnowledgeStore.forUser(paths.workspace(), username);
    }

    public synchronized AssistantService ensureAssistant() {
        if (closed) {
            return null;
        }
        if (assistant != null) {
            return assistant;
        }
        if (!hasApiKey()) {
            return null;
        }
        assistant = factory.apply(ConfigLoader.loadOrThrow(paths));
        return assistant;
    }

    public AssistantService assistant() {
        return assistant;
    }

    public KelsyPaths paths() {
        return paths;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (assistant != null) {
            assistant.close();
            assistant = null;
        }
    }

    static AssistantService createLocal(KelsyConfig config) {
        throw new UnsupportedOperationException("LocalAssistantService in a later task");
    }
}
```

Fix the test to match: no key → `ensureAssistant()` is null and factory not called; write key into `paths.config()` then `ensureAssistant()` returns fake.

- [ ] **Step 3: Run**

Run: `./mvnw -q -Dtest=KelsyRuntimeTest test`

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRuntime.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRuntimeTest.java
git commit -m "feat: add lazy kelsy runtime without live model"
```

---

### Task 10: ChatController send routing

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Create: `src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java`

`ChatController` currently always `sendChat`. Add an injectable `PeerSender` and `KelsyDeps` so tests never open a WebSocket.

- [ ] **Step 1: Write failing tests** (use a recording `PeerSender` and fake `AssistantService`)

Constructor for tests (keep existing `ChatController(AppState, ChatHistory)` delegating to production deps):

```java
public interface PeerSender {
    void sendChat(String text) throws Exception;
}

record KelsyDeps(KelsyRoomSettings settings, KelsyRuntime runtime) {}
```

`ChatControllerKelsyTest` outline (must compile as written):

```java
package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.kelsy.KelsyPaths;
import com.glodon.mordor.kmate.kelsy.KelsyRoomSettings;
import com.glodon.mordor.kmate.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import com.glodon.mordor.kmate.kelsy.KelsyRuntime;
import com.glodon.mordor.kmate.kelsy.service.AssistantService;
import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Sender;
import com.glodon.mordor.kmate.service.ChatHistory;
import com.glodon.mordor.kmate.service.CryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerKelsyTest {

    @TempDir Path tmp;

    @Test
    void mentionDoesNotCallPeerWhenEnabled() {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true);
        assertTrue(c.send("@kelsy 你好"));
        assertTrue(peer.isEmpty());
        assertEquals(List.of("你好"), asked);
        assertEquals(Sender.SELF, c.getMessages().get(c.getMessages().size() - 2).sender());
        assertEquals(Sender.ASSISTANT, c.getMessages().getLast().sender());
    }

    @Test
    void disabledMentionGoesToPeer() {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), false, true);
        assertTrue(c.send("@kelsy 你好"));
        assertEquals(List.of("@kelsy 你好"), peer);
    }

    @Test
    void busyMentionRejectedKeepsNoPeerCall() {
        List<String> peer = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        ChatController c = controller(peer, asked, true, true);
        c.send("@kelsy 第一问");
        asked.clear();
        assertFalse(c.send("@kelsy 第二问"));
        assertTrue(asked.isEmpty());
        assertTrue(peer.isEmpty());
    }

    @Test
    void peerStillSendsWhileBusy() {
        List<String> peer = new ArrayList<>();
        ChatController c = controller(peer, new ArrayList<>(), true, true);
        c.send("@kelsy 第一问");
        assertTrue(c.send("普通"));
        assertEquals(List.of("普通"), peer);
    }

    private ChatController controller(List<String> peer, List<String> asked,
                                      boolean enabled, boolean configured) throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        java.nio.file.Files.createDirectories(paths.config().getParent());
        if (configured) {
            java.nio.file.Files.writeString(paths.config(),
                    "{\"model\":{\"apiKey\":\"sk-test\"}}");
        }
        KelsyRoomSettings settings = new KelsyRoomSettings(new MemoryPrefs());
        if (enabled) {
            settings.enable("ROOM", "");
        }
        AssistantService fake = new AssistantService() {
            @Override
            public void chat(String text, ReplyHandler handler) {
                asked.add(text);
            }

            @Override
            public void close() {
            }
        };
        KelsyRuntime runtime = KelsyRuntime.open(paths, "me", cfg -> fake);
        ChatHistory history = new ChatHistory(
                tmp.resolve("messages.log"),
                CryptoService.forArchive("pw", "ROOM"));
        return new ChatController(
                "ROOM",
                "me",
                history,
                peer::add,
                settings,
                runtime);
    }
}
```

Replace the `throw` with a real helper once the ctor exists in Step 3. The helper must:

1. `KelsyPaths.forHome(tmp)` + write `{"model":{"apiKey":"x"}}` when `configured`
2. `KelsyRoomSettings` on `MemoryPrefs`; `enable("ROOM", "")` when `enabled`
3. `KelsyRuntime.open(paths, "me", cfg -> fakeAssistant(asked))` where fake `chat` records `text`, does **not** call `onComplete` (so `busy` stays true) for the busy tests
4. `ChatHistory` on `tmp.resolve("hist.log")` with `CryptoService.forArchive("pw", "ROOM")`
5. `new ChatController(stateOrNull, history, imCode="ROOM", username="me", peer::add, settings, runtime)`

If constructing `AppState` still needs `ImClient`, add an overload that does not register listeners when `PeerSender` is provided. Smallest change: new package-visible ctor:

```java
ChatController(String imCode, String username, ChatHistory history,
               PeerSender peerSender, KelsyRoomSettings settings, KelsyRuntime runtime)
```

Production ctor still takes `AppState` + `ChatHistory`, then delegates:

```java
this(state.client().imCode(), state.username(), history,
     state.client()::sendChat, new KelsyRoomSettings(), null);
```

Keep the existing listener/`loadInitialAsync` setup in the production ctor (it still has `AppState`). The test ctor must **not** register `ImClient` listeners.

`runtime == null` means lazy `KelsyRuntime.shared(username)` only when the room is enabled and a mention needs the agent (or when user clicks 添加).

- [ ] **Step 2: Run tests, expect fail**

Run: `./mvnw -q -Dtest=ChatControllerKelsyTest test`

Expected: FAIL

- [ ] **Step 3: Change `send` to `boolean` and branch on `KelsySendRouter`**

```java
public boolean send(String content) {
    if (content == null || content.isBlank()) {
        return false;
    }
    boolean enabled = settings.enabled(imCode);
    boolean configured = enabled && runtime != null && runtime.hasApiKey();
    var route = KelsySendRouter.route(enabled, kelsyBusy.get(), configured, content);
    return switch (route.kind()) {
        case PEER -> sendPeer(content);
        case BUSY -> false;
        case UNCONFIGURED -> {
            addSystem("尚未配置秘书 API key：" + (runtime == null
                    ? KelsyPaths.defaults().config()
                    : runtime.paths().config()));
            yield true;
        }
        case EMPTY_BODY, SLASH_ERROR -> {
            addSystem(route.error());
            yield true;
        }
        case FIND -> {
            addSelf(content);
            runFind(route.outgoing());
            yield true;
        }
        case ASK -> {
            addSelf(content);
            startAsk(route.outgoing());
            yield true;
        }
    };
}
```

`sendPeer` is the current try/`sendChat`/`addMessage(SELF)` body, returning true (or false only if you want — keep true even on send failure after `addSystem`, matching “input clears”).

`startAsk`:

- `kelsyBusy.set(true)`
- `AssistantMessage reply = AssistantMessage.streaming(Sender.ASSISTANT)`
- expose `liveAssistant` property for the list
- `runtime.ensureAssistant().chat(..., handler)` that `Platform.runLater` appends; on complete: `addMessage(new Message(id, ASSISTANT, reply.content(), now, "kelsy"))`, clear live, `kelsyBusy.set(false)`

Tests that avoid FX: fake assistant that never completes is enough for busy; for ASK message list, add SELF immediately (history + messages) and put a placeholder ASSISTANT only after complete. To satisfy “SELF + ASSISTANT in list” without FX, add a **pending** ASSISTANT `Message` with empty content at start, then fill on complete — **or** keep ASSISTANT only on complete and change the first test to assert last message is SELF with `@kelsy 你好` and `asked` captured.

Use the latter in tests (no live node required for unit tests):

```java
assertEquals(Sender.SELF, last.sender());
assertEquals("@kelsy 你好", last.content());
```

Live bubble is Task 14.

`runFind`: `runtime.store(username).search(FindQuery.parse(...))` then `addSystem(formatFind(...))` — copy `formatFind` from kelsy `ChatController`.

`refreshPeers`: after building human members, if `settings.enabled(imCode)` then `next.add(1, RoomMember.kelsy())`.

`humanCountProperty()`: `members.size()` minus 1 if kelsy present. Expose for the list label.

`enableKelsy(String avatarPath)` / `disableKelsy()` update settings, refresh members, on first enable call `KelsyRuntime.shared(username)` (or assigned runtime). `disableKelsy` does not cancel an in-flight ask; `kelsyBusy` still clears on complete; subsequent send uses `enabled=false`.

- [ ] **Step 4: Run**

Run: `./mvnw -q -Dtest=ChatControllerKelsyTest,HistoryCodecTest,ChatHistoryTest test`

Expected: PASS. Existing history tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java \
        src/test/java/com/glodon/mordor/kmate/ui/chat/ChatControllerKelsyTest.java
git commit -m "feat: keep @kelsy local when room enabled kelsy"
```

---

### Task 11: InputBar — clear only when consumed; busy hint; insert mention

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java`

No TestFX in this repo. Behavior is specified here; verify by compiling and the controller tests.

- [ ] **Step 1: Change `onSend` to `Function<String, Boolean>` (or `ChatController::send`)**

```java
public InputBar(java.util.function.Function<String, Boolean> onSend) {
```

```java
private final Label hint = new Label();
private final PauseTransition hideHint = new PauseTransition(Duration.seconds(3));

// in ctor after sendBtn:
hint.getStyleClass().add("kelsy-busy-hint");
hint.setVisible(false);
hint.setManaged(false);
hideHint.setOnFinished(e -> {
    hint.setVisible(false);
    hint.setManaged(false);
});
```

```java
private void send(Function<String, Boolean> onSend) {
    String text = textField.getText();
    if (text == null || text.isBlank()) {
        return;
    }
    Boolean consumed = onSend.apply(text);
    if (Boolean.FALSE.equals(consumed)) {
        hint.setText("秘书还在回复");
        hint.setVisible(true);
        hint.setManaged(true);
        hideHint.stop();
        hideHint.playFromStart();
        return;
    }
    clear();
}

public void insertMention(String snippet) {
    String cur = textField.getText() == null ? "" : textField.getText();
    if (KelsyMention.isMention(cur)) {
        textField.requestFocus();
        return;
    }
    textField.setText(snippet + cur);
    textField.positionCaret(textField.getText().length());
    textField.requestFocus();
}
```

Add `hint` to the HBox (after `sendBtn`). Keep send enabled whenever text is non-blank; **do not** bind to busy.

- [ ] **Step 2: Compile**

Run: `./mvnw -q -DskipTests compile`

Expected: FAIL until ChatPane still passes `controller::send` (method ref to `boolean send` matches `Function<String, Boolean>`). Update `ChatPane` in this task if it already constructs `InputBar`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java
git commit -m "feat: keep input when kelsy is busy"
```

---

### Task 12: Member list add / remove / click

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/service/AvatarService.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java` (`avatarOf` for kelsy)

- [ ] **Step 1: `AvatarService.copyLocalAs`**

Add next to existing `copyLocal`:

```java
public static Optional<Path> copyLocalAs(File picked, String basename) {
    // same as copyLocal but dest ~/.kmate/avatars/<basename>.<ext>
}
```

Extract shared copy from `copyLocal` so `self` still uses basename `self`.

- [ ] **Step 2: Room list UI**

In heading row, when expanded and kelsy not enabled, show a small button `添加 kelsy`. On click:

```java
AvatarService.chooseAndStoreKelsy(window, controller.imCode())
    .ifPresent(path -> controller.enableKelsy(path));
```

`chooseAndStoreKelsy`: FileChooser then `copyLocalAs(file, "kelsy-" + ChatHistory.sha256Hex(imCode))`.

When kelsy row is clicked: `onMention.accept(KelsyMention.INSERT)` (ctor new arg `Consumer<String> onMention`).

Kelsy row: context menu or a tiny `移除` when expanded. Calls `controller.disableKelsy()`.

Count label: bind `controller.humanCountProperty()` as `"%d 人"`, not raw `members` size.

`avatarOf("kelsy")`: load `settings.avatarPath(imCode)` via `AvatarService.load`.

- [ ] **Step 3: Compile**

Run: `./mvnw -q -DskipTests compile`

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/chat/RoomMemberList.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java \
        src/main/java/com/glodon/mordor/kmate/service/AvatarService.java
git commit -m "feat: add and remove kelsy per room"
```

---

### Task 13: Maven deps for AgentScope + LocalAssistantService

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/module-info.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/service/ModelFactory.java`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/service/LocalAssistantService.java`

- [ ] **Step 1: Add the same runtime deps as kelsy `pom.xml`**

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>2.0.1</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>2.0.1</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.17</version>
</dependency>
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark</artifactId>
    <version>0.24.0</version>
</dependency>
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark-ext-gfm-tables</artifactId>
    <version>0.24.0</version>
</dependency>
```

Copy `ModelFactory.java` and `LocalAssistantService.java` from kelsy; rewrite package/imports to `com.glodon.mordor.kmate.kelsy.*`. `LocalAssistantService.create` must seed `config.workspacePath()` (which after Task 6 defaults to `~/.kmate/kelsy/workspace`).

`KelsyRuntime.createLocal`:

```java
static AssistantService createLocal(KelsyConfig config) {
    return LocalAssistantService.create(config, "kmate");
}
```

Use the logged-in username from `shared(username)` as `userId` (already passed into `open`). Change `createLocal` to a lambda in `shared`:

```java
instance = open(KelsyPaths.defaults(), username,
        cfg -> LocalAssistantService.create(cfg, username));
```

`module-info.java` add what `mvn compile` reports as missing. Typical:

```
requires org.slf4j;
requires org.commonmark;
requires org.commonmark.ext.gfm.tables;
requires agentscope.harness;
requires agentscope.extensions.model.openai;
requires reactor.core;
```

If an automatic module name is different, use `jar --file=$HOME/.m2/repository/... --describe-module` and put that name in. If jlink/`javafx:run` cannot resolve automatic modules, leave `mvn test` green and fix launch in Task 16 (classpath), do not block this task.

- [ ] **Step 2: Compile + unit tests (no live API)**

Run: `./mvnw -q -Dtest=KelsyRuntimeTest,ChatControllerKelsyTest,SlashCommandsTest test`

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add pom.xml src/main/java/module-info.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/service/ModelFactory.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/service/LocalAssistantService.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRuntime.java
git commit -m "feat: embed AgentScope local assistant in kmate"
```

---

### Task 14: Port markdown, knowledge pane, assistant bubble

**Files:**
- Copy kelsy `ui/markdown/*`, `ui/knowledge/KnowledgePane.java`, `ui/chat/ToolCallCard.java`
- Create `src/main/java/com/glodon/mordor/kmate/kelsy/ui/AssistantBubble.java` from kelsy `MessageBubble.java`
- Copy `MarkdownRendererTest`
- Copy kelsy chat CSS rules needed by knowledge/assistant into `src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css` (append, do not delete existing rules)
- Modify: `MessageListView.java`, `ChatPane.java`

- [ ] **Step 1: Copy + rewrite packages**

| kelsy path | kmate path |
|---|---|
| `ui/markdown/*.java` | `kelsy/ui/markdown/` |
| `ui/knowledge/KnowledgePane.java` | `kelsy/ui/knowledge/` |
| `ui/chat/ToolCallCard.java` | `kelsy/ui/ToolCallCard.java` |
| `ui/chat/MessageBubble.java` | `kelsy/ui/AssistantBubble.java` |

In `AssistantBubble`: class rename; `Message` → `AssistantMessage`; `AvatarView` import `com.glodon.mordor.kmate.ui.AvatarView`.

Copy `MarkdownRendererTest` package to `com.glodon.mordor.kmate.kelsy.ui.markdown`.

- [ ] **Step 2: Run markdown tests**

Run: `./mvnw -q -Dtest=MarkdownRendererTest test`

Expected: PASS

- [ ] **Step 3: Wire list + pane**

`ChatController` exposes:

```java
public ObjectProperty<AssistantMessage> liveAssistantProperty() { return liveAssistant; }
public BooleanProperty knowledgeVisibleProperty() { return knowledgeVisible; }
public BooleanProperty thinkingVisibleProperty() { return thinkingVisible; }
```

Default `knowledgeVisible=false`, `thinkingVisible=true`.

`MessageListView`: listen to `liveAssistantProperty()`. When non-null, append one `AssistantBubble` at the end; when null, remove it. Completed `Sender.ASSISTANT` history messages render with a **finished** `AssistantMessage.of(Sender.ASSISTANT, m.content())` inside `AssistantBubble` (no live stream).

`ChatPane`:

```java
InputBar input = new InputBar(controller::send);
RoomMemberList members = new RoomMemberList(controller, input::insertMention);

KnowledgePane knowledge = null;
if (controller.kelsyEnabled()) {
    knowledge = new KnowledgePane(controller.knowledgeStore(), controller.memoryWarnProperty());
    controller.setOnOpenKnowledge(knowledge::open);
    controller.setOnRefreshKnowledge(knowledge::refresh);
}

BorderPane ui = new BorderPane();
ui.setTop(...);
ui.setLeft(members);
ui.setBottom(input);
if (knowledge == null) {
    ui.setCenter(new MessageListView(controller));
} else {
    BorderPane chat = new BorderPane();
    chat.setCenter(new MessageListView(controller));
    SplitPane split = new SplitPane(chat, knowledge);
    split.setDividerPositions(0.70);
    knowledge.managedProperty().bind(controller.knowledgeVisibleProperty());
    knowledge.visibleProperty().bind(controller.knowledgeVisibleProperty());
    ui.setCenter(split);
    // toggle: small button on ChatHeader or member list — add "知识库" on ChatHeader
}
```

Add a header control only when enabled: clicking toggles `knowledgeVisible`. Default false (collapsed).

When user enables kelsy **after** `ChatPane` constructed, rebuild is messy. Spec: add happens inside the pane. `ChatController.enableKelsy` sets a `BooleanProperty kelsyEnabled`. `ChatPane` listens and **rebuilds** the center (attach SplitPane + KnowledgePane on first enable; on disable detach). Implement this listener so add/remove does not require re-login.

- [ ] **Step 4: Compile + tests**

Run: `./mvnw -q test`

Expected: PASS (skip if a JavaFX toolkit test fails on CI headless — existing `MessageBubbleTest` already runs)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/ui \
        src/test/java/com/glodon/mordor/kmate/kelsy/ui \
        src/main/java/com/glodon/mordor/kmate/ui/chat \
        src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css
git commit -m "feat: show kelsy knowledge pane and assistant bubbles"
```

---

### Task 15: Shutdown + ChatHeader toggle

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatHeader.java`

- [ ] **Step 1: Close runtime on session end**

In `closeSession()` after `session.close()`:

```java
com.glodon.mordor.kmate.kelsy.KelsyRuntime.shutdown();
```

`QuitManager` already runs `closeSession` via `beforeHalt`.

- [ ] **Step 2: Header button「知识库」** visible only when `controller.kelsyEnabledProperty()`; toggles `knowledgeVisible`.

- [ ] **Step 3: Compile**

Run: `./mvnw -q -DskipTests compile`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/app/Mate4K.java \
        src/main/java/com/glodon/mordor/kmate/ui/chat/ChatHeader.java
git commit -m "feat: close kelsy runtime on quit and toggle knowledge pane"
```

---

### Task 16: Packaging so AgentScope survives jlink

**Files:**
- Modify: `pom.xml`

kelsy has no `module-info` and uses `copy-dependencies` + jpackage classpath. kmate jlink cannot swallow AgentScope automatic modules.

- [ ] **Step 1: Keep jlink image as the JDK+JavaFX runtime only if `--module` breaks**

Add `maven-dependency-plugin` executions copied from kelsy (`jpackage-libs`, exclude `org.openjfx`):

```xml
<outputDirectory>${project.build.directory}/jpackage-input/lib</outputDirectory>
<excludeGroupIds>org.openjfx</excludeGroupIds>
<includeScope>runtime</includeScope>
```

If `mvn -Pmac package` fails on jlink because `module-info` requires automatic modules:

1. Remove AgentScope / reactor / slf4j `requires` from `module-info` and compile those classes on the classpath by moving `kelsy/service/LocalAssistantService.java` and `ModelFactory.java` out of the named module — **too invasive**.
2. Preferred: drop `requires` for non-modular jars; mark the kmate module `requires static` only for jackson/commonmark; launch `javafx:run` with

```
<mainClass>com.glodon.mordor.kmate.app.Mate4K</mainClass>
```

without `module/` prefix **only if** the plugin docs for 0.0.8 allow classpath mode (`<commandlineArgs>`).

Concrete fallback that matches the spec (“jlink 运行时 + 额外 classpath”):

- jlink configuration: do **not** include kmate as a module that requires AgentScope. Use `--add-modules javafx.controls,java.desktop,java.prefs,java.net.http` style image (same as a vanilla FX runtime).
- jpackage: `--runtime-image target/kmate-runtime` + `--class-path target/kmate-1.0-SNAPSHOT.jar:target/jpackage-input/lib/*` + `--main-class com.glodon.mordor.kmate.app.Mate4K`.

Apply the same `--class-path` pattern to win and linux-deb profiles.

`javafx:run` for daily dev: if module-path run fails, document `./mvnw javafx:run` extra options; unit tests stay on Surefire classpath.

- [ ] **Step 2: `./mvnw -q test` still green after pom edits**

- [ ] **Step 3: Commit**

```bash
git add pom.xml src/main/java/module-info.java
git commit -m "build: ship AgentScope on jpackage classpath"
```

---

### Task 17: Full test + spec checklist

- [ ] **Step 1: Run all unit tests**

Run: `./mvnw test`

Expected: BUILD SUCCESS. `ImClientIT` skips if `server/dist/kserver` missing.

- [ ] **Step 2: Manual checklist (do not claim done without this)**

1. Login to a room **without** 添加 kelsy: layout identical; `@kelsy 你好` arrives on the other client.
2. 添加 kelsy + 选头像: member appears locally only; 人数 unchanged.
3. `@kelsy 你好`: other client does not get it; this client streams a reply (needs API key in `~/.kmate/kelsy/config.json`).
4. While streaming: send `普通` — other client receives it; second `@kelsy x` stays in the input with hint「秘书还在回复」.
5. `@kelsy /find 词` does not leave the room; bare `/find 词` does.
6. Re-login same `imCode`: kelsy still there; history shows `@kelsy` + final assistant text.
7. 移除 kelsy: `@kelsy` is forwarded again; knowledge pane gone.
8. Empty key: first `@kelsy` shows SYSTEM path to config; not forwarded.

- [ ] **Step 3: Commit only if Step 1 caused fixes**

---

## Self-review (against spec)

| Spec item | Task |
|---|---|
| Unadded room == old kmate | 10, 12, 14 |
| Add/remove + avatar per imCode | 5, 12 |
| `@kelsy` not forwarded | 1, 3, 10 |
| Mixed timeline + kelsy rendering | 8, 14 |
| Busy does not block peer; second mention blocked | 3, 10, 11 |
| `~/.kmate/kelsy` config/workspace | 6, 7 |
| One global secretary | 9, 13 |
| Persist in room history | 4, 10 |
| Mention = start `@kelsy` + boundary | 1 |
| Slash only with `@kelsy` | 3 |
| Missing key SYSTEM + path | 6, 10 |
| Knowledge pane default collapsed | 14, 15 |
| Close Agent on quit | 15 |
| jlink + extra classpath | 16 |
| Member count excludes kelsy | 12 |
| Empty `@kelsy` SYSTEM | 3, 10 |
| Copy `~/.kelsy/config.json` template | 6 |
| In-flight remove finishes then peer-routes | 10 |

No TBD/TODO left in tasks. Names used later (`KelsyMention.strip`, `KelsySendRouter.Kind`, `KelsyRuntime.shared/shutdown`, `boolean ChatController.send`, `RoomMember.kelsy()`, `PeerSender`) match earlier tasks.
