package com.glodon.mordor.kmate;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 登录面板：仿 QQ 登录窗样式。
 *
 * 整体结构（自上而下）：
 *   1. 顶部 banner：QQ 蓝渐变，左侧 "SiMate" 标题，右侧"在线 🟢"状态
 *   2. 白色表单卡片（含 5 个字段 + 绿色"连接"按钮）
 *   3. 底部 footer：左侧状态文字，右侧版本号
 *
 * 父容器是 VBox，按顺序堆叠三块。
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
        // super(spacing)：VBox 子节点之间的垂直间距，这里设 0（每块自己控制 padding）
        super(0);
        // CSS 类：对应 styles.css 里的 .app-bg（全局背景）和 .login-root（容器自身）
        getStyleClass().addAll("app-bg", "login-root");
        // 允许被父容器拉伸到全窗口
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // ============================================================
        // 顶部 banner：QQ 风格的蓝色渐变标题栏
        // HBox：左侧标题，右侧状态（中间用 Region 撑开）
        // ============================================================
        Label bannerTitle = new Label("SiMate");
        bannerTitle.getStyleClass().add("login-banner-title");

        // Region：空白占位节点
        javafx.scene.layout.Region bannerSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(bannerSpacer, Priority.ALWAYS);

        Label bannerStatus = new Label("在线 🟢");
        bannerStatus.getStyleClass().add("login-banner-status");

        HBox banner = new HBox(8, bannerTitle, bannerSpacer, bannerStatus);
        banner.getStyleClass().add("login-banner");
        banner.setMaxWidth(Double.MAX_VALUE);

        // ============================================================
        // 表单卡片：白底圆角阴影，QQ 风格表单区域
        // VBox：垂直堆叠错误提示 / info / 字段们 / 按钮
        // ============================================================
        Label title = new Label("SiMate");
        title.getStyleClass().add("login-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        Label subtitle = new Label("局域网加密聊天");
        subtitle.getStyleClass().add("login-subtitle");
        subtitle.setMaxWidth(Double.MAX_VALUE);
        subtitle.setAlignment(Pos.CENTER);

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
        serverPort.setMaxWidth(120);

        HBox ipRow = new HBox(12, ipBox, portBox);
        ipRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(ipBox, Priority.ALWAYS);

        // ---- 其余 3 个字段 ----
        VBox imCodeBox = fieldBox("IM_CODE", imCode, "输入配对码（如：ABC123）");
        VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");
        VBox usernameBox = fieldBox("用户名", username, "输入你的名称");

        VBox fields = new VBox(10,
                errorLabel, info, ipRow,
                imCodeBox, passwordBox, usernameBox);
        fields.setMaxWidth(Double.MAX_VALUE);
        fields.setFillWidth(true);

        // "连接"按钮：QQ 风格的绿色填充按钮
        Button connect = new Button("登 录");
        connect.setDefaultButton(true);
        connect.setMaxWidth(Double.MAX_VALUE);
        connect.getStyleClass().add("login-connect");
        connect.setOnAction(e -> handleConnect(onConnect));

        // 表单卡片容器（白底圆角）
        VBox card = new VBox(12, title, subtitle, fields, connect);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setFillWidth(true);

        // ============================================================
        // 底部 footer：左侧状态文字，右侧版本号
        // ============================================================
        Label footerLeft = new Label("● 在线");
        footerLeft.getStyleClass().add("login-footer-status");

        javafx.scene.layout.Region footerSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        Label footerRight = new Label("SiMate v1.0.0");
        footerRight.getStyleClass().add("login-footer-version");

        HBox footer = new HBox(8, footerLeft, footerSpacer, footerRight);
        footer.getStyleClass().add("login-footer");
        footer.setMaxWidth(Double.MAX_VALUE);

        // 组合：banner → card → footer（每个都是 HBox/VBox，maxSize=MAX 会自动填满宽度）
        getChildren().addAll(banner, card, footer);

        // 默认填入 IP 和端口（参考 SiMate 的 App.tsx 默认值）
        serverIp.setText("127.0.0.1");
        serverPort.setText("3000");
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

        VBox box = new VBox(2, l, field);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(true);
        return box;
    }

    private void handleConnect(Consumer<AppState> onConnect) {
        if (!validate()) return;
        AppState state = new AppState(username.getText().trim(), "Alice");
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