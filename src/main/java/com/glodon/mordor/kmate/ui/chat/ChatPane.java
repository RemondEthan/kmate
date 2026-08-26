package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import javafx.scene.layout.BorderPane;

/**
 * 聊天面板:顶部 header + 中间消息列表 + 底部输入栏。
 *
 * 创建 ChatController,作为消息数据源;UI 节点只负责渲染与转发事件。
 */
public class ChatPane extends BorderPane {

    public ChatPane(AppState state) {
        getStylesheets().add(
                ChatPane.class.getResource("chat.css").toExternalForm());
        getStyleClass().add("app-bg");

        ChatController controller = new ChatController(state);

        setTop(new ChatHeader(state));
        setCenter(new MessageListView(controller));
        setBottom(new InputBar(controller::send));
    }
}