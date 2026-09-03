package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.service.SaveLastLoginService;

/**
 * 登录面板的业务侧：预填 / 校验 / 落盘。真正连服务器由 LoginPane + ImClient 完成。
 */
public class LoginController {

    private final SaveLastLoginService saveService;

    public LoginController(SaveLastLoginService saveService) {
        this.saveService = saveService;
    }

    public record Prefilled(String ip, String port, String imCode,
                            String username, String peerName) {}

    public record Input(String ip, String port, String imCode,
                        String password, String username, boolean offline) {
        public Input(String ip, String port, String imCode,
                     String password, String username) {
            this(ip, port, imCode, password, username, false);
        }
    }

    public sealed interface Result {
        record Ok() implements Result {}
        record Invalid(String message) implements Result {}
    }

    public Prefilled prefill() {
        return new Prefilled(
                saveService.getServerIp(),
                saveService.getServerPort(),
                saveService.getImCode(),
                saveService.getUsername(),
                saveService.getPeerName());
    }

    public Result validate(Input input) {
        if (input.offline()) {
            if (input.username().isBlank()) {
                return new Result.Invalid("请输入用户名");
            }
            return new Result.Ok();
        }
        if (input.ip().isBlank()) {
            return new Result.Invalid("请输入服务器 IP");
        }
        if (input.port().isBlank()) {
            return new Result.Invalid("请输入端口");
        }
        if (!input.port().matches("\\d+")) {
            return new Result.Invalid("端口必须是数字");
        }
        int port = Integer.parseInt(input.port());
        if (port < 1 || port > 65535) {
            return new Result.Invalid("端口必须在 1-65535 之间");
        }
        if (input.imCode().isBlank()) {
            return new Result.Invalid("请输入 IM_CODE");
        }
        if (input.password().isBlank()) {
            return new Result.Invalid("请输入初始口令");
        }
        if (input.username().isBlank()) {
            return new Result.Invalid("请输入用户名");
        }
        return new Result.Ok();
    }

    public void save(Input input) {
        if (input.offline()) {
            saveService.save(
                    saveService.getServerIp(),
                    saveService.getServerPort(),
                    saveService.getImCode(),
                    input.username(),
                    saveService.getPeerName());
            return;
        }
        saveService.save(input.ip(), input.port(), input.imCode(),
                input.username(), saveService.getPeerName());
    }

    public String avatarPath() {
        return saveService.getAvatarPath();
    }

    public void saveAvatarPath(String path) {
        saveService.saveAvatarPath(path);
    }
}
