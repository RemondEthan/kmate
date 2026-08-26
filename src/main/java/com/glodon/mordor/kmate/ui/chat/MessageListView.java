package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.VBox;

/**
 * 消息列表:一个可滚动的 VBox,每个消息是一个 MessageBubble。
 *
 * 渲染来源:订阅 ChatController.getMessages() 的 ListChangeListener;
 * 首次构造时一次性渲染现有消息,之后只追加新增。
 *
 * 背景:resources/bg-chat.png,雪花浅蓝图,跟随本包路径。
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

        container = new VBox(4);
        container.setPadding(new Insets(8));
        container.getStyleClass().add("message-list-container");

        applyChatBackground(container);

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
    }

    private ObservableValue<? extends Number> bubbleMaxWidth() {
        return widthProperty().multiply(0.7);
    }

    private MessageBubble newBubble(Message m) {
        AppState s = controller.getState();
        return new MessageBubble(m, s.username(), s.peerName(), bubbleMaxWidth());
    }

    private void scrollToBottom() {
        Platform.runLater(() -> setVvalue(1.0));
    }

    private void applyChatBackground(VBox target) {
        Image img = new Image(getClass().getResource("bg-chat.png").toExternalForm());
        BackgroundImage bgImage = new BackgroundImage(
                img,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(100, 100, true, true, true, true));
        target.setBackground(new Background(bgImage));
    }
}