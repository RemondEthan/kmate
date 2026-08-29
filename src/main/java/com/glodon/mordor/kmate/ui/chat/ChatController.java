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
 * 聊天业务：把 ImClient 事件转成消息列表，发送时走加密转发。
 */
public class ChatController {

    private final AppState state;
    private final ChatHistory history;
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final ObservableList<RoomMember> members = FXCollections.observableArrayList();
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
        this.history = history;
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

    public Image avatarOf(String username) {
        if (username != null && username.equals(state.username())) {
            return state.avatar();
        }
        return username == null ? null : peerAvatars.get(username);
    }

    public void send(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            state.client().sendChat(content);
            followingLatest = true;
            noMoreOlder = false;
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
                // 保留 peerAvatars：聊天记录仍要用对方头像；对方重连后会再发 avatar 覆盖。
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
        if (peers.isEmpty()) {
            state.setPeerDisplay("等待对方");
        } else {
            state.setPeerDisplay(String.join(", ", peers.values()));
        }
        List<RoomMember> next = new ArrayList<>();
        next.add(new RoomMember(-1, state.username(), true));
        peers.forEach((id, name) -> next.add(new RoomMember(id, name, false)));
        members.setAll(next);
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
