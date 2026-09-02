package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.kelsy.KelsyPaths;
import com.glodon.mordor.kmate.kelsy.KelsyRoomSettings;
import com.glodon.mordor.kmate.kelsy.KelsyRuntime;
import com.glodon.mordor.kmate.kelsy.KelsySendRouter;
import com.glodon.mordor.kmate.kelsy.service.AssistantService;
import com.glodon.mordor.kmate.kelsy.service.FindQuery;
import com.glodon.mordor.kmate.kelsy.service.KnowledgeStore;
import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.model.RoomMember;
import com.glodon.mordor.kmate.model.Sender;
import com.glodon.mordor.kmate.service.AvatarService;
import com.glodon.mordor.kmate.service.ChatHistory;
import com.glodon.mordor.kmate.service.CryptoService;
import com.glodon.mordor.kmate.service.ImClient;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.IntegerBinding;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.image.Image;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天业务：把 ImClient 事件转成消息列表，发送时走加密转发。
 */
public class ChatController {

    public interface PeerSender {
        void sendChat(String text) throws Exception;
    }

    private final AppState state;
    private final String imCode;
    private final String username;
    private final ChatHistory history;
    private final PeerSender peerSender;
    private final KelsyRoomSettings settings;
    private KelsyRuntime runtime;
    private final AtomicBoolean kelsyBusy = new AtomicBoolean();
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final ObservableList<RoomMember> members = FXCollections.observableArrayList();
    private final IntegerBinding humanCount = Bindings.createIntegerBinding(this::countHumans, members);
    private final ObservableMap<String, Image> peerAvatars = FXCollections.observableHashMap();
    private final Map<Integer, String> peers = new LinkedHashMap<>();
    private final List<Message> liveDuringLoad = new ArrayList<>();
    private boolean historyReady;
    private boolean followingLatest = true;
    private boolean loadingOlder;
    private boolean noMoreOlder;

    public ChatController(AppState state) {
        this(state, createHistory(state));
    }

    ChatController(AppState state, ChatHistory history) {
        this.state = state;
        this.imCode = state.client().imCode();
        this.username = state.username();
        this.history = history;
        this.peerSender = state.client()::sendChat;
        this.settings = new KelsyRoomSettings();
        this.runtime = null;
        if (this.settings.enabled(this.imCode)) {
            try {
                this.runtime = KelsyRuntime.shared(this.username);
            } catch (UnsupportedOperationException ignored) {
                this.runtime = null;
            }
        }
        state.client().addListener(event -> {
            Diag.log("chat", "queue %s fx=%s", eventName(event), Platform.isFxApplicationThread());
            Platform.runLater(() -> {
                long t0 = System.nanoTime();
                onEvent(event);
                Diag.log("chat", "apply %s %dms peers=%s",
                        eventName(event), Diag.elapsedMs(t0), peers.values());
            });
        });
        peers.putAll(state.client().roster());
        state.client().avatars().forEach((name, png) ->
                AvatarService.fromPngBytes(png).ifPresent(img -> peerAvatars.put(name, img)));
        refreshPeers();
        Diag.log("chat", "controller ready roster=%s header=%s",
                peers.values(), state.peerDisplayProperty().get());
        history.loadInitialAsync(loaded -> Platform.runLater(() -> onHistoryLoaded(loaded)));
    }

    ChatController(String imCode, String username, ChatHistory history,
                   PeerSender peerSender, KelsyRoomSettings settings, KelsyRuntime runtime) {
        this.state = null;
        this.imCode = imCode;
        this.username = username;
        this.history = history;
        this.peerSender = peerSender;
        this.settings = settings;
        this.runtime = runtime;
        refreshPeers();
    }

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

    public String imCode() {
        return imCode;
    }

    public IntegerBinding humanCountProperty() {
        return humanCount;
    }

    public Image avatarOf(String username) {
        if (username != null && (RoomMember.kelsy().username().equals(username)
                || "kelsy".equalsIgnoreCase(username))) {
            return AvatarService.load(settings.avatarPath(imCode)).orElse(null);
        }
        if (state != null && username != null && username.equals(state.username())) {
            return state.avatar();
        }
        return username == null ? null : peerAvatars.get(username);
    }

    public boolean send(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        boolean enabled = settings.enabled(imCode);
        boolean configured = enabled && runtime != null && runtime.hasApiKey();
        var route = KelsySendRouter.route(enabled, kelsyBusy.get(), configured, content);
        return switch (route.kind()) {
            case PEER -> sendPeer(content);
            case BUSY -> false;
            case UNCONFIGURED -> {
                addSystem("尚未配置秘书 API key：" + (runtime == null
                        ? KelsyPaths.defaults().config()
                        : runtime.paths().config()));
                yield true;
            }
            case EMPTY_BODY, SLASH_ERROR -> {
                addSystem(route.error());
                yield true;
            }
            case FIND -> {
                addSelf(content);
                runFind(route.outgoing());
                yield true;
            }
            case ASK -> {
                addSelf(content);
                startAsk(route.outgoing());
                yield true;
            }
        };
    }

    public void enableKelsy(String avatarPath) {
        settings.enable(imCode, avatarPath);
        if (runtime == null) {
            runtime = KelsyRuntime.shared(username);
        }
        refreshPeers();
    }

    public void disableKelsy() {
        settings.disable(imCode);
        refreshPeers();
    }

    private boolean sendPeer(String content) {
        try {
            peerSender.sendChat(content);
            addSelf(content);
        } catch (Exception e) {
            addSystem("发送失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        return true;
    }

    private void addSelf(String content) {
        followingLatest = true;
        noMoreOlder = false;
        addMessage(new Message(
                UUID.randomUUID().toString(),
                Sender.SELF,
                content,
                LocalDateTime.now(),
                username));
    }

    private void startAsk(String outgoing) {
        kelsyBusy.set(true);
        AssistantService assistant = runtime.ensureAssistant();
        if (assistant == null) {
            kelsyBusy.set(false);
            return;
        }
        assistant.chat(outgoing, new AssistantService.ReplyHandler() {
            @Override
            public void onTextDelta(String delta) {
            }

            @Override
            public void onToolCall(String name, String argsPreview) {
            }

            @Override
            public void onComplete() {
                kelsyBusy.set(false);
            }

            @Override
            public void onError(Throwable error) {
                kelsyBusy.set(false);
            }
        });
    }

    private void runFind(String query) {
        if (runtime == null) {
            return;
        }
        List<KnowledgeStore.Hit> hits = runtime.store(username)
                .search(FindQuery.parse(query, LocalDate.now()));
        addSystem(formatFind(hits));
    }

    private static String formatFind(List<KnowledgeStore.Hit> hits) {
        if (hits.isEmpty()) {
            return "知识库中没有匹配";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeStore.Hit hit : hits) {
            sb.append(hit.relativePath()).append(':').append(hit.line())
                    .append(' ').append(hit.snippet()).append('\n');
        }
        return sb.toString().strip();
    }

    public void requestOlder() {
        if (loadingOlder || noMoreOlder || messages.isEmpty()) {
            return;
        }
        loadingOlder = true;
        followingLatest = false;
        String firstId = messages.get(0).id();
        history.loadOlderThanAsync(firstId, ChatHistory.PAGE_SIZE, older -> Platform.runLater(() -> {
            loadingOlder = false;
            if (older.isEmpty()) {
                noMoreOlder = true;
                return;
            }
            noMoreOlder = older.size() < ChatHistory.PAGE_SIZE;
            messages.addAll(0, older);
            while (messages.size() > ChatHistory.MEMORY_CAP) {
                messages.remove(messages.size() - 1);
            }
        }));
    }

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

    public void stopFollowing() {
        followingLatest = false;
    }

    public boolean followingLatest() {
        return followingLatest;
    }

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
                boolean firstSeen = peers.put(userId, username) == null;
                refreshPeers();
                if (firstSeen) {
                    addSystem(username + " 已加入");
                }
            }
            case ImClient.Event.PeerLeft(int userId, String username) -> {
                peers.remove(userId);
                peerAvatars.remove(username);
                refreshPeers();
                addSystem(username + " 已离开");
            }
            case ImClient.Event.PeerAvatar(int ignored, String username, byte[] png) -> {
                Optional<Image> img = AvatarService.fromPngBytes(png);
                if (img.isPresent()) {
                    peerAvatars.put(username, img.get());
                } else {
                    Diag.warn("chat", "peer avatar decode failed user=%s bytes=%d",
                            username, png == null ? 0 : png.length);
                }
            }
            case ImClient.Event.Closed(String reason) -> {
                state.setOnline(false);
                addSystem(reason);
            }
            case ImClient.Event.ServerError(String message) ->
                    addSystem("[错误] " + message);
            case ImClient.Event.DecryptFailed(String username) ->
                    addSystem("无法解密 " + username + " 的消息（口令是否一致？）");
            case ImClient.Event.Registered ignored -> {
                // 登录阶段已处理
            }
        }
    }

    private void refreshPeers() {
        if (state != null) {
            if (peers.isEmpty()) {
                state.setPeerDisplay("等待对方");
            } else {
                state.setPeerDisplay(String.join(", ", peers.values()));
            }
        }
        List<RoomMember> next = new ArrayList<>();
        next.add(new RoomMember(-1, username, true));
        peers.forEach((id, name) -> next.add(new RoomMember(id, name, false)));
        if (settings.enabled(imCode)) {
            next.add(1, RoomMember.kelsy());
        }
        members.setAll(next);
    }

    private int countHumans() {
        int n = 0;
        for (RoomMember member : members) {
            if (!member.isKelsy()) {
                n++;
            }
        }
        return n;
    }

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
        if (peers.isEmpty()) {
            addSystem("已加入房间，等待对方连接");
        } else {
            addSystem("已与 " + String.join(", ", peers.values()) + " 连接");
        }
        Diag.log("chat", "history loaded n=%d unlocked=%s", loaded.size(), history.isUnlocked());
    }

    private void addMessage(Message message) {
        history.appendAsync(message);
        if (!historyReady) {
            liveDuringLoad.add(message);
            messages.add(message);
            return;
        }
        if (!followingLatest) {
            return;
        }
        messages.add(message);
        evictFromHead();
    }

    private void evictFromHead() {
        while (messages.size() > ChatHistory.MEMORY_CAP) {
            messages.remove(0);
        }
    }

    private void addSystem(String text) {
        addMessage(new Message(
                UUID.randomUUID().toString(),
                Sender.SYSTEM,
                text,
                LocalDateTime.now()));
    }
}
