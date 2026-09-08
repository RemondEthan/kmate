# 登录卡接入大模型配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在登录卡右下角加 "⚙ 接入大模型" 按钮，点击弹模态对话框配置服务商 / 模型名 / API Key，写到 `~/.kmate/kelsy/config.json`。

**Architecture:** Provider 元数据外移到 classpath `providers.json`。`ModelFactory` 重构成查表 + 极简 formatter switch。新增 `ModelConfigDialog` 用 JavaFX `Dialog<ModelSettings>`。`LoginPane` 加按钮接线。

**Tech Stack:** Java 21、JavaFX 21、Jackson（已有）。无新增依赖。

**Spec:** `docs/superpowers/specs/2026-09-04-login-card-llm-config-design.md`

**测试策略：** 用户手工验证，本计划不写新自动测试；每个任务跑 `./mvnw test` 确认现有测试未破。

## Global Constraints

- **包路径**：新增 `kelsy.provider`（record + catalog）+ `ui.login.ModelConfigDialog`；其它包不动
- **依赖方向**：`ui.login → kelsy.{provider, service} → kelsy.config`；`kelsy.provider` 是叶子
- **不动范围**：不修改 `ConfigLoader`、`KelsyConfig`、`KelsyRuntime`、`ImClient`、`CryptoService`、`LocalAssistantService`、聊天模块；不引入新依赖
- **Jackson 反射**：新增的 `kelsy.provider` 包需在 `module-info.java` 加 `opens ... to com.fasterxml.jackson.databind`（参考现有 `kelsy.config` 的写法）
- **资源编码**：`providers.json` 必须 UTF-8，中文字段直接写
- **文件权限**：`ConfigLoader.save` 已经 `chmod 600`，本计划不动这块
- **Java 模块**：复用 `com.glodon.mordor.kmate`，新包无需 `exports`（内部用），但需 `opens` 给 Jackson
- **每个 Task 独立 commit**；失败可独立 `git revert`
- **中文注释 / 日志**：复用项目约定

---

## File map

**Create:**

- `src/main/resources/com/glodon/mordor/kmate/kelsy/providers.json` — 4 家服务商元数据
- `src/main/java/com/glodon/mordor/kmate/kelsy/provider/ProviderSpec.java` — record
- `src/main/java/com/glodon/mordor/kmate/kelsy/provider/ProviderCatalog.java` — classpath 加载 + 缓存
- `src/main/java/com/glodon/mordor/kmate/ui/login/ModelConfigDialog.java` — 模态对话框

**Modify:**

- `src/main/java/module-info.java` — 加 `opens ... kelsy.provider` 给 Jackson
- `src/main/java/com/glodon/mordor/kmate/kelsy/service/ModelFactory.java` — 删硬编码 URL，改查 catalog
- `src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java` — 加 `kelsyPaths()` getter
- `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java` — 加按钮 + 接线
- `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css` — `.login-model-config` 样式

**Do not modify:** `ConfigLoader`、`KelsyConfig`、`KelsyRuntime`、`ImClient`、`CryptoService`、聊天模块。

---

### Task 1: providers.json + ProviderSpec

**Files:**
- Create: `src/main/resources/com/glodon/mordor/kmate/kelsy/providers.json`
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/provider/ProviderSpec.java`

- [ ] **Step 1: 创建 providers.json**

在 `src/main/resources/com/glodon/mordor/kmate/kelsy/` 下新建目录（如果不存在），写入 `providers.json`：

```json
[
  {"id":"minimax",  "displayName":"MiniMax",         "baseUrl":"https://api.minimaxi.com/v1",          "defaultModelName":"MiniMax-M3"},
  {"id":"kimi",     "displayName":"月之暗面 Kimi",    "baseUrl":"https://api.moonshot.cn/v1",            "defaultModelName":"kimi-k2.5"},
  {"id":"glm",      "displayName":"智谱 GLM",        "baseUrl":"https://open.bigmodel.cn/api/paas/v4", "defaultModelName":"glm-4.7"},
  {"id":"deepseek", "displayName":"DeepSeek",        "baseUrl":"https://api.deepseek.com",              "defaultModelName":"deepseek-chat"}
]
```

注意中文字符：保存为 UTF-8（编辑器默认即可）。

- [ ] **Step 2: 创建 ProviderSpec.java**

`src/main/java/com/glodon/mordor/kmate/kelsy/provider/ProviderSpec.java`：

```java
package com.glodon.mordor.kmate.kelsy.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 一家大模型服务商的元数据。来自 classpath providers.json。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderSpec(
        String id,
        String displayName,
        String baseUrl,
        String defaultModelName) {
}
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/com/glodon/mordor/kmate/kelsy/providers.json \
        src/main/java/com/glodon/mordor/kmate/kelsy/provider/ProviderSpec.java
git commit -m "feat: add provider catalog resource and spec record"
```

---

### Task 2: ProviderCatalog（classpath 加载 + 缓存）

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/kelsy/provider/ProviderCatalog.java`
- Modify: `src/main/java/module-info.java` — 加 `opens ... kelsy.provider` 给 Jackson

**Interfaces produced:**
- `ProviderCatalog.all()` → `List<ProviderSpec>`（空列表 = 加载失败）
- `ProviderCatalog.findById(String)` → `Optional<ProviderSpec>`

- [ ] **Step 1: 加 module-info 的 open**

修改 `src/main/java/module-info.java`：在已有的 `opens com.glodon.mordor.kmate.kelsy.config to com.fasterxml.jackson.databind;` 下面加一行：

```
    opens com.glodon.mordor.kmate.kelsy.provider to com.fasterxml.jackson.databind;
```

最终 `module-info.java` 第 39-40 行：

```
    opens com.glodon.mordor.kmate.kelsy.config to com.fasterxml.jackson.databind;
    opens com.glodon.mordor.kmate.kelsy.provider to com.fasterxml.jackson.databind;
```

- [ ] **Step 2: 创建 ProviderCatalog.java**

`src/main/java/com/glodon/mordor/kmate/kelsy/provider/ProviderCatalog.java`：

```java
package com.glodon.mordor.kmate.kelsy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** 从 classpath providers.json 加载的提供商清单；进程内缓存，失败返回空列表。 */
public final class ProviderCatalog {

    private static final String RESOURCE = "/com/mordor/kmate/kelsy/providers.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile List<ProviderSpec> cached;

    private ProviderCatalog() {
    }

    public static List<ProviderSpec> all() {
        List<ProviderSpec> snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (ProviderCatalog.class) {
            if (cached == null) {
                cached = loadFromClasspath();
            }
            return cached;
        }
    }

    public static Optional<ProviderSpec> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String needle = id.strip();
        return all().stream()
                .filter(p -> p.id() != null && p.id().equalsIgnoreCase(needle))
                .findFirst();
    }

    private static List<ProviderSpec> loadFromClasspath() {
        var url = ProviderCatalog.class.getResource(RESOURCE);
        if (url == null) {
            return List.of();
        }
        try (var in = url.openStream()) {
            return MAPPER.readValue(in, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, ProviderSpec.class));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
```

- [ ] **Step 3: 编译**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 跑全测回归**

Run: `./mvnw test`
Expected: BUILD SUCCESS（现有 `ConfigLoaderTest`、`LoginControllerTest` 等不破）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/module-info.java \
        src/main/java/com/glodon/mordor/kmate/kelsy/provider/ProviderCatalog.java
git commit -m "feat: load provider catalog from classpath with cache"
```

---

### Task 3: ModelFactory 重构

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/kelsy/service/ModelFactory.java`

**目标**：删 switch 里硬编码的 4 家 baseUrl/modelName；改成查 `ProviderCatalog`。formatter 仍由 4 家 switch 选。

- [ ] **Step 1: 替换 resolve() 和新增 formatterFor / firstNonBlank**

将 `src/main/java/com/glodon/mordor/kmate/kelsy/service/ModelFactory.java` 整个 `resolve` 方法及其辅助方法替换为：

```java
package com.glodon.mordor.kmate.kelsy.service;

import com.glodon.mordor.kmate.kelsy.config.KelsyConfig.ModelSettings;
import com.glodon.mordor.kmate.kelsy.provider.ProviderCatalog;
import com.glodon.mordor.kmate.kelsy.provider.ProviderSpec;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;
import io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIBaseFormatter;

public final class ModelFactory {

    public record Resolved(
            String provider,
            String apiKey,
            String baseUrl,
            String modelName,
            OpenAIBaseFormatter formatter) {
    }

    private ModelFactory() {}

    public static Resolved resolve(ModelSettings settings) {
        String provider = settings.provider() == null || settings.provider().isBlank()
                ? "minimax"
                : settings.provider().strip().toLowerCase();
        String apiKey = settings.apiKey();
        ProviderSpec spec = ProviderCatalog.findById(provider).orElse(null);
        String baseUrl = firstNonBlank(settings.baseUrl(),
                spec != null ? spec.baseUrl() : null);
        String modelName = firstNonBlank(settings.modelName(),
                spec != null ? spec.defaultModelName() : null);
        OpenAIBaseFormatter formatter = formatterFor(provider);
        if (formatter == null) {
            throw new IllegalArgumentException(
                    "未知 model.provider：" + provider
                            + "。合法值：minimax, kimi, glm, deepseek");
        }
        return new Resolved(provider, apiKey, baseUrl, modelName, formatter);
    }

    public static Model create(ModelSettings settings) {
        Resolved r = resolve(settings);
        return OpenAIChatModel.builder()
                .apiKey(r.apiKey())
                .baseUrl(r.baseUrl())
                .modelName(r.modelName())
                .formatter(r.formatter())
                .stream(true)
                .build();
    }

    private static OpenAIBaseFormatter formatterFor(String provider) {
        return switch (provider) {
            case "minimax"  -> new MiniMaxFormatter();
            case "kimi"     -> new KimiFormatter();
            case "glm"      -> new GLMFormatter();
            case "deepseek" -> new DeepSeekFormatter();
            default         -> null;
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
```

要点：
- 删除原来的 `resolved(...)` 私有方法、4 个硬编码 URL 字面量、`case "minimax" -> resolved(provider, apiKey, settings, "https://api.minimaxi.com/v1", ...)` 等分支。
- 引入 `import com.glodon.mordor.kmate.kelsy.provider.ProviderCatalog;` 和 `ProviderSpec`。
- 异常文本保持 "未知 model.provider：" + 合法值列表，和原行为一致。

- [ ] **Step 2: 编译**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 跑全测回归**

Run: `./mvnw test`
Expected: BUILD SUCCESS（无现有 `ModelFactoryTest`，确认 `ConfigLoaderTest` / `LoginControllerTest` 不破）

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/glodon/mordor/kmate/kelsy/service/ModelFactory.java
git commit -m "refactor: drive ModelFactory baseUrl/modelName from provider catalog"
```

---

### Task 4: LoginController 加 kelsyPaths() getter

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java`

**目标**：让 `LoginPane` 拿到与 controller 一致的 `KelsyPaths`，传给 dialog（避免在 `LoginPane` 里再 `KelsyPaths.defaults()`，与 workspace 逻辑保持单一来源）。

- [ ] **Step 1: 加 getter**

在 `src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java` 的 `saveAvatarPath` 方法之后（文件末尾）追加：

```java
    public KelsyPaths kelsyPaths() {
        return kelsyPaths;
    }
```

不需要额外 import：`KelsyPaths` 已经在文件顶部 import 过。

- [ ] **Step 2: 编译**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 跑全测回归**

Run: `./mvnw test`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java
git commit -m "feat: expose LoginController.kelsyPaths() for dialog reuse"
```

---

### Task 5: ModelConfigDialog

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ui/login/ModelConfigDialog.java`

**Interfaces:**
- `public static Optional<KelsyConfig.ModelSettings> show(Window owner, KelsyPaths paths)`
- 取消/关闭 → `Optional.empty()`
- 保存成功 → `Optional.of(ModelSettings)`
- 保存失败（IOException）→ Dialog 不关闭，错误条红字

- [ ] **Step 1: 创建文件骨架**

`src/main/java/com/glodon/mordor/kmate/ui/login/ModelConfigDialog.java`：

```java
package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.kelsy.KelsyPaths;
import com.glodon.mordor.kmate.kelsy.config.ConfigLoader;
import com.glodon.mordor.kmate.kelsy.config.KelsyConfig;
import com.glodon.mordor.kmate.kelsy.provider.ProviderCatalog;
import com.glodon.mordor.kmate.kelsy.provider.ProviderSpec;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/** 登录卡点出的"接入大模型"配置对话框。写 ~/.kmate/kelsy/config.json 的 model 字段。 */
public final class ModelConfigDialog {

    private ModelConfigDialog() {}

    public static Optional<KelsyConfig.ModelSettings> show(Window owner, KelsyPaths paths) {
        Dialog<KelsyConfig.ModelSettings> dialog = new Dialog<>();
        dialog.setTitle("接入大模型");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getStylesheets().add(
                ModelConfigDialog.class.getResource("login.css").toExternalForm());

        ButtonType saveType = new ButtonType("保存", ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        List<ProviderSpec> providers = ProviderCatalog.all();
        boolean catalogFailed = providers.isEmpty();

        ComboBox<ProviderSpec> providerCombo = new ComboBox<>();
        providerCombo.getItems().setAll(providers);
        providerCombo.setMaxWidth(Double.MAX_VALUE);
        providerCombo.setCellFactory(p -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ProviderSpec s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.displayName());
            }
        });
        providerCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ProviderSpec s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.displayName());
            }
        });

        TextField modelNameField = field();
        modelNameField.setPromptText("模型名（如 MiniMax-M3）");

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPromptText("API Key");
        apiKeyField.getStyleClass().add("login-field");
        apiKeyField.setMaxWidth(Double.MAX_VALUE);

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("login-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        errorLabel.setWrapText(true);

        KelsyConfig current = ConfigLoader.peek(paths);
        KelsyConfig.ModelSettings model = current.model() != null
                ? current.model()
                : new KelsyConfig.ModelSettings(null, null, null, null);

        ProviderSpec initialSpec = providers.stream()
                .filter(p -> p.id() != null
                        && p.id().equalsIgnoreCase(
                                model.provider() == null ? "" : model.provider()))
                .findFirst()
                .orElse(providers.isEmpty() ? null : providers.get(0));
        if (initialSpec != null) {
            providerCombo.setValue(initialSpec);
            modelNameField.setText(model.modelName() != null && !model.modelName().isBlank()
                    ? model.modelName()
                    : initialSpec.defaultModelName());
            apiKeyField.setText(model.apiKey() == null ? "" : model.apiKey());
        }

        providerCombo.setOnAction(e -> {
            ProviderSpec s = providerCombo.getValue();
            if (s != null) {
                modelNameField.setText(s.defaultModelName());
                apiKeyField.clear();
                apiKeyField.requestFocus();
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setMaxWidth(Double.MAX_VALUE);
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(70);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);
        addRow(grid, 0, "服务商", providerCombo);
        addRow(grid, 1, "模型名", modelNameField);
        addRow(grid, 2, "API Key", apiKeyField);

        VBox content = new VBox(10, errorLabel, grid);
        content.setMaxWidth(Double.MAX_VALUE);
        content.setFillWidth(true);
        content.setPadding(new Insets(4, 0, 4, 0));
        dialog.getDialogPane().setContent(content);

        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
        Runnable refreshValidity = () -> {
            boolean ok = !catalogFailed
                    && providerCombo.getValue() != null
                    && !modelNameField.getText().isBlank()
                    && !apiKeyField.getText().isBlank();
            saveBtn.setDisable(!ok);
        };
        modelNameField.textProperty().addListener((o, a, b) -> refreshValidity.run());
        apiKeyField.textProperty().addListener((o, a, b) -> refreshValidity.run());
        providerCombo.valueProperty().addListener((o, a, b) -> refreshValidity.run());
        refreshValidity.run();

        if (catalogFailed) {
            providerCombo.setDisable(true);
            modelNameField.setDisable(true);
            apiKeyField.setDisable(true);
            errorLabel.setText("提供商清单加载失败");
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }

        saveBtn.addEventFilter(ActionEvent.ACTION, event -> {
            ProviderSpec spec = providerCombo.getValue();
            if (spec == null) {
                event.consume();
                return;
            }
            String apiKey = apiKeyField.getText();
            String modelName = modelNameField.getText().strip();
            try {
                KelsyConfig next = new KelsyConfig(
                        new KelsyConfig.ModelSettings(spec.id(), apiKey, spec.baseUrl(), modelName),
                        current.workspaceDir(),
                        current.lastUsername(),
                        current.selfAvatarPath(),
                        current.kelsyAvatarPath());
                ConfigLoader.save(paths, next);
                dialog.setResult(new KelsyConfig.ModelSettings(
                        spec.id(), apiKey, spec.baseUrl(), modelName));
            } catch (UncheckedIOException ex) {
                event.consume();
                showError(errorLabel, "无法写入配置：" + paths.config());
            }
        });

        return dialog.showAndWait();
    }

    private static TextField field() {
        TextField tf = new TextField();
        tf.getStyleClass().add("login-field");
        tf.setMaxWidth(Double.MAX_VALUE);
        return tf;
    }

    private static void addRow(GridPane grid, int row, String labelText, Control field) {
        Label l = new Label(labelText);
        l.getStyleClass().add("login-field-label");
        l.setMaxWidth(Double.MAX_VALUE);
        grid.add(l, 0, row);
        grid.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
        GridPane.setFillWidth(field, true);
    }

    private static void showError(Label label, String message) {
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
    }
}
```

- [ ] **Step 2: 编译**

Run: `./mvnw compile`
Expected: BUILD SUCCESS（注意 `LoginPane` 还没调用，所以即使签名错编译也能过）

- [ ] **Step 3: 跑全测回归**

Run: `./mvnw test`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/ModelConfigDialog.java
git commit -m "feat: add ModelConfigDialog for login card"
```

---

### Task 6: LoginPane 加按钮 + CSS

**Files:**
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java`
- Modify: `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css`

- [ ] **Step 1: 在 LoginPane 加按钮字段**

打开 `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java`，在 `private final Button connect = new Button("连 接");` 那一行（第 55 行附近）上方，加：

```java
    private final Button modelConfig = new Button("⚙ 接入大模型");
```

- [ ] **Step 2: 在 LoginPane 构造器内挂载按钮**

在 `connect.setOnAction(e -> handleConnect());` 这一行上方，加：

```java
        modelConfig.getStyleClass().add("login-model-config");
        modelConfig.setMaxWidth(Double.MAX_VALUE);
        modelConfig.setOnAction(e -> openModelConfig());
```

- [ ] **Step 3: 把按钮塞进 card 布局**

把 `VBox card = new VBox(10, cardTop, fields, offline, connect, cardBottom);` 这一行改为：

```java
        VBox card = new VBox(10, cardTop, fields, offline, modelConfig, connect, cardBottom);
```

（按钮位于 `offline` 与 `connect` 之间。）

- [ ] **Step 4: 加 openModelConfig 方法**

在 `private void handleConnect()` 上方，加：

```java
    private void openModelConfig() {
        Window owner = getScene() == null ? null : getScene().getWindow();
        ModelConfigDialog.show(owner, controller.kelsyPaths());
    }
```

并在文件顶部 imports 区域加一行（`Window` 是 `javafx.stage.Window`）：

```java
import javafx.stage.Window;
```

确认已有的 `import com.glodon.mordor.kmate.kelsy.config.KelsyConfig;` 仍在（`KelsyConfig` 之前 import 用于 `DEFAULT_WORKSPACE_DIR`，dialog 不需要这个 import，但保留无副作用）。

- [ ] **Step 5: 加 CSS**

打开 `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css`，在文件末尾追加：

```css
/* "接入大模型"次要按钮：白底、浅蓝边 */
.login-model-config {
    -fx-background-color: #F5F8FF;
    -fx-text-fill: #1E6FFF;
    -fx-border-color: #C0D3E8;
    -fx-border-radius: 4 4 4 4;
    -fx-background-radius: 4 4 4 4;
    -fx-padding: 6 12 6 12;
    -fx-font-size: 11px;
    -fx-cursor: hand;
    -fx-alignment: center;
}

.login-model-config:hover {
    -fx-background-color: #E8F0FE;
}

.login-model-config:pressed {
    -fx-background-color: #DCE7FA;
}

.login-model-config:disabled {
    -fx-opacity: 0.55;
}
```

- [ ] **Step 6: 编译**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: 跑全测回归**

Run: `./mvnw test`
Expected: BUILD SUCCESS

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java \
        src/main/resources/com/glodon/mordor/kmate/ui/login/login.css
git commit -m "feat: wire 接入大模型 button on login card"
```

---

### Task 7: 端到端手工验证

**Files:** 无（纯手测）

按 spec 第 7 节"测试"的 6 条逐一过：

- [ ] **Step 1: 首次启动场景**

删除 `~/.kmate/kelsy/config.json`（如果存在）→ 启动 kmate → 点 "⚙ 接入大模型" → 验证：provider 默认第一项 MiniMax、模型名 "MiniMax-M3"、API Key 空 → 填一个测试 key → 点保存 → 重新点 "⚙" → 三个字段都正确回显。

- [ ] **Step 2: 切服务商场景**

在 Dialog 里把服务商切到 kimi → 验证：模型名变 "kimi-k2.5"、API Key 清空、焦点跳到 API Key → 填 key → 保存 → 关闭 → 重新打开 → 看到 kimi 和新 key。

- [ ] **Step 3: config.json 解析失败场景**

备份 `~/.kmate/kelsy/config.json`，写入一段无效 JSON（`{bad json`）→ 启动 kmate → 点 "⚙" → 验证 Dialog 显示 provider 默认第一项、模型名空、API Key 空（不报错、不崩）。还原备份。

- [ ] **Step 4: 端到端秘书调用**

走完整流程：填服务器 IP/端口/IM_CODE/口令/用户名 → 点 "⚙" 配置一个有效的 model → 点 "连接" → 进聊天 → @秘书 → 验证秘书能正常响应（这是 `ModelFactory` 重构后还能正常用的关键回归）。

- [ ] **Step 5: 文件权限**

验证 `~/.kmate/kelsy/config.json` 权限仍是 `rw-------`（`ls -la ~/.kmate/kelsy/config.json` 显示 `-rw-------`）。

- [ ] **Step 6: 打包验证**

Run: `./mvnw -Pmac clean package`（macOS）/ 对应平台 profile
Expected: BUILD SUCCESS（GUI 改动需要视觉验证，但打包不能挂）

- [ ] **Step 7: 提交（可选）**

如果发现手测过程中需要小幅修复（如 CSS 微调、布局间距），直接 commit 在 `fix:` prefix 上，无须新 spec。

---

## Self-review notes

- **Spec 覆盖：** spec 第 3 节"目标"全部由 T1-T6 覆盖；"不在范围内"的 YAGNI 列表没有 task（符合预期）；错误处理 4 行表分别由 T2（catalog 失败 → 空列表 → Dialog 顶部红条）、T5（peek 失败 → 容错）、T5（save 失败 → event.consume + 红条）、T3（resolve 失败 → IllegalArgumentException）覆盖。
- **类型一致：** `ProviderSpec` record 字段名 `id/displayName/baseUrl/defaultModelName` 在 T1 定义、T2 读、T3 用、T5 Dialog 引用，全文一致。`KelsyConfig.ModelSettings(provider, apiKey, baseUrl, modelName)` 在 spec 中定义，T5 Dialog 构造时用 4-arg，T3 也读 `settings.baseUrl()/modelName()` 一致。`ProviderCatalog.all()` 返回 `List<ProviderSpec>`，`findById(String)` 返回 `Optional<ProviderSpec>` 在 T2 定义、T3 调用一致。
- **占位符扫描：** 无 TBD/TODO/占位代码。
- **任务大小：** 6 个任务，每个 2-8 个步骤，每个步骤 2-5 分钟操作量。T5（Dialog）最大但单文件单交付物，按钮接线放 T6 单独成任务以让 reviewer 可以单独卡 T5 评审。
