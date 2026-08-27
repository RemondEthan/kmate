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
 * 聊天面板:顶部 header + 中间消息列表 + 底部输入栏。
 *
 * 背景铺在 StackPane 底层，不跟着消息 VBox 长高，进入时就能铺满对话框。
 */
public class ChatPane extends BorderPane {

    public ChatPane(AppState state) {
        long t0 = System.nanoTime();
        getStylesheets().add(
                ChatPane.class.getResource("chat.css").toExternalForm());
        getStyleClass().add("app-bg");

        ChatController controller = new ChatController(state);
        Diag.log("ui", "controller %dms", Diag.elapsedMs(t0));

        setTop(new ChatHeader(state));
        setCenter(wrapWithWallpaper(new MessageListView(controller)));
        setBottom(new InputBar(controller::send));
        Diag.log("ui", "ChatPane constructed %dms", Diag.elapsedMs(t0));
    }

    private static StackPane wrapWithWallpaper(MessageListView list) {
        Region wallpaper = new Region();
        wallpaper.setMouseTransparent(true);
        wallpaper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        long t0 = System.nanoTime();
        Image img = new Image(ChatPane.class.getResource("bg-chat.png").toExternalForm());
        Diag.log("ui", "bg-chat.png loaded %dms error=%s w=%.0f",
                Diag.elapsedMs(t0), img.isError(), img.getWidth());
        wallpaper.setBackground(new Background(new BackgroundImage(
                img,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, false, true))));

        list.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane stack = new StackPane(wallpaper, list);
        stack.setMinSize(0, 0);
        stack.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        return stack;
    }
}
