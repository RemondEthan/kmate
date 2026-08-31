package com.glodon.mordor.kmate.app;

import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Taskbar;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 窗口标题栏、Windows 任务栏按钮、未读红点共用同一套图标。
 * 任务栏要 16/32，不能只丢一张 512 PNG。
 *
 * JavaFX 和 AWT 是两套图像系统：
 *   - JavaFX Image（javafx.scene.image.Image）：给 Stage.getIcons() 用。
 *   - java.awt.Image：给 Taskbar.setIconImage 用（Windows 任务栏 / macOS Dock 角标）。
 *
 * 这里把 PNG 资源读成两套，一套给 Stage，一套给 Taskbar。
 */
final class AppIcons {

    /*
     * Stage 需要的图标尺寸：16（窗口标题栏）、32（任务栏小图标）、48（中）、256（高分屏）。
     * 给系统越多尺寸，缩放时挑最接近的渲染，避免糊。
     */
    static final int[] STAGE_SIZES = {16, 32, 48, 256};

    private AppIcons() {}

    /**
     * 把 PNG 资源读成 AWT Image（java.awt.Image）。
     * 用 ImageIO 而不是 JavaFX 的 ImageIO 是为了拿到 AWT 那一侧的图像。
     */
    static java.awt.Image awtImage(String path) {
        try (InputStream in = AppIcons.class.getResourceAsStream(path)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 把 PNG 资源读成多个尺寸的 JavaFX Image 列表。
     */
    static List<Image> fxIcons(String path) {
        var url = AppIcons.class.getResource(path);
        if (url == null) {
            return List.of();
        }
        String spec = url.toExternalForm();
        List<Image> out = new ArrayList<>();
        for (int size : STAGE_SIZES) {
            // 第 3 个参数 true 表示按指定尺寸缩放；第 4 个参数 true 表示保留比例（背景透明）。
            out.add(new Image(spec, size, size, true, true));
        }
        return out;
    }

    /**
     * 把一组 JavaFX Image 应用到 Stage 的图标集合。
     * Stage.getIcons() 返回 ObservableList，setAll 直接替换整个列表。
     */
    static void applyStage(Stage stage, String path) {
        ObservableList<Image> icons = stage.getIcons();
        icons.setAll(fxIcons(path));
    }

    /**
     * 把 AWT Image 应用到操作系统任务栏 / Dock。
     *
     * 必须用 AwtSupport.run() 切到 AWT-EDT 执行：Taskbar API 设计上要求 EDT 上下文。
     * 不支持的环境（如 macOS）静默跳过。
     */
    static void applyTaskbar(java.awt.Image image) {
        if (image == null) {
            return;
        }
        AwtSupport.run(() -> {
            try {
                if (!Taskbar.isTaskbarSupported()) {
                    return;
                }
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(image);
                }
            } catch (Exception ignored) {
                // 部分环境不支持改任务栏图标
            }
        });
    }

}
