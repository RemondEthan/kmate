package com.glodon.mordor.kmate.app;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * 跨平台全局快捷键注册。
 *
 *   ⌘W / Ctrl+W —— 最小化窗口（保持登录）
 *   ⌘Q / Ctrl+Q —— 退出程序（由 onQuit 决定具体行为）
 *
 * 通过 Scene.getAccelerators() 注册，不依赖焦点控件，TextField 输入时也能触发。
 *
 * 为什么不直接给 Stage 或 Button 装 onKeyPressed：
 *   JavaFX 键盘事件默认只在"有焦点的控件"触发；TextField 一旦聚焦，
 *   父容器的 onKeyPressed 就拿不到 ⌘W / ⌘Q。
 *   Scene.getAccelerators() 是 FX 提供的全局快捷键层，绕开焦点机制。
 */
public final class ShortcutRegistrar {

    private ShortcutRegistrar() {}

    /**
     * 在 scene 上注册两条快捷键。
     *
     * @param scene       绑定的 Scene。
     * @param onMinimize  ⌘W / Ctrl+W 触发：窗口最小化。
     * @param onQuit      ⌘Q / Ctrl+Q 触发：交给 QuitManager 决定怎么退出。
     */
    public static void register(Scene scene, Runnable onMinimize, Runnable onQuit) {
        // SHORTCUT_DOWN 是平台修饰键：macOS = ⌘，Windows/Linux = Ctrl。
        KeyCombination.Modifier mod = KeyCombination.SHORTCUT_DOWN;
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, mod),
                onMinimize);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Q, mod),
                onQuit);
    }
}