package com.glodon.mordor.kmate.app;

import java.awt.Desktop;

/**
 * 接管 macOS / 其它平台的"应用退出"事件:
 *   - Glass Application.handleQuitAction(macOS ⌘Q 与 Dock「退出」)
 *   - Desktop.APP_QUIT_HANDLER(其它平台未来扩展)
 *
 * Stage.onCloseRequest 由 Mate4K 直接挂,因为需要 stage 引用。
 */
public final class OsQuitHandlers {

    private OsQuitHandlers() {}

    public static void install(Runnable onQuit) {
        MacQuitHook.install(onQuit);
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                Desktop.getDesktop().setQuitHandler((e, response) -> {
                    onQuit.run();
                    response.performQuit();
                });
            }
        } catch (Throwable t) {
            System.err.println("[Quit] Desktop quit hook 安装失败: " + t.getMessage());
        }
    }
}
