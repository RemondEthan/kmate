package com.glodon.mordor.kmate;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * 应用入口：登录窗口 ↔ 聊天窗口 的切换容器。
 *
 * StackPane：所有子节点叠在一起显示在同一区域，通过 add/remove 切换可见内容。
 * 这里用它来实现"登录成功之前显示 LoginPane，之后替换为 ChatPane"。
 */
public class Mate4K extends Application {

    // 窗口尺寸（像素）：宽度 480 +25%，高度 = 480 +10% 再 -15%
    private static final double WIDTH = 600;
    private static final double HEIGHT = 449;

    @Override
    public void start(Stage stage) {
        // StackPane：堆叠容器，子节点居中叠放
        StackPane root = new StackPane();
        root.getStyleClass().add("app-bg");

        // 先放登录面板；点"连接"后会替换为聊天面板
        LoginPane login = new LoginPane(state -> showChat(root, state));
        root.getChildren().add(login);

        // Scene = 场景：JavaFX 的内容容器，对应一个窗口的内容区域
        // 第二个参数是宽高（与 WIDTH/HEIGHT 对应）
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        // 加载全局样式表：所有节点都可以引用 styles.css 里的 .xxx 类
        scene.getStylesheets().add(Mate4K.class.getResource("styles.css").toExternalForm());

        // Stage：顶层窗口（即 macOS 上那个标题栏 + 内容区的窗口）
        stage.setTitle("SiMate");
        stage.setScene(scene);
        stage.show();  // 显示窗口（非阻塞，立即返回）
    }

    // 把 StackPane 的内容替换为聊天面板（setAll 会清空原有子节点）
    private void showChat(StackPane root, AppState state) {
        root.getChildren().setAll(new ChatPane(state));
    }

    public static void main(String[] args) {
        // Application.launch() 初始化 JavaFX 运行时，然后回调 start()
        launch();
    }
}