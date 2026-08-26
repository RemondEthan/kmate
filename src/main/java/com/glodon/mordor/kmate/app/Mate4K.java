package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.ui.chat.ChatPane;
import com.glodon.mordor.kmate.ui.login.LoginPane;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用入口：登录窗口 ↔ 聊天窗口 的切换容器 + 系统托盘。
 *
 * StackPane：所有子节点叠在一起显示在同一区域，通过 add/remove 切换可见内容。
 * 这里用它来实现"登录成功之前显示 LoginPane，之后替换为 ChatPane"。
 *
 * 跨平台快捷键：
 *   ⌘W / Ctrl+W —— 关闭窗口（单向，恢复靠托盘/dock）
 *   ⌘Q / Ctrl+Q —— 退出程序
 *
 * 托盘图标：双击或菜单"打开 Kmate"恢复窗口；"退出"则彻底退出。
 */
public class Mate4K extends Application {

    // 窗口尺寸（像素）：宽度 480 +25%，高度 = 480 +10% 再 -15%
    private static final double WIDTH = 600;
    private static final double HEIGHT = 449;

    @Override
    public void start(Stage stage) {
        // StackPane：堆叠容器，子节点居中叠放
        StackPane root = new StackPane();
        root.getStyleClass().add("app-bg");

        // 先放登录面板；点"连接"后会替换为聊天面板
        LoginPane login = new LoginPane(state -> showChat(root, state));
        // 登录面板在 StackPane 中居中显示，上下空白对称
        StackPane.setAlignment(login, Pos.CENTER);
        root.getChildren().add(login);

        // Scene = 场景：JavaFX 的内容容器，对应一个窗口的内容区域
        // 第二个参数是宽高（与 WIDTH/HEIGHT 对应）
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        // 加载全局样式表：所有节点都可以引用 styles.css 里的 .xxx 类
        scene.getStylesheets().add(Mate4K.class.getResource("/com/glodon/mordor/kmate/styles.css").toExternalForm());

        // 全局快捷键：Scene.getAccelerators() 不依赖焦点，TextField 输入时也能触发
        // SHORTCUT_DOWN 在 macOS 上映射为 ⌘，在 Windows/Linux 上映射为 Ctrl，自动跨平台
        final KeyCombination.Modifier shortcut = KeyCombination.SHORTCUT_DOWN;
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, shortcut),
                () -> {
                    if (stage.isShowing()) stage.hide();
                });                              // ⌘W / Ctrl+W：关闭窗口（单向，恢复靠托盘）

        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Q, shortcut),
                this::quitApp);                  // ⌘Q / Ctrl+Q：彻底退出程序

        // 关闭最后一个窗口不自动退出，让 ⌘W 真的只是"关闭窗口"
        Platform.setImplicitExit(false);

        // Stage：顶层窗口（即 macOS 上那个标题栏 + 内容区的窗口）
        stage.setTitle("k-mate");
        stage.setScene(scene);
        // 低于这个尺寸时气泡会被裁切（列表禁止横向滚动）
        stage.setMinWidth(480);
        stage.setMinHeight(360);

        // macOS 的 ⌘Q / Dock「退出」不会走到上面的 Scene accelerator，
        // Glass 会把它翻译成 WINDOW_CLOSE_REQUEST。implicitExit=false 时
        // 默认只关窗口、进程还在，所以这里必须走 quitApp()。
        stage.setOnCloseRequest(e -> {
            e.consume();
            quitApp();
        });

        stage.show();  // 显示窗口（非阻塞，立即返回）

        // 初始化系统托盘（菜单栏右侧 / Windows 任务栏右下 / Linux 系统托盘）
        initSystemTray(stage);
        installOsQuitHandlers();
    }

    // 把 StackPane 的内容替换为聊天面板（setAll 会清空原有子节点）
    private void showChat(StackPane root, AppState state) {
        root.getChildren().setAll(new ChatPane(state));
    }

    // ============================================================
    // 窗口显示/隐藏
    // ============================================================

    /** 恢复并前置窗口——托盘点击/菜单共用 */
    private void showWindow(Stage stage) {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }

    // ============================================================
    // 系统托盘（AWT SystemTray）
    // ============================================================

    // 托盘资源提升为字段，退出时需要主动移除，否则 AWT 线程会阻止 JVM 退出
    private SystemTray systemTray;
    private TrayIcon trayIcon;
    private final AtomicBoolean quitting = new AtomicBoolean();

    private void initSystemTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 当前系统不支持托盘图标，跳过");
            return;
        }

        Image image = loadTrayImage();
        if (image == null) return;

        try {
            systemTray = SystemTray.getSystemTray();
            trayIcon = new TrayIcon(image, "Kmate", buildTrayMenu(stage));
            trayIcon.setImageAutoSize(true);
            // 双击托盘图标也恢复窗口
            trayIcon.addActionListener(e -> Platform.runLater(() -> showWindow(stage)));
            systemTray.add(trayIcon);
        } catch (AWTException e) {
            System.err.println("[Tray] 无法添加托盘图标: " + e.getMessage());
        }
    }

    /** 托盘右键菜单：打开 / 隐藏 / 退出 */
    private PopupMenu buildTrayMenu(Stage stage) {
        PopupMenu menu = new PopupMenu();

        MenuItem openItem = new MenuItem("打开 Kmate");
        openItem.addActionListener(e -> Platform.runLater(() -> showWindow(stage)));

        MenuItem hideItem = new MenuItem("隐藏窗口");
        hideItem.addActionListener(e -> Platform.runLater(() -> {
            if (stage.isShowing()) stage.hide();
        }));

        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> quitApp());

        menu.add(openItem);
        menu.add(hideItem);
        menu.addSeparator();
        menu.add(quitItem);
        return menu;
    }

    /**
     * 彻底退出应用。
     *
     * 不能在 JavaFX 线程上直接 SystemTray.remove() / Platform.exit() / System.exit()：
     * 三者都会和 AWT EDT / FX 线程互相等待，表现为 ⌘Q 后窗口卡死、进程不结束。
     * 这里立刻返回 FX 线程，在独立线程里摘托盘，超时则 halt，保证进程一定死掉。
     */
    private void quitApp() {
        if (!quitting.compareAndSet(false, true)) return;
        Thread shutdown = new Thread(this::forceQuit, "kmate-shutdown");
        shutdown.start();
    }

    private void forceQuit() {
        SystemTray tray = systemTray;
        TrayIcon icon = trayIcon;
        systemTray = null;
        trayIcon = null;
        if (tray != null && icon != null) {
            Thread remover = new Thread(() -> {
                try {
                    tray.remove(icon);
                } catch (Throwable ignored) {
                    // 退出路径，移除失败也要继续杀进程
                }
            }, "kmate-tray-remove");
            remover.start();
            try {
                remover.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime.getRuntime().halt(0);
    }

    /** ⌘Q / Dock 退出：Scene accelerator 在 macOS 上收不到，必须挂 Glass + Desktop 两层。 */
    private void installOsQuitHandlers() {
        try {
            MacQuitHook.install(this::quitApp);
        } catch (Throwable t) {
            System.err.println("[Quit] Glass quit hook 安装失败: " + t.getMessage());
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                Desktop.getDesktop().setQuitHandler((e, response) -> {
                    quitApp();
                    response.performQuit();
                });
            }
        } catch (Throwable t) {
            System.err.println("[Quit] Desktop quit hook 安装失败: " + t.getMessage());
        }
    }

    /** 从 classpath 加载托盘图标 */
    private Image loadTrayImage() {
        try (InputStream is = getClass().getResourceAsStream("/icons/tray.png")) {
            if (is == null) {
                System.err.println("[Tray] 找不到 /icons/tray.png");
                return null;
            }
            BufferedImage img = ImageIO.read(is);
            return img;
        } catch (IOException e) {
            System.err.println("[Tray] 加载图标失败: " + e.getMessage());
            return null;
        }
    }

    public static void main(String[] args) {
        // Application.launch() 初始化 JavaFX 运行时，然后回调 start()
        launch();
    }
}