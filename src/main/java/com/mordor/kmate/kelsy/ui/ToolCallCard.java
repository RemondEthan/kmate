package com.mordor.kmate.kelsy.ui;

import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/** 助手气泡里的工具调用条，可点「打开」跳到知识库文件。 */
public final class ToolCallCard extends HBox {

    public ToolCallCard(String name, String openPath, Consumer<String> onOpen) {
        getStyleClass().add("tool-card");
        setSpacing(8);
        Label label = new Label("调用：" + (name == null ? "" : name));
        label.getStyleClass().add("tool-card-label");
        getChildren().add(label);
        if (openPath != null && !openPath.isBlank()) {
            Hyperlink open = new Hyperlink("打开");
            open.setOnAction(e -> {
                if (onOpen != null) {
                    onOpen.accept(openPath);
                }
            });
            getChildren().add(open);
        }
    }
}
