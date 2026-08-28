package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.service.ImClient;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.awt.EventQueue;
import java.awt.Taskbar;

/**
 * 窗口不在前台时收到聊天，给 Dock / 任务栏 / 托盘加红点；回到前台后清掉。
 */
final class UnreadAlert {

    private final Stage stage;
    private final TrayManager tray;
    private final Image normalIcon;
    private final Image alertIcon;
    private boolean on;

    private UnreadAlert(Stage stage, TrayManager tray, Image normalIcon, Image alertIcon) {
        this.stage = stage;
        this.tray = tray;
        this.normalIcon = normalIcon;
        this.alertIcon = alertIcon;
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                clear();
            }
        });
        stage.showingProperty().addListener((obs, was, showing) -> {
            if (showing && stage.isFocused()) {
                clear();
            }
        });
        stage.iconifiedProperty().addListener((obs, was, iconified) -> {
            if (!iconified && stage.isShowing() && stage.isFocused()) {
                clear();
            }
        });
    }

    static UnreadAlert install(Stage stage, TrayManager tray) {
        Image normal = load("/icons/Kmate.png");
        Image alert = load("/icons/Kmate-alert.png");
        return new UnreadAlert(stage, tray, normal, alert != null ? alert : normal);
    }

    void watch(ImClient client) {
        clear();
        client.addListener(event -> {
            if (event instanceof ImClient.Event.Chat) {
                Platform.runLater(this::onIncoming);
            }
        });
    }

    private void onIncoming() {
        if (!stage.isShowing() || stage.isIconified() || !stage.isFocused()) {
            set(true);
        }
    }

    void clear() {
        set(false);
    }

    private void set(boolean alert) {
        if (on == alert) {
            return;
        }
        on = alert;
        tray.setAlert(alert);
        if (alertIcon != null && normalIcon != null) {
            stage.getIcons().setAll(alert ? alertIcon : normalIcon);
        }
        applyDockBadge(alert);
    }

    private static void applyDockBadge(boolean alert) {
        if (!Taskbar.isTaskbarSupported()) {
            return;
        }
        EventQueue.invokeLater(() -> {
            try {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)
                        || taskbar.isSupported(Taskbar.Feature.ICON_BADGE_NUMBER)) {
                    taskbar.setIconBadge(alert ? "•" : null);
                }
            } catch (Exception ignored) {
                // 部分桌面环境不支持角标
            }
        });
    }

    private static Image load(String path) {
        var url = UnreadAlert.class.getResource(path);
        return url == null ? null : new Image(url.toExternalForm());
    }
}
