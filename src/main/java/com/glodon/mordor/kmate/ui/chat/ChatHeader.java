package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 聊天页顶栏：左侧标题（用户名 + 在线状态圆点），右侧元信息（"自己 ↔ 对方"）。
 *
 * <p>两个 Label 都用 Bindings.createStringBinding 跟 AppState 的 ObservableProperty 绑定：
 * <ul>
 *   <li>标题：state.onlineProperty 变化时重算（在线 ↔ 离线换绿/红圆点）</li>
 *   <li>元信息：state.peerDisplayProperty 变化时重算（对方加入 / 离开）</li>
 * </ul>
 *
 * <p>中间用一个 Region + HBox.setHgrow(ALWAYS) 把两个 Label 撑到两端。
 */
public class ChatHeader extends HBox {

    public ChatHeader(AppState state) {
        super();
        getStyleClass().add("header");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(10, 12, 10, 12));

        // 左侧标题：用户名 + 在线状态绿/红圆点（用 emoji 占位）。
        Label title = new Label();
        title.getStyleClass().add("header-title");
        // createStringBinding(计算函数, 依赖...)。每当依赖的 Observable 值变化，Label 文字自动重算。
        title.textProperty().bind(Bindings.createStringBinding(
                () -> state.username() + (state.onlineProperty().get() ? " 🟢" : " 🔴"),
                state.onlineProperty()));

        // 中间弹性空白：把右侧 Label 顶到最右边
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 右侧元信息："用户名 ↔ 对方名"
        Label meta = new Label();
        meta.getStyleClass().add("header-meta");
        meta.textProperty().bind(Bindings.createStringBinding(
                () -> state.username() + " ↔ " + state.peerDisplayProperty().get(),
                state.peerDisplayProperty()));

        getChildren().addAll(title, spacer, meta);
    }
}
