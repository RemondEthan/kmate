package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import javafx.scene.layout.BorderPane;

/**
 * 聊天面板：顶部 header + 中间消息列表 + 底部输入栏。
 *
 * BorderPane：把内部区域分成 5 块——top / bottom / left / right / center。
 * 这里只用了 top / center / bottom 三块，未指定的 left / right 会留空。
 */
public class ChatPane extends BorderPane {

    public ChatPane(AppState state) {
        // 给 BorderPane 自身打上 .app-bg 类，让背景与登录页一致
        getStyleClass().add("app-bg");
        getStylesheets().add(ChatPane.class.getResource("chat.css").toExternalForm());

        // setTop：把节点放在 BorderPane 顶部
        setTop(new ChatHeader(state));
        // setCenter：放中间，自动占满 Top 和 Bottom 之间的剩余空间
        setCenter(new MessageListView(state));
        // setBottom：放底部，输入栏固定在窗口底部
        setBottom(new InputBar(this::handleSend));
    }

    // 占位的发送回调：当前 demo 模式不会真正把消息追加到列表
    private void handleSend() {
    }
}