package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.service.SaveLastLoginService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginControllerTest {

    private final LoginController controller = new LoginController(new SaveLastLoginService());

    @Test
    void onlineStillRequiresServerFields() {
        LoginController.Input empty = new LoginController.Input("", "", "", "", "", false);
        assertInstanceOf(LoginController.Result.Invalid.class, controller.validate(empty));
        LoginController.Input noUser = new LoginController.Input(
                "127.0.0.1", "3000", "ABC", "pw", "", false);
        assertInstanceOf(LoginController.Result.Invalid.class, controller.validate(noUser));
    }

    @Test
    void offlineAcceptsUsernameOnly() {
        LoginController.Input input = new LoginController.Input("", "", "", "", "法内狂徒", true);
        assertInstanceOf(LoginController.Result.Ok.class, controller.validate(input));
    }

    @Test
    void offlineRejectsBlankUsername() {
        LoginController.Input input = new LoginController.Input("", "", "", "", "  ", true);
        var result = controller.validate(input);
        assertInstanceOf(LoginController.Result.Invalid.class, result);
        assertTrue(((LoginController.Result.Invalid) result).message().contains("用户名"));
    }
}
