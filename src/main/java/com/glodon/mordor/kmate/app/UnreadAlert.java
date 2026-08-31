package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.service.ImClient;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.Taskbar;

/**
 * 窗口不在前台时收到聊天，给 Dock / 任务栏 / 托盘加红点；回到前台后清掉。
 *
 * 触发条件（任一满足就视为"后台"，加红点）：
 *   - 窗口被隐藏（!stage.isShowing()）
 *   - 窗口被最小化（stage.isIconified()）
 *   - 窗口未获焦点（!stage.isFocused()）
 *
 * 四个监听点：
 *   - focusedProperty: 焦点进/出
 *   - showingProperty: 窗口显示/隐藏
 *   - iconifiedProperty: 最小化状态
 *   - ImClient.Event.Chat: 收到新消息时判断（消息回调在 HttpClient 线程，要 hop 到 FX）
 */
final class UnreadAlert {

    private final Stage stage;
    private final TrayManager tray;
    private final java.awt.Image awtNormal;
    private final java.awt.Image awtAlert;
    // 当前红点状态。true = 显示红点。用来去重，避免重复触发。
    private boolean on;

    private UnreadAlert(Stage stage, TrayManager tray,
                        java.awt.Image awtNormal, java.awt.Image awtAlert) {
        this.stage = stage;
        this.tray = tray;
        this.awtNormal = awtNormal;
        this.awtAlert = awtAlert != null ? awtAlert : awtNormal;
        // 窗口获焦 → 一定有用户在面前 → 清掉红点。
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                clear();
            }
        });
        stage.showingProperty().addListener((obs, was, showing) -> {
            // 显示且已获焦 → 视为"在用"。
            if (showing && stage.isFocused()) {
                clear();
            }
        });
        stage.iconifiedProperty().addListener((obs, was, iconified) -> {
            // 从最小化恢复且已获焦 → 清掉红点。
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

    /**
     * 订阅某个 ImClient 的聊天事件。
     * 监听回调在 HttpClient 内部线程触发，需要 Platform.runLater 跳到 FX 线程才能碰 stage。
     */
    void watch(ImClient client) {
        clear();
        client.addListener(event -> {
            if (event instanceof ImClient.Event.Chat) {
                Platform.runLater(this::onIncoming);
            }
        });
    }

    /**
     * 收到一条新聊天消息：判断窗口当前是否在前台，决定要不要加红点。
     */
    private void onIncoming() {
        if (!stage.isShowing() || stage.isIconified() || !stage.isFocused()) {
            set(true);
        }
    }

    void clear() {
        set(false);
    }

    /**
     * 切换红点状态：托盘图标 / Stage 图标 / 任务栏图标 / Dock 角标 同步切换。
     * 幂等：状态相同就跳过。
     */
    private void set(boolean alert) {
        if (on == alert) {
            return;
        }
        on = alert;
        // 托盘图标换图
        tray.setAlert(alert);
        // JavaFX Stage 图标换图
        String path = alert ? "/icons/Kmate-alert.png" : "/icons/Kmate.png";
        AppIcons.applyStage(stage, path);
        // 任务栏图标换图
        AppIcons.applyTaskbar(alert ? awtAlert : awtNormal);
        // Dock 角标
        applyDockBadge(alert);
    }

    /**
     * 设置/取消 macOS Dock 角标（"•"）。
     * Taskbar.setIconBadge 需要 AWT-EDT，且只 macOS 上受支持。
     */
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
