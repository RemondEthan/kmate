package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.model.Sender;
import com.glodon.mordor.kmate.app.Mate4K;
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

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息列表：一个可滚动的 VBox，每个消息是一个 MessageBubble。
 *
 * ScrollPane：滚动容器。当内容超出可视区域时自动出现滚动条。
 * setContent() 把内部的 VBox 挂进来，VBox 装的就是一行行的气泡。
 *
 * 背景：从 resources/bg-chat.png 加载雪花浅蓝图，覆盖整个消息区域。
 * 对应 SiMate/client 的 ChatWindow.tsx 第 208 行的 url(/bg-chat.svg)。
 */
public class MessageListView extends ScrollPane {

    // 装所有消息气泡的垂直盒子
    private final VBox container;

    // 登录时填入的用户名 / 对方名，用于在气泡上显示
    private final AppState state;

    public MessageListView(AppState state) {
        this.state = state;
        getStyleClass().add("message-list");

        // setFitToWidth(true)：让内部内容（VBox）的宽度跟随 ScrollPane 宽度，
        // 避免出现水平滚动条；消息多时只让垂直方向滚动
        setFitToWidth(true);
        // 滚动条策略：水平从不显示；垂直按需出现
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        container = new VBox(4);  // VBox 子节点间垂直间距 4px
        container.setPadding(new Insets(8));
        container.getStyleClass().add("message-list-container");

        applyChatBackground(container);

        // 把内容盒子装进 ScrollPane
        setContent(container);

        // 新增气泡后滚到底。runLater：等这一帧 layout 算完，立刻 setVvalue 会停在旧高度
        container.getChildren().addListener((ListChangeListener<Node>) c -> scrollToBottom());
        // 首屏示例加入时 ScrollPane 往往还没完成布局，高度变化后再钉一次底部
        container.heightProperty().addListener((obs, o, n) -> setVvalue(1.0));

        renderSampleMessages();
    }

    public void addMessage(Message msg) {
        container.getChildren().add(
                new MessageBubble(msg, state.username(), state.peerName(), bubbleMaxWidth()));
    }

    private ObservableValue<? extends Number> bubbleMaxWidth() {
        // 约 70% 列表宽度，给头像和边距留空；缩到最小窗口时也不会超过视口
        return widthProperty().multiply(0.7);
    }

    private void scrollToBottom() {
        Platform.runLater(() -> setVvalue(1.0));
    }

    /**
     * 给消息容器加上雪花浅蓝背景图。
     *
     * Image：从 classpath 加载图片资源（resources/bg-chat.png）
     * BackgroundImage / Background：JavaFX 的背景包装，把图设成节点背景
     *   BackgroundRepeat.NO_REPEAT：不重复平铺
     *   BackgroundPosition.CENTER：居中
     *   BackgroundSize(w%, h%, true, true, true, true)：cover 模式，铺满容器
     */
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

    // 写死一组示例消息，用来填充聊天界面（demo 阶段没有真实通信）
    private void renderSampleMessages() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 24, 9, 30);
        List<Message> sample = List.of(
                new Message("m1", Sender.SYSTEM, "已与 Alice 建立加密通道", t),
                new Message("m2", Sender.PEER, "在吗？配对成功了 🎉", t.plusMinutes(1)),
                new Message("m3", Sender.SELF, "看到了，Hello!", t.plusMinutes(2)),
                new Message("m4", Sender.PEER, "今天有空吗，想和你讨论一下 Q4 的 OKR", t.plusMinutes(3)),
                new Message("m5", Sender.SELF, "下午 3 点可以，我已经把上周的 draft 同步到本地了", t.plusMinutes(4)),
                new Message("m6", Sender.PEER, "好的，那我们 3 点见 👌", t.plusMinutes(5)),
                new Message("m7", Sender.SELF, "👍", t.plusMinutes(6))
        );
        for (Message m : sample) {
            addMessage(m);
        }
    }
}