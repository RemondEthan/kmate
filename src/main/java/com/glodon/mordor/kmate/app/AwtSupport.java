package com.glodon.mordor.kmate.app;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

/**
 * macOS 上 JavaFX 线程就是 AppKit 主线程。AWT Toolkit / 托盘 / Taskbar
 * 的首次初始化若发生在这条线程上，会和 AWT-EDT 互相等待，窗口直接卡死。
 *
 * 这个类提供两个工具方法隔离 AWT 初始化：
 *   - preinit()  在 JavaFX 启动前开一条子线程触发 Toolkit/GraphicsEnvironment 初始化。
 *                等最多 8 秒，初始化完就 join 回收线程。
 *   - run()      把一段动作安全地丢到 AWT-EDT 上执行（用于后续的托盘/任务栏/Dock 调用）。
 */
final class AwtSupport {

    // 工具类私有构造器。
    private AwtSupport() {}

    /**
     * 在 FX 启动前异步预热 AWT 子系统。
     *
     * Toolkit.getDefaultToolkit() 是触发 AWT 初始化的入口；
     * GraphicsEnvironment.getLocalGraphicsEnvironment() 同理（macOS 上还会触发 NSApplication 注册）。
     * 两者都得跑在非 FX 线程，否则会和 AWT-EDT 互等，窗口卡死。
     *
     * 8 秒 join 是为了"主流程正常情况不需要等 8 秒"，但万一张初始化卡了 8 秒以上
     * 也别无限阻塞——主线程直接继续往下走，Toolkit 那边会自己后台完成。
     */
    static void preinit() {
        Thread t = new Thread(() -> {
            Toolkit.getDefaultToolkit();
            GraphicsEnvironment.getLocalGraphicsEnvironment();
        }, "kmate-awt-preinit");
        t.setDaemon(true);
        t.start();
        try {
            t.join(8000);
        } catch (InterruptedException e) {
            // 当前线程被中断 → 重新设置中断标志，调用方决定是否响应。
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 安全地把一段动作扔到 AWT-EDT 上跑。
     *
     * 这里先开一条"hop"线程，再在 hop 里用 EventQueue.invokeLater(action) 把 action 排到 AWT 队列。
     * 为什么不直接 EventQueue.invokeLater：当前线程（通常是 FX 线程或看门狗线程）不应该在 AWT 队列空时阻塞。
     * hop 线程起到"接力"作用：发起调度后立刻返回，不管 AWT 那边跑多久。
     *
     * 异常被吞：AWT 不可用时（例如 headless 环境）整体静默跳过，不影响主功能。
     */
    static void run(Runnable action) {
        Thread hop = new Thread(() -> {
            try {
                EventQueue.invokeLater(action);
            } catch (Throwable ignored) {
                // AWT 不可用时忽略
            }
        }, "kmate-awt");
        hop.setDaemon(true);
        hop.start();
    }
}
