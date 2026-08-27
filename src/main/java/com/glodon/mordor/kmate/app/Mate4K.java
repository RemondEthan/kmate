package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.ui.chat.ChatPane;
import com.glodon.mordor.kmate.ui.login.LoginPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Mate4K extends Application {

    private static final double WIDTH = 600;
    private static final double HEIGHT = 449;

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        root.getStyleClass().add("app-bg");
        root.getChildren().add(new LoginPane(state ->
                root.getChildren().setAll(new ChatPane(state))));

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                Mate4K.class.getResource("app.css").toExternalForm());

        stage.setTitle("k-mate");
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(360);
        Platform.setImplicitExit(false);
        stage.show();
        Diag.startFxWatchdog();

        TrayManager trayManager = TrayManager.install(stage);
        QuitManager quitManager = new QuitManager(trayManager.tray(), trayManager.icon());

        trayManager.setOnQuit(quitManager::quit);
        ShortcutRegistrar.register(scene, stage, quitManager::quit);
        // 红点 / 关闭按钮只隐藏窗口；真正退出走 ⌘Q、Dock「退出」或托盘「退出」
        stage.setOnCloseRequest(e -> {
            e.consume();
            stage.hide();
        });
        OsQuitHandlers.install(quitManager::quit, () -> FxStageSupport.show(stage));
    }

    public static void main(String[] args) {
        launch();
    }
}
