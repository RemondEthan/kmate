package com.glodon.mordor.kmate.service;

import com.glodon.mordor.kmate.common.Diag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 对 KServer 的 WebSocket 会话：握手后发 register，收到 registered 再派生密钥。
 * 每 5 秒发 ping + 加密保活（服务端约 90 秒无入站即踢）。
 *
 * 关键概念：
 *
 * 1. WebSocket.Listener：JDK 自带的 WebSocket 客户端 API（Java 11+）。
 *    实现这个接口的实例由 HttpClient.newWebSocketBuilder().buildAsync(uri, listener) 触发回调。
 *    Listener 的回调默认在某个内部线程触发，所以这里所有回调都假设在非 FX 线程。
 *
 * 2. 握手流程：
 *    客户端 connect → onOpen → 发 register → 服务端返回 registered（带 padding）
 *    → 客户端用 padding 派生 AES key → handshake.complete → LoginPane 进入 ChatPane。
 *
 * 3. 加密：
 *    所有 text/avatar 出站消息用 CryptoService 加密（AES-256-GCM）；
 *    服务端只转发密文，不知道明文、不知道密钥。
 *
 * 4. 线程模型：
 *    - httpExec     HttpClient 内部用，按需创建线程。
 *    - sendExec     单线程，序列化所有 sendText/sendPing，避免 WebSocket 并发写。
 *    - keepalive    单线程 ScheduledExecutor，5 秒一次心跳。
 *    - listener     HttpClient 内部线程（不在我们掌控）。
 *    - FX 线程      由 ChatController 自己用 Platform.runLater 跳。
 *
 * 5. 状态机：
 *    closed=true → connect() 把它设 false → onOpen → 等 registered → registered=true → 保活启动。
 *    close() / onClose / onError → 回到 closed=true。
 */
public final class ImClient implements WebSocket.Listener {

    /**
     * 保活用的明文："" 是 ASCII SOH 控制字符，几乎不会出现在正常聊天里。
     * 加密后随 text 帧发出，让服务端知道"我还活着"。收到时直接丢弃，不当成聊天内容。
     */
    public static final String KEEPALIVE = "";

    /*
     * 密封接口 + record：ImClient 向外暴露的事件类型。
     * Java 17+ 的 sealed interface 限定子类只能是这里的这些 record；
     * ChatController.onEvent 用 switch 模式匹配穷举，编译器会检查覆盖完整性。
     *
     * 为什么用 sealed 而不是普通 interface：UI 端需要 switch 穷举所有事件类型
     * 来决定怎么处理，sealed 让漏掉某个事件时编译器报错，避免上线后才发现。
     */
    public sealed interface Event {
        /* 收到 "registered"：握手成功，padding 已收到，AES key 已派生。 */
        record Registered(int userId, String padding) implements Event {}
        /* 解密成功的聊天消息。 */
        record Chat(String username, String plaintext) implements Event {}
        /* 对方 peer_connected：roster 加一人。 */
        record PeerJoined(int userId, String username) implements Event {}
        /* 对方 peer_disconnected。 */
        record PeerLeft(int userId, String username) implements Event {}
        /* 服务端 error 帧，message 是错误描述。 */
        record ServerError(String message) implements Event {}
        /* 连接关闭（主动或被动），reason 是关闭原因。 */
        record Closed(String reason) implements Event {}
        /* 收到 text 帧但解密失败：通常是双方口令不一致。 */
        record DecryptFailed(String username) implements Event {}
        /* 收到对方的头像 PNG 字节。 */
        record PeerAvatar(int userId, String username, byte[] png) implements Event {}
    }

    // 在线会话用的加密器。registered=true 后 key 才非 null。
    private final CryptoService crypto = new CryptoService();
    /*
     * 监听者列表：CopyOnWriteArrayList 让 addListener 和 emit 可以并发
     * （listener 可能在任意线程被调用，UI 端也可能在新线程里 addListener）。
     * 读多写少场景的最简单并发集合。
     */
    private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    /*
     * 在线 peer 名册。KServer 在 peer_connected 时给 user_id + username，
     * peer_disconnected 时按 user_id 移除。
     * 之所以按 user_id 而不是 username，是因为 username 可能重名但 id 是服务端全局唯一。
     */
    private final ConcurrentHashMap<Integer, String> roster = new ConcurrentHashMap<>();
    /*
     * 对方头像 PNG 字节缓存（username → bytes）。新头像到达时覆盖旧值。
     */
    private final ConcurrentHashMap<String, byte[]> avatars = new ConcurrentHashMap<>();
    /*
     * WebSocket onText 可能被切分成多个帧调用；用 StringBuilder 把同一消息的所有帧拼起来，
     * 直到 last=true 才一次性解析。
     */
    private final StringBuilder textBuf = new StringBuilder();

    /*
     * 保活线程：单线程 ScheduledExecutor，每 5s 跑一次。
     * daemon=true：不阻塞 JVM 退出。
     */
    private final ScheduledExecutorService keepalive =
            Executors.newSingleThreadScheduledExecutor(r -> daemon("kmate-keepalive", r));

    // HttpClient 内部用的线程池（按需创建）。
    private final ExecutorService httpExec =
            Executors.newCachedThreadPool(r -> daemon("kmate-http", r));
    /*
     * 发送专用线程：所有 ws.sendText / ws.sendPing / ws.sendClose 都在这里串行执行。
     * 原因：JDK 的 WebSocket 实现要求同一时刻只能有一个写者，并发写会抛异常或乱序。
     */
    private final ExecutorService sendExec =
            Executors.newSingleThreadExecutor(r -> daemon("kmate-ws-send", r));

    private final HttpClient http = HttpClient.newBuilder()
            .executor(httpExec)
            .connectTimeout(Duration.ofSeconds(8))   // TCP 握手最多 8 秒
            .build();

    // 当前活跃 socket。close() 时置 null，再去 sendClose 旧 socket。
    private WebSocket socket;
    private String username = "";
    private String password = "";
    private String imCode = "";
    // 选头像后用 setAvatarPlaintext 设进来；registered 后会 publish 出去。
    private volatile String avatarPlaintext;
    // 是否已收到"registered"帧，密钥已派生。
    private volatile boolean registered;
    // 连接是否已关闭（标志位），connect() 把它设回 false。
    private volatile boolean closed = true;
    /*
     * 握手 Future：connect() 返回这个给调用方，调用方 .thenRun(...) 进入 ChatPane。
     * registered 帧到达时 complete(null)；12 秒超时则抛 TimeoutException。
     */
    private CompletableFuture<Void> handshake = new CompletableFuture<>();
    // 保活任务句柄，close() 时 cancel。
    private ScheduledFuture<?> keepaliveTask;

    public String imCode() {
        return imCode;
    }

    public String password() {
        return password;
    }

    /**
     * 注册一个事件监听器。所有事件（连接成功、收到消息、连接断开等）都会回调到这里。
     * 回调线程 = HttpClient 内部线程，不一定是 FX 线程；监听器自己 hop。
     */
    public void addListener(Consumer<Event> listener) {
        listeners.add(listener);
    }

    /** 在线 peer 名册快照。返回不可变副本。 */
    public Map<Integer, String> roster() {
        return Map.copyOf(roster);
    }

    /** 对方头像字节快照。 */
    public Map<String, byte[]> avatars() {
        return Map.copyOf(avatars);
    }

    /**
     * 设置自己的头像明文（base64 编码的 JPEG 字节）。
     * 若已经注册成功，立刻发出去；否则缓存等 registered 时再发。
     */
    public void setAvatarPlaintext(String base64Png) {
        this.avatarPlaintext = base64Png;
        if (registered && base64Png != null && !base64Png.isBlank()) {
            publishAvatar();
        }
    }

    /**
     * 建立 WebSocket 连接、发送 register 帧、等待 registered 完成握手。
     * 返回的 CompletableFuture 在握手成功（12 秒内）时完成，否则抛异常。
     *
     * 流程：
     *   1. close() 先关掉旧连接（如果有）。
     *   2. 重置所有状态（roster、avatars、registered）。
     *   3. 异步建立 WebSocket，onOpen 后立即 send register。
     *   4. handleRaw 收到 "registered" → handshake.complete。
     */
    public CompletableFuture<Void> connect(String host, int port, String imCode,
                                           String password, String username) {
        close();
        this.closed = false;
        this.username = username;
        this.password = password;
        this.imCode = imCode;
        this.registered = false;
        // 新一次连接的 handshake；旧的已经在 close() 里 completeExceptionally。
        this.handshake = new CompletableFuture<>();
        roster.clear();
        avatars.clear();

        URI uri = URI.create("ws://" + host + ":" + port + "/");
        Diag.log("ws", "connect %s user=%s", uri, username);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .buildAsync(uri, this)   // this 作为 Listener 传进去
                .thenAccept(ws -> {
                    // 这一步还在 HttpClient 内部线程，不在 FX 线程。
                    this.socket = ws;
                    Diag.log("ws", "socket open, enqueue register");
                    // register 不需要"已注册"，false 表示忽略 registered 检查。
                    enqueueSend(Protocol.register(imCode, username), false);
                })
                .exceptionally(ex -> {
                    Diag.error("ws", "connect failed: %s", unwrap(ex).toString());
                    handshake.completeExceptionally(unwrap(ex));
                    return null;
                });

        // 整体 12 秒握手超时；超时后 handshake 抛 TimeoutException。
        return handshake.orTimeout(12, TimeUnit.SECONDS);
    }

    /**
     * 发送一条加密消息。会在 sendExec 线程串行加密 + sendText。
     *
     * @throws IllegalStateException 尚未注册成功
     */
    public void sendChat(String plaintext) {
        sendEncrypted(plaintext);
    }

    /**
     * 关闭连接。可重复调用；幂等。
     * 流程：标志位置 closed → 取消保活 → 异步 sendClose(1秒超时) → 失败也不抛。
     */
    public void close() {
        Diag.log("ws", "close requested registered=%s", registered);
        closed = true;
        stopKeepalive();
        registered = false;
        roster.clear();
        avatars.clear();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            sendExec.execute(() -> {
                long t0 = System.nanoTime();
                try {
                    // 协议层 close 帧；服务端看到会清理房间映射。
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
                    Diag.log("ws", "sendClose done %dms", Diag.elapsedMs(t0));
                } catch (Exception e) {
                    Diag.error("ws", "sendClose failed: %s", e.toString());
                } finally {
                    closed.complete(null);
                }
            });
            try {
                closed.get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 退出路径：尽力发送 close，超时也继续
            }
        }
        if (!handshake.isDone()) {
            handshake.completeExceptionally(new IllegalStateException("连接已关闭"));
        }
    }

    /**
     * WebSocket 连接打开回调。在 HttpClient 内部线程触发。
     * 必须 webSocket.request(1) 才能让后续 onText / onBinary 被调用：
     * JDK 的 WebSocket Listener 用 back-pressure，需要业务方显式 request(N) 才会触发 N 次回调。
     */
    @Override
    public void onOpen(WebSocket webSocket) {
        Diag.log("ws", "onOpen");
        webSocket.request(1);
    }

    /**
     * 收到文本帧回调。可能一帧接一段，last=true 才表示整条消息结束。
     *
     * 流程：累积到 textBuf → last 时 parse + handleRaw → request(1) 让下一帧到达。
     */
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuf.append(data);
        if (last) {
            String raw = textBuf.toString();
            textBuf.setLength(0);
            long t0 = System.nanoTime();
            handleRaw(raw);
            long ms = Diag.elapsedMs(t0);
            // 处理单帧超过 50ms 就 warn，便于发现协议解析慢。
            if (ms >= 50) {
                Diag.log("ws", "handleRaw slow %dms len=%d", ms, raw.length());
            }
        }
        webSocket.request(1);
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    /**
     * 服务端关闭连接回调。reason 可能是空（异常断连）。
     * 这里停止保活 + 通知监听者 + 让未完成的 handshake 抛异常。
     */
    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        Diag.log("ws", "onClose code=%d reason=%s", statusCode, reason);
        stopKeepalive();
        registered = false;
        String msg = reason == null || reason.isBlank() ? "连接已断开" : reason;
        if (!handshake.isDone()) {
            handshake.completeExceptionally(new IllegalStateException(msg));
        }
        emit(new Event.Closed(msg));
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    /**
     * WebSocket 错误回调。HttpClient 内部线程。
     */
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        Diag.error("ws", "onError: %s", error == null ? "null" : error.toString());
        if (!handshake.isDone()) {
            handshake.completeExceptionally(unwrap(error));
        }
        emit(new Event.Closed(error.getMessage() == null ? "WebSocket 错误" : error.getMessage()));
    }

    /**
     * 二进制帧回调。本协议不用二进制帧，只 request 一帧后让 JDK 丢弃。
     */
    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        webSocket.request(1);
        return WebSocket.Listener.super.onBinary(webSocket, data, last);
    }

    /**
     * 解析一条完整 JSON 文本，按 type 分发到具体处理逻辑。
     *
     * 注意：这里 switch 用字符串字面量，是经典 switch（字符串匹配），编译器会生成 hash 表加速。
     */
    private void handleRaw(String raw) {
        Protocol.Incoming msg = Protocol.parse(raw);
        Diag.log("ws", "recv type=%s user=%s id=%d listeners=%d roster=%d",
                msg.type(), msg.username(), msg.userId(), listeners.size(), roster.size());
        switch (msg.type()) {
            case "registered" -> {
                if (closed) {
                    // 收到 registered 但用户已经主动关掉了 → 丢弃。
                    return;
                }
                // 关键：用服务端给的 padding 派生 AES key。从此密文能解。
                crypto.initialize(password, msg.padding());
                registered = true;
                startKeepalive();
                publishAvatar();
                Diag.log("ws", "handshake complete userId=%d roster=%s",
                        msg.userId(), roster.values());
                handshake.complete(null);
                emit(new Event.Registered(msg.userId(), msg.padding()));
            }
            case "avatar" -> {
                if (!crypto.isReady()) {
                    Diag.warn("ws", "drop avatar, crypto not ready");
                    return;
                }
                try {
                    // 头像也是加密的；先解密再 Base64 decode 出 PNG 字节。
                    String plain = crypto.decrypt(msg.content());
                    byte[] png = Base64.getDecoder().decode(plain);
                    avatars.put(msg.username(), png);
                    Diag.log("ws", "recv avatar user=%s bytes=%d", msg.username(), png.length);
                    emit(new Event.PeerAvatar(msg.userId(), msg.username(), png));
                } catch (Exception e) {
                    Diag.warn("ws", "avatar decode failed user=%s: %s", msg.username(), e.toString());
                }
            }
            case "text" -> {
                if (!crypto.isReady()) {
                    Diag.warn("ws", "drop text, crypto not ready");
                    return;
                }
                try {
                    String plain = crypto.decrypt(msg.content());
                    // 保活 sentinel：收到后丢弃，不当成聊天内容。
                    if (KEEPALIVE.equals(plain)) {
                        return;
                    }
                    emit(new Event.Chat(msg.username(), plain));
                } catch (CryptoService.CryptoException e) {
                    // 解密失败通常是双方 password 不一致，提示用户检查口令。
                    emit(new Event.DecryptFailed(msg.username()));
                }
            }
            case "peer_connected" -> {
                roster.put(msg.userId(), msg.username());
                emit(new Event.PeerJoined(msg.userId(), msg.username()));
            }
            case "peer_disconnected" -> {
                roster.remove(msg.userId());
                avatars.remove(msg.username());
                emit(new Event.PeerLeft(msg.userId(), msg.username()));
            }
            case "error" -> {
                Diag.error("ws", "server error: %s", msg.message());
                if (!handshake.isDone()) {
                    handshake.completeExceptionally(new IllegalStateException(msg.message()));
                }
                emit(new Event.ServerError(msg.message()));
            }
            default -> Diag.warn("ws", "unknown type ignored: %s", msg.type());
        }
    }

    /**
     * 把当前 avatarPlaintext 加密后通过 avatar 帧发出。
     * 只在已注册且有明文时调用。
     */
    private void publishAvatar() {
        String plain = avatarPlaintext;
        if (!registered || !crypto.isReady() || plain == null || plain.isBlank()) {
            return;
        }
        String cipher = crypto.encrypt(plain);
        Diag.log("ws", "publish avatar cipherLen=%d", cipher.length());
        enqueueSend(Protocol.avatar(cipher), true);
    }

    /**
     * 发 ping 帧：RFC 6455 协议层心跳，触发服务端发回 pong。
     * 服务端 90 秒无入站即踢，所以这里 5 秒一次。
     */
    private void sendPing() {
        sendExec.execute(() -> {
            WebSocket ws = socket;
            if (ws == null || closed) {
                return;
            }
            try {
                ws.sendPing(ByteBuffer.wrap(new byte[]{1})).join();
            } catch (Exception e) {
                Diag.warn("ws", "ping failed: %s", e.toString());
            }
        });
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return WebSocket.Listener.super.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return WebSocket.Listener.super.onPong(webSocket, message);
    }

    /**
     * 加密 + 包装 + 投递 sendExec。
     */
    private void sendEncrypted(String plaintext) {
        if (!registered || !crypto.isReady()) {
            throw new IllegalStateException("尚未注册成功");
        }
        String payload = Protocol.text(crypto.encrypt(plaintext), username);
        enqueueSend(payload, true);
    }

    /**
     * 所有 ws.sendXxx 都在 sendExec 线程串行执行，避免并发写。
     * requireRegistered=true 时若 registered=false 直接丢弃（防竞态）。
     */
    private void enqueueSend(String payload, boolean requireRegistered) {
        sendExec.execute(() -> {
            WebSocket ws = socket;
            if (ws == null || closed || (requireRegistered && !registered)) {
                Diag.warn("ws", "send dropped socket=%s closed=%s registered=%s",
                        ws != null, closed, registered);
                return;
            }
            long t0 = System.nanoTime();
            try {
                Diag.log("ws", "sendText begin len=%d", payload.length());
                ws.sendText(payload, true).join();
                Diag.log("ws", "sendText done %dms", Diag.elapsedMs(t0));
            } catch (Exception e) {
                Diag.error("ws", "sendText failed after %dms: %s", Diag.elapsedMs(t0), e.toString());
            }
        });
    }

    /** 创建守护线程的工具方法，避免每个 new Thread 都写一遍 setDaemon(true)。 */
    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * 启动保活：每 5 秒发 WebSocket ping + 加密 KEEPALIVE。
     * 先 stopKeepalive() 保证不重复 schedule。
     */
    private void startKeepalive() {
        stopKeepalive();
        keepaliveTask = keepalive.scheduleAtFixedRate(() -> {
            try {
                if (registered) {
                    sendPing();
                    sendEncrypted(KEEPALIVE);
                }
            } catch (Exception ignored) {
                // 保活失败由关闭回调处理
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /** 取消保活任务句柄。close() 时调用。 */
    private void stopKeepalive() {
        if (keepaliveTask != null) {
            keepaliveTask.cancel(false);
            keepaliveTask = null;
        }
    }

    /**
     * 广播事件给所有监听者。
     * 某个监听者抛异常被吞掉，避免把后续监听者牵连。
     */
    private void emit(Event event) {
        Diag.log("ws", "emit %s listeners=%d", event.getClass().getSimpleName(), listeners.size());
        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                Diag.error("ws", "listener failed %s: %s", event.getClass().getSimpleName(), e.toString());
            }
        }
    }

    /**
     * 拆开嵌套的 CompletionException 包装，直到拿到真正的根因。
     * JDK 的 Future 接口默认会用 CompletionException 包一层，根因在 getCause()。
     */
    private static Throwable unwrap(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur != cur.getCause()) {
            cur = cur.getCause();
        }
        return cur;
    }
}