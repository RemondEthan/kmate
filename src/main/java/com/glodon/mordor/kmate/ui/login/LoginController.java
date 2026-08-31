package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.service.SaveLastLoginService;

/**
 * 登录面板的业务侧：预填 / 校验 / 落盘。
 *
 * <p>设计思路是把"做 UI"和"做业务"分到两个文件：
 * <ul>
 *   <li>{@link LoginPane}：纯 UI 装配 + 委托</li>
 *   <li>本类：表单校验、读取预填值、把登录信息落盘</li>
 * </ul>
 * 这样 LoginPane 不会被业务细节污染，本类也可以单测。
 *
 * <p>真正"连服务器"由 LoginPane 触发 ImClient 完成，不在本类职责内。
 */
public class LoginController {

    // 落盘 / 读取"上次登录信息"的服务。本类不直接接触 Preferences API。
    private final SaveLastLoginService saveService;

    public LoginController(SaveLastLoginService saveService) {
        this.saveService = saveService;
    }

    /**
     * 登录界面打开时用来预填表单的快照。
     *
     * <p>字段含义：
     * <ul>
     *   <li>ip / port：上次连接的服务器地址</li>
     *   <li>imCode：上次配对码</li>
     *   <li>username：上次显示的用户名</li>
     *   <li>peerName：上次对方的名字（用于聊天页默认显示对方是谁）</li>
     * </ul>
     */
    public record Prefilled(String ip, String port, String imCode,
                            String username, String peerName) {}

    /**
     * 表单原始输入（未校验）。
     * password 故意不做 trim：口令可能有奇怪的合法首尾空格。
     */
    public record Input(String ip, String port, String imCode,
                        String password, String username) {}

    /**
     * 校验结果。sealed 让调用方必须用 switch 穷举（编译期保护）。
     */
    public sealed interface Result {
        /** 校验通过，可以进入下一步（去连服务器）。 */
        record Ok() implements Result {}
        /** 校验失败，message 给用户看的提示语。 */
        record Invalid(String message) implements Result {}
    }

    /**
     * 从 SaveLastLoginService 读出"上次登录信息"，交给 LoginPane 填表。
     */
    public Prefilled prefill() {
        return new Prefilled(
                saveService.getServerIp(),
                saveService.getServerPort(),
                saveService.getImCode(),
                saveService.getUsername(),
                saveService.getPeerName());
    }

    /**
     * 校验表单输入。按顺序检查，第一条不通过就返回。
     *
     * <p>校验规则（顺序敏感，提示语按这个顺序）：
     * <ol>
     *   <li>服务器 IP 不能为空</li>
     *   <li>端口不能为空</li>
     *   <li>端口必须是纯数字</li>
     *   <li>端口范围必须在 1-65535</li>
     *   <li>IM_CODE 不能为空</li>
     *   <li>初始口令不能为空</li>
     *   <li>用户名不能为空</li>
     * </ol>
     */
    public Result validate(Input input) {
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

    /**
     * 登录成功后落盘。注意：这里不落 password（安全考虑，不持久化口令），
     * 也不覆写 peerName（peerName 是从服务器握手后回填的，不在登录表单里）。
     */
    public void save(Input input) {
        saveService.save(input.ip(), input.port(), input.imCode(),
                input.username(), saveService.getPeerName());
    }

    /**
     * 读头像路径（绝对路径字符串）。LoginPane 启动时用它预填头像预览。
     */
    public String avatarPath() {
        return saveService.getAvatarPath();
    }

    /**
     * 用户选了新头像后调：把新路径写进 Preferences，下次启动会读出来。
     */
    public void saveAvatarPath(String path) {
        saveService.saveAvatarPath(path);
    }
}
