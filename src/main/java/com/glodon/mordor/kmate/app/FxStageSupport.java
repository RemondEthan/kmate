package com.glodon.mordor.kmate.app;

import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * 从 AWT/AppKit 回调恢复 JavaFX 窗口时，必须先 hop 出原生栈，
 * 再进 FX 线程，否则会和 Glass 死锁。
 */
final class FxStageSupport {

    private FxStageSupport() {}

    static void show(Stage stage) {
        runHopped(() -> {
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            if (!stage.isShowing()) {
                stage.show();
            }
            stage.toFront();
            stage.requestFocus();
        });
    }

    static void hide(Stage stage) {
        runHopped(() -> {
            if (stage.isShowing()) {
                stage.hide();
            }
        });
    }

    static void runHopped(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        Thread hop = new Thread(() -> Platform.runLater(action), "kmate-fx-hop");
        hop.setDaemon(true);
        hop.start();
    }
}
