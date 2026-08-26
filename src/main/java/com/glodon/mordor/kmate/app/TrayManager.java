package com.glodon.mordor.kmate.app;

import javafx.application.Platform;
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
 * install(Stage) 创建托盘图标 + 右键菜单 + 双击恢复窗口的监听;
 * 系统不支持托盘或加载失败时,tray() / icon() 返回 null,QuitManager 据此跳过清理。
 *
 * 图标路径固定为 /icons/tray.png(平台资源,不挂特性)。
 */
public final class TrayManager {

    private final SystemTray tray;
    private final TrayIcon icon;
    private Runnable onQuit = () -> {};

    private TrayManager(SystemTray tray, TrayIcon icon) {
        this.tray = tray;
        this.icon = icon;
    }

    public static TrayManager install(Stage stage) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 当前系统不支持托盘图标,跳过");
            return new TrayManager(null, null);
        }
        Image image = loadTrayImage();
        if (image == null) return new TrayManager(null, null);

        try {
            SystemTray tray = SystemTray.getSystemTray();
            TrayIcon icon = new TrayIcon(image, "Kmate");
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> Platform.runLater(() -> showWindow(stage)));
            tray.add(icon);

            TrayManager tm = new TrayManager(tray, icon);
            icon.setPopupMenu(tm.buildMenu(stage));
            return tm;
        } catch (AWTException e) {
            System.err.println("[Tray] 无法添加托盘图标: " + e.getMessage());
            return new TrayManager(null, null);
        }
    }

    public SystemTray tray() { return tray; }
    public TrayIcon icon() { return icon; }

    public void setOnQuit(Runnable onQuit) {
        this.onQuit = onQuit == null ? () -> {} : onQuit;
    }

    private static Image loadTrayImage() {
        try (InputStream is = TrayManager.class.getResourceAsStream("/icons/tray.png")) {
            if (is == null) {
                System.err.println("[Tray] 找不到 /icons/tray.png");
                return null;
            }
            return ImageIO.read(is);
        } catch (IOException e) {
            System.err.println("[Tray] 加载图标失败: " + e.getMessage());
            return null;
        }
    }

    private PopupMenu buildMenu(Stage stage) {
        PopupMenu menu = new PopupMenu();

        MenuItem openItem = new MenuItem("打开 Kmate");
        openItem.addActionListener(e -> Platform.runLater(() -> showWindow(stage)));

        MenuItem hideItem = new MenuItem("隐藏窗口");
        hideItem.addActionListener(e -> Platform.runLater(() -> {
            if (stage.isShowing()) stage.hide();
        }));

        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> onQuit.run());

        menu.add(openItem);
        menu.add(hideItem);
        menu.addSeparator();
        menu.add(quitItem);
        return menu;
    }

    private static void showWindow(Stage stage) {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }
}
