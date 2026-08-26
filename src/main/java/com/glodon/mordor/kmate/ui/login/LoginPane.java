package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.service.SaveLastLoginService;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 登录面板：极简风格，白色表单卡片铺满窗口，四周留出缩减后的灰边。
 *
 * 窗口 600×449、卡片原宽 380 时，左右灰边各约 110px、上下各约 64px。
 * 边框空白缩 40% 后，内边距为上下 38px、左右 66px，卡片吃掉收回的空间。
 */
public class LoginPane extends VBox {

    // 5 个表单字段：IP、端口、配对码、口令、用户名
    private final TextField serverIp = new TextField();
    private final TextField serverPort = new TextField();
    private final TextField imCode = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField username = new TextField();

    // 错误提示标签，默认隐藏
    private final Label errorLabel = new Label();

    public LoginPane(Consumer<AppState> onConnect) {
        super(0);
        // CSS 类：对应 styles.css 里的 .app-bg 和 .login-root
        getStyleClass().addAll("app-bg", "login-root");
        getStylesheets().add(LoginPane.class.getResource("login.css").toExternalForm());
        // 允许被父容器拉伸到全窗口；卡片随剩余空间变宽变高
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setFillWidth(true);
        // 原左右灰边 (600-380)/2=110，上下约 64；缩 40% 后留 60%
        setPadding(new Insets(38, 66, 38, 66));

        // ============================================================
        // 表单卡片：白底圆角阴影，垂直居中
        // ============================================================
        errorLabel.getStyleClass().add("login-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);

        Label info = new Label("请与对方约定相同的 IM_CODE 和初始口令进行配对");
        info.getStyleClass().add("login-info");
        info.setWrapText(true);
        info.setMaxWidth(Double.MAX_VALUE);

        // ---- IP + 端口 同一行 ----
        VBox ipBox = fieldBox("服务器 IP", serverIp, "127.0.0.1");
        VBox portBox = fieldBox("端口", serverPort, "3000");

        HBox ipRow = new HBox(12, ipBox, portBox);
        ipRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(ipBox, Priority.ALWAYS);
        HBox.setHgrow(portBox, Priority.ALWAYS);

        // ---- 其余 3 个字段 ----
        VBox imCodeBox = fieldBox("IM_CODE", imCode, "输入配对码（如：ABC123）");
        VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");
        VBox usernameBox = fieldBox("用户名", username, "输入你的名称");

        VBox fields = new VBox(8,
                errorLabel, info, ipRow,
                imCodeBox, passwordBox, usernameBox);
        fields.setMaxWidth(Double.MAX_VALUE);
        fields.setFillWidth(true);

        // "连接"按钮：MUI contained primary 风格
        Button connect = new Button("连 接");
        connect.setDefaultButton(true);
        connect.setMaxWidth(Double.MAX_VALUE);
        connect.getStyleClass().add("login-connect");
        connect.setOnAction(e -> handleConnect(onConnect));

        // 表单卡片：吃掉内边距以外的全部空间，收回的 40% 空白落到卡片内部
        Region cardTop = new Region();
        Region cardBottom = new Region();
        VBox.setVgrow(cardTop, Priority.ALWAYS);
        VBox.setVgrow(cardBottom, Priority.ALWAYS);

        VBox card = new VBox(10, cardTop, fields, connect, cardBottom);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(card, Priority.ALWAYS);

        getChildren().add(card);

        // 预填：先尝试上次保存的配置，缺省才用硬编码默认
        // 密码绝不预填
        SaveLastLoginService saved = new SaveLastLoginService();
        serverIp.setText(saved.getServerIp());
        serverPort.setText(saved.getServerPort());
        imCode.setText(saved.getImCode());
        username.setText(saved.getUsername());
    }

    /**
     * 构造 "Label + 输入控件" 的垂直小块。
     */
    private VBox fieldBox(String labelText, Control field, String placeholder) {
        Label l = new Label(labelText);
        l.getStyleClass().add("login-field-label");
        l.setMaxWidth(Double.MAX_VALUE);

        // instanceof 模式匹配：根据实际类型分别设置 promptText 和样式类
        if (field instanceof TextField tf) {
            tf.setPromptText(placeholder);
            tf.getStyleClass().add("login-field");
        } else if (field instanceof PasswordField pf) {
            pf.setPromptText(placeholder);
            pf.getStyleClass().add("login-field");
        }
        field.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(3, l, field);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(true);
        return box;
    }

    private void handleConnect(Consumer<AppState> onConnect) {
        if (!validate()) return;
        String ip        = serverIp.getText().trim();
        String port      = serverPort.getText().trim();
        String code      = imCode.getText().trim();
        String user      = username.getText().trim();
        String peer      = new SaveLastLoginService().getPeerName();  // 当前 UI 没暴露，保持上次值

        // 登录成功 → 落盘（密码不写）
        new SaveLastLoginService().save(ip, port, code, user, peer);

        AppState state = new AppState(user, peer);
        onConnect.accept(state);
    }

    private boolean validate() {
        if (serverIp.getText().isBlank())     return showError("请输入服务器 IP");
        if (serverPort.getText().isBlank())   return showError("请输入端口");
        if (!serverPort.getText().matches("\\d+")) return showError("端口必须是数字");
        if (imCode.getText().isBlank())       return showError("请输入 IM_CODE");
        if (password.getText().isBlank())     return showError("请输入初始口令");
        if (username.getText().isBlank())     return showError("请输入用户名");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        return true;
    }

    private boolean showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        return false;
    }
}