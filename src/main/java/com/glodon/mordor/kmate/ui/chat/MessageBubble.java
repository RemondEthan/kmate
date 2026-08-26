package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.Message;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.format.DateTimeFormatter;

/**
 * 单条消息气泡：根据消息发送方（SELF/PEER/SYSTEM）渲染不同样式。
 *
 *   - SELF：右对齐，蓝色气泡 + 蓝色头像
 *   - PEER：左对齐，灰色气泡 + 灰色头像
 *   - SYSTEM：居中，浅橙色背景，无头像
 *
 * 类继承 HBox：整个气泡占据一行，内部再嵌一个 HBox 来摆放
 * "头像 + 文字气泡 + 时间" 的横向组合。
 */
public class MessageBubble extends HBox {

    // 时间显示格式：HH:mm（小时:分钟）
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    // CSS 类名，对应 styles.css 里的样式
    private static final String STYLE_SELF  = "bubble-self";
    private static final String STYLE_PEER  = "bubble-peer";
    private static final String STYLE_SYS   = "bubble-system";
    private static final String STYLE_NAME  = "bubble-name";
    private static final String STYLE_TIME  = "bubble-time";

    private final ObservableValue<? extends Number> maxBubbleWidth;

    public MessageBubble(Message msg, String myName, String peerName,
                         ObservableValue<? extends Number> maxBubbleWidth) {
        super(4);  // HBox 内部子节点水平间距 4px
        this.maxBubbleWidth = maxBubbleWidth;
        // setFillHeight(false)：HBox 不强制所有子节点撑满自身高度
        // 气泡高度按内容走，不会被同一行其他子节点拉高
        setFillHeight(false);
        // 气泡行的内边距：上下 2，左右 4（让相邻气泡之间有点空气）
        setPadding(new Insets(2, 4, 2, 4));

        // 根据发送方走不同分支
        switch (msg.sender()) {
            case SELF -> renderSelf(msg, myName);
            case PEER -> renderPeer(msg, peerName);
            case SYSTEM -> renderSystem(msg);
        }
    }

    // 我方发送的消息：右侧对齐
    private void renderSelf(Message msg, String myName) {
        // HBox 内的子节点靠右排列（默认靠左）
        setAlignment(Pos.CENTER_RIGHT);

        Label name = new Label(myName.isBlank() ? "我" : myName);
        name.getStyleClass().add(STYLE_NAME);

        Label time = new Label(HHMM.format(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);

        // 气泡本体：Label 用来显示文字
        Label bubble = new Label(msg.content());
        bindBubbleWidth(bubble);
        bubble.getStyleClass().add(STYLE_SELF);   // 应用蓝色样式

        StackPane avatar = avatar(myName.isBlank() ? "我" : myName, true);

        // 垂直堆叠：发送者名 + 气泡 + 时间，右对齐
        VBox col = new VBox(2, name, bubble, time);
        col.setAlignment(Pos.BOTTOM_RIGHT);

        // 横向：气泡列 + 头像
        HBox row = new HBox(4, col, avatar);
        row.setAlignment(Pos.BOTTOM_RIGHT);

        getChildren().add(row);
    }

    // 对方发送的消息：左侧对齐
    private void renderPeer(Message msg, String peerName) {
        setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(peerName.isBlank() ? "对方" : peerName);
        name.getStyleClass().add(STYLE_NAME);

        Label time = new Label(HHMM.format(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);

        Label bubble = new Label(msg.content());
        bindBubbleWidth(bubble);
        bubble.getStyleClass().add(STYLE_PEER);

        StackPane avatar = avatar(peerName.isBlank() ? "对方" : peerName, false);

        VBox col = new VBox(2, name, bubble, time);
        col.setAlignment(Pos.BOTTOM_LEFT);

        HBox row = new HBox(4, avatar, col);
        row.setAlignment(Pos.BOTTOM_LEFT);

        getChildren().add(row);
    }

    // 系统提示：居中显示一行小字，无头像
    private void renderSystem(Message msg) {
        setAlignment(Pos.CENTER);
        Label bubble = new Label(msg.content());
        bindBubbleWidth(bubble);
        bubble.getStyleClass().add(STYLE_SYS);
        getChildren().add(bubble);
    }

    private void bindBubbleWidth(Label bubble) {
        bubble.setWrapText(true);
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }

    /**
     * 构造圆形头像：圆形背景 + 名字首字母。
     *
     * StackPane：把所有子节点叠在中心点。这里把圆形和首字母叠在一起。
     *
     * @param name  显示首字母的名字
     * @param self  true=蓝色（我方），false=灰色（对方）
     */
    private StackPane avatar(String name, boolean self) {
        // Circle(radius)：圆形节点，常用作头像背景
        Circle bg = new Circle(12);
        // 颜色通过 CSS 类控制，便于全局调整主题色
        bg.setFill(self ? Color.web("#1976D2") : Color.web("#6B7280"));

        Label initial = new Label(name.substring(0, 1));
        // 这里直接用 setStyle 而非样式类：这些细节调整频率很高，避免污染 CSS
        initial.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: 600;");

        // 把圆形和文字叠在一起，居中对齐
        return new StackPane(bg, initial);
    }
}