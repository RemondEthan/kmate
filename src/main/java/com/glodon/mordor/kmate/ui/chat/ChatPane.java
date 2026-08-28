package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.model.AppState;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * 聊天面板：整窗背景图 + 左侧成员列表 + 顶栏 / 消息 / 输入。
 */
public class ChatPane extends StackPane {

    public ChatPane(AppState state) {
        long t0 = System.nanoTime();
        getStylesheets().add(
                ChatPane.class.getResource("chat.css").toExternalForm());
        getStyleClass().add("chat-root");
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        ChatController controller = new ChatController(state);
        Diag.log("ui", "controller %dms", Diag.elapsedMs(t0));

        BorderPane ui = new BorderPane();
        ui.getStyleClass().add("chat-ui");
        ui.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        ui.setTop(new ChatHeader(state));
        ui.setLeft(new RoomMemberList(controller));
        ui.setCenter(new MessageListView(controller));
        ui.setBottom(new InputBar(controller::send));

        getChildren().addAll(wallpaper(), ui);
        Diag.log("ui", "ChatPane constructed %dms", Diag.elapsedMs(t0));
    }

    private static Region wallpaper() {
        Region wallpaper = new Region();
        wallpaper.setMouseTransparent(true);
        wallpaper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        long t0 = System.nanoTime();
        Image img = new Image(ChatPane.class.getResource("bg-chat.png").toExternalForm());
        Diag.log("ui", "bg-chat.png loaded %dms error=%s w=%.0f",
                Diag.elapsedMs(t0), img.isError(), img.getWidth());
        if (img.isError()) {
            Diag.warn("ui", "bg-chat.png failed to load");
        }
        wallpaper.setBackground(new Background(new BackgroundImage(
                img,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, false, true))));
        return wallpaper;
    }
}
