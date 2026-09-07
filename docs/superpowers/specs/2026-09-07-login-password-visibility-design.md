# 登录卡密码框明文切换 — Design Spec

Date: 2026-09-07
Status: Approved (brainstorming)

## 背景

`LoginPane` 里的"初始口令"用的是 `javafx.scene.control.PasswordField`(`LoginPane.java:45`),只能看到掩码 `•`,用户无法确认自己输入的口令是否正确(尤其配对码那种长字符串)。当前 `LoginController.validate` 也不验证口令格式,只能在服务器握手失败时才能发现。给个"小眼睛"按钮让用户随时核对明文是最直接的修复。

## 目标

- 在"初始口令"输入框右侧贴边放一个"小眼睛"切换按钮(Unicode 👁 / 🙈)。
- 默认隐藏(显示 `•`),点一下切到明文(`TextField`),再点切回。
- 切换瞬间焦点不丢失(保留在当前可见的那个字段上),后续可继续输入。
- 切换逻辑放在独立小组件 `PasswordFieldWithToggle` 里,`LoginPane` 改动最小化。
- 不影响"连接/握手/落盘"主流程;`controller.save(input)` / `handleConnect` / `applyOffline` 调用全部沿用现有 API(`getText` / `setDisable`)。
- 单元测试覆盖核心状态/同步/禁用逻辑,视觉上 `./mvnw javafx:run` 跑一遍确认。

## 设计决策

- **图标风格**:Unicode 👁 / 🙈(用户已选)。理由:不引入新资源;`MaterialDesignE` 里的矢量图标虽然更好,但用户偏好文字符号。注意 Windows 上 JavaFX 会回退到系统字体的单色字形,功能不受影响。
- **按钮位置**:StackPane 内 `CENTER_RIGHT` 贴边叠加(用户已选)。理由:与主流应用的"输入框自带眼睛"一致,不破坏 `fieldBox` 助手的尺寸计算。
- **组件抽取**:新建 `com.mordor.kmate.ui.login.PasswordFieldWithToggle`,**不**复用给 `ModelConfigDialog` 的 `apiKeyField`(用户明确说"只改登录卡")。将来要扩展可单独 follow-up。
- **可见字段切换方式**:不靠 `PasswordField` 单控件 `setVisible`,而是内部同时持有 `PasswordField hidden` + `TextField shown`,点击时翻转两者的 `visible/managed`。`hidden.textProperty` 与 `shown.textProperty` 用 `bindBidirectional` 同步,所有读写都从 `hidden` 走(因为它是 `Control` 派生的稳定 API)。
- **CSS 补丁**:用 `.password-with-toggle > .login-field` 选择器给两个字段都加右内边距 28px,避免文字盖在眼睛上;不修改 `.login-field` 本身,其它文本框不受影响。

## 组件 / 文件

### 1. 新增 `src/main/java/com/mordor/kmate/ui/login/PasswordFieldWithToggle.java`

继承 `StackPane`,~70 行。核心结构:

```
StackPane PasswordFieldWithToggle  (class="password-with-toggle")
├── PasswordField hidden            (class="login-field", visible 初始 true)
├── TextField shown                 (class="login-field", visible 初始 false)
└── Button eye                      (class="login-eye-toggle", StackPane.setAlignment=CENTER_RIGHT)
```

公共 API(全部沿用 `PasswordField` 的方法名与语义):

| 方法 | 行为 |
|---|---|
| `getText()` | 返回 `hidden.getText()`(双向绑定所以等价于 `shown.getText()`) |
| `setText(String s)` | `s == null ? "" : s` 写入 `hidden`(绑定后 `shown` 同步) |
| `clear()` | `hidden.clear()` |
| `setPromptText(String s)` | 同时设给两个字段,保证视觉一致 |
| `setDisable(boolean on)` | 委托给 `hidden` / `shown` / `eye` 三个;JavaFX 中 `StackPane` 本身没有 `setDisable`,这里手动分别 disable |

内部状态:
- `BooleanProperty visible`(默认 `false` —— 隐藏态)
- `visible.addListener` 翻转时:`hidden.setVisible(!v); hidden.setManaged(!v); shown.setVisible(v); shown.setManaged(v); eye.setText(v ? "🙈" : "👁")`
- 切换后 `currentVisible().requestFocus()`(try-catch 静默吞掉 scene 未挂载的异常)

`eye` 按钮 `setOnAction` 翻转 `visible`。

### 2. 修改 `src/main/java/com/mordor/kmate/ui/login/LoginPane.java`

- 把字段从 `private final PasswordField password = new PasswordField();` 改为 `private final PasswordFieldWithToggle password = new PasswordFieldWithToggle();`
- 把对应的 `VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");` 替换成内联构造(参考已有的 `workspaceRow` 模式):
  - `Label passwordLabel = new Label("初始口令");`
  - `passwordLabel.getStyleClass().add("login-field-label"); passwordLabel.setMaxWidth(Double.MAX_VALUE);`
  - `password.setPromptText("输入初始口令");`
  - `VBox passwordBox = new VBox(3, passwordLabel, password);`
  - `passwordBox.setMaxWidth(Double.MAX_VALUE); passwordBox.setFillWidth(true);`
- `applyOffline(boolean on)` 中的 `password.setDisable(on)` **无需改**——`PasswordFieldWithToggle.setDisable` 沿用同名方法。
- `handleConnect()` 中的 `password.getText()` **无需改**——同上。

### 3. 修改 `src/main/resources/com/mordor/kmate/ui/login/login.css`

在文件末尾追加:

```css
.password-with-toggle > .login-field {
    -fx-padding: 5 28 5 8;
}

.login-eye-toggle {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-cursor: hand;
    -fx-font-size: 14px;
    -fx-padding: 0 6 0 6;
    -fx-min-width: 28;
    -fx-pref-width: 28;
}

.login-eye-toggle:hover {
    -fx-background-color: #F0F4FA;
    -fx-background-radius: 4;
}

.login-eye-toggle:disabled {
    -fx-opacity: 0.4;
}
```

`.login-field` 本身不动,避免影响 `serverIp/port/imCode/username/workspace` 等其他文本框。

### 4. 新增 `src/test/java/com/mordor/kmate/ui/login/PasswordFieldWithToggleTest.java`

JUnit 5 覆盖:

- 初始:`hidden.isVisible() == true`,`shown.isVisible() == false`,`getText() == ""`,`eye.getText() == "👁"`
- `setText("abc")` 后两个字段 `getText()` 都是 `"abc"`
- `setText(null)` 后两个字段 `getText()` 都是 `""`
- 模拟 `eye.fire()`:`hidden.isVisible() == false`,`shown.isVisible() == true`,`eye.getText() == "🙈"`
- 再次 `eye.fire()`:回到隐藏态
- 切换后焦点:把组件挂到测试用的 `Stage` 后,`fire()` 后当前可见字段 `isFocused()`(若 TestFX/robot 不可用,改用 `BooleanProperty` 内部 state 检查)
- `setDisable(true)`:三个子节点 `isDisable()` 都为 true

## 数据流

```
User 输入
  └─> 双向绑定 hidden ↔ shown (StringProperty)
        ├─> LoginPane.password.getText()   [handleConnect 取值,不感知明文/密文]
        └─> LoginPane.password.setDisable(on)  [applyOffline 切脱机,内部带 eye 一起 disable]

User 点击 eye
  └─> PasswordFieldWithToggle.toggle()
        ├─> visible.set(!visible.get())
        │     ├─> hidden.setVisible(!v); hidden.setManaged(!v)
        │     ├─> shown.setVisible(v);  shown.setManaged(v)
        │     └─> currentVisible().requestFocus()
        └─> eye.setText(v ? "🙈" : "👁")
```

## 错误处理

- `eye.setDisable(true)` 后,`ActionEvent` 不会触发,不需要在 toggle 里再判一次
- `requestFocus` 在 `LoginPane` 构造过程中(此时 scene 尚未挂载)被调用会抛 NPE → 包 `try-catch (Exception)` 静默
- `setText(null)` 在 JavaFX 双向绑定里会自动转空串,但 `PasswordFieldWithToggle.setText` 显式做 `s == null ? "" : s` 保险
- `SaveLastLoginService` 仍不存密码(项目本来就没存,见 CLAUDE.md);明文态仅在内存里,生命周期 = LoginPane 生命周期

## 验证

- [ ] `./mvnw test` 全部通过(含新增 `PasswordFieldWithToggleTest`)
- [ ] `./mvnw clean javafx:run` 启动,手动验证:
  1. 密码框右侧出现 👁
  2. 输入一段文字 → 点击 👁 → 框内出现明文 + 按钮变 🙈
  3. 继续输入 → 文字正常追加,焦点未丢
  4. 点击 🙈 → 回到密文
  5. 勾选"脱机登录" → 整个密码框 + 眼睛按钮都变灰,点眼睛无效
  6. 其它文本框(IP/IM_CODE/用户名/工作区)的边框、聚焦色、内边距不变
  7. 切换明文/密文不影响"连接"按钮、错误提示
- [ ] (可选)在 Windows + Linux 上分别跑一次登录页,确认 👁 / 🙈 有可见字形
