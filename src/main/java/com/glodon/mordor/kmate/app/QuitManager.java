package com.glodon.mordor.kmate.app;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 退出守卫 + 异步 forceQuit。
 *
 * quit() 用 AtomicBoolean 防止重入;真正退出在独立线程里移除托盘 + Runtime.halt,
 * 保证 JVM 一定死掉（避免 AWT 线程持有托盘导致进程不退出）。
 *
 * 为什么不用 System.exit(0)：AWT/Swing 关闭钩子里如果有非守护线程挂着，
 * System.exit 会无限等待这些线程；用 Runtime.halt 是直接 SIGKILL 整个进程，立即死。
 */
public final class QuitManager {

    /*
     * 防止 quit() 被并发调用多次（托盘菜单 + ⌘Q 同时触发）。
     * AtomicBoolean.compareAndSet 原子地"false → true"，只有第一个调用者继续往下走。
     */
    private final AtomicBoolean quitting = new AtomicBoolean();

    private final TrayManager trayManager;
    /*
     * halt 之前要跑一段清理逻辑（关闭 ImClient / 落盘最后一条消息等）。
     * 不为 null 时才跑。
     */
    private final Runnable beforeHalt;

    public QuitManager(TrayManager trayManager, Runnable beforeHalt) {
        this.trayManager = trayManager;
        this.beforeHalt = beforeHalt;
    }

    /**
     * 触发退出流程。幂等：第一次调用真正跑，第二次起直接返回。
     */
    public void quit() {
        if (!quitting.compareAndSet(false, true)) return;
        // 先跑清理逻辑：关 WebSocket、落盘历史。
        if (beforeHalt != null) {
            try {
                beforeHalt.run();
            } catch (Throwable ignored) {
                // 退出路径，关连接失败也要继续杀进程
            }
        }
        // 再在独立线程里真正杀进程（不阻塞当前调用栈）。
        Thread shutdown = new Thread(this::forceQuit, "kmate-shutdown");
        shutdown.start();
    }

    /**
     * 真正退出的逻辑：移除托盘图标 → halt 进程。
     *
     * 移除托盘开另一条线程，最多等 400ms；超时也不管，直接 halt。
     * 因为 halt 是强杀，托盘没干净退出也无妨。
     */
    private void forceQuit() {
        SystemTray tr = trayManager == null ? null : trayManager.tray();
        TrayIcon ic = trayManager == null ? null : trayManager.icon();
        if (tr != null && ic != null) {
            Thread remover = new Thread(() -> {
                try {
                    tr.remove(ic);
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
}
