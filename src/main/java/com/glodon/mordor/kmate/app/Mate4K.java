package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.service.ImClient;
import com.glodon.mordor.kmate.ui.chat.ChatPane;
import com.glodon.mordor.kmate.ui.login.LoginPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Kmate 桌面客户端入口。
 *
 * <p>这是 JavaFX 应用的主类，所有 UI 装配从这里开始。
 *
 * <p>启动后大致按下列顺序装配：
 * <ol>
 *   <li>建立根容器 StackPane，加 .app-bg 样式</li>
 *   <li>把登录面板塞进根容器（{@link #showLogin()}）</li>
 *   <li>创建 Scene，绑定 CSS 样式表</li>
 *   <li>设置 Stage 标题、图标、最小尺寸</li>
 *   <li>关闭 implicitExit：避免用户关窗时整个 JVM 跟着退出</li>
 *   <li>抢单实例锁：抢不到就直接退出（已有进程在跑）</li>
 *   <li>显示窗口</li>
 *   <li>启动 FX 看门狗（Diag.startFxWatchdog）</li>
 *   <li>初始化托盘 + 任务栏图标 + 红点提醒</li>
 *   <li>构造 QuitManager 并把它注入到托盘 / 快捷键 / 系统 quit 钩子</li>
 *   <li>接管窗口关闭按钮（默认行为改成"藏窗口"，不退出进程）</li>
 *   <li>安装 macOS 专属的 quit / reopen 钩子</li>
 * </ol>
 *
 * <p>两个核心状态切换：
 * <ul>
 *   <li>{@link #showLogin()} —— 把根容器内容替换成登录面板</li>
 *   <li>{@link #enterChat(AppState)} —— 登录成功后替换成聊天面板，并挂上红点监听</li>
 * </ul>
 */
public class Mate4K extends Application {

    // 主窗口默认尺寸。449 高 = 接近正方形小窗，配合登录面板的布局调过。
    private static final double WIDTH = 720;
    private static final double HEIGHT = 449;

    // 主窗口 Stage。start() 注入，整个生命周期都在用它。
    private Stage stage;
    // 根容器：始终是一个 StackPane，里面只放一个"当前页"（LoginPane 或 ChatPane）。
    // 用 StackPane 而不是 BorderPane 是因为我们只需要"占满窗口"这一个布局。
    private StackPane root;
    // 当前已登录的 IM 会话。未登录时为 null；重新登录时旧的会被 closeSession() 清理。
    private ImClient session;
    // 红点提醒：监听 session 收到的新消息，按窗口状态切换 Dock / 任务栏 / 托盘图标。
    private UnreadAlert unreadAlert;

    /**
     * JavaFX 启动入口，由 Application.launch() 在 FX Application Thread 上调用。
     *
     * <p>注意：这个方法跑在 FX Application Thread 上，所以可以直接碰 Stage / Scene 等 UI 控件，
     * 不用 Platform.runLater。
     */
    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.root = new StackPane();
        // app-bg 是 CSS class，定义在 app.css 里，给整个窗口加背景色。
        root.getStyleClass().add("app-bg");
        showLogin();

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        // 加载项目自带的 CSS（与 Mate4K.class 同包）。
        scene.getStylesheets().add(
                Mate4K.class.getResource("app.css").toExternalForm());

        stage.setTitle("k-mate");
        // 窗口左上角的小图标
        AppIcons.applyStage(stage, "/icons/Kmate.png");
        stage.setScene(scene);
        // 最小尺寸：再小就放不下登录面板，会被裁掉。
        stage.setMinWidth(560);
        stage.setMinHeight(360);
        // 关闭 implicitExit：默认情况下用户点窗口关闭按钮会让整个 JVM 退出，
        // 我们改成"藏窗口保持运行"（见 setOnCloseRequest），所以这里必须关掉 implicitExit。
        Platform.setImplicitExit(false);

        // 单实例锁：抢到就没事，抢不到说明本机已经在跑一个 Kmate，把当前进程直接退出。
        // claim 的 lambda 是"已有进程被要求显示窗口时"的回调 —— 因为我们抢不到锁，
        // 这个回调不会被触发，传一个空实现即可。
        if (!SingleInstance.claim(() -> FxStageSupport.show(stage))) {
            Platform.exit();
            return;
        }

        stage.show();
        // 启动 FX 看门狗：卡住 FX 线程会打印堆栈到 stderr。
        Diag.startFxWatchdog();

        // 下面这一坨都是"主窗口以外的桌面集成"：托盘、任务栏、红点。
        TrayManager trayManager = TrayManager.install(stage);
        // 任务栏图标：Windows / Linux 桌面左下角的程序图标
        AppIcons.applyTaskbar(AppIcons.awtImage("/icons/Kmate.png"));
        unreadAlert = UnreadAlert.install(stage, trayManager);
        // QuitManager 负责"安全退出"：先跑清理逻辑，再 halt 进程。
        QuitManager quitManager = new QuitManager(trayManager, this::closeSession);

        // 把 quit 回调注入到三个入口：
        //   1. 托盘菜单点"退出"
        //   2. ⌘Q / Ctrl+Q 快捷键
        //   3. macOS 系统 quit 事件 / Dock 退出
        trayManager.setOnQuit(quitManager::quit);
        // ⌘W / Ctrl+W = 最小化窗口（不退出）。注意区分：W 是 minimize，Q 是 quit。
        ShortcutRegistrar.register(scene, () -> FxStageSupport.minimize(stage), quitManager::quit);
        // 红点：藏窗口。⌘W：最小化。会话保持，点 Dock 还原聊天窗。
        // 默认情况下点窗口关闭按钮会触发 implicitExit（已关） + stage.close()，我们 consume 掉，
        // 改成"只是隐藏窗口"，会话保持在线。
        stage.setOnCloseRequest(e -> {
            e.consume();
            FxStageSupport.hide(stage);
        });
        // macOS 专属：Dock 图标点击 / 系统 quit 菜单的兜底入口。
        OsQuitHandlers.install(quitManager::quit, () -> FxStageSupport.show(stage));
    }

    /**
     * 把根容器内容切换为登录面板。
     * 进入应用 / 注销后都会调用。
     */
    private void showLogin() {
        stage.setTitle("k-mate");
        // StackPane.setAll：把现有所有子节点清空，放进新节点。
        // LoginPane 构造参数是登录成功后的回调（this::enterChat）。
        root.getChildren().setAll(new LoginPane(this::enterChat));
    }

    /**
     * 登录成功回调。把根容器换成聊天面板，并把红点监听挂到新的 IM 客户端。
     *
     * <p>登录前先 closeSession()：处理重新登录的场景（清掉旧 session）。
     *
     * @param state 登录成功后的应用状态（包含用户名、IM 客户端、头像等）。
     */
    private void enterChat(AppState state) {
        closeSession();
        session = state.client();
        // 红点要订阅新 session 的聊天事件
        unreadAlert.watch(session);
        // 窗口标题改成用户名，让用户在 Dock 上一眼看出是哪个账号
        stage.setTitle(state.username());
        root.getChildren().setAll(new ChatPane(state));
    }

    /**
     * 关掉当前 IM 会话。幂等：session 为 null 时什么都不做。
     *
     * <p>被两处调用：
     * <ul>
     *   <li>{@link #enterChat(AppState)} —— 重新登录前清理</li>
     *   <li>QuitManager.beforeHalt —— 退出前清理（落盘最后一条消息等）</li>
     * </ul>
     */
    private void closeSession() {
        if (unreadAlert != null) {
            // 清掉红点，避免退出后还有角标
            unreadAlert.clear();
        }
        // 把字段先置 null 再 close：防止 close 过程中又有人读 session。
        ImClient client = session;
        session = null;
        if (client != null) {
            client.close();
        }
    }

    /**
     * JVM 入口。
     *
     * <p>{@link AwtSupport#preinit()} 必须在 launch() 之前调用：
     * AWT 必须在 FX 启动前先初始化，否则 macOS 上会偶发死锁。
     */
    public static void main(String[] args) {
        AwtSupport.preinit();
        launch();
    }
}
