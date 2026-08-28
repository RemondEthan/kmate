package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;

/**
 * 消息列表:一个可滚动的 VBox,每个消息是一个 MessageBubble。
 *
 * 渲染来源:订阅 ChatController.getMessages() 的 ListChangeListener;
 * 首次构造时一次性渲染现有消息,之后只追加新增。
 *
 * 背景由 ChatPane 铺在底层，本列表保持透明。
 */
public class MessageListView extends ScrollPane {

    private final VBox container;
    private final ChatController controller;

    public MessageListView(ChatController controller) {
        this.controller = controller;
        getStyleClass().add("message-list");

        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        container = new VBox(4);
        container.setPadding(new Insets(8));
        container.getStyleClass().add("message-list-container");
        container.setFillWidth(true);

        setContent(container);

        // 首次渲染:把 controller 里已有的消息一次性画出来
        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }

        // 之后订阅:只追加新增的(忽略其它变更类型)
        controller.getMessages().addListener((ListChangeListener<Message>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (Message m : c.getAddedSubList()) {
                        container.getChildren().add(newBubble(m));
                    }
                }
            }
        });

        // 新增气泡后滚到底
        container.getChildren().addListener((ListChangeListener<Node>) c -> scrollToBottom());
        // 首屏示例消息高度变化后再钉一次底部
        container.heightProperty().addListener((obs, o, n) -> setVvalue(1.0));
        // 对方头像后到时刷新已有气泡
        controller.peerAvatars().addListener((MapChangeListener<String, Image>) c -> rebuild());
    }

    private void rebuild() {
        container.getChildren().clear();
        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }
    }

    private ObservableValue<? extends Number> bubbleMaxWidth() {
        return widthProperty().multiply(0.7);
    }

    private MessageBubble newBubble(Message m) {
        AppState s = controller.getState();
        String peer = (m.from() != null && !m.from().isBlank()) ? m.from() : s.peerName();
        return new MessageBubble(m, s.username(), peer, controller.avatarOf(m.from()),
                s.avatar(), bubbleMaxWidth());
    }

    private void scrollToBottom() {
        Platform.runLater(() -> setVvalue(1.0));
    }
}