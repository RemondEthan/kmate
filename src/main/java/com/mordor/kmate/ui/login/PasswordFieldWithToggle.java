package com.mordor.kmate.ui.login;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * 密码输入框 + 明文切换眼睛。
 *
 * 内部同时持有 PasswordField(密文)和 TextField(明文),通过 visible 属性切换
 * 谁显示;两者的 textProperty 双向绑定,所以读 getText() 始终拿到最新值。
 * 切换时焦点保持在当前可见的那个字段上,避免用户切完明文还要再点一次。
 *
 * 对外 API 沿用 PasswordField 的方法名(setText / getText / clear / setDisable
 * / setPromptText),LoginPane 替换字段类型后调用点不需要改。
 */
public class PasswordFieldWithToggle extends StackPane {

    static final String EYE_OPEN = "👁";  // 👁
    static final String EYE_OFF = "🙈";   // 🙈 看不见的猴子

    final PasswordField hidden = new PasswordField();
    final TextField shown = new TextField();
    final Button eye = new Button(EYE_OPEN);
    final javafx.beans.property.BooleanProperty visible =
            new javafx.beans.property.SimpleBooleanProperty(false);

    public PasswordFieldWithToggle() {
        getStyleClass().add("password-with-toggle");

        hidden.getStyleClass().add("login-field");
        shown.getStyleClass().add("login-field");
        eye.getStyleClass().add("login-eye-toggle");

        hidden.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        shown.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        hidden.setVisible(true);
        hidden.setManaged(true);
        shown.setVisible(false);
        shown.setManaged(false);

        getChildren().addAll(hidden, shown, eye);
        StackPane.setAlignment(eye, Pos.CENTER_RIGHT);

        hidden.textProperty().bindBidirectional(shown.textProperty());

        eye.setOnAction(e -> visible.set(!visible.get()));
    }

    public String getText() {
        return hidden.getText();
    }

    public void setText(String text) {
        hidden.setText(text == null ? "" : text);
    }

    public void clear() {
        hidden.clear();
    }

    public void setPromptText(String text) {
        hidden.setPromptText(text);
        shown.setPromptText(text);
    }
}