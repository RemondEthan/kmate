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
 *
 * <p>布局：
 * <pre>
 * ChatPane (StackPane)
 *   ├── Region wallpaper     (背景图，鼠标穿透)
 *   └── BorderPane ui
 *         ├── top    = ChatHeader
 *         ├── left   = RoomMemberList
 *         ├── center = MessageListView
 *         └── bottom = InputBar
 * </pre>
 *
 * <p>把 controller 的引用通过构造传给子组件，子组件再调 controller 的方法（发消息、加载历史等）。
 */
public class ChatPane extends StackPane {

    public ChatPane(AppState state) {
        long t0 = System.nanoTime();
        getStylesheets().add(
                ChatPane.class.getResource("chat.css").toExternalForm());
        getStyleClass().add("chat-root");
        // 让 ChatPane 占满 Stage
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // controller 在 ChatPane 构造里 new：构造完就已经把 ImClient 事件订阅 + 历史加载都启动了
        ChatController controller = new ChatController(state);
        Diag.log("ui", "controller %dms", Diag.elapsedMs(t0));

        // BorderPane：经典四向布局（顶 / 左 / 中 / 底）
        BorderPane ui = new BorderPane();
        ui.getStyleClass().add("chat-ui");
        ui.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        ui.setTop(new ChatHeader(state));
        ui.setLeft(new RoomMemberList(controller));
        ui.setCenter(new MessageListView(controller));
        // InputBar 只关心"按回车发消息"，所以传 controller::send 方法引用
        ui.setBottom(new InputBar(controller::send));

        // StackPane 里先放背景图（垫底），再放 BorderPane。背景图设了 mouseTransparent，不会拦截事件。
        getChildren().addAll(wallpaper(), ui);
        Diag.log("ui", "ChatPane constructed %dms", Diag.elapsedMs(t0));
    }

    /**
     * 背景图：load bg-chat.png，铺在窗口里。
     *
     * <p>mouseTransparent = true：背景不接收鼠标事件，所有点击穿透给上层的 BorderPane。
     * BackgroundSize 的最后一个参数 true = 宽度撑满窗口，高度按比例（不裁切），
     * false = false 表示不强制定宽高（用 AUTO + cover 由 CSS / 控件控制）。
     */
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
                // BackgroundSize(width, height, widthAsPercentage, heightAsPercentage,
                //                  contain, cover)
                //   contain = true 时整图完整显示；
                //   cover = true 时铺满、可能裁切。
                // 这里 cover = false，width = AUTO，height = AUTO，但最后一参 true 表示宽度撑满。
                new BackgroundSize(BackgroundSize.AUTO, BackgroundSize.AUTO, false, false, false, true))));
        return wallpaper;
    }
}
