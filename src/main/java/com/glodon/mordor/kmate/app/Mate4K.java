package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.common.Diag;
import com.glodon.mordor.kmate.kelsy.KelsyRuntime;
import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.service.ImClient;
import com.glodon.mordor.kmate.ui.chat.ChatPane;
import com.glodon.mordor.kmate.ui.login.LoginPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Mate4K extends Application {

    private static final double WIDTH = 720;
    private static final double HEIGHT = 449;

    private Stage stage;
    private StackPane root;
    private ImClient session;
    private UnreadAlert unreadAlert;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.root = new StackPane();
        root.getStyleClass().add("app-bg");
        showLogin();

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                Mate4K.class.getResource("app.css").toExternalForm());

        stage.setTitle("k-mate");
        AppIcons.applyStage(stage, "/icons/Kmate.png");
        stage.setScene(scene);
        stage.setMinWidth(560);
        stage.setMinHeight(360);
        Platform.setImplicitExit(false);

        if (!SingleInstance.claim(() -> FxStageSupport.show(stage))) {
            Platform.exit();
            return;
        }

        stage.show();
        Diag.startFxWatchdog();

        TrayManager trayManager = TrayManager.install(stage);
        AppIcons.applyTaskbar(AppIcons.awtImage("/icons/Kmate.png"));
        unreadAlert = UnreadAlert.install(stage, trayManager);
        QuitManager quitManager = new QuitManager(trayManager, this::closeSession);

        trayManager.setOnQuit(quitManager::quit);
        ShortcutRegistrar.register(scene, () -> FxStageSupport.minimize(stage), quitManager::quit);
        // 红点：藏窗口。⌘W：最小化。会话保持，点 Dock 还原聊天窗。
        stage.setOnCloseRequest(e -> {
            e.consume();
            FxStageSupport.hide(stage);
        });
        OsQuitHandlers.install(quitManager::quit, () -> FxStageSupport.show(stage));
    }

    private void showLogin() {
        stage.setTitle("k-mate");
        root.getChildren().setAll(new LoginPane(this::enterChat));
    }

    private void enterChat(AppState state) {
        closeSession();
        session = state.client();
        unreadAlert.watch(session);
        stage.setTitle(state.username());
        root.getChildren().setAll(new ChatPane(state));
    }

    private void closeSession() {
        if (unreadAlert != null) {
            unreadAlert.clear();
        }
        ImClient client = session;
        session = null;
        if (client != null) {
            client.close();
        }
        KelsyRuntime.shutdown();
    }

    public static void main(String[] args) {
        AwtSupport.preinit();
        launch();
    }
}
