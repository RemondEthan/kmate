# kmate Package Refactor — Design Spec

Date: 2026-08-26
Status: Approved (brainstorming)

## 背景

`com.glodon.mordor.kmate` 包下平铺了 14 个类(应用入口、UI、模型、服务、平台 hook 全混在一起),不符合"工程化"的基本要求。本 spec 把代码按 **特性优先 + 共享层** 的混合策略重新组织,并在过程中引入 Controller / UI 分离、`Mate4K` 瘦身。

不在范围内:补单元测试、引入 DI / 日志框架、引入 FXML、调整 CSS 配色或布局。

## 目标目录结构

```
kmate/src/main/java/com/glodon/mordor/kmate/
├── app/
│   ├── Mate4K.java              // 仅保留 start() + main
│   ├── TrayManager.java         // 托盘初始化/菜单/图标加载
│   ├── ShortcutRegistrar.java   // ⌘W/⌘Q 与跨平台别名
│   ├── OsQuitHandlers.java      // Glass hook + Desktop quit handler
│   ├── QuitManager.java         // quit 守卫 + 异步 forceQuit
│   └── MacQuitHook.java         // 已存在,原地保留
├── ui/
│   ├── login/
│   │   ├── LoginPane.java       // 仅渲染 + 委托 controller
│   │   └── LoginController.java // prefill / validate / connect
│   └── chat/
│       ├── ChatPane.java        // BorderPane 装配
│       ├── ChatController.java  // 持有 ObservableList<Message>
│       ├── ChatHeader.java
│       ├── InputBar.java
│       ├── MessageListView.java // 订阅 controller.getMessages()
│       └── EmojiPopover.java
├── model/
│   ├── AppState.java
│   ├── Message.java
│   └── Sender.java
├── service/
│   └── SaveLastLoginService.java
└── common/                       // 预留,本次保持空
```

资源文件同步拆分,跟随所属 Java 包:

```
kmate/src/main/resources/com/glodon/mordor/kmate/
├── app/
│   ├── app.css          // :root 颜色 token + .app-bg
│   └── tray.png
└── ui/
    ├── login/
    │   └── login.css    // .login-* 全套
    └── chat/
        ├── chat.css     // .header/.bubble-*/.input-bar/.chat-bg
        └── bg-chat.png
```

## 模块边界

依赖方向自上而下:

```
app  ──►  ui  ──►  service  ──►  model
                  │
                  └──►  common
```

- `model` 是叶子,不依赖任何包内的其它内容
- `service` 仅依赖 `model`(以及 JDK / JavaFX)
- `ui.login` / `ui.chat` 依赖 `service` 与 `model`,互不依赖(数据流通过 `AppState` 在登录成功时从 `LoginPane` 传向 `ChatPane`)
- `app` 是最上层,组装所有子系统

## Controller 设计

### LoginController(`ui/login/`)

```java
public class LoginController {
    private final SaveLastLoginService saveService;

    public record Prefilled(String ip, String port, String imCode,
                            String username, String peerName) {}
    public record Input(String ip, String port, String imCode,
                        String password, String username) {}

    public sealed interface Result {
        record Ok(AppState state) implements Result {}
        record Invalid(String message) implements Result {}
    }

    public Prefilled prefill();
    public Result connect(Input input);
}
```

- `prefill()` 调一次 `saveService.get*()`,失败时使用空字符串默认
- `connect(Input)` 先做字段校验(非空 + 端口必须为数字),返回 `Invalid(msg)`;通过则 `saveService.save(...)`(密码不写),返回 `Ok(new AppState(username, saveService.getPeerName()))`

UI 通过返回值模式处理错误,**不引入 listener / callback 注册**(保持轻量,未来要异步再升级)。

### LoginPane(仅渲染)

构造器内:

1. 实例化 `LoginController(new SaveLastLoginService())`
2. 装配 UI(字段、标签、按钮,与当前一致)
3. 调 `controller.prefill()` 把 IP / 端口 / IM_CODE / 用户名填进 TextField
4. 连按钮事件:`controller.connect(input)` 拿到 `Result`,分支处理:`Ok` → `onConnect.accept(state)`;`Invalid` → 调用已有的 `showError(msg)`

### ChatController(`ui/chat/`)

```java
public class ChatController {
    private final AppState state;
    private final ObservableList<Message> messages =
            FXCollections.observableArrayList();

    public ChatController(AppState state);

    public ObservableList<Message> getMessages();
    public AppState getState();

    public void send(String content);
}
```

- 构造时调用 `bootstrapDemoMessages()` 把当前的 7 条示例数据(`m1..m7`)写入 `messages`
- `send(content)` 空文本忽略,否则追加一条 `Sender.SELF` 的 `Message`(`id = UUID.randomUUID().toString()`,`timestamp = LocalDateTime.now()`)

### MessageListView(订阅式渲染)

构造步骤:

1. 装配 ScrollPane + VBox 容器 + 背景图(`bg-chat.png` 改为相对自身 classpath)+ 滚动行为
2. 遍历 `controller.getMessages()`,为每条构造 `MessageBubble` 加入容器
3. 给 `controller.getMessages()` 挂 `ListChangeListener`,只处理 `wasAdded()` 分支,新增的逐条追加

`InputBar` 回调签名从 `Runnable` 改为 `Consumer<String>`,把当前文本传出去。`ChatPane` 把它接到 `controller::send`;**清空输入框的动作留在 `InputBar` 内部**——回调返回后调自己的 `clear()`。理由:Controller 不应持有 UI 引用,清空属于 UI 关注点。

## `app/` 子系统拆分

### `Mate4K.start()` 目标态

只做 Scene / Stage 装配与子系统启动:

```java
@Override
public void start(Stage stage) {
    StackPane root = new StackPane();
    root.getStyleClass().add("app-bg");
    root.getChildren().add(new LoginPane(state ->
            root.getChildren().setAll(new ChatPane(state))));

    Scene scene = new Scene(root, WIDTH, HEIGHT);
    scene.getStylesheets().add(
            Mate4K.class.getResource("app.css").toExternalForm());

    stage.setTitle("k-mate");
    stage.setScene(scene);
    stage.setMinWidth(480);
    stage.setMinHeight(360);
    Platform.setImplicitExit(false);
    stage.show();

    TrayManager trayManager = TrayManager.install(stage);
    QuitManager quitManager = new QuitManager(trayManager.tray(), trayManager.icon());

    ShortcutRegistrar.register(scene, stage, quitManager::quit);
    stage.setOnCloseRequest(e -> { e.consume(); quitManager.quit(); });
    OsQuitHandlers.install(quitManager::quit);
}
```

`quitManager` / `trayManager` 保持为 `start()` 内局部变量——它们只在启动期被消费,不需要作为实例字段暴露。

### TrayManager

- `public static TrayManager install(Stage stage)`:支持检测 + 图像加载 + 菜单构建 + `systemTray.add(icon)`,失败返回 `empty()`(`tray` / `icon` 都为 `null`)
- `public SystemTray tray()`、`public TrayIcon icon()`:暴露给 `QuitManager` 清理
- 内部 `loadImage()` 维持对 `/icons/tray.png` 的引用,路径不变

### ShortcutRegistrar

- `public static void register(Scene scene, Stage stage, Runnable onQuit)`
- `⌘W / Ctrl+W` → `if (stage.isShowing()) stage.hide();`(单向隐藏,不实现再按一次显示)
- `⌘Q / Ctrl+Q` → `onQuit.run()`

### OsQuitHandlers

- `public static void install(Runnable onQuit)`
- 内部委托 `MacQuitHook.install(onQuit)`(已存在,签名不变)
- 再注册 `Desktop.setQuitHandler(APP_QUIT_HANDLER)`,失败仅 `System.err.println`
- **不**接管 `Stage.onCloseRequest`(那一段留在 `Mate4K.start()` 里,因为需要 `stage` 引用)

### QuitManager

- 持有 `AtomicBoolean quitting` + 传入的 `SystemTray` / `TrayIcon`
- `public void quit()`:`compareAndSet(false, true)` 守卫,新开 `kmate-shutdown` 线程调 `forceQuit()`
- `private void forceQuit()`:另起 `kmate-tray-remove` 线程移除托盘(join 400ms),最终 `Runtime.getRuntime().halt(0)`

## CSS 拆分映射

| 现行 `styles.css` 片段 | 目标文件 |
|---|---|
| `:root { ... }` + `.app-bg` | `app/app.css` |
| `.login-root` `.login-card` `.login-field*` `.login-info` `.login-error` `.login-connect*` | `ui/login/login.css` |
| `.header*` `.chat-bg` `.bubble-*` `.input-bar*` | `ui/chat/chat.css` |
| `.message-list*`(当前 CSS 中**未定义**,沿用 JavaFX 默认) | 不迁移 |

加载方式:

- `Mate4K` 在 Scene 级加载 `app.css`
- `LoginPane` 在自己的 `getStylesheets()` 追加 `login.css`
- `ChatPane` 在自己的 `getStylesheets()` 追加 `chat.css`

`:root` 中的颜色 token 在 `app.css` 中定义,通过 Scene 级加载注入整个场景,子包样式表可照常引用。

## `module-info.java` 变更

新增的 6 个包都需要 `exports`;`opens ... to javafx.fxml` 按现有写法逐包保留(当前代码未使用 FXML,但保持现状避免打破假设):

```java
module com.glodon.mordor.kmate {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires java.desktop;
    requires java.prefs;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.materialdesign2;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;

    exports com.glodon.mordor.kmate.app;
    exports com.glodon.mordor.kmate.ui.login;
    exports com.glodon.mordor.kmate.ui.chat;
    exports com.glodon.mordor.kmate.model;
    exports com.glodon.mordor.kmate.service;
    exports com.glodon.mordor.kmate.common;

    opens com.glodon.mordor.kmate.app      to javafx.fxml;
    opens com.glodon.mordor.kmate.ui.login to javafx.fxml;
    opens com.glodon.mordor.kmate.ui.chat  to javafx.fxml;
    opens com.glodon.mordor.kmate.model    to javafx.fxml;
    opens com.glodon.mordor.kmate.service  to javafx.fxml;
    opens com.glodon.mordor.kmate.common   to javafx.fxml;
}
```

## 资源路径改写清单

- `Mate4K.class.getResource("styles.css")` → `Mate4K.class.getResource("app.css")`(resources 路径变 `app/app.css`)
- `MessageListView.applyChatBackground`:`Mate4K.class.getResource("bg-chat.png")` → `getClass().getResource("bg-chat.png")`,实际资源迁至 `ui/chat/bg-chat.png`
- `TrayManager.loadImage()`:`/icons/tray.png` 路径不变(图标是平台资源,不挂特性)

## 迁移顺序(降风险)

按顺序执行,每一步都跑 `mvn compile` 与启动验证:

1. **建包 + 搬文件**:仅改 `package` 与 `import`,不重命名、不改逻辑;`model` / `service` / `common` 先搬(零依赖),`ui.login` / `ui.chat` 接着搬,`app` 最后
2. **拆 CSS**:`styles.css` 拆 3 份,`app.css` 由 Scene 加载,`login.css` / `chat.css` 由各 Pane 追加;资源文件迁移
3. **抽 `LoginController`**:重写 `LoginPane` 为"装配 + 委托";验证 prefill / 校验 / 落盘 / 进入聊天都正常
4. **抽 `ChatController` + `MessageListView` 改为订阅**:验证示例消息渲染、滚动到底、`InputBar` 发送(SELF 消息追加,输入框清空)
5. **拆 `Mate4K`**:依次抽出 `TrayManager` / `ShortcutRegistrar` / `OsQuitHandlers` / `QuitManager`;每抽一个就跑一遍 ⌘W 隐藏、托盘双击恢复、⌘Q 干净退出

## 显式不做

- 不引入 SLF4J / 通用日志框架:当前 `System.err.println` 即可
- 不引入 DI 框架(Guice / Spring):构造器手注即可
- 不引入 Controller 基类或接口:`LoginController` / `ChatController` 业务差异太大,抽基类无收益
- 不补单元测试:本次仅重构结构,覆盖范围不变

## 风险与回退

- 主要风险点:包路径改错导致 `mvn compile` 失败、`styles.css` 拆分后样式丢失、`Mate4K` 拆分过程中 quit 路径漏掉某个 hook
- 回退方式:每个迁移步骤都可独立 commit;若某步后启动出现视觉或行为回归,直接 `git revert` 对应 commit 即可