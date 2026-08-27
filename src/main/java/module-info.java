module com.glodon.mordor.kmate {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires java.desktop;   // 用于 AWT SystemTray（托盘图标）
    requires java.prefs;     // Preferences API，用于持久化上次登录配置
    requires java.net.http;  // java.net.http.WebSocket

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.materialdesign2;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;

    exports com.glodon.mordor.kmate.app;
    exports com.glodon.mordor.kmate.ui.login;
    exports com.glodon.mordor.kmate.ui.chat;
    exports com.glodon.mordor.kmate.model;
    exports com.glodon.mordor.kmate.service;
    exports com.glodon.mordor.kmate.common;

    opens com.glodon.mordor.kmate.app      to javafx.fxml;
    opens com.glodon.mordor.kmate.ui.login to javafx.fxml;
    opens com.glodon.mordor.kmate.ui.chat  to javafx.fxml;
    opens com.glodon.mordor.kmate.model    to javafx.fxml;
    opens com.glodon.mordor.kmate.service  to javafx.fxml;
    opens com.glodon.mordor.kmate.common   to javafx.fxml;
}
