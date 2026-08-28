package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.service.ImClient;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.Taskbar;

/**
 * 窗口不在前台时收到聊天，给 Dock / 任务栏 / 托盘加红点；回到前台后清掉。
 */
final class UnreadAlert {

    private final Stage stage;
    private final TrayManager tray;
    private final java.awt.Image awtNormal;
    private final java.awt.Image awtAlert;
    private boolean on;

    private UnreadAlert(Stage stage, TrayManager tray,
                        java.awt.Image awtNormal, java.awt.Image awtAlert) {
        this.stage = stage;
        this.tray = tray;
        this.awtNormal = awtNormal;
        this.awtAlert = awtAlert != null ? awtAlert : awtNormal;
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
        return new UnreadAlert(
                stage,
                tray,
                AppIcons.awtImage("/icons/Kmate.png"),
                AppIcons.awtImage("/icons/Kmate-alert.png"));
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
        String path = alert ? "/icons/Kmate-alert.png" : "/icons/Kmate.png";
        AppIcons.applyStage(stage, path);
        AppIcons.applyTaskbar(alert ? awtAlert : awtNormal);
        applyDockBadge(alert);
    }

    private static void applyDockBadge(boolean alert) {
        AwtSupport.run(() -> {
            try {
                if (!Taskbar.isTaskbarSupported()) {
                    return;
                }
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

}
