package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.ui.chat.ChatPane;
import com.glodon.mordor.kmate.ui.login.LoginPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.layout.StackPane;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import java.awt.Desktop;
import java.awt.SystemTray;
import java.awt.TrayIcon;

public class Mate4K extends Application {

    private static final double WIDTH = 600;
    private static final double HEIGHT = 449;

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        root.getStyleClass().add("app-bg");

        LoginPane login = new LoginPane(state -> showChat(root, state));
        StackPane.setAlignment(login, Pos.CENTER);
        root.getChildren().add(login);

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                Mate4K.class.getResource("app.css").toExternalForm());

        final KeyCombination.Modifier shortcut = KeyCombination.SHORTCUT_DOWN;
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, shortcut),
                () -> { if (stage.isShowing()) stage.hide(); });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Q, shortcut),
                this::quitApp);

        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(e -> { e.consume(); quitApp(); });

        stage.setTitle("k-mate");
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(360);

        stage.show();

        TrayManager.install(stage);
        installOsQuitHandlers();
    }

    private void showChat(StackPane root, AppState state) {
        root.getChildren().setAll(new ChatPane(state));
    }

    private void installOsQuitHandlers() {
        MacQuitHook.install(this::quitApp);
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                Desktop.getDesktop().setQuitHandler((e, r) -> {
                    quitApp();
                    r.performQuit();
                });
            }
        } catch (Throwable t) {
            System.err.println("[Quit] Desktop quit hook 安装失败: " + t.getMessage());
        }
    }

    /** 占位 quit:Task 11 之前仍在 Mate4K;Task 11 把这里删除并把字段迁移到 QuitManager。 */
    private void quitApp() {
        SystemTray tray = SystemTray.getSystemTray();
        for (TrayIcon i : tray.getTrayIcons()) {
            tray.remove(i);
        }
        Runtime.getRuntime().halt(0);
    }

    public static void main(String[] args) {
        launch();
    }
}
