package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 聊天窗口顶部标题栏：左侧标题 + 中间弹性空白 + 右侧对方信息。
 *
 * HBox：水平盒子，子节点从左到右排列。
 * 通过 setHgrow(spacer, ALWAYS) 让中间的 Region 撑开，把标题推到左侧、
 * 把 meta 标签推到右侧。
 */
public class ChatHeader extends HBox {

    public ChatHeader(AppState state) {
        super();  // HBox 默认间距 0
        getStyleClass().add("header");
        // 子节点在 HBox 内的垂直对齐方式
        setAlignment(Pos.CENTER_LEFT);
        // 内边距：上 10、右 12、下 10、左 12
        setPadding(new Insets(10, 12, 10, 12));

        Label title = new Label("SiMate 🟢");
        title.getStyleClass().add("header-title");

        // Region：空白占位节点，配合 Hgrow 推动后续节点靠右
        Region spacer = new Region();
        // ALWAYS：该节点会占据所有剩余水平空间
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label meta = new Label(state.username() + " ↔ " + state.peerName());
        meta.getStyleClass().add("header-meta");

        // 按顺序加入 HBox：title 在左，spacer 占中间，meta 在右
        getChildren().addAll(title, spacer, meta);
    }
}