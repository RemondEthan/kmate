package com.glodon.mordor.kmate.ui.chat;

import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

/**
 * 聊天输入栏：附件按钮 + 文本输入框 + 表情按钮 + 发送按钮。
 *
 * HBox 横向排列 4 个元素：附件 | 输入框 | 表情 | 发送。
 * 文本框占据所有剩余空间（Hgrow=ALWAYS），按钮固定大小。
 */
public class InputBar extends HBox {

    // 文本框（消息内容）和发送按钮在整个生命周期内都需要引用，所以提为字段
    private final TextField textField;
    private final Button sendBtn;

    // 表情弹窗组件，按表情按钮时弹出
    private final EmojiPopover emojiPopover;

    public InputBar(Consumer<String> onSend) {
        super(6);  // HBox 子节点之间水平间距 6px
        getStyleClass().add("input-bar");
        setAlignment(Pos.CENTER_LEFT);  // 子节点垂直居中、水平靠左

        // ---- 附件按钮：用 Material Design 图标库的回形针图标 ----
        Button attach = new Button();
        // FontIcon：ikonli 包提供的图标节点，setGraphic 把图标放进 Button
        attach.setGraphic(new FontIcon(MaterialDesignP.PAPERCLIP));
        attach.setOnAction(e -> showAttachStub());

        // ---- 发送按钮（先创建并禁用，因为还没输入内容）----
        sendBtn = new Button();
        sendBtn.setGraphic(new FontIcon(MaterialDesignS.SEND));
        sendBtn.setDisable(true);  // 初始禁用：空文本不能发送

        // ---- 文本输入框 ----
        textField = new TextField();
        textField.setPromptText("输入消息...");
        // setOnAction：按回车键时触发，相当于"提交"
        textField.setOnAction(e -> send(onSend));
        // 监听文本变化：空文本时禁用发送按钮，否则启用
        textField.textProperty().addListener((obs, o, n) ->
                sendBtn.setDisable(n == null || n.isBlank()));
        // setHgrow：让 textField 占据所有剩余水平空间
        HBox.setHgrow(textField, Priority.ALWAYS);

        // ---- 表情按钮 ----
        Button emoji = new Button("😊");
        // 这里直接用 setStyle 设置细节样式（背景透明、鼠标手型）
        emoji.setStyle("-fx-font-size: 14px; -fx-background-color: transparent; -fx-cursor: hand;");
        emojiPopover = new EmojiPopover(textField);
        emoji.setOnAction(e -> emojiPopover.show(emoji));

        // 发送按钮的点击事件（创建完 textField 后再绑定，避免引用顺序问题）
        sendBtn.setOnAction(e -> send(onSend));

        // 按顺序加入 HBox：附件 → 文本框 → 表情 → 发送
        getChildren().addAll(attach, textField, emoji, sendBtn);
    }

    // 把当前文本发出去；空文本则忽略
    private void send(Consumer<String> onSend) {
        String text = textField.getText();
        if (text == null || text.isBlank()) return;
        onSend.accept(text);
        clear();
    }

    // 文件传输的占位提示：当前 demo 阶段没实现真实附件
    private void showAttachStub() {
        // Alert：模态对话框（INFORMATION 风格）
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText("文件传输未实现（demo 模式）");
        alert.showAndWait();  // 显示并阻塞等待用户关闭
    }

    public String getText() {
        return textField.getText();
    }

    public void clear() {
        textField.clear();
    }
}