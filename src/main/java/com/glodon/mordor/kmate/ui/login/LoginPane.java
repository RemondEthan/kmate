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
import javafx.scene.control.CheckBox;
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
 * 登录面板:极简风格,白色表单卡片铺满窗口,四周留出缩减后的灰边。
 *
 * 窗口 600×449、卡片原宽 380 时,左右灰边各约 110px、上下各约 64px。
 * 边框空白缩 40% 后,内边距为上下 38px、左右 66px,卡片吃掉收回的空间。
 *
 * 本类只做"装配 + 委托":所有业务校验与落盘由 LoginController 完成。
 */
public class LoginPane extends VBox {

    private final TextField serverIp = new TextField();
    private final TextField serverPort = new TextField();
    private final TextField imCode = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField username = new TextField();

    private final Label errorLabel = new Label();
    private final CheckBox offline = new CheckBox("脱机登录");
    private final Label info = new Label();

    private final LoginController controller;
    private final Consumer<AppState> onConnect;
    private final Button connect = new Button("连 接");
    private String avatarPath = "";
    private StackPane avatarSlot;

    public LoginPane(Consumer<AppState> onConnect) {
        super(0);
        this.controller = new LoginController(new SaveLastLoginService());
        this.onConnect = onConnect;

        getStyleClass().addAll("app-bg", "login-root");
        getStylesheets().add(
                LoginPane.class.getResource("login.css").toExternalForm());

        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setFillWidth(true);
        setPadding(new Insets(38, 66, 38, 66));

        errorLabel.getStyleClass().add("login-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);

        info.getStyleClass().add("login-info");
        info.setWrapText(true);
        info.setMaxWidth(Double.MAX_VALUE);

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
        HBox profileRow = new HBox(10, avatarPicker(), usernameBox);
        profileRow.setAlignment(Pos.CENTER_LEFT);
        profileRow.setMaxWidth(Double.MAX_VALUE);

        VBox fields = new VBox(8,
                errorLabel, info, ipRow,
                imCodeBox, passwordBox, profileRow);
        fields.setMaxWidth(Double.MAX_VALUE);
        fields.setFillWidth(true);

        offline.getStyleClass().add("login-offline");
        offline.setSelected(false);
        offline.selectedProperty().addListener((obs, o, on) -> applyOffline(on));

        connect.setDefaultButton(true);
        connect.setMaxWidth(Double.MAX_VALUE);
        connect.getStyleClass().add("login-connect");
        connect.setOnAction(e -> handleConnect());

        Region cardTop = new Region();
        Region cardBottom = new Region();
        VBox.setVgrow(cardTop, Priority.ALWAYS);
        VBox.setVgrow(cardBottom, Priority.ALWAYS);

        VBox card = new VBox(10, cardTop, fields, offline, connect, cardBottom);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(card, Priority.ALWAYS);

        getChildren().add(card);

        LoginController.Prefilled p = controller.prefill();
        serverIp.setText(p.ip());
        serverPort.setText(p.port());
        imCode.setText(p.imCode());
        username.setText(p.username());
        avatarPath = controller.avatarPath();
        refreshAvatarPreview();
        applyOffline(false);
    }

    private void applyOffline(boolean on) {
        serverIp.setDisable(on);
        serverPort.setDisable(on);
        imCode.setDisable(on);
        password.setDisable(on);
        info.setText(on
                ? "脱机只和本机秘书对话，不会连接服务器"
                : "请与对方约定相同的 IM_CODE 和初始口令进行配对");
        connect.setText(on ? "进 入" : "连 接");
    }

    private StackPane avatarPicker() {
        avatarSlot = new StackPane();
        avatarSlot.getStyleClass().add("login-avatar-slot");
        avatarSlot.setCursor(Cursor.HAND);
        avatarSlot.setOnMouseClicked(e -> pickAvatar());
        refreshAvatarPreview();
        return avatarSlot;
    }

    private void pickAvatar() {
        AvatarService.chooseAndStore(getScene() == null ? null : getScene().getWindow())
                .ifPresent(path -> {
                    avatarPath = path;
                    controller.saveAvatarPath(path);
                    refreshAvatarPreview();
                });
    }

    private void refreshAvatarPreview() {
        if (avatarSlot == null) {
            return;
        }
        var photo = AvatarService.load(avatarPath).orElse(null);
        AvatarView view = new AvatarView(
                username.getText(),
                photo,
                true,
                40,
                photo == null ? "头像" : null);
        avatarSlot.getChildren().setAll(view);
    }

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

    private void handleConnect() {
        var input = new LoginController.Input(
                serverIp.getText().trim(),
                serverPort.getText().trim(),
                imCode.getText().trim(),
                password.getText(),
                username.getText().trim(),
                offline.isSelected());

        var result = controller.validate(input);
        if (result instanceof LoginController.Result.Invalid i) {
            showError(i.message());
            return;
        }

        if (input.offline()) {
            hideError();
            controller.save(input);
            onConnect.accept(AppState.offline(
                    input.username(),
                    AvatarService.load(avatarPath).orElse(null)));
            return;
        }

        hideError();
        connect.setDisable(true);
        connect.setText("连接中...");

        ImClient client = new ImClient();
        AvatarService.thumbnailBase64(avatarPath).ifPresent(client::setAvatarPlaintext);
        int port = Integer.parseInt(input.port());
        Diag.log("login", "connect click user=%s host=%s:%s", input.username(), input.ip(), input.port());
        client.connect(input.ip(), port, input.imCode(), input.password(), input.username())
                .thenRun(() -> {
                    Diag.log("login", "handshake ok, queue enter-chat");
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
                    Platform.runLater(() -> {
                        client.close();
                        connect.setDisable(false);
                        connect.setText("连 接");
                        showError(connectErrorMessage(ex));
                    });
                    return null;
                });
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private static String connectErrorMessage(Throwable ex) {
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
