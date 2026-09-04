# Login Workspace Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Login page can pick the secretary workspace directory; the path is stored in `config.json` and `KelsyRuntime` uses it.

**Architecture:** Same as standalone kelsy: text field + DirectoryChooser. `ConfigLoader.save` writes `workspaceDir` without touching `model`. `KelsyPaths.withWorkspace` keeps config under `~/.kmate/kelsy/` while pointing workspace at the chosen folder. `KelsyRuntime.shared` peeks that path (re-opens if it changed).

**Tech Stack:** Java 21, JavaFX 21, JUnit 5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-03-login-workspace-picker-design.md`

---

## File map

**Modify:**

- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyPaths.java` — `withWorkspace(Path)`
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyPathsTest.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/config/ConfigLoader.java` — `save`
- `src/test/java/com/glodon/mordor/kmate/kelsy/config/ConfigLoaderTest.java`
- `src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRuntime.java` — `resolve` + `shared` uses peek workspace
- `src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRuntimeTest.java`
- `src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java` — `Input.workspaceDir`, inject `KelsyPaths`, save workspace
- `src/test/java/com/glodon/mordor/kmate/ui/login/LoginControllerTest.java`
- `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java` — field + browse
- `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css` — `.login-browse`

---

### Task 1: `KelsyPaths.withWorkspace` and `ConfigLoader.save`

**Files:**
- Modify: `KelsyPaths.java`, `KelsyPathsTest.java`, `ConfigLoader.java`, `ConfigLoaderTest.java`

- [ ] **Step 1: Failing path + save tests**

Add to `KelsyPathsTest`:

```java
    @Test
    void withWorkspaceKeepsConfig() {
        Path home = Path.of("/tmp/home");
        KelsyPaths paths = KelsyPaths.forHome(home).withWorkspace(Path.of("/data/ws"));
        assertEquals(home.resolve(".kmate/kelsy/config.json"), paths.config());
        assertEquals(Path.of("/data/ws"), paths.workspace());
        assertEquals(home.resolve(".kelsy/config.json"), paths.legacyConfig());
    }
```

Add to `ConfigLoaderTest`:

```java
    @Test
    void saveWorkspaceKeepsApiKey() throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp.resolve("cfg"));
        Files.createDirectories(paths.config().getParent());
        Files.writeString(paths.config(), """
                {"model":{"apiKey":"sk-keep","baseUrl":"https://x","modelName":"M"},"workspaceDir":"old"}
                """);
        KelsyConfig current = ConfigLoader.peek(paths);
        ConfigLoader.save(paths, new KelsyConfig(
                current.model(), "/tmp/custom-ws", "ada", current.selfAvatarPath(), current.kelsyAvatarPath()));
        KelsyConfig again = ConfigLoader.peek(paths);
        assertEquals("sk-keep", again.model().apiKey());
        assertEquals("/tmp/custom-ws", again.workspaceDir());
        assertEquals("ada", again.lastUsername());
    }
```

- [ ] **Step 2: Run — expect FAIL**

`./mvnw -q test -Dtest=KelsyPathsTest,ConfigLoaderTest`

Expected: FAIL compile (`withWorkspace` / `save` missing).

- [ ] **Step 3: Implement**

`KelsyPaths`:

```java
    public KelsyPaths withWorkspace(Path workspace) {
        return new KelsyPaths(config, workspace, legacyConfig);
    }
```

`ConfigLoader.save` (mirror standalone kelsy; pretty JSON; `restrictToOwner`):

```java
    public static void save(KelsyPaths paths, KelsyConfig config) {
        try {
            Files.createDirectories(paths.config().getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(paths.config().toFile(), config);
            restrictToOwner(paths.config());
        } catch (IOException e) {
            throw new UncheckedIOException("无法写入配置：" + paths.config(), e);
        }
    }
```

Need `com.fasterxml.jackson.databind.ObjectMapper` — already used in this class.

- [ ] **Step 4: Re-run tests — PASS**

`./mvnw -q test -Dtest=KelsyPathsTest,ConfigLoaderTest`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/KelsyPaths.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsyPathsTest.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/config/ConfigLoader.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/config/ConfigLoaderTest.java
git commit -m "$(cat <<'EOF'
feat: save kelsy workspaceDir without dropping api key

EOF
)"
```

---

### Task 2: Runtime uses peeked workspace

**Files:**
- Modify: `KelsyRuntime.java`, `KelsyRuntimeTest.java`

- [ ] **Step 1: Failing runtime test**

```java
    @Test
    void openUsesWorkspaceFromConfig() throws Exception {
        KelsyPaths home = KelsyPaths.forHome(tmp);
        Files.createDirectories(home.config().getParent());
        Path custom = tmp.resolve("custom-ws");
        Files.writeString(home.config(),
                "{\"model\":{\"apiKey\":\"\"},\"workspaceDir\":\""
                        + custom.toAbsolutePath() + "\"}");
        KelsyPaths resolved = KelsyRuntime.resolve(home);
        assertEquals(custom.toAbsolutePath().normalize(),
                resolved.workspace().toAbsolutePath().normalize());
        assertEquals(home.config(), resolved.config());
        KelsyRuntime runtime = KelsyRuntime.open(resolved, "alice", p -> null);
        assertTrue(Files.isDirectory(custom));
        runtime.close();
    }
```

`KelsyConfig.workspacePath()` does not `normalize()`; compare with `toAbsolutePath().normalize()` on both sides, or write the JSON path already absolute.

- [ ] **Step 2: Run — expect FAIL** (`resolve` missing)

`./mvnw -q test -Dtest=KelsyRuntimeTest#openUsesWorkspaceFromConfig`

- [ ] **Step 3: Implement `resolve` and `shared`**

```java
    public static KelsyPaths resolve(KelsyPaths homePaths) {
        return homePaths.withWorkspace(ConfigLoader.peek(homePaths).workspacePath());
    }

    public static synchronized KelsyRuntime shared(String username) {
        KelsyPaths paths = resolve(KelsyPaths.defaults());
        if (instance != null
                && !instance.paths.workspace().toAbsolutePath().normalize()
                .equals(paths.workspace().toAbsolutePath().normalize())) {
            shutdown();
        }
        if (instance == null) {
            instance = open(paths, username,
                    cfg -> LocalAssistantService.create(cfg, username));
        }
        return instance;
    }
```

`open` already seeds `paths.workspace()`. Do not change `open` signature.

- [ ] **Step 4: Run `KelsyRuntimeTest` — PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/KelsyRuntime.java \
        src/test/java/com/glodon/mordor/kmate/kelsy/KelsyRuntimeTest.java
git commit -m "$(cat <<'EOF'
feat: point KelsyRuntime workspace at config.json

EOF
)"
```

---

### Task 3: LoginController persists workspace

**Files:**
- Modify: `LoginController.java`, `LoginControllerTest.java`

- [ ] **Step 1: Extend `Input` and tests**

`Input` add last field `String workspaceDir`. Keep the 5-arg convenience ctor as `this(..., false, "")` or add 7-arg and update all call sites.

Existing tests that only check validate: pass `""` as workspace — still OK.

Add (inject temp `KelsyPaths` so `save` does not write `~/.kmate/kelsy/config.json`):

```java
    @Test
    void validateIgnoresBlankWorkspace() {
        LoginController.Input online = new LoginController.Input(
                "127.0.0.1", "3000", "ABC", "pw", "ada", false, "");
        assertInstanceOf(LoginController.Result.Ok.class, controller.validate(online));
        LoginController.Input offline = new LoginController.Input(
                "", "", "", "", "ada", true, "");
        assertInstanceOf(LoginController.Result.Ok.class, controller.validate(offline));
    }

    @Test
    void saveWritesWorkspaceAndKeepsKey(@TempDir Path tmp) throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        Files.createDirectories(paths.config().getParent());
        Files.writeString(paths.config(), "{\"model\":{\"apiKey\":\"sk-x\"},\"workspaceDir\":\"old\"}");
        Memory prefs via existing SaveLastLoginService is OK if we only assert config file —
        LoginController c = new LoginController(new SaveLastLoginService(), paths);
        c.save(new LoginController.Input(
                "127.0.0.1", "3000", "R", "pw", "ada", false, tmp.resolve("ws").toString()));
        KelsyConfig again = ConfigLoader.peek(paths);
        assertEquals("sk-x", again.model().apiKey());
        assertTrue(again.workspaceDir().contains("ws"));
        assertEquals("ada", again.lastUsername());
    }
```

`SaveLastLoginService` in that test still writes real Preferences (username). Acceptable; do not invent a fake prefs unless already easy. Prefer: only assert `config.json`.

Blank workspace on save → `KelsyConfig.DEFAULT_WORKSPACE_DIR`.

- [ ] **Step 2: Run — FAIL compile**

`./mvnw -q test -Dtest=LoginControllerTest`

- [ ] **Step 3: Implement**

```java
    private final KelsyPaths kelsyPaths;

    public LoginController(SaveLastLoginService saveService) {
        this(saveService, KelsyPaths.defaults());
    }

    LoginController(SaveLastLoginService saveService, KelsyPaths kelsyPaths) {
        this.saveService = saveService;
        this.kelsyPaths = kelsyPaths;
    }

    public String workspaceDir() {
        return ConfigLoader.peek(kelsyPaths).workspaceDir();
    }

    public void save(Input input) {
        // existing Preferences save first
        String dir = input.workspaceDir() == null ? "" : input.workspaceDir().strip();
        if (dir.isEmpty()) {
            dir = KelsyConfig.DEFAULT_WORKSPACE_DIR;
        }
        ConfigLoader.ensureAndHasApiKey(kelsyPaths);
        KelsyConfig current = ConfigLoader.peek(kelsyPaths);
        ConfigLoader.save(kelsyPaths, new KelsyConfig(
                current.model(), dir, input.username(),
                current.selfAvatarPath(), current.kelsyAvatarPath()));
    }
```

`validate` unchanged except `Input` arity. Do not fail on blank workspace.

- [ ] **Step 4: Tests PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java \
        src/test/java/com/glodon/mordor/kmate/ui/login/LoginControllerTest.java
git commit -m "$(cat <<'EOF'
feat: persist login workspace dir into kelsy config

EOF
)"
```

---

### Task 4: LoginPane field + browse

**Files:**
- Modify: `LoginPane.java`, `login.css`

No JavaFX test for the chooser.

- [ ] **Step 1: CSS**

Append (copy standalone kelsy):

```css
.login-browse {
    -fx-background-color: #EEF3FB;
    -fx-text-fill: #1565C0;
    -fx-background-radius: 4 4 4 4;
    -fx-padding: 6 12 6 12;
    -fx-font-size: 11px;
    -fx-cursor: hand;
}
```

- [ ] **Step 2: Pane**

`TextField workspace` + `Button browse = new Button("浏览")`.

Row after `profileRow`, before `offline`:

```java
        VBox workspaceBox = fieldBox("工作区路径", workspace, KelsyConfig.DEFAULT_WORKSPACE_DIR);
        HBox.setHgrow(workspaceBox, Priority.ALWAYS);
        browse.getStyleClass().add("login-browse");
        browse.setOnAction(e -> pickWorkspace());
        HBox workspaceRow = new HBox(8, workspaceBox, browse);
        workspaceRow.setAlignment(Pos.BOTTOM_LEFT);
        workspaceRow.setMaxWidth(Double.MAX_VALUE);
```

Add `workspaceRow` into the `fields` VBox.

Prefill: `workspace.setText(controller.workspaceDir());`

`pickWorkspace`:

```java
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择工作区");
        File start = new File(expandHome(workspace.getText()));
        if (start.isDirectory()) {
            chooser.setInitialDirectory(start);
        }
        File picked = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (picked != null) {
            workspace.setText(picked.getAbsolutePath());
        }
```

`expandHome`: if text is blank, expand `KelsyConfig.DEFAULT_WORKSPACE_DIR`; if starts with `~`, splice `user.home`.

`handleConnect` Input must pass `workspace.getText()`.

`controller.save(input)` can throw `UncheckedIOException` — catch around save (offline path and success handshake) and `showError` the message (`无法写入配置` if message blank). Do **not** start handshake if save fails. On the online path, save currently happens **after** handshake success; spec says write on 连接/进入. **Write before handshake** (and before `AppState.offline`) so a failed write never enters chat. If handshake then fails, config already has the new path — acceptable (same as kelsy enter).

Order for online:
1. validate
2. save (workspace + prefs) — on failure show error, return
3. connect
4. on success enter chat (prefs already saved; do not save twice, or save again is fine)

- [ ] **Step 3: Headless tests**

`./mvnw -q test -Dtest=LoginControllerTest,KelsyRuntimeTest,ConfigLoaderTest,KelsyPathsTest`

Then `./mvnw -q test`

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java \
        src/main/resources/com/glodon/mordor/kmate/ui/login/login.css
git commit -m "$(cat <<'EOF'
feat: add workspace path and browse on login

EOF
)"
```

---

## Spec coverage

| Spec | Task |
|---|---|
| Text + 浏览, default prompt | 4 |
| Prefill / save `config.json` `workspaceDir`, keep model | 1, 3, 4 |
| Empty → default | 3, 4 |
| Seed missing dir | 2 (`open`) |
| Offline + online | 3, 4 |
| `KelsyRuntime` uses peek workspace; config path unchanged | 1, 2 |
| `shared` reopen if path changed | 2 |
| Tests listed | 1–3 |
| No DirectoryChooser test | 4 |

## Placeholder scan

No TBD. `Input` gains `workspaceDir`. `ConfigLoader.save(KelsyPaths, KelsyConfig)`. `KelsyPaths.withWorkspace`. `KelsyRuntime.resolve`.
