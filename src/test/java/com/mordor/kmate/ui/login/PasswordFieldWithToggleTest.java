package com.mordor.kmate.ui.login;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordFieldWithToggleTest {

    @BeforeAll
    static void initJavaFX() {
        // 初始化 JavaFX 平台，否则 PasswordField 等控件无法实例化
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // 平台已初始化，忽略
        }
    }

    @Test
    void initialStateHiddenFieldIsVisibleAndShownFieldIsNot() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        assertTrue(f.hidden.isVisible(), "初始应显示密文框");
        assertFalse(f.shown.isVisible(), "初始应隐藏明文框");
        assertTrue(f.hidden.isManaged(), "密文框应参与布局");
        assertFalse(f.shown.isManaged(), "明文框不参与布局");
    }

    @Test
    void initialTextIsEmpty() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        assertEquals("", f.getText());
    }

    @Test
    void initialEyeButtonShowsOpenGlyph() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        assertEquals("👁", f.eye.getText());  // 👁
    }

    @Test
    void carriesLoginFieldStyleAndPasswordWithToggleClass() {
        PasswordFieldWithToggle f = new PasswordFieldWithToggle();
        assertTrue(f.hidden.getStyleClass().contains("login-field"), "密文框需带 .login-field");
        assertTrue(f.shown.getStyleClass().contains("login-field"), "明文框需带 .login-field");
        assertTrue(f.getStyleClass().contains("password-with-toggle"),
                "容器需带 .password-with-toggle 让 CSS 补丁命中");
    }
}