package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.service.AvatarService;
import com.glodon.mordor.kmate.service.ImClient;
import com.glodon.mordor.kmate.service.SaveLastLoginService;
import com.glodon.mordor.kmate.ui.AvatarView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 登录面板：极简风格，白色表单卡片铺满窗口，四周留出缩减后的灰边。
 *
 * <p>窗口 720×449、卡片原宽 380 时，左右灰边各约 110px、上下各约 64px。
 * 边框空白缩 40% 后，内边距为上下 38px、左右 66px，卡片吃掉收回的空间。
 *
 * <p>本类只做"装配 + 委托"：所有业务校验与落盘由 {@link LoginController} 完成。
 *
 * <p>整体布局结构（外到内）：
 * <pre>
 * LoginPane (VBox, padding 38,66,38,66, app-bg 背景)
 *   └── VBox card (.login-card, 卡片背景)
 *         ├── Region cardTop    (弹性空间，把 fields 顶到中间)
 *         ├── VBox fields
 *         │     ├── errorLabel  (默认隐藏)
 *         │     ├── info        (灰色提示)
 *         │     ├── HBox ipRow  (服务器 IP + 端口)
 *         │     ├── imCodeBox
 *         │     ├── passwordBox
 *         │     └── HBox profileRow (头像 + 用户名)
 *         ├── Button connect
 *         └── Region cardBottom (弹性空间)
 * </pre>
 */
public class LoginPane extends VBox {

    // 表单字段：服务器地址、端口、配对码、初始口令、本地显示的用户名。
    private final TextField serverIp = new TextField();
    private final TextField serverPort = new TextField();
    private final TextField imCode = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField username = new TextField();

    // 错误信息条：校验失败或连接失败时显示
    private final Label errorLabel = new Label();

    // 业务侧：负责校验 / 落盘 / 预填
    private final LoginController controller;
    // 登录成功回调：通知 Mate4K 切到聊天面板
    private final Consumer<AppState> onConnect;
    // "连 接"按钮：用户点这个触发登录
    private final Button connect = new Button("连 接");
    // 当前选中的头像文件路径（绝对路径）。空字符串 = 没选过。
    private String avatarPath = "";
    // 头像点击热区（StackPane，套了一个 AvatarView 在里面）
    private StackPane avatarSlot;

    public LoginPane(Consumer<AppState> onConnect) {
        // VBox 间距 = 0：内部 card 自己控制间距
        super(0);
        this.controller = new LoginController(new SaveLastLoginService());
        this.onConnect = onConnect;

        // 整体背景 + 登录根样式（在 app.css / login.css 里定义）
        getStyleClass().addAll("app-bg", "login-root");
        getStylesheets().add(
                LoginPane.class.getResource("login.css").toExternalForm());

        // 让 LoginPane 占满 Stage（Stage 内部是 StackPane，这里要抢空间）
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setFillWidth(true);
        // 内边距：上下 38，左右 66（对应"边框空白缩 40%"后的样子）
        setPadding(new Insets(38, 66, 38, 66));

        // 错误条：默认隐藏。setManaged(false) 让它不参与布局，省空间。
        errorLabel.getStyleClass().add("login-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);

        // 顶部灰字提示
        Label info = new Label("请与对方约定相同的 IM_CODE 和初始口令进行配对");
        info.getStyleClass().add("login-info");
        info.setWrapText(true);
        info.setMaxWidth(Double.MAX_VALUE);

        // 服务器 IP + 端口：横向并排，等宽（ALWAYS + ALWAYS）
        VBox ipBox = fieldBox("服务器 IP", serverIp, "127.0.0.1");
        VBox portBox = fieldBox("端口", serverPort, "3000");

        HBox ipRow = new HBox(12, ipBox, portBox);
        ipRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(ipBox, Priority.ALWAYS);
        HBox.setHgrow(portBox, Priority.ALWAYS);

        VBox imCodeBox = fieldBox("IM_CODE", imCode, "输入配对码(如:ABC123)");
        VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");
        VBox usernameBox = fieldBox("用户名", username, "输入你的名称");
        HBox.setHgrow(usernameBox, Priority.ALWAYS);
        // 头像 + 用户名：左对齐，间距 10
        HBox profileRow = new HBox(10, avatarPicker(), usernameBox);
        profileRow.setAlignment(Pos.CENTER_LEFT);
        profileRow.setMaxWidth(Double.MAX_VALUE);

        // 表单整体 VBox：所有字段 + 错误条 + 提示 + IP 行 + 配对码 + 口令 + 头像行
        VBox fields = new VBox(8,
                errorLabel, info, ipRow,
                imCodeBox, passwordBox, profileRow);
        fields.setMaxWidth(Double.MAX_VALUE);
        fields.setFillWidth(true);

        // 按钮：默认按钮（按 Enter 直接登录）+ 占满宽度
        connect.setDefaultButton(true);
        connect.setMaxWidth(Double.MAX_VALUE);
        connect.getStyleClass().add("login-connect");
        connect.setOnAction(e -> handleConnect());

        // 两个弹性 Region：把 fields+connect 顶到垂直居中
        Region cardTop = new Region();
        Region cardBottom = new Region();
        VBox.setVgrow(cardTop, Priority.ALWAYS);
        VBox.setVgrow(cardBottom, Priority.ALWAYS);

        // 卡片本身：垂直布局 + login-card 样式（白底、圆角等）
        VBox card = new VBox(10, cardTop, fields, connect, cardBottom);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        // card 在 LoginPane 里垂直占满（ALWAYS 让 card 高度 = LoginPane - padding）
        VBox.setVgrow(card, Priority.ALWAYS);

        getChildren().add(card);

        // 启动时从 SaveLastLoginService 读预填值
        LoginController.Prefilled p = controller.prefill();
        serverIp.setText(p.ip());
        serverPort.setText(p.port());
        imCode.setText(p.imCode());
        username.setText(p.username());
        avatarPath = controller.avatarPath();
        refreshAvatarPreview();
    }

    /**
     * 头像点击区：就是一个 40x40 的 StackPane，套了一个 AvatarView 进去。
     * 点击 → 调 pickAvatar() 弹文件选择框。
     */
    private StackPane avatarPicker() {
        avatarSlot = new StackPane();
        avatarSlot.getStyleClass().add("login-avatar-slot");
        // 鼠标悬停变手型，提示"可点"
        avatarSlot.setCursor(Cursor.HAND);
        avatarSlot.setOnMouseClicked(e -> pickAvatar());
        refreshAvatarPreview();
        return avatarSlot;
    }

    /**
     * 弹文件选择框，让用户选头像。选完之后刷新预览 + 落盘。
     * AvatarService.chooseAndStore 在没有 Window 时也能跑（FileChooser.showOpenDialog 需要 Window）。
     */
    private void pickAvatar() {
        AvatarService.chooseAndStore(getScene() == null ? null : getScene().getWindow())
                .ifPresent(path -> {
                    avatarPath = path;
                    controller.saveAvatarPath(path);
                    refreshAvatarPreview();
                });
    }

    /**
     * 重画头像预览。从 AvatarService.load 读图（读不到就显示"头像"占位文字）。
     * AvatarView 的 emptyText 参数：true = 显示"头像"灰色占位，false = 显示名字首字。
     */
    private void refreshAvatarPreview() {
        if (avatarSlot == null) {
            return;
        }
        var photo = AvatarService.load(avatarPath).orElse(null);
        AvatarView view = new AvatarView(
                username.getText(),
                photo,
                true,  // self = true：自己的头像用绿色底
                40,
                photo == null ? "头像" : null);
        avatarSlot.getChildren().setAll(view);
    }

    /**
     * 构造一个标准的"标签在上、输入框在下"的字段盒子。
     * field 可以是 TextField 或 PasswordField，两者共用同一套样式。
     *
     * @param labelText   字段标签
     * @param field       输入控件（TextField / PasswordField）
     * @param placeholder 输入框为空时的灰色提示
     */
    private VBox fieldBox(String labelText, Control field, String placeholder) {
        Label l = new Label(labelText);
        l.getStyleClass().add("login-field-label");
        l.setMaxWidth(Double.MAX_VALUE);

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

    /**
     * "连 接"按钮的点击处理。
     *
     * <p>流程：
     * <ol>
     *   <li>把表单里的 TextField 全部读出来，打包成 LoginController.Input</li>
     *   <li>调 controller.validate 校验。失败就 showError 并 return</li>
     *   <li>按钮变 "连接中..."，禁用</li>
     *   <li>构造 ImClient，发起 connect，返回 CompletableFuture</li>
     *   <li>成功后：跳回 FX 线程，落盘 + 回调 onConnect（切到聊天面板）</li>
     *   <li>失败后：跳回 FX 线程，关掉 client，恢复按钮文案，显示错误</li>
     * </ol>
     */
    private void handleConnect() {
        var input = new LoginController.Input(
                serverIp.getText().trim(),
                serverPort.getText().trim(),
                imCode.getText().trim(),
                password.getText(),    // 口令不 trim
                username.getText().trim());

        var result = controller.validate(input);
        if (result instanceof LoginController.Result.Invalid i) {
            showError(i.message());
            return;
        }

        hideError();
        connect.setDisable(true);
        connect.setText("连接中...");

        ImClient client = new ImClient();
        // 如果选了头像，把头像转成 base64 字符串塞给客户端，握手时带上
        AvatarService.thumbnailBase64(avatarPath).ifPresent(client::setAvatarPlaintext);
        int port = Integer.parseInt(input.port());
        Diag.log("login", "connect click user=%s host=%s:%s", input.username(), input.ip(), input.port());
        // connect() 返回 CompletableFuture<Void>：握手成功 -> thenRun；失败 -> exceptionally
        client.connect(input.ip(), port, input.imCode(), input.password(), input.username())
                .thenRun(() -> {
                    Diag.log("login", "handshake ok, queue enter-chat");
                    // ImClient 在自己的线程池里调 thenRun，要 hop 回 FX 线程才能碰 UI
                    Platform.runLater(() -> {
                        long t0 = System.nanoTime();
                        Diag.log("login", "enter chat begin");
                        controller.save(input);
                        onConnect.accept(new AppState(
                                input.username(),
                                client,
                                AvatarService.load(avatarPath).orElse(null)));
                        Diag.log("login", "enter chat done %dms", Diag.elapsedMs(t0));
                    });
                })
                .exceptionally(ex -> {
                    Diag.error("login", "handshake failed: %s", connectErrorMessage(ex));
                    // 失败：同样要 hop 回 FX 线程操作 UI
                    Platform.runLater(() -> {
                        client.close();
                        connect.setDisable(false);
                        connect.setText("连 接");
                        showError(connectErrorMessage(ex));
                    });
                    return null;
                });
    }

    /**
     * 把错误信息显示到 errorLabel 上。setManaged(true) 让它参与布局。
     */
    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    /**
     * 隐藏错误条 + 让它退出布局。
     */
    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    /**
     * 把异常链 unwrap 到最深的 cause，然后翻译成中文给用户看。
     *
     * <p>已知翻译：
     * <ul>
     *   <li>TimeoutException → "连接超时"</li>
     *   <li>Connection refused / ConnectException → "无法连接服务器"</li>
     *   <li>其他 → 直接显示最深层 message；message 为空 → "连接失败"</li>
     * </ul>
     */
    private static String connectErrorMessage(Throwable ex) {
        // unwrap 异常链：CompletableFuture 经常包 CompletionException 等好几层
        Throwable cur = ex;
        while (cur.getCause() != null && cur != cur.getCause()) {
            cur = cur.getCause();
        }
        if (cur instanceof java.util.concurrent.TimeoutException) {
            return "连接超时";
        }
        String message = cur.getMessage();
        if (message == null || message.isBlank()) {
            return "连接失败";
        }
        if (message.contains("Connection refused") || message.contains("ConnectException")) {
            return "无法连接服务器";
        }
        return message;
    }
}
