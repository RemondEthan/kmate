package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.model.Sender;
import com.glodon.mordor.kmate.service.ImClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天业务：把 ImClient 事件转成消息列表，发送时走加密转发。
 */
public class ChatController {

    private final AppState state;
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final Map<Integer, String> peers = new LinkedHashMap<>();

    public ChatController(AppState state) {
        this.state = state;
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
        refreshPeers();
        Diag.log("chat", "controller ready roster=%s header=%s",
                peers.values(), state.peerDisplayProperty().get());
        if (peers.isEmpty()) {
            addSystem("已加入房间，等待对方连接");
        } else {
            addSystem("已与 " + String.join(", ", peers.values()) + " 连接");
        }
    }

    public ObservableList<Message> getMessages() {
        return messages;
    }

    public AppState getState() {
        return state;
    }

    public void send(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            state.client().sendChat(content);
            messages.add(new Message(
                    UUID.randomUUID().toString(),
                    Sender.SELF,
                    content,
                    LocalDateTime.now(),
                    state.username()));
        } catch (Exception e) {
            addSystem("发送失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private void onEvent(ImClient.Event event) {
        switch (event) {
            case ImClient.Event.Chat(String username, String plaintext) ->
                    messages.add(new Message(
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
                refreshPeers();
                addSystem(username + " 已离开");
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
        };
    }

    private void addSystem(String text) {
        messages.add(new Message(
                UUID.randomUUID().toString(),
                Sender.SYSTEM,
                text,
                LocalDateTime.now()));
    }
}
