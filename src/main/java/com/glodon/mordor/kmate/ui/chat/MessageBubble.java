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

import java.time.format.DateTimeFormatter;

/**
 * 单条消息：头像 + 小字名字 + 气泡。系统消息仍居中、无头像。
 */
public class MessageBubble extends HBox {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String STYLE_SELF = "bubble-self";
    private static final String STYLE_PEER = "bubble-peer";
    private static final String STYLE_SYS = "bubble-system";
    private static final String STYLE_NAME = "bubble-name";
    private static final String STYLE_TIME = "bubble-time";

    private final ObservableValue<? extends Number> maxBubbleWidth;

    public MessageBubble(Message msg, String myName, String peerName, Image peerAvatar, Image myAvatar,
                         ObservableValue<? extends Number> maxBubbleWidth) {
        super(4);
        this.maxBubbleWidth = maxBubbleWidth;
        setFillHeight(false);
        setPadding(new Insets(2, 4, 2, 4));

        switch (msg.sender()) {
            case SELF -> renderSide(msg, displayName(myName, "我"), myAvatar, true);
            case PEER -> renderSide(msg, displayName(peerName, "对方"), peerAvatar, false);
            case SYSTEM -> renderSystem(msg);
        }
    }

    private void renderSide(Message msg, String name, Image photo, boolean self) {
        setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add(STYLE_NAME);
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(80, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));

        AvatarView avatar = new AvatarView(name, photo, self, 28);

        Label time = new Label(HHMM.format(msg.timestamp()));
        time.getStyleClass().add(STYLE_TIME);

        TextFlow bubble = EmojiImages.flow(msg.content());
        bindBubbleWidth(bubble);
        bubble.getStyleClass().add(self ? STYLE_SELF : STYLE_PEER);

        VBox col = new VBox(2, nameLabel, bubble, time);
        col.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        HBox row = self ? new HBox(6, col, avatar) : new HBox(6, avatar, col);
        row.setAlignment(self ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        getChildren().add(row);
    }

    private void renderSystem(Message msg) {
        setAlignment(Pos.CENTER);
        TextFlow bubble = EmojiImages.flow(msg.content());
        bindBubbleWidth(bubble);
        bubble.getStyleClass().add(STYLE_SYS);
        getChildren().add(bubble);
    }

    private void bindBubbleWidth(Region bubble) {
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(120, maxBubbleWidth.getValue().doubleValue()),
                maxBubbleWidth));
    }

    private static String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }
}
