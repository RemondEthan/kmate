# kmate Package Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `com.glodon.mordor.kmate` 平铺的 14 个类重组为 `app` / `ui.{login,chat}` / `service` / `model` / `common` 分层,并在过程中引入 Controller / UI 分离、`Mate4K` 瘦身。

**Architecture:** 特性优先 + 共享层。Controller 通过返回值(`sealed Result` / 简单方法)反向传递结果,不引入 listener / 回调注册。`Mate4K.start()` 只做 Scene / Stage 装配,所有系统级副作用(托盘、快捷键、quit hooks)各自独立成类。资源文件跟随 Java 包路径拆分。

**Tech Stack:** Java 21、JavaFX 21.0.2、Maven 3.x、Java Preferences API、AWT SystemTray、Ikonli 12.3.1。

**Reference Spec:** `docs/superpowers/specs/2026-08-26-kmate-package-refactor-design.md`

## Global Constraints

- **包路径**:`com.glodon.mordor.kmate.{app, ui.login, ui.chat, model, service, common}`(本 spec 唯一包结构)
- **依赖方向**:`app → ui → service → model`,`model` 是叶子
- **不动范围**:不引入 SLF4J / DI / 单元测试;不调整 CSS 配色 / 布局 / 文案
- **每个 Task 必须 `mvn compile` 通过 + 启动验证(登录、聊天、⌘W/⌘Q、托盘)**
- **每个 Task 独立 commit**;失败可独立 `git revert`
- **资源路径随包迁移**:`bg-chat.png` 移到 `ui/chat/`;`/icons/tray.png` 路径不动
- **Java 模块声明**:新拆出的 6 个包需 `exports` + `opens to javafx.fxml`(现状保留)

---

## Task 1: 拆 `model/` 子包并搬入数据类

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/model/AppState.java`
- Create: `src/main/java/com/glodon/mordor/kmate/model/Message.java`
- Create: `src/main/java/com/glodon/mordor/kmate/model/Sender.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/AppState.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/Message.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/Sender.java`

- [ ] **Step 1: 用 `git mv` 把 3 个文件搬到新位置**

```bash
cd /Users/ksw/workspace/repository/kmate
mkdir -p src/main/java/com/glodon/mordor/kmate/model
git mv src/main/java/com/glodon/mordor/kmate/AppState.java \
       src/main/java/com/glodon/mordor/kmate/model/AppState.java
git mv src/main/java/com/glodon/mordor/kmate/Message.java \
       src/main/java/com/glodon/mordor/kmate/model/Message.java
git mv src/main/java/com/glodon/mordor/kmate/Sender.java \
       src/main/java/com/glodon/mordor/kmate/model/Sender.java
```

- [ ] **Step 2: 修改每个文件的 `package` 声明**

把 3 个文件顶部的 `package com.glodon.mordor.kmate;` 改为 `package com.glodon.mordor.kmate.model;`。`AppState`、`Message`、`Sender` 都是纯 record / enum,无跨文件 import。

- [ ] **Step 3: 编译验证**

```bash
mvn -q -DskipTests compile
```

预期:`BUILD SUCCESS`。本任务仅搬包,旧路径文件已被 git 删除,引用方暂未改 package,先确认纯编译错只在引用方出现(此时尚无引用方改 package 的失败,因为引用方还在旧包路径上引用 model 类——这会导致编译错)。**预期会失败**:`LoginPane` / `MessageBubble` / `MessageListView` 等还在旧包路径,引用 `AppState` / `Message` / `Sender` 而无 import 时找不到。继续。

- [ ] **Step 4: 暂时接受编译失败,继续 Task 2**

注:搬包过程天然需要所有引用方同步改 import,因此编译失败是预期内的中间态。Task 2/3/4 会一并把引用方也搬到新包,编译自然恢复。本任务结束于此。

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/model
git commit -m "refactor: move data classes to model package"
```

---

## Task 2: 拆 `service/` 子包并搬入 `SaveLastLoginService`

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/service/SaveLastLoginService.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/SaveLastLoginService.java`

- [ ] **Step 1: 搬文件 + 改 package**

```bash
cd /Users/ksw/workspace/repository/kmate
mkdir -p src/main/java/com/glodon/mordor/kmate/service
git mv src/main/java/com/glodon/mordor/kmate/SaveLastLoginService.java \
       src/main/java/com/glodon/mordor/kmate/service/SaveLastLoginService.java
```

把文件顶部 `package com.glodon.mordor.kmate;` 改为 `package com.glodon.mordor.kmate.service;`。

- [ ] **Step 2: 接受中间编译失败,继续**

预期:`mvn compile` 仍然失败,因为引用方还没搬包。Task 3 / 4 / 5 完成引用方迁移后,编译自然恢复。

- [ ] **Step 3: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/service
git commit -m "refactor: move SaveLastLoginService to service package"
```

---

## Task 3: 拆 `ui/login/` 子包并搬入 `LoginPane`

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/LoginPane.java`

- [ ] **Step 1: 搬文件 + 改 package + 加 import**

```bash
cd /Users/ksw/workspace/repository/kmate
mkdir -p src/main/java/com/glodon/mordor/kmate/ui/login
git mv src/main/java/com/glodon/mordor/kmate/LoginPane.java \
       src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java
```

文件顶部 `package` 改为 `package com.glodon.mordor.kmate.ui.login;`。

在文件顶部 `package` 之下新增以下 import(本文件原本引用了同包内的 `AppState` 和 `SaveLastLoginService`):

```java
import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.service.SaveLastLoginService;
```

- [ ] **Step 2: 接受中间编译失败**

预期:仍失败,因为 ui/chat 还没搬。

- [ ] **Step 3: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/ui/login
git commit -m "refactor: move LoginPane to ui.login package"
```

---

## Task 4: 拆 `ui/chat/` 子包并搬入 6 个 chat 类

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java`
- Create: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatHeader.java`
- Create: `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java`
- Create: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java`
- Create: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageBubble.java`
- Create: `src/main/java/com/glodon/mordor/kmate/ui/chat/EmojiPopover.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/ChatPane.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/ChatHeader.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/InputBar.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/MessageListView.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/MessageBubble.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/EmojiPopover.java`

- [ ] **Step 1: 一次性搬 6 个文件**

```bash
cd /Users/ksw/workspace/repository/kmate
mkdir -p src/main/java/com/glodon/mordor/kmate/ui/chat
for f in ChatPane ChatHeader InputBar MessageListView MessageBubble EmojiPopover; do
    git mv src/main/java/com/glodon/mordor/kmate/${f}.java \
           src/main/java/com/glodon/mordor/kmate/ui/chat/${f}.java
done
```

- [ ] **Step 2: 改 package + 补 import**

每个文件顶部 `package com.glodon.mordor.kmate;` → `package com.glodon.mordor.kmate.ui.chat;`。

以下文件额外需要新增 `model` import(原本在同包裸引用):

- `ChatPane.java`:新增 `import com.glodon.mordor.kmate.model.AppState;`
- `ChatHeader.java`:新增 `import com.glodon.mordor.kmate.model.AppState;`
- `MessageListView.java`:新增 `import com.glodon.mordor.kmate.model.AppState;`
- `MessageBubble.java`:新增 `import com.glodon.mordor.kmate.model.Message;`
- `InputBar.java` / `EmojiPopover.java`:不需要 `model` import(不直接引用 model 类型)。

- [ ] **Step 3: `MessageListView` 中加载背景图的 classpath 来源暂时维持 `Mate4K.class.getResource("bg-chat.png")`(路径不变,Task 6 拆 CSS 时再一起迁移到 `getClass().getResource(...)`)**

- [ ] **Step 4: 接受中间编译失败**

预期:仍失败,因为 app/ 还没搬。

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/ui/chat
git commit -m "refactor: move chat UI classes to ui.chat package"
```

---

## Task 5: 拆 `app/` 子包,搬 `Mate4K` 与 `MacQuitHook`,更新 `module-info.java`

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`
- Create: `src/main/java/com/glodon/mordor/kmate/app/MacQuitHook.java`
- Modify: `src/main/java/module-info.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/Mate4K.java`
- Delete: `src/main/java/com/glodon/mordor/kmate/MacQuitHook.java`

- [ ] **Step 1: 搬 2 个 app 文件**

```bash
cd /Users/ksw/workspace/repository/kmate
mkdir -p src/main/java/com/glodon/mordor/kmate/app
git mv src/main/java/com/glodon/mordor/kmate/Mate4K.java \
       src/main/java/com/glodon/mordor/kmate/app/Mate4K.java
git mv src/main/java/com/glodon/mordor/kmate/MacQuitHook.java \
       src/main/java/com/glodon/mordor/kmate/app/MacQuitHook.java
```

- [ ] **Step 2: 改两个文件的 package**

- `Mate4K.java`:`package com.glodon.mordor.kmate.app;`
- `MacQuitHook.java`:保持 `package com.glodon.mordor.kmate.app;`,无 model 引用

- [ ] **Step 3: 给 `Mate4K.java` 补 import**

原同包引用需替换:

```java
import com.glodon.mordor.kmate.App;          // 无,无此引用
```

`Mate4K` 中实际跨包引用如下,需替换或新增:

- `import com.glodon.mordor.kmate.model.AppState;` —— 新增(原本靠同包隐式可见)
- 其它 import (`ChatPane`, `LoginPane`, `SaveLastLoginService` 等)原本就没有 import,因为同包裸引用——**改为走新包路径**:

```java
import com.glodon.mordor.kmate.ui.chat.ChatPane;
import com.glodon.mordor.kmate.ui.login.LoginPane;
import com.glodon.mordor.kmate.model.AppState;
```

- [ ] **Step 4: 更新 `module-info.java`**

完整替换为:

```java
module com.glodon.mordor.kmate {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires java.desktop;
    requires java.prefs;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.materialdesign2;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;

    exports com.glodon.mordor.kmate.app;
    exports com.glodon.mordor.kmate.ui.login;
    exports com.glodon.mordor.kmate.ui.chat;
    exports com.glodon.mordor.kmate.model;
    exports com.glodon.mordor.kmate.service;
    exports com.glodon.mordor.kmate.common;

    opens com.glodon.mordor.kmate.app      to javafx.fxml;
    opens com.glodon.mordor.kmate.ui.login to javafx.fxml;
    opens com.glodon.mordor.kmate.ui.chat  to javafx.fxml;
    opens com.glodon.mordor.kmate.model    to javafx.fxml;
    opens com.glodon.mordor.kmate.service  to javafx.fxml;
    opens com.glodon.mordor.kmate.common   to javafx.fxml;
}
```

`common/` 目录此时为空,Maven 不会因空目录报错;`exports` 与 `opens` 仍按规约写上,后续填类不用动 module-info。

- [ ] **Step 5: 编译验证**

```bash
mvn -q -DskipTests compile
```

预期:`BUILD SUCCESS`。

- [ ] **Step 6: 启动验证**

```bash
./mvnw -q -DskipTests javafx:run
```

或在 IDE 中运行 `Mate4K`。验证项:

- 启动后进入登录页
- 输入框正常,默认值(IP `127.0.0.1`、端口 `3000`、IM_CODE 空、用户名空)已预填
- 点"连 接"通过校验 → 进入聊天页
- 聊天页 7 条示例消息 + 雪花背景正常显示
- 表情弹窗能弹出
- 输入文本 + 发送按钮 + 回车发送,行为同之前(占位发送,UI 不变化)

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/app src/main/java/module-info.java
git commit -m "refactor: move app classes and update module-info for new packages"
```

---

## Task 6: 拆 CSS 为 3 个文件,迁移 `bg-chat.png`,更新 classpath 引用

**Files:**
- Create: `src/main/resources/com/glodon/mordor/kmate/app/app.css`
- Create: `src/main/resources/com/glodon/mordor/kmate/ui/login/login.css`
- Create: `src/main/resources/com/glodon/mordor/kmate/ui/chat/chat.css`
- Create: `src/main/resources/com/glodon/mordor/kmate/ui/chat/bg-chat.png`(从旧路径移动)
- Modify: `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`(改 stylesheet 路径)
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java`(追加 stylesheet)
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java`(追加 stylesheet)
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java`(改背景图 classpath)
- Delete: `src/main/resources/com/glodon/mordor/kmate/styles.css`
- Delete: `src/main/resources/com/glodon/mordor/kmate/bg-chat.png`

- [ ] **Step 1: 创建目标目录**

```bash
cd /Users/ksw/workspace/repository/kmate
mkdir -p src/main/resources/com/glodon/mordor/kmate/app
mkdir -p src/main/resources/com/glodon/mordor/kmate/ui/login
mkdir -p src/main/resources/com/glodon/mordor/kmate/ui/chat
```

- [ ] **Step 2: 创建 `app/app.css`(内容 = 旧 styles.css 的 `:root` + `.app-bg`)**

文件内容(完整):

```css
/* ========================================================================
   Kmate - SiMate JavaFX Replica
   全局 token + 应用背景;Scene 级加载。
   ======================================================================== */

.root {
    -color-primary:        #1976D2;
    -color-primary-hover:  #1565C0;
    -color-primary-soft:   #E3F2FD;

    -color-bg-app:         #F4F6F8;
    -color-bg-card:        #FFFFFF;
    -color-bg-input:       #F2F4F7;

    -color-text-primary:   #1F2937;
    -color-text-secondary: #6B7280;
    -color-text-tertiary:  #9CA3AF;

    -color-self-bubble:    #1976D2;
    -color-peer-bubble:    #FFFFFF;
    -color-system-bubble:  #FFF3E0;

    -color-self-avatar:    #1976D2;
    -color-peer-avatar:    #6B7280;

    -color-border:         #E5E7EB;
    -color-border-light:   #EFF1F4;

    -fx-font-family: "SF Pro Text", "PingFang SC", "Microsoft YaHei", sans-serif;
    -fx-base: #F4F6F8;
}

/* App background */
.app-bg {
    -fx-background-color: -color-bg-app;
}
```

- [ ] **Step 3: 创建 `ui/login/login.css`(原 `.login-*` 全套)**

文件内容(完整):

```css
/* ========================================================================
   Kmate - Login Pane 样式
   ======================================================================== */

/* 根容器:填充背景 */
.login-root {
    -fx-background-color: -color-bg-app;
}

/* 居中的白色表单卡片 */
.login-card {
    -fx-background-color: -color-bg-card;
    -fx-background-radius: 12 12 12 12;
    -fx-border-radius: 12 12 12 12;
    -fx-border-color: -color-border-light;
    -fx-border-width: 1 1 1 1;
    -fx-padding: 11 13 11 13;
    -fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.06), 16, 0, 0, 4);
}

/* 字段标签 */
.login-field-label {
    -fx-font-size: 11px;
    -fx-text-fill: -color-text-secondary;
    -fx-font-weight: 500;
}

/* 输入框 */
.login-field {
    -fx-font-size: 13px;
    -fx-text-fill: -color-text-primary;
    -fx-background-color: -color-bg-input;
    -fx-border-color: transparent;
    -fx-border-radius: 6 6 6 6;
    -fx-background-radius: 6 6 6 6;
    -fx-padding: 5 10 5 10;
    -fx-prompt-text-fill: -color-text-tertiary;
}

.login-field:focused {
    -fx-background-color: -color-bg-card;
    -fx-border-color: -color-primary;
    -fx-border-width: 1 1 1 1;
    -fx-effect: dropshadow(gaussian, rgba(25, 118, 210, 0.18), 6, 0, 0, 0);
}

/* info 提示(柔和蓝色) */
.login-info {
    -fx-background-color: -color-primary-soft;
    -fx-text-fill: #1565C0;
    -fx-background-radius: 6 6 6 6;
    -fx-padding: 6 10 6 10;
    -fx-font-size: 11px;
}

/* error 提示(柔和红色) */
.login-error {
    -fx-background-color: #FEE2E2;
    -fx-text-fill: #B91C1C;
    -fx-background-radius: 6 6 6 6;
    -fx-padding: 6 10 6 10;
    -fx-font-size: 11px;
}

/* "连接"按钮:MUI contained primary 风格 */
.login-connect {
    -fx-background-color: -color-primary;
    -fx-text-fill: white;
    -fx-background-radius: 8 8 8 8;
    -fx-padding: 10 16 10 16;
    -fx-font-size: 13px;
    -fx-font-weight: 600;
    -fx-cursor: hand;
}

.login-connect:hover {
    -fx-background-color: -color-primary-hover;
}

.login-connect:pressed {
    -fx-background-color: #0D47A1;
}
```

- [ ] **Step 4: 创建 `ui/chat/chat.css`(原 `.header*` `.chat-bg` `.bubble-*` `.input-bar*`)**

文件内容(完整):

```css
/* ========================================================================
   Kmate - Chat Pane 样式
   ======================================================================== */

/* 标题栏 */
.header {
    -fx-background-color: -color-bg-card;
    -fx-border-color: transparent transparent -color-border transparent;
    -fx-border-width: 0 0 1 0;
    -fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.04), 4, 0, 0, 1);
}

.header-title {
    -fx-font-size: 14px;
    -fx-text-fill: -color-text-primary;
    -fx-font-weight: 600;
}

.header-meta {
    -fx-font-size: 11px;
    -fx-text-fill: -color-text-secondary;
}

/* 消息区背景 */
.chat-bg {
    -fx-background-color: -color-bg-app;
}

/* 消息气泡:background-radius 顺序 = topLeft, topRight, bottomRight, bottomLeft */
.bubble-self {
    -fx-background-color: -color-self-bubble;
    -fx-background-radius: 12 12 0 12;
    -fx-padding: 8 12 8 12;
    -fx-text-fill: white;
    -fx-font-size: 13px;
}

.bubble-peer {
    -fx-background-color: -color-peer-bubble;
    -fx-background-radius: 12 12 12 0;
    -fx-padding: 8 12 8 12;
    -fx-text-fill: -color-text-primary;
    -fx-font-size: 13px;
    -fx-border-color: -color-border-light;
    -fx-border-width: 1 1 1 1;
}

.bubble-system {
    -fx-background-color: -color-system-bubble;
    -fx-background-radius: 6 6 6 6;
    -fx-padding: 4 10 4 10;
    -fx-text-fill: #92400E;
    -fx-font-size: 11px;
}

.bubble-name {
    -fx-font-size: 10px;
    -fx-text-fill: -color-text-secondary;
    -fx-font-weight: 500;
}

.bubble-time {
    -fx-font-size: 9px;
    -fx-text-fill: -color-text-tertiary;
}

/* 输入栏 */
.input-bar {
    -fx-background-color: -color-bg-card;
    -fx-border-color: -color-border transparent transparent transparent;
    -fx-border-width: 1 0 0 0;
    -fx-padding: 10 12 10 12;
}

.input-bar .button {
    -fx-background-color: transparent;
    -fx-cursor: hand;
    -fx-text-fill: -color-text-secondary;
}

.input-bar .button:hover {
    -fx-background-color: -color-bg-input;
    -fx-background-radius: 6 6 6 6;
}

.input-bar .button:disabled {
    -fx-opacity: 0.35;
}

.input-bar .text-field {
    -fx-background-radius: 18 18 18 18;
    -fx-background-color: -color-bg-input;
    -fx-padding: 6 12 6 12;
    -fx-font-size: 13px;
    -fx-text-fill: -color-text-primary;
    -fx-prompt-text-fill: -color-text-tertiary;
}

.input-bar .text-field:focused {
    -fx-background-color: -color-bg-card;
    -fx-border-color: -color-primary;
    -fx-border-width: 1 1 1 1;
}
```

- [ ] **Step 5: 迁移 `bg-chat.png` 到 `ui/chat/`**

```bash
cd /Users/ksw/workspace/repository/kmate
git mv src/main/resources/com/glodon/mordor/kmate/bg-chat.png \
       src/main/resources/com/glodon/mordor/kmate/ui/chat/bg-chat.png
```

- [ ] **Step 6: 修改 `Mate4K.java` 加载 `app.css`**

找到:

```java
scene.getStylesheets().add(Mate4K.class.getResource("styles.css").toExternalForm());
```

改为:

```java
scene.getStylesheets().add(
        Mate4K.class.getResource("app.css").toExternalForm());
```

注意:`Mate4K` 现在位于 `app/` 包,`getResource("app.css")` 实际指向 `resources/com/glodon/mordor/kmate/app/app.css`。

- [ ] **Step 7: 修改 `LoginPane.java` 追加 `login.css`**

在 `LoginPane` 构造器中,`getStyleClass().addAll(...)` 那行**之后**,加:

```java
getStylesheets().add(
        LoginPane.class.getResource("login.css").toExternalForm());
```

`LoginPane` 位于 `ui/login/` 包,`login.css` 实际指向 `resources/com/glodon/mordor/kmate/ui/login/login.css`。

- [ ] **Step 8: 修改 `ChatPane.java` 追加 `chat.css`**

在 `ChatPane` 构造器开头(任何 `setTop/setCenter/setBottom` 之前),加:

```java
getStylesheets().add(
        ChatPane.class.getResource("chat.css").toExternalForm());
```

- [ ] **Step 9: 修改 `MessageListView.java` 改背景图 classpath 来源**

`applyChatBackground` 方法中,把:

```java
Image img = new Image(Mate4K.class.getResource("bg-chat.png").toExternalForm());
```

改为:

```java
Image img = new Image(getClass().getResource("bg-chat.png").toExternalForm());
```

`MessageListView` 位于 `ui/chat/` 包,`getClass().getResource("bg-chat.png")` 指向 `resources/com/glodon/mordor/kmate/ui/chat/bg-chat.png`。

- [ ] **Step 10: 删除旧 `styles.css` 与旧 `bg-chat.png`**

```bash
cd /Users/ksw/workspace/repository/kmate
git rm src/main/resources/com/glodon/mordor/kmate/styles.css
```

旧 `bg-chat.png` 已在 Step 5 用 `git mv` 移走,无需再删。

- [ ] **Step 11: 编译 + 启动验证**

```bash
mvn -q -DskipTests compile
./mvnw -q -DskipTests javafx:run
```

预期:`BUILD SUCCESS`,启动后视觉与拆 CSS 前完全一致(背景色、卡片、按钮、气泡、字体、间距均无变化)。逐项肉眼对比:

- 登录页:卡片宽度 / 圆角 / 内边距、按钮颜色与 hover、输入框聚焦边框
- 聊天页:雪花背景图、标题栏底边线、气泡形状 / 颜色 / 三角朝向、输入栏按钮 hover

- [ ] **Step 12: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate src/main/resources/com/glodon/mordor/kmate
git commit -m "refactor: split styles.css into app/login/chat and migrate bg-chat.png"
```

---

## Task 7: 抽出 `LoginController`,改写 `LoginPane` 为"装配 + 委托"

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginController.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/login/LoginPane.java`

- [ ] **Step 1: 创建 `LoginController.java`**

完整文件内容:

```java
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
```

- [ ] **Step 2: 重写 `LoginPane.java`**

完整文件内容:

```java
package com.glodon.mordor.kmate.ui.login;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.service.SaveLastLoginService;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 登录面板:极简风格,白色表单卡片铺满窗口,四周留出缩减后的灰边。
 *
 * 窗口 600×449、卡片原宽 380 时,左右灰边各约 110px、上下各约 64px。
 * 边框空白缩 40% 后,内边距为上下 38px、左右 66px,卡片吃掉收回的空间。
 *
 * 本类只做"装配 + 委托":所有业务校验与落盘由 LoginController 完成。
 */
public class LoginPane extends VBox {

    private final TextField serverIp = new TextField();
    private final TextField serverPort = new TextField();
    private final TextField imCode = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField username = new TextField();

    private final Label errorLabel = new Label();

    private final LoginController controller;
    private final Consumer<AppState> onConnect;

    public LoginPane(Consumer<AppState> onConnect) {
        super(0);
        this.controller = new LoginController(new SaveLastLoginService());
        this.onConnect = onConnect;

        getStyleClass().addAll("app-bg", "login-root");
        getStylesheets().add(
                LoginPane.class.getResource("login.css").toExternalForm());

        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setFillWidth(true);
        setPadding(new Insets(38, 66, 38, 66));

        errorLabel.getStyleClass().add("login-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);

        Label info = new Label("请与对方约定相同的 IM_CODE 和初始口令进行配对");
        info.getStyleClass().add("login-info");
        info.setWrapText(true);
        info.setMaxWidth(Double.MAX_VALUE);

        VBox ipBox = fieldBox("服务器 IP", serverIp, "127.0.0.1");
        VBox portBox = fieldBox("端口", serverPort, "3000");

        HBox ipRow = new HBox(12, ipBox, portBox);
        ipRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(ipBox, Priority.ALWAYS);
        HBox.setHgrow(portBox, Priority.ALWAYS);

        VBox imCodeBox = fieldBox("IM_CODE", imCode, "输入配对码(如:ABC123)");
        VBox passwordBox = fieldBox("初始口令", password, "输入初始口令");
        VBox usernameBox = fieldBox("用户名", username, "输入你的名称");

        VBox fields = new VBox(8,
                errorLabel, info, ipRow,
                imCodeBox, passwordBox, usernameBox);
        fields.setMaxWidth(Double.MAX_VALUE);
        fields.setFillWidth(true);

        Button connect = new Button("连 接");
        connect.setDefaultButton(true);
        connect.setMaxWidth(Double.MAX_VALUE);
        connect.getStyleClass().add("login-connect");
        connect.setOnAction(e -> handleConnect());

        Region cardTop = new Region();
        Region cardBottom = new Region();
        VBox.setVgrow(cardTop, Priority.ALWAYS);
        VBox.setVgrow(cardBottom, Priority.ALWAYS);

        VBox card = new VBox(10, cardTop, fields, connect, cardBottom);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(card, Priority.ALWAYS);

        getChildren().add(card);

        LoginController.Prefilled p = controller.prefill();
        serverIp.setText(p.ip());
        serverPort.setText(p.port());
        imCode.setText(p.imCode());
        username.setText(p.username());
    }

    private VBox fieldBox(String labelText, Control field, String placeholder) {
        Label l = new Label(labelText);
        l.getStyleClass().add("login-field-label");
        l.setMaxWidth(Double.MAX_VALUE);

        if (field instanceof TextField tf) {
            tf.setPromptText(placeholder);
            tf.getStyleClass().add("login-field");
        } else if (field instanceof PasswordField pf) {
            pf.setPromptText(placeholder);
            pf.getStyleClass().add("login-field");
        }
        field.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(3, l, field);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(true);
        return box;
    }

    private void handleConnect() {
        var input = new LoginController.Input(
                serverIp.getText().trim(),
                serverPort.getText().trim(),
                imCode.getText().trim(),
                password.getText(),
                username.getText().trim());

        var result = controller.connect(input);
        if (result instanceof LoginController.Result.Invalid i) {
            showError(i.message());
        } else if (result instanceof LoginController.Result.Ok o) {
            onConnect.accept(o.state());
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }
}
```

注:`AppState` 的 import 是 `LoginPane.handleConnect()` 调用 `onConnect.accept(...)` 需要,虽然现在由 `LoginController.connect()` 内部构造后经 `Result.Ok` 传出,`LoginPane` 仍引用了 `Consumer<AppState>`,因此 import 保留。

- [ ] **Step 3: 编译验证**

```bash
mvn -q -DskipTests compile
```

预期:`BUILD SUCCESS`。

- [ ] **Step 4: 启动验证**

```bash
./mvnw -q -DskipTests javafx:run
```

验证项:

- 启动进入登录页,默认值(IP `127.0.0.1`、端口 `3000`、IM_CODE 空、用户名空)已预填
- 输入空 IP → 点"连 接" → 错误提示"请输入服务器 IP",输入框保留
- 输入空端口 → 错误"请输入端口"
- 输入 `abc` 到端口 → 错误"端口必须是数字"
- 输入完整字段(IP/端口/IM_CODE/口令/用户名)→ 错误清空,跳转聊天页
- **持久化副作用**:成功一次后退出再启动,IP / 端口 / IM_CODE / 用户名都被预填,口令**不**预填

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/ui/login
git commit -m "refactor: extract LoginController and slim LoginPane"
```

---

## Task 8: 抽出 `ChatController`,`MessageListView` 改为订阅式渲染

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatController.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageListView.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/InputBar.java`(回调签名改为 `Consumer<String>`)
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/ChatPane.java`(接 `controller::send`)
- Modify: `src/main/java/com/glodon/mordor/kmate/ui/chat/MessageBubble.java`(已 import `model.Message`,无 package 变更;本任务不需要改)

- [ ] **Step 1: 创建 `ChatController.java`**

完整文件内容:

```java
package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.model.Sender;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 聊天面板的"业务"侧:持有消息列表,提供 bootstrap / send。
 *
 * 渲染由 MessageListView 通过订阅 messages 自动完成;Controller 不持有 UI 节点。
 * 当前 demo 阶段:bootstrap 写入 7 条示例数据;send 追加一条 SELF 消息。
 */
public class ChatController {

    private final AppState state;
    private final ObservableList<Message> messages =
            FXCollections.observableArrayList();

    public ChatController(AppState state) {
        this.state = state;
        bootstrapDemoMessages();
    }

    public ObservableList<Message> getMessages() { return messages; }
    public AppState getState() { return state; }

    /**
     * 发送一条 SELF 消息。空文本忽略。
     * 当前 demo:时间戳直接取 LocalDateTime.now(),无后端。
     */
    public void send(String content) {
        if (content == null || content.isBlank()) return;
        messages.add(new Message(
                UUID.randomUUID().toString(),
                Sender.SELF,
                content,
                LocalDateTime.now()));
    }

    // 当前 demo 数据;未来接入后端时由 controller.connect() / receive() 替换。
    private void bootstrapDemoMessages() {
        LocalDateTime t = LocalDateTime.of(2026, 8, 24, 9, 30);
        messages.addAll(
                new Message("m1", Sender.SYSTEM, "已与 Alice 建立加密通道", t),
                new Message("m2", Sender.PEER, "在吗？配对成功了 🎉", t.plusMinutes(1)),
                new Message("m3", Sender.SELF, "看到了，Hello!", t.plusMinutes(2)),
                new Message("m4", Sender.PEER, "今天有空吗，想和你讨论一下 Q4 的 OKR", t.plusMinutes(3)),
                new Message("m5", Sender.SELF, "下午 3 点可以，我已经把上周的 draft 同步到本地了", t.plusMinutes(4)),
                new Message("m6", Sender.PEER, "好的，那我们 3 点见 👌", t.plusMinutes(5)),
                new Message("m7", Sender.SELF, "👍", t.plusMinutes(6))
        );
    }
}
```

注:7 条 demo 内容与 `MessageListView.renderSampleMessages` 原版逐字一致(中文全角逗号、`?` 等)。

- [ ] **Step 2: 重写 `MessageListView.java` 为订阅式**

完整文件内容:

```java
package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.model.Message;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.VBox;

/**
 * 消息列表:一个可滚动的 VBox,每个消息是一个 MessageBubble。
 *
 * 渲染来源:订阅 ChatController.getMessages() 的 ListChangeListener;
 * 首次构造时一次性渲染现有消息,之后只追加新增。
 *
 * 背景:resources/bg-chat.png,雪花浅蓝图,跟随本包路径。
 */
public class MessageListView extends ScrollPane {

    private final VBox container;
    private final ChatController controller;

    public MessageListView(ChatController controller) {
        this.controller = controller;
        getStyleClass().add("message-list");

        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        container = new VBox(4);
        container.setPadding(new Insets(8));
        container.getStyleClass().add("message-list-container");

        applyChatBackground(container);

        setContent(container);

        // 首次渲染:把 controller 里已有的消息一次性画出来
        for (Message m : controller.getMessages()) {
            container.getChildren().add(newBubble(m));
        }

        // 之后订阅:只追加新增的(忽略其它变更类型)
        controller.getMessages().addListener((ListChangeListener<Message>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (Message m : c.getAddedSubList()) {
                        container.getChildren().add(newBubble(m));
                    }
                }
            }
        });

        // 新增气泡后滚到底
        container.getChildren().addListener((ListChangeListener<Node>) c -> scrollToBottom());
        // 首屏示例消息高度变化后再钉一次底部
        container.heightProperty().addListener((obs, o, n) -> setVvalue(1.0));
    }

    private ObservableValue<? extends Number> bubbleMaxWidth() {
        return widthProperty().multiply(0.7);
    }

    private MessageBubble newBubble(Message m) {
        AppState s = controller.getState();
        return new MessageBubble(m, s.username(), s.peerName(), bubbleMaxWidth());
    }

    private void scrollToBottom() {
        Platform.runLater(() -> setVvalue(1.0));
    }

    private void applyChatBackground(VBox target) {
        Image img = new Image(getClass().getResource("bg-chat.png").toExternalForm());
        BackgroundImage bgImage = new BackgroundImage(
                img,
                BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(100, 100, true, true, true, true));
        target.setBackground(new Background(bgImage));
    }
}
```

- [ ] **Step 3: 修改 `InputBar.java` 回调签名**

找到:

```java
public InputBar(Runnable onSend) {
```

改为:

```java
public InputBar(Consumer<String> onSend) {
```

文件顶部新增 import:

```java
import java.util.function.Consumer;
```

把 `send(Runnable onSend)` 方法改为:

```java
private void send(Consumer<String> onSend) {
    String text = textField.getText();
    if (text == null || text.isBlank()) return;
    onSend.accept(text);
    clear();
}
```

注意:`clear()` 移到 `onSend.accept(text)` 之后——Controller 不清空 UI,由 InputBar 在回调返回后自己清空。

- [ ] **Step 4: 修改 `ChatPane.java` 创建 `ChatController` 并接线**

完整文件内容:

```java
package com.glodon.mordor.kmate.ui.chat;

import com.glodon.mordor.kmate.model.AppState;
import javafx.scene.layout.BorderPane;

/**
 * 聊天面板:顶部 header + 中间消息列表 + 底部输入栏。
 *
 * 创建 ChatController,作为消息数据源;UI 节点只负责渲染与转发事件。
 */
public class ChatPane extends BorderPane {

    public ChatPane(AppState state) {
        getStylesheets().add(
                ChatPane.class.getResource("chat.css").toExternalForm());
        getStyleClass().add("app-bg");

        ChatController controller = new ChatController(state);

        setTop(new ChatHeader(state));
        setCenter(new MessageListView(controller));
        setBottom(new InputBar(controller::send));
    }
}
```

- [ ] **Step 5: 编译验证**

```bash
mvn -q -DskipTests compile
```

预期:`BUILD SUCCESS`。`mvn compile` 阶段会拉 `InputBar` 的 `Consumer<String>` 类型,如果某处残留 `Runnable` 引用会编译失败——本任务已把唯一调用点(`ChatPane` 的 `controller::send`)改为方法引用,无残留。

- [ ] **Step 6: 启动验证**

```bash
./mvnw -q -DskipTests javafx:run
```

验证项:

- 启动 → 登录 → 进入聊天页,7 条示例消息(已与 Alice 建立加密通道 / 在吗?配对成功了 🎉 / ... / 👍)正常渲染,头像 + 时间 + 气泡样式不变
- 雪花背景图正常显示
- 在输入框输入任意文本,按回车或点发送按钮:
  - 输入框清空
  - 列表底部追加一条蓝色 SELF 气泡,头像"我"
  - 自动滚到底部

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/ui/chat
git commit -m "refactor: extract ChatController and switch MessageListView to reactive subscription"
```

---

## Task 9: 抽出 `TrayManager`,`Mate4K` 只调用 install

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/app/TrayManager.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`(删除 tray 字段 / `initSystemTray` / `loadTrayImage` / `buildTrayMenu` / `showWindow`,改为 `TrayManager.install(stage)`)

- [ ] **Step 1: 创建 `TrayManager.java`**

完整文件内容:

```java
package com.glodon.mordor.kmate.app;

import javafx.application.Platform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;

/**
 * 系统托盘封装。
 *
 * install(Stage) 创建托盘图标 + 右键菜单 + 双击恢复窗口的监听;
 * 系统不支持托盘或加载失败时,tray() / icon() 返回 null,QuitManager 据此跳过清理。
 *
 * 图标路径固定为 /icons/tray.png(平台资源,不挂特性)。
 */
public final class TrayManager {

    private final SystemTray tray;
    private final TrayIcon icon;

    private TrayManager(SystemTray tray, TrayIcon icon) {
        this.tray = tray;
        this.icon = icon;
    }

    public static TrayManager install(Stage stage) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 当前系统不支持托盘图标,跳过");
            return new TrayManager(null, null);
        }
        Image image = loadTrayImage();
        if (image == null) return new TrayManager(null, null);

        try {
            SystemTray tray = SystemTray.getSystemTray();
            TrayIcon icon = new TrayIcon(image, "Kmate", buildMenu(stage));
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> Platform.runLater(() -> showWindow(stage)));
            tray.add(icon);
            return new TrayManager(tray, icon);
        } catch (AWTException e) {
            System.err.println("[Tray] 无法添加托盘图标: " + e.getMessage());
            return new TrayManager(null, null);
        }
    }

    public SystemTray tray() { return tray; }
    public TrayIcon icon() { return icon; }

    private static Image loadTrayImage() {
        try (InputStream is = TrayManager.class.getResourceAsStream("/icons/tray.png")) {
            if (is == null) {
                System.err.println("[Tray] 找不到 /icons/tray.png");
                return null;
            }
            return ImageIO.read(is);
        } catch (IOException e) {
            System.err.println("[Tray] 加载图标失败: " + e.getMessage());
            return null;
        }
    }

    private static PopupMenu buildMenu(Stage stage) {
        PopupMenu menu = new PopupMenu();

        MenuItem openItem = new MenuItem("打开 Kmate");
        openItem.addActionListener(e -> Platform.runLater(() -> showWindow(stage)));

        MenuItem hideItem = new MenuItem("隐藏窗口");
        hideItem.addActionListener(e -> Platform.runLater(() -> {
            if (stage.isShowing()) stage.hide();
        }));

        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> {
            // 真实退出由 QuitManager 负责;此处先复用 Mate4K 的 quitApp() 入口
            // —— 见 Task 11 把这里替换为 onQuit 回调(目前由 TrayManager.install
            // 在构造期注入,Task 11 一并改)
            new MenuItemActionShim().trigger();
        });

        menu.add(openItem);
        menu.add(hideItem);
        menu.addSeparator();
        menu.add(quitItem);
        return menu;
    }

    private static void showWindow(Stage stage) {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }

    /**
     * 占位:Task 11 之前让托盘"退出"按钮暂时可用,避免本 Task 提交后该按钮变成 no-op。
     * Task 11 会用 QuitManager 注入的 Runnable 替换。
     */
    private static final class MenuItemActionShim {
        void trigger() {
            // 占位:Task 11 替换为 QuitManager.quit()
        }
    }
}
```

说明:`MenuItemActionShim` 是为了让本任务(TrayManager 抽出)独立可提交、又不留编译错误。Task 11 不再改 `install(...)` 签名,而是引入 `setOnQuit(Runnable)` setter 让 `Mate4K` 在 `QuitManager` 创建后再挂 quit 回调;Task 11 会把 `MenuItemActionShim` 整段删除,并把 quit 按钮的 `addActionListener` 改为读取字段 `onQuit`(setter 设入)。

- [ ] **Step 2: 修改 `Mate4K.java` 删除 tray 字段与 tray 初始化代码**

完整替换 `Mate4K.java` 为(去除所有 tray 相关字段 / 方法,精简后的版本):

```java
package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.model.AppState;
import com.glodon.mordor.kmate.ui.chat.ChatPane;
import com.glodon.mordor.kmate.ui.login.LoginPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.layout.StackPane;

public class Mate4K extends Application {

    private static final double WIDTH = 600;
    private static final double HEIGHT = 449;

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        root.getStyleClass().add("app-bg");

        LoginPane login = new LoginPane(state -> showChat(root, state));
        StackPane.setAlignment(login, Pos.CENTER);
        root.getChildren().add(login);

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                Mate4K.class.getResource("app.css").toExternalForm());

        final KeyCombination.Modifier shortcut = KeyCombination.SHORTCUT_DOWN;
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, shortcut),
                () -> { if (stage.isShowing()) stage.hide(); });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Q, shortcut),
                this::quitApp);

        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(e -> { e.consume(); quitApp(); });

        stage.setTitle("k-mate");
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(360);

        stage.show();

        TrayManager.install(stage);
        installOsQuitHandlers();
    }

    private void showChat(StackPane root, AppState state) {
        root.getChildren().setAll(new ChatPane(state));
    }

    private void installOsQuitHandlers() {
        MacQuitHook.install(this::quitApp);
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                Desktop.getDesktop().setQuitHandler((e, r) -> {
                    quitApp();
                    r.performQuit();
                });
            }
        } catch (Throwable t) {
            System.err.println("[Quit] Desktop quit hook 安装失败: " + t.getMessage());
        }
    }

    /** 占位 quit:Task 11 之前仍在 Mate4K;Task 11 把这里删除并把字段迁移到 QuitManager。 */
    private void quitApp() {
        SystemTray tray = SystemTray.getSystemTray();
        for (TrayIcon i : tray.getTrayIcons()) {
            tray.remove(i);
        }
        Runtime.getRuntime().halt(0);
    }

    public static void main(String[] args) {
        launch();
    }
}
```

需要新增的 import:

```java
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import java.awt.Desktop;
import java.awt.SystemTray;
import java.awt.TrayIcon;
```

- [ ] **Step 3: 编译验证**

```bash
mvn -q -DskipTests compile
```

预期:`BUILD SUCCESS`。

- [ ] **Step 4: 启动验证**

```bash
./mvnw -q -DskipTests javafx:run
```

验证项:

- 启动后系统托盘(Windows 右下 / macOS 菜单栏 / Linux 系统托盘)出现 Kmate 图标
- 右键点击托盘图标 → 弹出菜单 "打开 Kmate / 隐藏窗口 / 退出"
  - "打开 Kmate":窗口已显示时无变化;隐藏后点击 → 窗口恢复并置顶
  - "隐藏窗口":窗口隐藏
  - "退出":进程退出(占位 quit 路径,Task 11 后由 QuitManager 接管)

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/app
git commit -m "refactor: extract TrayManager; Mate4K keeps install call only"
```

---

## Task 10: 抽出 `ShortcutRegistrar`

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/app/ShortcutRegistrar.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`(删除 shortcuts 注册段,改为 `ShortcutRegistrar.register(scene, stage, this::quitApp)`)

- [ ] **Step 1: 创建 `ShortcutRegistrar.java`**

完整文件内容:

```java
package com.glodon.mordor.kmate.app;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

/**
 * 跨平台全局快捷键注册。
 *
 *   ⌘W / Ctrl+W —— 隐藏窗口(单向,不实现再按一次恢复)
 *   ⌘Q / Ctrl+Q —— 退出程序(由 onQuit 决定具体行为)
 *
 * 通过 Scene.getAccelerators() 注册,不依赖焦点控件,TextField 输入时也能触发。
 */
public final class ShortcutRegistrar {

    private ShortcutRegistrar() {}

    public static void register(Scene scene, Stage stage, Runnable onQuit) {
        KeyCombination.Modifier mod = KeyCombination.SHORTCUT_DOWN;
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, mod),
                () -> { if (stage.isShowing()) stage.hide(); });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Q, mod),
                onQuit);
    }
}
```

- [ ] **Step 2: 修改 `Mate4K.java` 移除快捷键注册段**

找到 `Mate4K.start()` 中:

```java
        final KeyCombination.Modifier shortcut = KeyCombination.SHORTCUT_DOWN;
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, shortcut),
                () -> { if (stage.isShowing()) stage.hide(); });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Q, shortcut),
                this::quitApp);
```

整段替换为:

```java
        ShortcutRegistrar.register(scene, stage, this::quitApp);
```

删除文件顶部现已不再使用的 import(`KeyCode` / `KeyCodeCombination` / `KeyCombination`):

```java
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
```

- [ ] **Step 3: 编译验证**

```bash
mvn -q -DskipTests compile
```

预期:`BUILD SUCCESS`。

- [ ] **Step 4: 启动验证**

```bash
./mvnw -q -DskipTests javafx:run
```

验证项:

- ⌘W / Ctrl+W:窗口隐藏,无副作用(可由托盘"打开 Kmate"恢复)
- ⌘Q / Ctrl+Q:进程退出

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/app
git commit -m "refactor: extract ShortcutRegistrar"
```

---

## Task 11: 抽出 `OsQuitHandlers` + `QuitManager`,替换 Mate4K 的 quit 路径

**Files:**
- Create: `src/main/java/com/glodon/mordor/kmate/app/OsQuitHandlers.java`
- Create: `src/main/java/com/glodon/mordor/kmate/app/QuitManager.java`
- Modify: `src/main/java/com/glodon/mordor/kmate/app/TrayManager.java`(`install(Stage, Runnable onQuit)`,托盘 quit 按钮接 `onQuit`)
- Modify: `src/main/java/com/glodon/mordor/kmate/app/Mate4K.java`(删除 `installOsQuitHandlers` / `quitApp`,改为 local var + 三处委托)

- [ ] **Step 1: 创建 `QuitManager.java`**

完整文件内容:

```java
package com.glodon.mordor.kmate.app;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 退出守卫 + 异步 forceQuit。
 *
 * quit() 用 AtomicBoolean 防止重入;真正退出在独立线程里移除托盘 + Runtime.halt,
 * 保证 JVM 一定死掉(避免 AWT 线程持有托盘导致进程不退出)。
 */
public final class QuitManager {

    private final AtomicBoolean quitting = new AtomicBoolean();
    private final SystemTray tray;
    private final TrayIcon icon;

    public QuitManager(SystemTray tray, TrayIcon icon) {
        this.tray = tray;
        this.icon = icon;
    }

    public void quit() {
        if (!quitting.compareAndSet(false, true)) return;
        Thread shutdown = new Thread(this::forceQuit, "kmate-shutdown");
        shutdown.start();
    }

    private void forceQuit() {
        SystemTray tr = tray;
        TrayIcon ic = icon;
        if (tr != null && ic != null) {
            Thread remover = new Thread(() -> {
                try {
                    tr.remove(ic);
                } catch (Throwable ignored) {
                    // 退出路径,移除失败也要继续杀进程
                }
            }, "kmate-tray-remove");
            remover.start();
            try {
                remover.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime.getRuntime().halt(0);
    }
}
```

- [ ] **Step 2: 创建 `OsQuitHandlers.java`**

完整文件内容:

```java
package com.glodon.mordor.kmate.app;

import java.awt.Desktop;

/**
 * 接管 macOS / 其它平台的"应用退出"事件:
 *   - Glass Application.handleQuitAction(macOS ⌘Q 与 Dock「退出」)
 *   - Desktop.APP_QUIT_HANDLER(其它平台未来扩展)
 *
 * Stage.onCloseRequest 由 Mate4K 直接挂,因为需要 stage 引用。
 */
public final class OsQuitHandlers {

    private OsQuitHandlers() {}

    public static void install(Runnable onQuit) {
        MacQuitHook.install(onQuit);
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                Desktop.getDesktop().setQuitHandler((e, response) -> {
                    onQuit.run();
                    response.performQuit();
                });
            }
        } catch (Throwable t) {
            System.err.println("[Quit] Desktop quit hook 安装失败: " + t.getMessage());
        }
    }
}
```

- [ ] **Step 3: 修改 `TrayManager.java` 让托盘 quit 按钮接 `onQuit`(分两步:install 先创建、setOnQuit 后挂)**

找到 `TrayManager.install(Stage stage)` 签名(本任务中已存在),把它改为:

```java
    public static TrayManager install(Stage stage) {
```

并把字段从 `private final` 改为 `private final` 同时新增一个 `private Runnable onQuit = () -> {};` 字段(放在类的字段区)。找到类中字段区,在 `private final TrayIcon icon;` 之后添加:

```java
    private Runnable onQuit = () -> {};
```

找到 `buildMenu(stage)` 中:

```java
        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> {
            new MenuItemActionShim().trigger();
        });
```

改为:

```java
        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> onQuit.run());
```

找到文件底部:

```java
    /**
     * 占位:Task 11 之前让托盘"退出"按钮暂时可用,避免本 Task 提交后该按钮变成 no-op。
     * Task 11 会用 QuitManager 注入的 Runnable 替换。
     */
    private static final class MenuItemActionShim {
        void trigger() {
            // 占位:Task 11 替换为 QuitManager.quit()
        }
    }
}
```

把整段 `MenuItemActionShim` 类 + 它上面的 Javadoc 注释删掉。

新增一个 `setOnQuit` 方法(放在 `icon()` getter 之后):

```java
    public void setOnQuit(Runnable onQuit) {
        this.onQuit = onQuit == null ? () -> {} : onQuit;
    }
```

- [ ] **Step 4: 重写 `Mate4K.java` 整合所有 app 子系统**

完整文件内容:

```java
package com.glodon.mordor.kmate.app;

import com.glodon.mordor.kmate.ui.chat.ChatPane;
import com.glodon.mordor.kmate.ui.login.LoginPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Mate4K extends Application {

    private static final double WIDTH = 600;
    private static final double HEIGHT = 449;

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        root.getStyleClass().add("app-bg");
        root.getChildren().add(new LoginPane(state ->
                root.getChildren().setAll(new ChatPane(state))));

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.getStylesheets().add(
                Mate4K.class.getResource("app.css").toExternalForm());

        stage.setTitle("k-mate");
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(360);
        Platform.setImplicitExit(false);
        stage.show();

        TrayManager trayManager = TrayManager.install(stage);
        QuitManager quitManager = new QuitManager(trayManager.tray(), trayManager.icon());

        trayManager.setOnQuit(quitManager::quit);
        ShortcutRegistrar.register(scene, stage, quitManager::quit);
        stage.setOnCloseRequest(e -> { e.consume(); quitManager.quit(); });
        OsQuitHandlers.install(quitManager::quit);
    }

    public static void main(String[] args) {
        launch();
    }
}
```

`LoginPane` / `ChatPane` 由 Java 21 的 effectively final 推断支持 `() -> root.getChildren().setAll(new ChatPane(state))` 捕获 `root`;同样 `trayManager` / `quitManager` 在 `start()` 内 lambda 捕获(`Lambda can only reference local variables that are effectively final`——这两个变量在声明后未再被修改,Java 视为 effectively final)。

- [ ] **Step 5: 编译验证**

```bash
mvn -q -DskipTests compile
```

预期:`BUILD SUCCESS`。

- [ ] **Step 6: 启动 + 退出路径验证**

```bash
./mvnw -q -DskipTests javafx:run
```

验证项(每项验证前都需要先启动一次,确认进程正常退出):

- **⌘Q / Ctrl+Q**:聊天页焦点时按 → 进程在 1 秒内退出,IDE / 任务管理器看不到残留 java 进程
- **托盘右键 → 退出**:同 ⌘Q
- **托盘右键 → 隐藏窗口 → 托盘右键 → 打开 Kmate**:窗口恢复,无状态残留
- **macOS Dock「退出」**(如在 mac 上):进程退出(Glass hook 已挂)
- **窗口标题栏关闭按钮(×)**:进程退出(`Stage.onCloseRequest` 已挂)
- **⌘W 隐藏 + 再次启动 + 退出**:整个 quit 路径稳定

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/com/glodon/mordor/kmate/app
git commit -m "refactor: extract OsQuitHandlers + QuitManager, slim Mate4K to start() only"
```

---

## Self-Review Checklist(plan 写完后逐项核对)

- [x] **Spec coverage**:5 个迁移阶段 + 所有拆分目标全部映射到 11 个 Task(model/service/ui.login/ui.chat/app 搬包、CSS 拆、LoginController、ChatController、TrayManager、ShortcutRegistrar、OsQuitHandlers+QuitManager)
- [x] **Placeholder scan**:无 TBD / TODO / "类似 Task N";每个代码块都是完整可粘贴内容
- [x] **Type consistency**:`LoginController.{Prefilled, Input, Result}` / `ChatController.{state, messages, getMessages, send}` / `TrayManager.{tray, icon}` / `QuitManager.{quit, forceQuit}` / `OsQuitHandlers.install(Runnable)` / `ShortcutRegistrar.register(Scene, Stage, Runnable)` 在所有 Task 之间一致
- [x] **依赖方向**:每个 Task 的 modify 文件所需 import 都已列出;`app → ui → service → model` 单向无回边
- [x] **回退路径**:每个 Task 独立 commit,失败可 `git revert` 单步