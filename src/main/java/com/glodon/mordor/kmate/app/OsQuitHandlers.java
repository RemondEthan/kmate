package com.glodon.mordor.kmate.app;

import java.awt.Desktop;
import java.awt.desktop.AppReopenedListener;

/**
 * 接管 macOS 系统级应用事件：
 *   - Glass handleQuitAction：⌘Q / Dock「退出」
 *   - Desktop APP_QUIT_HANDLER：AWT Desktop 的另一种 quit 入口（部分 macOS JDK 版本走这里）
 *   - Desktop APP_EVENT_REOPENED：点击 Dock 图标（窗口已隐藏时）
 *
 * 三条路任一触发都会调到 onQuit / onShow。
 * 跨平台实现：macOS 走 Glass + Desktop；其他系统这两 API 都不支持，自然跳过。
 */
public final class OsQuitHandlers {

    private OsQuitHandlers() {}

    /**
     * 安装系统级 quit / reopen hook。
     *
     * @param onQuit  退出事件回调
     * @param onShow  Dock 图标点击 / Cmd+Tab 回调
     */
    public static void install(Runnable onQuit, Runnable onShow) {
        // 1. Glass 钩子（主要）。
        MacQuitHook.install(onQuit, onShow);
        // 2. AWT Desktop 钩子（兜底）。需要切到 AWT-EDT 执行，Desktop API 设计如此。
        AwtSupport.run(() -> {
            try {
                if (!Desktop.isDesktopSupported()) {
                    return;
                }
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                    // quit handler 是回调接口，先跑我们的 onQuit，再告诉系统"我也退出了"。
                    desktop.setQuitHandler((e, response) -> {
                        onQuit.run();
                        response.performQuit();
                    });
                }
                // 重开应用（Dock 图标点击）→ 把隐藏的窗口拉回来。
                desktop.addAppEventListener((AppReopenedListener) e -> onShow.run());
            } catch (Throwable t) {
                System.err.println("[Quit] Desktop hook 安装失败: " + t.getMessage());
            }
        });
    }
}
