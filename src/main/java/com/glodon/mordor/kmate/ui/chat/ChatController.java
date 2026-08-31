package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.model.RoomMember;
import com.glodon.mordor.kmate.model.Sender;
import com.glodon.mordor.kmate.service.AvatarService;
import com.glodon.mordor.kmate.service.ChatHistory;
import com.glodon.mordor.kmate.service.CryptoService;
import com.glodon.mordor.kmate.service.ImClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.image.Image;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天业务控制器：把 ImClient 事件转换成消息列表，发消息时走加密转发。
 *
 * <p>职责：
 * <ul>
 *   <li>订阅 ImClient 事件，把 Chat / PeerJoined / PeerLeft / DecryptFailed / Closed 等转成 Message</li>
 *   <li>维护"在线列表"（peers）和"群成员表"（members）</li>
 *   <li>维护对方的头像缓存（peerAvatars）</li>
 *   <li>异步加载聊天历史，UI 显示前先拼好"历史 + 实时"</li>
 *   <li>提供 send / requestOlder / followLatest 等方法给 UI 层调用</li>
 * </ul>
 *
 * <p>线程模型：
 * <ul>
 *   <li>所有 ObservableList / ObservableMap 修改都在 FX 线程（事件回调里 Platform.runLater）</li>
 *   <li>ChatHistory 内部走独立线程，本类用 Platform.runLater 把回调跳回 FX</li>
 * </ul>
 */
public class ChatController {

    // 当前登录状态（用户名、IM 客户端、自己的头像等）
    private final AppState state;
    // 聊天历史持久化（默认存到 ~/.../chat-<imCode>.dat，加密落盘）
    private final ChatHistory history;

    // 给 ListView 绑定的数据源
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final ObservableList<RoomMember> members = FXCollections.observableArrayList();

    // 对方用户名 → 头像图片。FX ObservableMap，UI 可以直接 bind 监听变化
    private final ObservableMap<String, Image> peerAvatars = FXCollections.observableHashMap();

    // 当前房间的在线名单：userId → 用户名。LinkedHashMap 保持插入顺序（先来先排）
    private final Map<Integer, String> peers = new LinkedHashMap<>();

    // 历史还没加载完时收到的实时消息暂存这里，加载完做去重合并
    private final List<Message> liveDuringLoad = new ArrayList<>();

    // 历史是否加载完成。false 时收到的消息进 liveDuringLoad 而不是直接加进 messages
    private boolean historyReady;
    // 是否跟随最新消息。用户向上翻看历史时会被设为 false
    private boolean followingLatest = true;
    // 是否正在加载更老的历史（防止重复触发）
    private boolean loadingOlder;
    // 没有更多历史了（服务器或本地文件用完了）
    private boolean noMoreOlder;

    public ChatController(AppState state) {
        this(state, createHistory(state));
    }

    /**
     * 测试用构造器：允许注入一个假的 ChatHistory。
     * 正常代码路径走上面的单参构造，里面会调 createHistory 自己造一个。
     */
    ChatController(AppState state, ChatHistory history) {
        this.state = state;
        this.history = history;

        // 订阅 ImClient 事件。事件来自 ImClient 自己的线程池，必须 hop 到 FX 线程
        // 才能碰 ObservableList。
        state.client().addListener(event -> {
            Diag.log("chat", "queue %s fx=%s", eventName(event), Platform.isFxApplicationThread());
            Platform.runLater(() -> {
                long t0 = System.nanoTime();
                onEvent(event);
                Diag.log("chat", "apply %s %dms peers=%s",
                        eventName(event), Diag.elapsedMs(t0), peers.values());
            });
        });

        // 握手后服务器已经把当前房间的在线名单推过来，先搬进 peers
        peers.putAll(state.client().roster());
        // 顺便把服务器推过来的 PNG 头像解码成 Image，存进 peerAvatars
        state.client().avatars().forEach((name, png) ->
                AvatarService.fromPngBytes(png).ifPresent(img -> peerAvatars.put(name, img)));
        refreshPeers();
        Diag.log("chat", "controller ready roster=%s header=%s",
                peers.values(), state.peerDisplayProperty().get());

        // 异步加载聊天历史，加载完成跳回 FX 线程
        history.loadInitialAsync(loaded -> Platform.runLater(() -> onHistoryLoaded(loaded)));
    }

    /**
     * 给定 AppState 构造默认的 ChatHistory：文件路径 = 默认目录 + imCode.dat，
     * 加密用 password + imCode 派生的 key。
     */
    private static ChatHistory createHistory(AppState state) {
        ImClient client = state.client();
        return new ChatHistory(
                ChatHistory.defaultFile(client.imCode()),
                CryptoService.forArchive(client.password(), client.imCode()));
    }

    public ObservableList<Message> getMessages() {
        return messages;
    }

    public ObservableList<RoomMember> getMembers() {
        return members;
    }

    public AppState getState() {
        return state;
    }

    public ObservableMap<String, Image> peerAvatars() {
        return peerAvatars;
    }

    /**
     * 按用户名拿头像。自己从 state.avatar() 拿，别人的从 peerAvatars 拿。
     */
    public Image avatarOf(String username) {
        if (username != null && username.equals(state.username())) {
            return state.avatar();
        }
        return username == null ? null : peerAvatars.get(username);
    }

    /**
     * 发消息：调 ImClient.sendChat 加密转发，再把消息加进列表。
     * 失败时往消息列表塞一条系统消息（红色）。
     */
    public void send(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            state.client().sendChat(content);
            // 刚发完消息，认为用户回到"跟随最新"状态
            followingLatest = true;
            // 也清掉"没更多历史"的标记，下次能再往上翻
            noMoreOlder = false;
            // 自己发的消息自己先显示（乐观更新），不用等服务端回包
            addMessage(new Message(
                    UUID.randomUUID().toString(),
                    Sender.SELF,
                    content,
                    LocalDateTime.now(),
                    state.username()));
        } catch (Exception e) {
            addSystem("发送失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /**
     * 请求加载更老的历史。
     *
     * <p>触发条件：用户向上滚动到顶。
     * 已经在加载 / 已经到底 / 列表空 都直接 return。
     */
    public void requestOlder() {
        if (loadingOlder || noMoreOlder || messages.isEmpty()) {
            return;
        }
        loadingOlder = true;
        followingLatest = false;
        // 取当前最老一条的 id，按这个 id 找更早的
        String firstId = messages.get(0).id();
        history.loadOlderThanAsync(firstId, ChatHistory.PAGE_SIZE, older -> Platform.runLater(() -> {
            loadingOlder = false;
            if (older.isEmpty()) {
                noMoreOlder = true;
                return;
            }
            // 返回的条数 < PAGE_SIZE 就认为到底了
            noMoreOlder = older.size() < ChatHistory.PAGE_SIZE;
            // 插到列表头部（更老的在上面）
            messages.addAll(0, older);
            // 内存里只保留最近 MEMORY_CAP 条，超出就从尾部删
            while (messages.size() > ChatHistory.MEMORY_CAP) {
                messages.remove(messages.size() - 1);
            }
        }));
    }

    /**
     * 用户点了"跳到最新"：放弃当前查看位置，加载最近 MEMORY_CAP 条覆盖整个列表。
     */
    public void followLatest() {
        if (followingLatest) {
            return;
        }
        followingLatest = true;
        noMoreOlder = false;
        history.loadNewestAsync(ChatHistory.MEMORY_CAP, newest -> Platform.runLater(() -> {
            messages.setAll(newest);
            evictFromHead();
        }));
    }

    /**
     * 用户开始向上翻看历史，标记为"不再跟随最新"。
     * 新消息到了不再自动插到列表末尾（否则用户会看到列表跳一下）。
     */
    public void stopFollowing() {
        followingLatest = false;
    }

    public boolean followingLatest() {
        return followingLatest;
    }

    /**
     * ImClient 事件分发。sealed interface + switch expression 编译期穷举。
     */
    private void onEvent(ImClient.Event event) {
        switch (event) {
            case ImClient.Event.Chat(String username, String plaintext) ->
                    addMessage(new Message(
                            UUID.randomUUID().toString(),
                            Sender.PEER,
                            plaintext,
                            LocalDateTime.now(),
                            username));
            case ImClient.Event.PeerJoined(int userId, String username) -> {
                // firstSeen：putIfAbsent 的语义，第一次见到这个 userId 才提示"已加入"
                boolean firstSeen = peers.put(userId, username) == null;
                refreshPeers();
                if (firstSeen) {
                    addSystem(username + " 已加入");
                }
            }
            case ImClient.Event.PeerLeft(int userId, String username) -> {
                peers.remove(userId);
                // 对方走了，头像缓存也跟着删
                peerAvatars.remove(username);
                refreshPeers();
                addSystem(username + " 已离开");
            }
            case ImClient.Event.PeerAvatar(int ignored, String username, byte[] png) -> {
                // 服务器推过来的 PNG 字节流，解码成 Image 存进缓存
                Optional<Image> img = AvatarService.fromPngBytes(png);
                if (img.isPresent()) {
                    peerAvatars.put(username, img.get());
                } else {
                    Diag.warn("chat", "peer avatar decode failed user=%s bytes=%d",
                            username, png == null ? 0 : png.length);
                }
            }
            case ImClient.Event.Closed(String reason) -> {
                // 连接断了，把 online 标 false（标题栏的红绿灯会变红）
                state.setOnline(false);
                addSystem(reason);
            }
            case ImClient.Event.ServerError(String message) ->
                    addSystem("[错误] " + message);
            case ImClient.Event.DecryptFailed(String username) ->
                    // 一般是双方口令不一致
                    addSystem("无法解密 " + username + " 的消息（口令是否一致？）");
            case ImClient.Event.Registered ignored -> {
                // 登录阶段已经处理过 Registered，这里 no-op
            }
        }
    }

    /**
     * 把 peers 转成两个 UI 用的状态：
     * <ol>
     *   <li>state.peerDisplay（标题栏右侧的"对方"显示）</li>
     *   <li>members（左侧成员列表 ObservableList）</li>
     * </ol>
     */
    private void refreshPeers() {
        if (peers.isEmpty()) {
            state.setPeerDisplay("等待对方");
        } else {
            state.setPeerDisplay(String.join(", ", peers.values()));
        }
        // 成员列表：自己（id = -1，self = true）+ 所有人
        List<RoomMember> next = new ArrayList<>();
        next.add(new RoomMember(-1, state.username(), true));
        peers.forEach((id, name) -> next.add(new RoomMember(id, name, false)));
        members.setAll(next);
    }

    /**
     * 事件名拼字符串，仅用于 Diag 日志。switch 也是 sealed 穷举。
     */
    private static String eventName(ImClient.Event event) {
        return switch (event) {
            case ImClient.Event.PeerJoined(int userId, String username) ->
                    "PeerJoined(" + userId + "," + username + ")";
            case ImClient.Event.PeerLeft(int userId, String username) ->
                    "PeerLeft(" + userId + "," + username + ")";
            case ImClient.Event.Chat(String username, String plaintext) ->
                    "Chat(" + username + ",len=" + plaintext.length() + ")";
            case ImClient.Event.Closed(String reason) -> "Closed(" + reason + ")";
            case ImClient.Event.ServerError(String message) -> "ServerError(" + message + ")";
            case ImClient.Event.DecryptFailed(String username) -> "DecryptFailed(" + username + ")";
            case ImClient.Event.Registered(int userId, String ignored) -> "Registered(" + userId + ")";
            case ImClient.Event.PeerAvatar(int userId, String username, byte[] png) ->
                    "PeerAvatar(" + userId + "," + username + ",len=" + png.length + ")";
        };
    }

    /**
     * 历史加载完成。把"暂存的实时消息"和"历史"做去重合并。
     *
     * <p>去重逻辑：按 message.id 比对，同 id 的实时消息丢弃（说明服务端回包了，或者本地已经存过）。
     */
    private void onHistoryLoaded(List<Message> loaded) {
        List<Message> merged = new ArrayList<>(loaded);
        for (Message live : liveDuringLoad) {
            boolean already = false;
            for (Message existing : merged) {
                if (existing.id().equals(live.id())) {
                    already = true;
                    break;
                }
            }
            if (!already) {
                merged.add(live);
            }
        }
        messages.setAll(merged);
        evictFromHead();
        historyReady = true;
        // 加载完后塞一条系统消息告诉用户当前状态
        if (peers.isEmpty()) {
            addSystem("已加入房间，等待对方连接");
        } else {
            addSystem("已与 " + String.join(", ", peers.values()) + " 连接");
        }
        Diag.log("chat", "history loaded n=%d unlocked=%s", loaded.size(), history.isUnlocked());
    }

    /**
     * 收到 / 发出消息的统一入口：先落盘，再决定加进列表还是暂存。
     */
    private void addMessage(Message message) {
        // 先异步落盘（ChatHistory 内部用独立线程串行写）
        history.appendAsync(message);
        // 历史还没加载完：实时消息不能直接加进 messages（会和加载回来的历史重复）
        if (!historyReady) {
            liveDuringLoad.add(message);
            messages.add(message);
            return;
        }
        // 用户在看历史：实时消息来了先不插，避免列表跳动
        if (!followingLatest) {
            return;
        }
        messages.add(message);
        evictFromHead();
    }

    /**
     * 内存里只留 MEMORY_CAP 条最新的，从头开始删。
     * 异步落盘那边是全量的，所以删内存里的不影响持久化。
     */
    private void evictFromHead() {
        while (messages.size() > ChatHistory.MEMORY_CAP) {
            messages.remove(0);
        }
    }

    /**
     * 系统消息：灰色居中显示的那种。生成一个 UUID 当 id，SENDER = SYSTEM。
     */
    private void addSystem(String text) {
        addMessage(new Message(
                UUID.randomUUID().toString(),
                Sender.SYSTEM,
                text,
                LocalDateTime.now()));
    }
}
