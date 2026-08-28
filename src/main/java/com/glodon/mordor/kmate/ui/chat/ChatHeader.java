package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public class ChatHeader extends HBox {

    public ChatHeader(AppState state) {
        super();
        getStyleClass().add("header");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(10, 12, 10, 12));

        Label title = new Label();
        title.getStyleClass().add("header-title");
        title.textProperty().bind(Bindings.createStringBinding(
                () -> state.username() + (state.onlineProperty().get() ? " 🟢" : " 🔴"),
                state.onlineProperty()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label meta = new Label();
        meta.getStyleClass().add("header-meta");
        meta.textProperty().bind(Bindings.createStringBinding(
                () -> state.username() + " ↔ " + state.peerDisplayProperty().get(),
                state.peerDisplayProperty()));

        getChildren().addAll(title, spacer, meta);
    }
}
