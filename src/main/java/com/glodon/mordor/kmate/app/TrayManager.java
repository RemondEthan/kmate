package com.glodon.mordor.kmate.app;

import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;

/**
 * 系统托盘封装。
 *
 * install(Stage) 立即返回占位对象，真正的 SystemTray.add 在 AWT 线程完成；
 * 系统不支持托盘或加载失败时，tray() / icon() 返回 null，QuitManager 据此跳过清理。
 *
 * 图标路径固定为 /icons/tray.png（平台资源，不挂特性）。
 *
 * 托盘菜单：
 *   - 打开 Kmate     → 显示窗口
 *   - 隐藏窗口        → 隐藏窗口（macOS 上同时收后台）
 *   - 退出            → onQuit 回调（默认 no-op，由 Mate4K 在启动后改成 QuitManager::quit）
 */
public final class TrayManager {

    // 三个字段都标 volatile：跨线程访问（FX 线程读、托盘菜单线程写）。
    private volatile SystemTray tray;
    private volatile TrayIcon icon;
    private volatile Image normalImage;
    private volatile Image alertImage;
    // 退出回调：默认 no-op，setOnQuit 由 Mate4K 启动后注入。
    private volatile Runnable onQuit = () -> {};

    /**
     * 创建 TrayManager 并异步触发托盘初始化（AWT 线程执行）。
     * 调用方立即拿到实例，但 tray()/icon() 可能还没初始化好，要等 AWT 线程跑完。
     */
    public static TrayManager install(Stage stage) {
        TrayManager tm = new TrayManager();
        AwtSupport.run(() -> tm.attach(stage));
        return tm;
    }

    /**
     * 真正把 TrayIcon 加到系统托盘。必须在 AWT-EDT 上调用（SystemTray.add 内部要求）。
     * 加载失败（找不到图、不支持托盘、权限拒绝）静默返回，icon/tray 保持 null。
     */
    private void attach(Stage stage) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 当前系统不支持托盘图标，跳过");
            return;
        }
        Image image = loadTrayImage("/icons/tray.png");
        if (image == null) {
            return;
        }
        Image alert = loadTrayImage("/icons/tray-alert.png");

        try {
            SystemTray systemTray = SystemTray.getSystemTray();
            TrayIcon trayIcon = new TrayIcon(image, "Kmate");
            trayIcon.setImageAutoSize(true);
            // 单击托盘图标 → 显示窗口
            trayIcon.addActionListener(e -> FxStageSupport.show(stage));
            systemTray.add(trayIcon);
            // add 之后才能 setPopupMenu；先后顺序不能反。
            trayIcon.setPopupMenu(buildMenu(stage));
            this.normalImage = image;
            this.alertImage = alert != null ? alert : image;
            this.icon = trayIcon;
            this.tray = systemTray;
        } catch (AWTException e) {
            // 部分 GNOME / KDE 会拒绝 add：例如 Wayland 没实现 status notifier item。
            System.err.println("[Tray] 无法添加托盘图标: " + e.getMessage());
        }
    }

    public SystemTray tray() { return tray; }
    public TrayIcon icon() { return icon; }

    /**
     * 注入退出回调。Mate4K 启动时调用，把默认 no-op 替换成 QuitManager::quit。
     */
    public void setOnQuit(Runnable onQuit) {
        this.onQuit = onQuit == null ? () -> {} : onQuit;
    }

    /**
     * 切换托盘图标：true 用 alert 图标（新消息），false 用 normal 图标。
     * 在 AWT-EDT 上跑。
     */
    void setAlert(boolean alert) {
        AwtSupport.run(() -> {
            if (icon == null || normalImage == null) {
                return;
            }
            icon.setImage(alert && alertImage != null ? alertImage : normalImage);
        });
    }

    private static Image loadTrayImage(String path) {
        try (InputStream is = TrayManager.class.getResourceAsStream(path)) {
            if (is == null) {
                System.err.println("[Tray] 找不到 " + path);
                return null;
            }
            return ImageIO.read(is);
        } catch (IOException e) {
            System.err.println("[Tray] 加载图标失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 构造托盘右键菜单（AWT PopupMenu）。
     * PopupMenu 是 AWT 控件，与 JavaFX 控件是两套；这里用 AWT 是因为 SystemTray 只接 AWT PopupMenu。
     */
    private PopupMenu buildMenu(Stage stage) {
        PopupMenu menu = new PopupMenu();

        MenuItem openItem = new MenuItem("打开 Kmate");
        openItem.addActionListener(e -> FxStageSupport.show(stage));

        MenuItem hideItem = new MenuItem("隐藏窗口");
        hideItem.addActionListener(e -> FxStageSupport.hide(stage));

        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> onQuit.run());

        menu.add(openItem);
        menu.add(hideItem);
        menu.addSeparator();
        menu.add(quitItem);
        return menu;
    }

}