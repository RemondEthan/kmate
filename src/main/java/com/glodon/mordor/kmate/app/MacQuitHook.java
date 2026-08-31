package com.glodon.mordor.kmate.app;

import com.sun.glass.ui.Application;

/**
 * 接住 macOS 系统菜单的 ⌘Q / Dock「退出」。
 *
 * JavaFX 把这次退出做成 Glass {@code handleQuitAction}，不会走进 Scene
 * accelerator。implicitExit=false 时默认处理几乎是空操作，必须自己接管。
 *
 * 实现要点：
 *   - 拿到 Application.GetApplication() 单例。
 *   - 用新的 EventHandler 替换旧的；新 handler 内部每个回调都先调 prev.xxx()
 *     把事件转发给旧 handler，避免破坏 JavaFX 自己的处理。
 *   - handleQuitAction 重写为 onQuit.run()。
 *   - handleDidBecomeActiveAction / handleDidUnhideAction 触发 onShow：把隐藏的窗口拉回来。
 */
final class MacQuitHook {

    private MacQuitHook() {}

    /**
     * 安装 Glass 事件 hook。
     *
     * @param onQuit  ⌘Q / Dock "退出" 时调用。
     * @param onShow  Cmd+Tab / 点 Dock 让应用回到前台时调用（恢复窗口显示）。
     */
    static void install(Runnable onQuit, Runnable onShow) {
        try {
            Application glass = Application.GetApplication();
            if (glass == null) return;
            // 拿到旧 handler，所有回调先转发给它，避免破坏 JavaFX 自身处理。
            Application.EventHandler prev = glass.getEventHandler();
            glass.setEventHandler(new Application.EventHandler() {
            @Override
            public void handleQuitAction(Application app, long time) {
                // ⌘Q / Dock "退出" → 走我们的 QuitManager。
                onQuit.run();
            }

            @Override
            public void handleWillFinishLaunchingAction(Application app, long time) {
                if (prev != null) prev.handleWillFinishLaunchingAction(app, time);
            }

            @Override
            public void handleDidFinishLaunchingAction(Application app, long time) {
                if (prev != null) prev.handleDidFinishLaunchingAction(app, time);
            }

            @Override
            public void handleWillBecomeActiveAction(Application app, long time) {
                if (prev != null) prev.handleWillBecomeActiveAction(app, time);
            }

            @Override
            public void handleDidBecomeActiveAction(Application app, long time) {
                if (prev != null) prev.handleDidBecomeActiveAction(app, time);
                // Cmd+Tab / 点 Dock 回到前台：把已隐藏的聊天窗拉回来
                if (onShow != null) {
                    onShow.run();
                }
            }

            @Override
            public void handleWillResignActiveAction(Application app, long time) {
                if (prev != null) prev.handleWillResignActiveAction(app, time);
            }

            @Override
            public void handleDidResignActiveAction(Application app, long time) {
                if (prev != null) prev.handleDidResignActiveAction(app, time);
            }

            @Override
            public void handleDidReceiveMemoryWarning(Application app, long time) {
                if (prev != null) prev.handleDidReceiveMemoryWarning(app, time);
            }

            @Override
            public void handleWillHideAction(Application app, long time) {
                if (prev != null) prev.handleWillHideAction(app, time);
            }

            @Override
            public void handleDidHideAction(Application app, long time) {
                if (prev != null) prev.handleDidHideAction(app, time);
            }

            @Override
            public void handleWillUnhideAction(Application app, long time) {
                if (prev != null) prev.handleWillUnhideAction(app, time);
            }

            @Override
            public void handleDidUnhideAction(Application app, long time) {
                if (prev != null) prev.handleDidUnhideAction(app, time);
                if (onShow != null) {
                    onShow.run();
                }
            }

            @Override
            public void handleOpenFilesAction(Application app, long time, String[] files) {
                if (prev != null) prev.handleOpenFilesAction(app, time, files);
            }

            @Override
            public boolean handleThemeChanged(String theme) {
                return prev != null && prev.handleThemeChanged(theme);
            }
        });
        } catch (Throwable t) {
            System.err.println("[Quit] Glass quit hook 安装失败: " + t);
        }
    }
}
