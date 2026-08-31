package com.glodon.mordor.kmate.ui;

import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

/**
 * 圆形头像控件。
 *
 * <p>三种展示形式（按优先级）：
 * <ol>
 *   <li>有图：用 ImageView 按 cover 模式裁成圆形</li>
 *   <li>无图 + 无 emptyText：显示名字首字（中文就一个字，英文就是首字母）</li>
 *   <li>无图 + 有 emptyText：显示占位文字（一般用于"未设置头像"的群成员）</li>
 * </ol>
 *
 * <p>控件本身是 StackPane：背景圆 + 文字 / 图片 都叠在一起，圆角裁剪通过 setClip(Circle) 实现。
 *
 * <p>裁剪圆用 bind 跟宽高对齐：哪怕 size 之后又变了，圆心也始终在中心。
 */
public class AvatarView extends StackPane {

    /**
     * 简化构造：不传占位文字（无图时显示名字首字）。
     */
    public AvatarView(String name, Image photo, boolean self, double size) {
        this(name, photo, self, size, null);
    }

    /**
     * 完整构造。
     *
     * @param name      用户名（用于取首字）
     * @param photo     头像图片，可以为 null（无图）
     * @param self      是否是"我"自己。true 时占位背景色用 avatar-fallback-self（绿），
     *                  false 时用 avatar-fallback（灰）。
     * @param size      头像边长（正方形）。圆半径 = size / 2。
     * @param emptyText 占位文字，比如"未"表示"未设置头像"。为 null / 空白时显示名字首字。
     */
    public AvatarView(String name, Image photo, boolean self, double size, String emptyText) {
        // CSS class：让 app.css 里能给所有头像统一设样式
        getStyleClass().add("avatar-view");
        // 强制控件是正方形。StackPane 默认会跟着内容走，不强制会变成 0。
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);

        // 圆形遮罩：用一个 Circle 当 clip，把超出的部分裁掉。
        double radius = size / 2;
        Circle clip = new Circle(radius);
        // 圆心绑到控件正中心：宽高怎么变，圆心都跟着动。
        clip.centerXProperty().bind(widthProperty().divide(2));
        clip.centerYProperty().bind(heightProperty().divide(2));
        setClip(clip);

        // 优先用图片。要排除掉图片加载失败、宽高 0 这两种情况。
        if (photo != null && !photo.isError() && photo.getWidth() > 0 && photo.getHeight() > 0) {
            ImageView view = new ImageView(photo);
            view.setPreserveRatio(true);   // 保持宽高比
            view.setSmooth(true);          // 高质量缩放
            // cover 模式：算出能完全覆盖 size x size 的最小缩放比。
            // 横向纵向哪个比例更大，就以哪个为准，保证短边刚好被填满，长边溢出（被 clip 裁掉）。
            double scale = Math.max(size / photo.getWidth(), size / photo.getHeight());
            view.setFitWidth(photo.getWidth() * scale);
            view.setFitHeight(photo.getHeight() * scale);
            getChildren().add(view);
            return;
        }

        // 无图：纯色背景圆 + 文字
        Circle bg = new Circle(radius);
        // "我"用绿底（avatar-fallback-self），别人用灰底（avatar-fallback）
        bg.getStyleClass().add(self ? "avatar-fallback-self" : "avatar-fallback");
        Label initial = new Label(placeholder(name, emptyText));
        // 占位文字样式：有 emptyText 用灰色 placeholder，否则用首字 initial
        initial.getStyleClass().add(emptyText == null || emptyText.isBlank()
                ? "avatar-initial" : "avatar-placeholder");
        getChildren().addAll(bg, initial);
    }

    /**
     * 算出要显示的文字。
     * 优先级：emptyText 非空 > 名字首字 > "?"。
     */
    private static String placeholder(String name, String emptyText) {
        if (emptyText != null && !emptyText.isBlank()) {
            return emptyText;
        }
        if (name == null || name.isBlank()) {
            return "?";
        }
        return name.substring(0, 1);
    }
}
