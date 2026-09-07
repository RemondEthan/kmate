package com.glodon.mordor.kmate.app;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 托盘 / 任务栏图标闪烁定时器。
 * <p>
 * 每 {@link #INTERVAL_MS} 翻转一次 phase，由回调负责切换图标。
 * 最长闪烁 {@link #MAX_DURATION_MS} 后自动停止。
 */
final class BlinkTimer {

    private static final long INTERVAL_MS = 500;
    private static final long MAX_DURATION_MS = 30_000;

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kmate-blink");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> future;
    private volatile boolean phase;
    private long startTime;

    void start(Runnable onTick) {
        stop();
        phase = false;
        startTime = System.currentTimeMillis();
        future = executor.scheduleAtFixedRate(() -> {
            phase = !phase;
            onTick.run();
            if (System.currentTimeMillis() - startTime >= MAX_DURATION_MS) {
                stop();
            }
        }, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    void stop() {
        ScheduledFuture<?> f = future;
        if (f != null) {
            f.cancel(false);
            future = null;
        }
    }

    boolean isPhase() {
        return phase;
    }
}
