module com.mordor.kmate {
    requires javafx.controls;
    requires com.fasterxml.jackson.databind;
    requires javafx.fxml;
    requires javafx.web;
    requires java.desktop;   // 用于 AWT SystemTray（托盘图标）
    requires java.prefs;     // Preferences API，用于持久化上次登录配置
    requires java.net.http;  // java.net.http.WebSocket
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;

    requires agentscope.core;
    requires agentscope.harness;
    requires agentscope.extensions.model.openai;
    requires reactor.core;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.materialdesign2;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;

    exports com.mordor.kmate.app;
    exports com.mordor.kmate.ui.login;
    exports com.mordor.kmate.ui.chat;
    exports com.mordor.kmate.model;
    exports com.mordor.kmate.service;
    exports com.mordor.kmate.common;

    opens com.mordor.kmate.app      to javafx.fxml;
    opens com.mordor.kmate.ui.login to javafx.fxml;
    opens com.mordor.kmate.ui.chat  to javafx.fxml;
    opens com.mordor.kmate.model    to javafx.fxml;
    opens com.mordor.kmate.service  to javafx.fxml;
    opens com.mordor.kmate.common   to javafx.fxml;
    opens com.mordor.kmate.kelsy.config to com.fasterxml.jackson.databind;
    opens com.mordor.kmate.kelsy.provider to com.fasterxml.jackson.databind;
}
