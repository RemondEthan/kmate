package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.ui.AvatarView;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单条消息气泡：头像 + 小字名字 + 气泡 + 时间。
 *
 * <p>三种 sender 对应三种渲染：
 * <ul>
 *   <li>SELF：右对齐，气泡用 bubble-self 样式（绿色），名字后面追加"我"</li>
 *   <li>PEER：左对齐，气泡用 bubble-peer 样式（白色），名字用 peerName</li>
 *   <li>SYSTEM：整条居中，无头像，灰色样式 bubble-system</li>
 * </ul>
 *
 * <p>气泡宽度通过 ObservableValue 跟外层 ListView 的宽度绑：
 * ListView 缩放时气泡自动按 70% 计算最大宽度，不会撑出窗口。
 */
public class MessageBubble extends HBox {

    // 时间戳格式化：精确到秒
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 气泡 / 名字 / 时间用的 CSS class（在 chat.css 里定义）
    private static final String STYLE_SELF = "bubble-self";
    private static final String STYLE_PEER = "bubble-peer";
    private static final String STYLE_SYS = "bubble-system";
    private static final String STYLE_NAME = "bubble-name";
    private static final String STYLE_TIME = "bubble-time";

    // 气泡最大宽度 = MessageListView 宽 × 70%。通过 Observable 传进来，自己不存固定值
    private final ObservableValue<? extends Number> maxBubbleWidth;

    /**
     * @param msg             消息数据
     * @param myName          当前用户自己的名字（SELF 消息显示用）
     * @param peerName        默认对方名（PEER 消息没 from 字段时兜底）
     * @param peerAvatar      对方头像图片
     * @param myAvatar        自己头像图片
     * @param maxBubbleWidth  气泡的最大宽度 Observable（一般是 ListView 宽 × 0.7）
     */
    public MessageBubble(Message msg, String myName, String peerName, Image peerAvatar, Image myAvatar,
                         ObservableValue<? extends Number> maxBubbleWidth) {
        super(4);  // HBox 子节点间距 4
        this.maxBubbleWidth = maxBubbleWidth;
        setFillHeight(false);  // 不要让 HBox 撑满高度
        setPadding(new Insets(2, 4, 2, 4));

        // 按 sender 分发到三种渲染
        switch (msg.sender()) {
            case SELF -> renderSide(msg, displayName(myName, "我"), myAvatar, true);
            case PEER -> renderSide(msg, displayName(peerName, "对方"), peerAvatar, false);
            case SYSTEM -> renderSystem(msg);
        }
    }

    /**
     * 渲染普通聊天消息（自己或对方）。
     *
     * <p>结构（self = true 时顺序相反）：
     * <pre>
     * HBox row
     *   ├── VBox col
     *   │     ├── Label nameLabel    ("Alice（我）" / "Bob")
     *   │     ├── TextFlow bubble    (消息内容 + emoji)
     *   │     └── Label time         (yyyy-MM-dd HH:mm:ss)
     *   └── AvatarView avatar        (28x28 圆形头像)
     * </pre>
     */
    private void renderSide(Message msg, String name, Image photo, boolean self) {
        // self 右对齐，peer 左对齐
        setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add(STYLE_NAME);
        nameLabel.setWrapText(false);
        // 长名字用省略号
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setAlignment(sideMetaAlignment(self));
        // 名字 Label 宽度也绑到气泡最大宽度，最小 80px（避免太窄）
        nameLabel.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(80, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));

        AvatarView avatar = new AvatarView(name, photo, self, 28);

        Label time = new Label(formatTime(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);
        time.setAlignment(sideMetaAlignment(self));
        // 时间和名字共用一个宽度（避免错位）
        time.maxWidthProperty().bind(nameLabel.maxWidthProperty());

        // 消息正文：emoji 替换成图片，文字保留为 Text 节点
        TextFlow bubble = EmojiImages.flow(msg.content());
        bindBubbleWidth(bubble);
        // self = true 用绿色气泡，false 用白色气泡
        bubble.getStyleClass().add(self ? STYLE_SELF : STYLE_PEER);

        // 垂直列：名字 + 气泡 + 时间
        VBox col = new VBox(2, nameLabel, bubble, time);
        col.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        // self = true 时 气泡在前头像在后（贴右）；peer 时相反（贴左）
        HBox row = self ? new HBox(6, col, avatar) : new HBox(6, avatar, col);
        row.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        getChildren().add(row);
    }

    /**
     * 系统消息：居中、无头像、灰色样式。
     */
    private void renderSystem(Message msg) {
        setAlignment(Pos.CENTER);
        TextFlow bubble = EmojiImages.flow(msg.content());
        bindBubbleWidth(bubble);
        bubble.getStyleClass().add(STYLE_SYS);
        getChildren().add(bubble);
    }

    /**
     * 把气泡的最大宽度绑到外部 Observable + 下限 120px。
     * 不直接 .bind() 而是用 createDoubleBinding 是为了加 max(120, ...) 的下限保护。
     */
    private void bindBubbleWidth(Region bubble) {
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }

    static String formatTime(LocalDateTime timestamp) {
        return DATE_TIME.format(timestamp);
    }

    /**
     * 名字 / 时间的对齐：self 靠右，peer 靠左（跟气泡对齐）。
     */
    static Pos sideMetaAlignment(boolean self) {
        return self ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT;
    }

    /**
     * 名字兜底：null / 空 用 fallback；否则用真名。
     */
    private static String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }
}
