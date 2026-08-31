package com.glodon.mordor.kmate.app;

import com.sun.glass.ui.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.lang.reflect.Method;

/**
 * 从 AWT/AppKit 回调恢复 JavaFX 窗口时，必须先 hop 出原生栈，
 * 再进 FX 线程，否则会和 Glass 死锁或把 show() 吃掉。
 *
 * 三个 Stage 操作都遵循同一模式：
 *   1. 回调（来自 AWT/Glass/托盘菜单）跑到这条线程上。
 *   2. runHopped() 启一条 daemon 线程做"接力"。
 *   3. 接力线程里 Platform.runLater(action) 把 action 排进 FX 线程队列。
 *   4. 当前栈立刻返回；FX 线程随后在合适的时机执行 action。
 *
 * 为什么需要 hop：AWT-EDT、JavaFX 线程、Glass 主线程都可能是回调发生的现场，
 * 它们之间有锁依赖，直接调 Stage.show() 可能和 Glass 卡死或被忽略。
 */
final class FxStageSupport {

    // 启动时判一次 macOS，后面 hideMacApp() 直接看这个标志，避免每次都读系统属性。
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private FxStageSupport() {}

    /**
     * 显示窗口：从最小化恢复 + 显示 + 拉到前台 + 请求焦点。
     *
     * isIconified：窗口被最小化（macOS 上是 Dock 图标状态）。
     * isShowing：窗口是否"在屏"（包括隐藏到 Dock 但没退出的状态）。
     * toFront：把窗口拉到 z-order 最前面。
     * requestFocus：让窗口获取键盘焦点，能立即响应键盘事件。
     */
    static void show(Stage stage) {
        runHopped(() -> {
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            if (!stage.isShowing()) {
                stage.show();
            }
            stage.toFront();
            stage.requestFocus();
        });
    }

    /**
     * 最小化窗口：仅当窗口当前在屏时才操作，避免无意义的图标抖动。
     */
    static void minimize(Stage stage) {
        runHopped(() -> {
            if (stage.isShowing()) {
                stage.setIconified(true);
            }
        });
    }

    /**
     * 隐藏窗口 + macOS 上整体收回后台（不占前台）。
     *
     * 为什么额外 hideMacApp：仅 stage.hide() 时应用仍占前台，点 Dock 不会再发 become-active；
     * Glass 也没有 applicationShouldHandleReopen。先把应用收进后台，
     * 下次点 Dock 就会 unhide / become-active，再把窗口拉回来。
     */
    static void hide(Stage stage) {
        runHopped(() -> {
            if (stage.isShowing()) {
                stage.hide();
            }
            hideMacApp();
        });
    }

    /**
     * 把一段 FX 操作安全地从其他线程调度到 FX 线程。
     *
     * 起一条 daemon "hop" 线程做中转，避免直接在 AWT-EDT 或 Glass 主线程上调
     * Platform.runLater 引发的死锁。
     */
    static void runHopped(Runnable action) {
        Thread hop = new Thread(() -> Platform.runLater(action), "kmate-fx-hop");
        hop.setDaemon(true);
        hop.start();
    }

    /**
     * 调用 Glass 私有 API _hide()：把 macOS 应用整体收后台（不退出进程）。
     * 非 macOS 直接跳过。
     *
     * com.sun.glass.ui.Application 是 JavaFX 内部 Glass 抽象层；
     * _hide() 是 protected/package-private，没公开 API，所以走反射。
     * pom.xml 里 --add-exports javafx.graphics/com.sun.glass.ui=com.glodon.mordor.kmate 让这个 import 能编译。
     */
    private static void hideMacApp() {
        if (!MAC) {
            return;
        }
        try {
            Application glass = Application.GetApplication();
            if (glass == null) {
                return;
            }
            Method hide = glass.getClass().getDeclaredMethod("_hide");
            hide.setAccessible(true);
            hide.invoke(glass);
        } catch (Throwable t) {
            System.err.println("[os] mac hide app failed: " + t);
        }
    }
}
