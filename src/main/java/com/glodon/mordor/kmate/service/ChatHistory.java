package com.glodon.mordor.kmate.service;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.model.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 按 IM_CODE 一份加密文本档案。打开失败（密码不对）则不解密、不改写。
 *
 * 物理结构（plaintext 视角）：
 *   第 1 行：  加密("kmate-history-v1")  ← 头部标记，用于校验密码是否正确
 *   第 2..N 行：每行 = 加密(HistoryCodec.encode(Message))  ← 一条历史消息
 *
 * 密码派生：CryptoService.forArchive(password, imCode)，padding = "|archive|<imCode>"。
 * 与在线会话的 padding 不同（在线是服务端随机给的），确保档案用密码能离线解开。
 *
 * 容量策略：
 *   MEMORY_CAP = 100        内存中保留的最新消息条数（超出滚动）
 *   PAGE_SIZE  = 50         一次"上滑加载更多"返回的条数
 *   DISK_MAX_MESSAGES = 2000 磁盘文件中最多保留的条数（超出滚动删最旧）
 *   DISK_MAX_BYTES = 8 MiB   磁盘文件最大字节数（按加密后长度算）
 *
 * 线程模型：所有磁盘 IO 走单线程 executor（kmate-history），
 * 避免多线程同时读写同一文件。FX 线程不直接调用读写，而是用 *Async 方法扔任务给 executor。
 */
public final class ChatHistory {

    /** 内存里保留的最新消息条数。超过就 evict 最旧。 */
    public static final int MEMORY_CAP = 100;
    /** 一次"上滑加载更多"返回的消息条数。 */
    public static final int PAGE_SIZE = 50;
    /** 磁盘文件中保留的最大消息条数。超出滚动删除最旧的。 */
    public static final int DISK_MAX_MESSAGES = 2000;
    /** 磁盘文件最大字节数（密文长度）。 */
    public static final long DISK_MAX_BYTES = 8L * 1024 * 1024;
    /** 文件头标记的明文。解出头一行应等于它，否则视为密码错误。 */
    static final String HEADER = "kmate-history-v1";

    // 物理文件路径。
    private final Path file;
    // 用于加解密的 CryptoService，必须在构造前先调用 forArchive() 派生好密钥。
    private final CryptoService crypto;
    // 可由构造器覆盖（测试用），默认走 DISK_MAX_* 常量。
    private final int diskMaxMessages;
    private final long diskMaxBytes;

    /*
     * 单线程 executor：所有磁盘 IO 都排队到这里执行。
     * 这样 open/append/load* 之间不会并发读写同一文件，简化并发控制。
     * 线程名 "kmate-history" 便于在 thread dump 里定位；daemon=true 让它不阻塞 JVM 退出。
     */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kmate-history");
        t.setDaemon(true);
        return t;
    });

    /*
     * 是否已解锁（密码正确）。
     * volatile：因为 FX 线程读，kmate-history 线程写。
     */
    private volatile boolean unlocked;

    /**
     * 常用构造器：使用默认上限。
     */
    public ChatHistory(Path file, CryptoService archiveCrypto) {
        this(file, archiveCrypto, DISK_MAX_MESSAGES, DISK_MAX_BYTES);
    }

    /**
     * 完整构造器：测试可以用更小的上限验证滚动逻辑。
     */
    public ChatHistory(Path file, CryptoService archiveCrypto, int diskMaxMessages, long diskMaxBytes) {
        this.file = file;
        this.crypto = archiveCrypto;
        this.diskMaxMessages = diskMaxMessages;
        this.diskMaxBytes = diskMaxBytes;
    }

    /**
     * 默认档案路径：~/.kmate/history/<sha256(imCode)>/messages.log
     * 用 sha256 而不是 imCode 原串，避免目录名出现特殊字符。
     */
    public static Path defaultFile(String imCode) {
        return defaultFile(Path.of(System.getProperty("user.home"), ".kmate"), imCode);
    }

    /** 同上，但根目录可注入（测试用）。 */
    public static Path defaultFile(Path kmateRoot, String imCode) {
        return kmateRoot.resolve("history").resolve(sha256Hex(imCode)).resolve("messages.log");
    }

    /**
     * 打开档案：文件不存在则创建并写入加密头部；存在则尝试解密头部验证密码。
     *
     * 三种结果：
     *   - 文件不存在 → 创建空档案（仅头部），返回 true。
     *   - 头部能解出 "kmate-history-v1" → 解锁成功，返回 true。
     *   - 解密失败（密码不对）或 IO 错误 → 返回 false，不改写文件。
     *
     * 注意这里可能同步阻塞（读 / 写文件），调用方一般走 loadInitialAsync()。
     */
    public boolean open() {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, crypto.encrypt(HEADER) + System.lineSeparator(), StandardCharsets.UTF_8);
                restrictOwnerOnly(file);
                unlocked = true;
                return true;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                // 文件存在但为空：当成全新档案，初始化头部。
                Files.writeString(file, crypto.encrypt(HEADER) + System.lineSeparator(), StandardCharsets.UTF_8);
                unlocked = true;
                return true;
            }
            // 解密第一行验证密码。
            String header = crypto.decrypt(lines.get(0).trim());
            unlocked = HEADER.equals(header);
            return unlocked;
        } catch (CryptoService.CryptoException e) {
            // 密码不对 → 解密抛异常 → 标记未解锁，文件保持原样。
            unlocked = false;
            return false;
        } catch (IOException e) {
            Diag.warn("history", "open failed: %s", e.getMessage());
            unlocked = false;
            return false;
        }
    }

    /**
     * 当前是否解锁（密码正确且文件可用）。
     */
    public boolean isUnlocked() {
        return unlocked;
    }

    /**
     * 同步追加一条消息到档案。失败只 warn，不抛异常（不能因为档案写挂让 UI 跟着挂）。
     *
     * 流程：encode → encrypt → append 一行 → 触发 trimIfNeeded。
     */
    public void append(Message message) {
        if (!unlocked || message == null) {
            return;
        }
        try {
            Files.writeString(
                    file,
                    crypto.encrypt(HistoryCodec.encode(message)) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            // 每写一条都检查是否需要滚动（消息数 / 文件大小阈值）。
            trimIfNeeded();
        } catch (IOException | CryptoService.CryptoException e) {
            Diag.warn("history", "append failed: %s", e.getMessage());
        }
    }

    /**
     * 异步追加：扔给 kmate-history 线程执行，调用方立刻返回（不阻塞 FX 线程）。
     */
    public void appendAsync(Message message) {
        exec.execute(() -> append(message));
    }

    /**
     * 异步读取最近 MEMORY_CAP 条消息作为初始内容。
     * 任务里先 open()（懒初始化），再 loadNewest(MEMORY_CAP)，回调在 executor 线程触发。
     * 调用方负责把结果 hop 回 FX 线程（见 ChatController）。
     */
    public void loadInitialAsync(Consumer<List<Message>> callback) {
        exec.execute(() -> {
            open();
            callback.accept(loadNewest(MEMORY_CAP));
        });
    }

    /**
     * 异步读取最新 limit 条消息（点击"回到底部"时调用）。
     */
    public void loadNewestAsync(int limit, Consumer<List<Message>> callback) {
        exec.execute(() -> callback.accept(loadNewest(limit)));
    }

    /**
     * 异步读取"早于 messageId"的消息，用于上滑翻页。
     * 返回的消息按时间正序排列（最旧的在 list[0]）。
     */
    public void loadOlderThanAsync(String messageId, int limit, Consumer<List<Message>> callback) {
        exec.execute(() -> callback.accept(loadOlderThan(messageId, limit)));
    }

    /**
     * 同步读取最新 limit 条；调用前保证已 open。
     */
    public List<Message> loadNewest(int limit) {
        if (!unlocked) {
            return List.of();
        }
        List<Message> all = readMessages();
        if (all.size() <= limit) {
            return all;
        }
        // subList 返回的是 all 的视图；用 List.copyOf 转成不可变副本，避免后续被 trim 影响。
        return List.copyOf(all.subList(all.size() - limit, all.size()));
    }

    /**
     * 同步读取"早于 messageId"的消息（不含 messageId 那条本身）。
     * 返回空 list 表示：未解锁 / 锚点无效 / 已是最后一条。
     */
    public List<Message> loadOlderThan(String messageId, int limit) {
        if (!unlocked || messageId == null || limit <= 0) {
            return List.of();
        }
        List<Message> all = readMessages();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (messageId.equals(all.get(i).id())) {
                idx = i;
                break;
            }
        }
        if (idx <= 0) {
            return List.of();
        }
        int from = Math.max(0, idx - limit);
        return List.copyOf(all.subList(from, idx));
    }

    /**
     * 等待所有已排队任务执行完。最多等 timeout + unit。
     * 用 submit(() -> null).get(timeout) 这种"占位任务"等队列清空是常用技巧。
     */
    public void flush(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            exec.submit(() -> null).get(timeout, unit);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("history flush failed", e);
        }
    }

    /**
     * 关闭 executor（不再接受新任务，等待正在执行的跑完）。
     * 应用退出时调用，保证最后一条 append 落盘。
     */
    public void close() {
        exec.shutdown();
    }

    /**
     * 读取并解密整个档案。
     * 坏行（解码异常）跳过——档案有可能因密码改过/文件损坏而出现不可解密行，
     * 不能让单行坏掉就把整文件丢了。
     */
    private List<Message> readMessages() {
        List<Message> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // 从 1 开始：第 0 行是头部标记，不是消息。
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    out.add(HistoryCodec.decode(crypto.decrypt(line)));
                } catch (RuntimeException ignored) {
                    // 坏行跳过
                }
            }
        } catch (IOException e) {
            Diag.warn("history", "read failed: %s", e.getMessage());
        }
        return out;
    }

    /**
     * 检查并滚动：消息数超阈值或字节数超阈值时，从最旧开始删。
     *
     * 用 tmp + atomic move 替换：
     *   1. 写到 messages.log.tmp
     *   2. Files.move(tmp, file, ATOMIC_MOVE) 原子替换，避免读到半截文件
     *   3. 失败退回到普通 REPLACE_EXISTING
     *
     * 因为是单线程 executor 串行执行，不需要锁。
     */
    private void trimIfNeeded() throws IOException {
        if (Files.size(file) <= diskMaxBytes) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() <= diskMaxMessages + 1) {
                return;
            }
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return;
        }
        String header = lines.get(0);
        List<String> msgs = new ArrayList<>(lines.subList(1, lines.size()));
        // 先按消息数滚动。
        while (msgs.size() > diskMaxMessages) {
            msgs.remove(0);
        }
        // 再按字节数滚动（密文长度近似用 ASCII 字符数 + 换行）。
        while (encodedSize(header, msgs) > diskMaxBytes && !msgs.isEmpty()) {
            msgs.remove(0);
        }
        Path tmp = file.resolveSibling("messages.log.tmp");
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(System.lineSeparator());
        for (String line : msgs) {
            sb.append(line).append(System.lineSeparator());
        }
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 某些文件系统不支持 atomic move → 退到普通 move。
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictOwnerOnly(file);
    }

    private static long encodedSize(String header, List<String> msgs) {
        long n = header.length() + 1L;
        for (String line : msgs) {
            n += line.length() + 1L;
        }
        return n;
    }

    /**
     * POSIX 系统下把档案设为"只有自己能读写"，避免同机其他用户偷窥。
     * Windows 等不支持 POSIX 权限的系统静默跳过。
     */
    private static void restrictOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等非 POSIX
        }
    }

    /**
     * 把 imCode 哈希成 64 位 hex，用于构造目录名。
     * 不参与密码派生（密码派生是 CryptoService 的事），仅做路径隔离。
     */
    static String sha256Hex(String imCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(imCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}