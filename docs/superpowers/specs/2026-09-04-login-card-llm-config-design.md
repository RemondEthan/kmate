# 登录卡接入大模型配置 — Design Spec

Date: 2026-09-04
Status: Approved (brainstorming)

## 背景

kmate 的智能秘书 (`kelsy`) 已经能跑，但用户要切换大模型服务商或填 API Key，必须手编 `~/.kmate/kelsy/config.json` 里的 `model` 字段。对非开发同学不友好，也无法"配一次就用"。

后端已就绪：
- `KelsyConfig.ModelSettings(provider, apiKey, baseUrl, modelName)` 是 record
- `ConfigLoader.peek/save/ensureAndHasApiKey` 读写同一份 JSON（POSIX `rw-------`）
- `ModelFactory.resolve` 已经能按 provider 选 formatter（4 家：MiniMaxFormatter / KimiFormatter / GLMFormatter / DeepSeekFormatter）
- `LoginController.save` 已经会在落盘时保留 `current.model()`

唯一缺口：UI。本 spec 加这个 UI，并顺带把硬编码在 Java switch 里的 baseUrl / modelName 拆到 classpath JSON，让"加新服务商"只需改资源文件、不必动 Java。

## 目标

- 登录卡右下角加按钮 "⚙ 接入大模型"，点击弹模态 `Dialog`。
- Dialog 暴露 3 个字段：
  - **服务商**：`ComboBox<ProviderSpec>`，从 classpath `providers.json` 读 4 家
  - **模型名**：`TextField`，随服务商切换自动预填该服务商的默认模型名，用户可改
  - **API Key**：`PasswordField`，必填，预填当前 `config.json` 里的值
- 保存：写入 `~/.kmate/kelsy/config.json` 的 `model` 字段（保留 `workspaceDir / lastUsername / selfAvatarPath / kelsyAvatarPath`）。
- 取消：写盘动作不发生。
- 登录主流程不动。`LoginController.save` 会在用户后续点 "连接" 时重读 `config.json`，自然拿到刚保存的 model 字段。
- `ModelFactory` 重构：baseUrl / modelName 改查 `ProviderCatalog`；formatter 仍由 provider id 在更小的 switch 里选（4 个 formatter 类是 Java 代码，不拆）。
- 4 家初始服务商：minimax、kimi、glm、deepseek。

## 不在范围内

- 多 profile / 多 key 切换、API key 可见性切换按钮（👁）。
- 运行时热重载（保存后立刻让秘书用新 key，下次重启才生效）。
- provider 模糊搜索 / 收藏。
- 从环境变量 / Keychain 读 API key。
- 写入前加密 API key（POSIX `chmod 600` 已够）。
- provider 自定义 UI（让终端用户写新服务商）；当前是开发者改 JSON 重打包扩展。
- 改 `server/`、改 `chat/` 历史房间路径。
- 任何新的 Preferences 字段；model 仍只存在 `config.json`。

## 组件

### 新增

| 文件 | 职责 |
|---|---|
| `kelsy/provider/ProviderSpec.java` | record `(id, displayName, baseUrl, defaultModelName)`，复用 `ConfigLoader` 已用的 Jackson |
| `kelsy/provider/ProviderCatalog.java` | 单例缓存从 classpath `/com/glodon/mordor/kmate/kelsy/providers.json` 加载；暴露 `all()` 和 `findById(String)` |
| `ui/login/ModelConfigDialog.java` | JavaFX `Dialog<ModelSettings>`，预填、校验、写盘；返回 `Optional<ModelSettings>` |
| `resources/com/glodon/mordor/kmate/kelsy/providers.json` | 4 家提供商的 JSON |

### 修改

| 文件 | 改动 |
|---|---|
| `kelsy/service/ModelFactory.java` | 删 switch 中的硬编码 baseUrl / modelName；改成查 `ProviderCatalog.findById`，未命中回退到 `settings.baseUrl/modelName`；formatter 仍由 `formatterFor(String)` 小 switch 选 |
| `ui/login/LoginPane.java` | 加按钮 `⚙ 接入大模型`（在 `connect` 上一行），点击调用 `ModelConfigDialog.show(owner)` |
| `resources/com/glodon/mordor/kmate/ui/login/login.css` | 加 `.login-model-config` 样式（次要按钮，浅蓝边、白底） |

### providers.json 内容

```json
[
  {"id":"minimax",  "displayName":"MiniMax",         "baseUrl":"https://api.minimaxi.com/v1",          "defaultModelName":"MiniMax-M3"},
  {"id":"kimi",     "displayName":"月之暗面 Kimi",    "baseUrl":"https://api.moonshot.cn/v1",            "defaultModelName":"kimi-k2.5"},
  {"id":"glm",      "displayName":"智谱 GLM",        "baseUrl":"https://open.bigmodel.cn/api/paas/v4", "defaultModelName":"glm-4.7"},
  {"id":"deepseek", "displayName":"DeepSeek",        "baseUrl":"https://api.deepseek.com",              "defaultModelName":"deepseek-chat"}
]
```

## UI 行为

### 打开 Dialog

`ModelConfigDialog.show(Window owner)`:

1. `ProviderCatalog.all()` 拿到 4 家
2. `ConfigLoader.peek(paths)` 拿当前 `model`
3. 预填：
   - `providerCombo`：在 catalog 里按 id 找当前 provider，命不中默认选第一项
   - `modelNameField`：当前 `modelName()` 非空就用，否则当前 provider 的 `defaultModelName`
   - `apiKeyField`：当前 `apiKey()` 原样填（PasswordField 自动遮罩）
4. 切 `providerCombo`：`modelNameField.setText(spec.defaultModelName())`、`apiKeyField.clear()`、`apiKeyField.requestFocus()`

### 加载失败

- `ProviderCatalog.all()` 返回空列表（classpath 缺文件 / JSON 坏） → Dialog 顶部红条 "提供商清单加载失败"，`providerCombo` 禁用、模型名 / API Key 输入框禁用、保存按钮禁用。Dialog 仍可关闭，不阻塞登录主流程。
- `ConfigLoader.peek` 失败 → 按"空配置"处理（provider 默认第一项、其它字段空），不报错。已有 `ConfigLoader.peek` 的容错语义。

### 校验

任一字段为空 → 保存按钮禁用、错误条红字：
- "请选择服务商"
- "请填写模型名"
- "请填写 API Key"

### 保存

```
save():
    spec      = providerCombo.getValue()
    apiKey    = apiKeyField.getText()
    modelName = modelNameField.getText().strip()

    current = ConfigLoader.peek(paths)
    next    = new KelsyConfig(
                  new ModelSettings(spec.id(), apiKey, spec.baseUrl(), modelName),
                  current.workspaceDir(), current.lastUsername(),
                  current.selfAvatarPath(), current.kelsyAvatarPath())

    ConfigLoader.save(paths, next)   ← IOException → Dialog 内显示，不关闭
    return new ModelSettings(...)
```

成功 → `Dialog` 返回 `Optional.of(modelSettings)`，自动关闭。

### 取消 / 关闭

返回 `Optional.empty()`，写盘动作不发生。

## 数据流

```
启动 kmate
  → LoginPane 构造（⚙ 按钮显示，不主动拉 model 配置）
  → 用户点 ⚙ 接入大模型
       → ModelConfigDialog.show(owner)
            ├─ ProviderCatalog.all()           ← classpath providers.json（缓存命中直接返回）
            └─ ConfigLoader.peek(paths)        ← 当前 config.json
       → 用户编辑 → 保存
            └─ ConfigLoader.save(paths, next)  ← 写 ~/.kmate/kelsy/config.json
  → 用户点 "连接" / "进入"
       → LoginController.save(input)
            └─ ConfigLoader.peek + KelsyConfig(... current.model(), ...) ← 重读最新
       → KelsyRuntime.shared(username)
            └─ LocalAssistantService.create(cfg, username)
                 └─ ModelFactory.create(cfg.model())
                      └─ ModelFactory.resolve(...)
                           ├─ ProviderCatalog.findById(provider)   ← 拿 baseUrl / defaultModelName
                           └─ formatterFor(provider)              ← 拿 formatter
```

**关键不变量**：从 Dialog 保存到 `KelsyRuntime` 启动之间，`~/.kmate/kelsy/config.json` 始终是最新值。中间无缓存层污染。

## ModelFactory 重构细节

```java
public static Resolved resolve(ModelSettings settings) {
    String provider = normalizeProvider(settings.provider());
    String apiKey   = settings.apiKey();
    ProviderSpec spec = ProviderCatalog.findById(provider).orElse(null);
    String baseUrl   = firstNonBlank(settings.baseUrl(),   spec != null ? spec.baseUrl() : null);
    String modelName = firstNonBlank(settings.modelName(), spec != null ? spec.defaultModelName() : null);
    OpenAIBaseFormatter formatter = formatterFor(provider);
    if (formatter == null) {
        throw new IllegalArgumentException("未知 model.provider：" + provider
                + "。合法值：minimax, kimi, glm, deepseek");
    }
    return new Resolved(provider, apiKey, baseUrl, modelName, formatter);
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
```

**回退语义**：
- 旧 config 里有 catalog 之外的 provider id（比如手编辑过的 `zhipu-proxy`）→ `findById` 返回空、`formatterFor` 返回 null → `resolve()` 抛 `IllegalArgumentException`，维持原行为。
- catalog 有 id，但用户 config 里 `baseUrl / modelName` 非空 → 用户值优先（保留手编辑覆盖）。

## 错误处理

| 阶段 | 失败情况 | 用户感知 |
|---|---|---|
| ProviderCatalog 加载 | classpath 缺文件 / JSON 坏 | Dialog 显示红条 "提供商清单加载失败"，保存按钮禁用 |
| ConfigLoader.peek | config.json 解析失败 | 按 "空配置" 处理，Dialog 字段全空 |
| ConfigLoader.save | 写盘抛 IOException | Dialog 内显示 "无法写入配置：<路径>"，不关闭 |
| ModelFactory.resolve | provider 不在 catalog | `IllegalArgumentException`（保持现有行为） |

## 测试

不写自动测试，由手工验证：

1. 首次启动（无 config.json）→ 点 ⚙ → Dialog 字段全空 / provider 默认第一项 → 填 key → 保存 → 重新点 ⚙ → 字段都正确回显
2. 切 provider → apiKey 清空、modelName 跟着变 → 保存 → re-open 看持久化正确
3. config.json 解析失败 → Dialog 显示空字段，不崩
4. 启动脱机登录（offline=true）→ 点 ⚙ 配置 model → 点 "进入" → 进聊天 → @秘书 调用，秘书能正常响应
5. macOS / Linux 文件权限保持 `rw-------`
6. `./mvnw -Pmac clean package` 至少跑通一次（CLAUDE.md 里"GUI 改动需要视觉验证"是手测，但打包不能挂）

`./mvnw test` 仍要全绿（确认现有测试没被这次改动打破）。
