package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.service.SaveLastLoginService;

/**
 * 登录面板的"业务"侧:预填 / 校验 / 落盘 / 产出 AppState。
 *
 * UI 负责装配 + 回调式消费本类的返回值;本类不持有任何 JavaFX 节点。
 */
public class LoginController {

    private final SaveLastLoginService saveService;

    public LoginController(SaveLastLoginService saveService) {
        this.saveService = saveService;
    }

    /** 启动时拉一次上次的成功登录配置(密码永远不预填) */
    public record Prefilled(String ip, String port, String imCode,
                            String username, String peerName) {}

    /** UI 把 5 个字段打包后传给 connect() */
    public record Input(String ip, String port, String imCode,
                        String password, String username) {}

    /** connect() 结果:成功 = Ok(新 AppState),失败 = Invalid(错误文案) */
    public sealed interface Result {
        record Ok(AppState state) implements Result {}
        record Invalid(String message) implements Result {}
    }

    /** 拉取上次的成功登录配置;缺省值由 SaveLastLoginService 提供 */
    public Prefilled prefill() {
        return new Prefilled(
                saveService.getServerIp(),
                saveService.getServerPort(),
                saveService.getImCode(),
                saveService.getUsername(),
                saveService.getPeerName());
    }

    /**
     * 校验输入;通过则落盘并返回 Ok。
     * 密码不持久化(由 SaveLastLoginService.save() 自行处理)。
     */
    public Result connect(Input input) {
        if (input.ip().isBlank())       return new Result.Invalid("请输入服务器 IP");
        if (input.port().isBlank())     return new Result.Invalid("请输入端口");
        if (!input.port().matches("\\d+"))
                                       return new Result.Invalid("端口必须是数字");
        if (input.imCode().isBlank())   return new Result.Invalid("请输入 IM_CODE");
        if (input.password().isBlank()) return new Result.Invalid("请输入初始口令");
        if (input.username().isBlank()) return new Result.Invalid("请输入用户名");

        saveService.save(input.ip(), input.port(), input.imCode(),
                         input.username(), saveService.getPeerName());
        return new Result.Ok(new AppState(input.username(), saveService.getPeerName()));
    }
}
