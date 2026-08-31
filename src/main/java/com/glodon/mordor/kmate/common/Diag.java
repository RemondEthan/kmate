package com.glodon.mordor.kmate.common;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 诊断日志：默认只写 WARN / ERROR。调试轨迹用 {@link #log}，需 {@code -Dkmate.debug=true} 才输出。
 * macOS: ~/Library/Logs/kmate.log
 *
 * 三个日志级别：
 *   - log(...)   DEBUG 级，只在 -Dkmate.debug=true 时输出。用于追踪流程，如"握手完成 120ms"。
 *   - warn(...)  WARN 级，默认就写。性能阈值越过、协议层未识别字段等。
 *   - error(...) ERROR 级，默认就写。异常路径。
 *
 * 每条日志格式：HH:mm:ss.SSS [线程名] [级别] [tag] 消息体
 * 同时写到 stderr 和日志文件，便于本地终端观察 + 事后追溯。
 *
 * 还附带 FX 看门狗（见 {@link #startFxWatchdog}）：定期 ping JavaFX 线程，
 * 如果 2 秒没响应就 dump 所有线程栈，辅助定位 UI 卡死根因。
 */
public final class Diag {

    /*
     * 日志时间戳格式，精确到毫秒。withZone(systemDefault) 让 Instant 能用本地时区格式化。
     */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    /*
     * 日志文件路径：macOS → ~/Library/Logs/kmate.log；其他 → ~/.kmate/kmate.log。
     * resolveLogFile() 在类加载时执行一次，失败则降级写到系统临时目录。
     */
    private static final Path LOG_FILE = resolveLogFile();
    /*
     * 文件写入锁：多线程同时写文件需要同步；stderr 输出不需要锁。
     * 用 Object LOCK 而不是文件锁，简单且对单进程足够。
     */
    private static final Object LOCK = new Object();
    /*
     * 看门狗启动标志：AtomicBoolean.compareAndSet 保证整个进程只启动一次。
     * 即便多处调用 startFxWatchdog() 也不会重复排程。
     */
    private static final AtomicBoolean WATCHDOG = new AtomicBoolean();
    /*
     * 读取启动参数 -Dkmate.debug=true。Boolean.getBoolean 内部用 System.getProperty，
     * 进程启动时确定一次，运行期不变。
     */
    private static final boolean DEBUG = Boolean.getBoolean("kmate.debug");
    /*
     * 上次线程 dump 时间：volatile 保证多线程可见。dump 间隔最小 8 秒，防止抖动时连续刷屏。
     */
    private static volatile long lastThreadDumpNanos;

    // 工具类私有构造器，禁止实例化。
    private Diag() {}

    /**
     * 当前日志文件路径（供"在菜单里打开日志"等功能用）。
     */
    public static Path logFile() {
        return LOG_FILE;
    }

    /**
     * DEBUG 级日志。
     * DEBUG=false 时直接返回，不格式化、不写文件，零开销（注意：%s 也不会被求值之外的方法处理）。
     *
     * @param tag    模块标签，如 "ws" / "chat" / "login"，便于 grep
     * @param format printf 风格格式串
     * @param args   格式参数
     */
    public static void log(String tag, String format, Object... args) {
        if (DEBUG) {
            write("DEBUG", tag, format, args);
        }
    }

    /**
     * WARN 级日志，无条件写入。
     */
    public static void warn(String tag, String format, Object... args) {
        write("WARN", tag, format, args);
    }

    /**
     * ERROR 级日志，无条件写入。
     */
    public static void error(String tag, String format, Object... args) {
        write("ERROR", tag, format, args);
    }

    /**
     * 三级日志共用的写实现。
     *
     * 流程：格式化消息 → 拼一行（含时间/线程/级别/tag） → 写 stderr → 加锁写文件。
     * 文件用 StandardOpenOption.CREATE | APPEND：不存在则创建，存在则追加。
     * 写文件失败被吞掉——诊断通道挂了不能让主流程跟着挂。
     */
    private static void write(String level, String tag, String format, Object... args) {
        String body;
        try {
            // args 为空 → 原样输出，避免 format 里没有 %s 时 String.format 抛异常。
            body = args.length == 0 ? format : String.format(format, args);
        } catch (Exception e) {
            // 格式化异常（参数类型不匹配等）→ 把原始 format 串 + 异常消息一起写，至少不丢信息。
            body = format + " (" + e.getMessage() + ")";
        }
        String line = TIME.format(Instant.now())
                + " [" + Thread.currentThread().getName() + "]"
                + " [" + level + "]"
                + " [" + tag + "] "
                + body;
        // stderr：开发时直接看终端。
        System.err.println(line);
        // 文件：事后追溯；多条线程同时写，需要 LOCK 保护。
        synchronized (LOCK) {
            try {
                Files.writeString(LOG_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // 诊断通道失败不影响主流程
            }
        }
    }

    /**
     * 计算从某 nanoTime 到现在的耗时（ms）。
     * 常用法：long t0 = System.nanoTime(); ...; Diag.log("...", "done %dms", Diag.elapsedMs(t0));
     * 不用 System.currentTimeMillis() 因为它受系统时间调整影响（对时/夏令时会跳）。
     */
    public static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 启动 FX 线程看门狗：每 2 秒往 FX 线程队列扔一个 no-op，
     * 2 秒内没完成就 dump 线程栈（认为 UI 卡死）。
     *
     * 用 ScheduledExecutorService 而不是 java.util.Timer，前者线程异常不影响后续调度。
     * 线程设为守护线程，主线程退出时 JVM 不会等它。
     *
     * 整个进程只启动一次（AtomicBoolean 守门）；重复调用直接返回。
     */
    public static void startFxWatchdog() {
        if (!WATCHDOG.compareAndSet(false, true)) {
            return;
        }
        log("fx", "watchdog start, logFile=%s", LOG_FILE);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kmate-fx-watchdog");
            t.setDaemon(true);
            return t;
        });
        // 初次延迟 1s，之后每 2s 跑一次 pingFx。
        exec.scheduleAtFixedRate(Diag::pingFx, 1, 2, TimeUnit.SECONDS);
    }

    /**
     * 一次心跳：从调度线程把 complete(null) 排到 FX 线程，再用 2s 超时等它跑完。
     * FX 线程被长任务阻塞 → CompletableFuture 不会 complete → 2s 后超时 → dump 线程栈。
     *
     * 注意这一行 Platform.runLater(() -> ping.complete(null))：把任务塞进 FX 队列。
     * 与之相对的是 Platform.isFxApplicationThread()：判断当前线程是不是 FX 线程本身。
     * 看门狗跑在 ScheduledExecutor 的独立线程，所以这里一定是 false。
     */
    private static void pingFx() {
        if (!Platform.isFxApplicationThread() && !WATCHDOG.get()) {
            return;
        }
        long t0 = System.nanoTime();
        CompletableFuture<Void> ping = new CompletableFuture<>();
        try {
            Platform.runLater(() -> ping.complete(null));
        } catch (IllegalStateException e) {
            // JavaFX toolkit 没初始化完就会抛这个；只有非常早期的启动阶段会撞上，记一条 warn 即可。
            warn("fx", "toolkit not ready: %s", e.getMessage());
            return;
        }
        try {
            // orTimeout 2 秒内没完成就抛 TimeoutException。
            ping.orTimeout(2, TimeUnit.SECONDS).join();
            long ms = elapsedMs(t0);
            // 200ms 还算"慢了但活着"，只 warn；超过 2s 才认为卡死。
            if (ms >= 200) {
                warn("fx", "pulse slow %dms", ms);
            }
        } catch (Exception e) {
            // CompletableFuture.join() 抛 CompletionException，真实异常在 getCause。
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                warn("fx", "FX thread blocked >2000ms (卡死嫌疑)");
                dumpThreads();
            } else {
                error("fx", "watchdog error: %s", cause.toString());
            }
        }
    }

    /**
     * dump 所有线程的栈顶 12 帧，便于在日志里分析卡死位置。
     *
     * 8 秒内只 dump 一次，避免卡死恢复瞬间刷一屏。
     */
    private static void dumpThreads() {
        long now = System.nanoTime();
        if (now - lastThreadDumpNanos < TimeUnit.SECONDS.toNanos(8)) {
            return;
        }
        lastThreadDumpNanos = now;
        StringBuilder sb = new StringBuilder("thread dump:");
        // Thread.getAllStackTraces()：所有活线程 → (栈帧数组)。
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            sb.append(System.lineSeparator())
                    .append("  - ")
                    .append(t.getName())
                    .append(" state=")
                    .append(t.getState());
            StackTraceElement[] frames = e.getValue();
            // 每线程只打印前 12 帧，够定位又不至于爆日志。
            int limit = Math.min(frames.length, 12);
            for (int i = 0; i < limit; i++) {
                sb.append(System.lineSeparator()).append("      ").append(frames[i]);
            }
        }
        warn("fx", "%s", sb);
    }

    /**
     * 计算日志文件路径并确保父目录存在。
     * macOS 走 ~/Library/Logs（Apple 推荐），其他系统走 ~/.kmate。
     * 父目录创建失败就退到 java.io.tmpdir，保证日志至少能落盘。
     */
    private static Path resolveLogFile() {
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase();
        Path path = os.contains("mac")
                ? Path.of(home, "Library", "Logs", "kmate.log")
                : Path.of(home, ".kmate", "kmate.log");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ignored) {
            path = Path.of(System.getProperty("java.io.tmpdir", "."), "kmate.log");
        }
        return path;
    }
}
