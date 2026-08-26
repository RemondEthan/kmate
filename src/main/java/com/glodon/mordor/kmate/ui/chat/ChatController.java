package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.model.Sender;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 聊天面板的"业务"侧:持有消息列表,提供 bootstrap / send。
 *
 * 渲染由 MessageListView 通过订阅 messages 自动完成;Controller 不持有 UI 节点。
 * 当前 demo 阶段:bootstrap 写入 7 条示例数据;send 追加一条 SELF 消息。
 */
public class ChatController {

    private final AppState state;
    private final ObservableList<Message> messages =
            FXCollections.observableArrayList();

    public ChatController(AppState state) {
        this.state = state;
        bootstrapDemoMessages();
    }

    public ObservableList<Message> getMessages() { return messages; }
    public AppState getState() { return state; }

    /**
     * 发送一条 SELF 消息。空文本忽略。
     * 当前 demo:时间戳直接取 LocalDateTime.now(),无后端。
     */
    public void send(String content) {
        if (content == null || content.isBlank()) return;
        messages.add(new Message(
                UUID.randomUUID().toString(),
                Sender.SELF,
                content,
                LocalDateTime.now()));
    }

    // 当前 demo 数据;未来接入后端时由 controller.connect() / receive() 替换。
    private void bootstrapDemoMessages() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 24, 9, 30);
        messages.addAll(
                new Message("m1", Sender.SYSTEM, "已与 Alice 建立加密通道", t),
                new Message("m2", Sender.PEER, "在吗？配对成功了 🎉", t.plusMinutes(1)),
                new Message("m3", Sender.SELF, "看到了，Hello!", t.plusMinutes(2)),
                new Message("m4", Sender.PEER, "今天有空吗，想和你讨论一下 Q4 的 OKR", t.plusMinutes(3)),
                new Message("m5", Sender.SELF, "下午 3 点可以，我已经把上周的 draft 同步到本地了", t.plusMinutes(4)),
                new Message("m6", Sender.PEER, "好的，那我们 3 点见 👌", t.plusMinutes(5)),
                new Message("m7", Sender.SELF, "👍", t.plusMinutes(6))
        );
    }
}