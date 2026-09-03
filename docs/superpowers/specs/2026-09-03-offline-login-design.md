# 脱机登录 — Design Spec

Date: 2026-09-03
Status: Approved (brainstorming)

## 背景

kmate 登录后必须对 IM server 做 WebSocket 握手才能进入 `ChatPane`。本机秘书 tars 已可按房间手动添加，但没有服务器时无法进房，也就无法只和 tars 对话。

## 目标

- 登录页增加「脱机登录」勾选。勾选后**不创建、不连接** WebSocket。
- 脱机只校验用户名（加现有头像）；服务器 IP、端口、IM_CODE、口令不参与校验。
- 进入与线上相同的 `ChatPane`。tars **不**自动加入，仍要点「添加 tars」。
- 使用固定本机房间 `__offline__` 的加密 `ChatHistory`，与任何线上 `IM_CODE` 隔离；每次脱机进入都加载这份历史。
- 发送仍走现有 `KelsySendRouter`。凡是会走对端的路径，脱机时不发送、不写历史，输入框保留，提示「脱机登录，消息无法发送」。
- 未勾选时，登录、连接、发送行为与现在完全一致。

## 不在范围内

- 脱机与线上房间共用或导入历史。
- 记住上次是否勾选脱机。
- 脱机自动添加 tars。
- 新做一套脱机聊天页或假 `ImClient`。
- 改独立 kelsy 仓库、改 server。

## 登录

在「连 接」按钮上方增加勾选「脱机登录」，默认不勾，不写入 Preferences。

勾选后：

- 服务器 IP、端口、IM_CODE、初始口令**禁用**（仍显示上次预填，取消勾选后不必重填）。
- 说明文案改为：脱机只和本机秘书对话，不会连接服务器。
- 按钮文案改为「进 入」。
- `LoginController.validate` 在脱机时只要求用户名非空；IP / 端口 / IM_CODE / 口令即使为空也通过。
- `LoginPane` 不 `new ImClient()`、不 `connect`。保存仍只写用户名和头像路径（IP/端口/IM_CODE 保持 Preferences 里已有值，不覆盖成空）。

未勾选：校验、按钮「连 接」、握手失败文案均不变。

## 进房

`AppState` 增加 `offline`（`boolean`）。脱机构造：`client == null`，`offline == true`，`online == false`，`peerDisplay == "脱机"`。

`Mate4K.enterChat`：`client == null` 时不 `unreadAlert.watch`、不把 session 设成非空。`closeSession` 已对空 client 空操作。

`ChatController` 生产构造在 `state.offline()` 时：

- `imCode` 为常量 `__offline__`（公开，便于单测与档案路径）。
- 不 `addListener`、不读 roster / 线头像。
- `peerSender` 不会被调用（见发送）。
- 历史：`ChatHistory.defaultFile("__offline__")` + `CryptoService.forArchive`，口令为固定本机常量 `offline`（不向用户收集；仅保护本地文件，与线上房间密钥无关）。
- `refreshPeers` 只有自己；若该房间已 `KelsyRoomSettings.enabled("__offline__")` 则在自己后面插入 tars（与线上同一套 Preferences 键，按 imCode 哈希隔离）。

点自己头像：只更新本机 `AvatarService` + Preferences + `AppState.avatar`，不发 WebSocket。

## 发送

`ChatController.send` 的返回值从 `boolean` 改为小结果类型，例如：

```text
SendResult(boolean accepted, String hint)
```

- `accepted == true`：`InputBar` 清空输入（含 SYSTEM / ASK / FIND 等已处理的提及）。
- `accepted == false`：不清空；若 `hint` 非空则显示 3 秒。

路由仍是 `KelsySendRouter.route(enabled, busy, configured, content)`。脱机时对 `PEER` **不**调用 `sendPeer`：

| 情况 | 结果 |
|---|---|
| 空正文 | `InputBar` 现有逻辑，不调用 `send` |
| 脱机且路由为 `PEER`（未添加 tars 的任何内容，或已添加后的普通消息 / 单独 `/find`） | `accepted=false`，`hint=脱机登录，消息无法发送` |
| 路由为 `BUSY` | `accepted=false`，`hint=秘书还在回复`（线上线下相同） |
| `UNCONFIGURED` / `EMPTY_BODY` / `SLASH_ERROR` | 与现在相同：SYSTEM，`accepted=true` |
| `ASK` / `FIND` | 与现在相同：本地秘书或检索，写入 `__offline__` 历史，`accepted=true` |

线上 `PEER` 仍走 `sendPeer`（加密转发 + 自己的气泡）。

`InputBar` 的 `Function<String, Boolean>` 改为消费 `SendResult`（或等价），禁止把脱机失败显示成「秘书还在回复」。

## 数据流

```
LoginPane（脱机勾选）
  → validate 仅用户名
  → AppState(offline, client=null)
  → ChatPane / ChatController
       imCode=__offline__
       ChatHistory.loadInitialAsync
       send → KelsySendRouter
         PEER → hint，不写盘
         ASK/FIND → 本机 tars + 该档案
```

## 测试

不启 JavaFX、不连服务器：

- 脱机校验：仅用户名即可；其余字段空着通过。
- 在线校验：缺 IP / 端口 / IM_CODE / 口令 / 用户名仍失败（回归）。
- 脱机 `send`：未添加 tars 时任意内容 `accepted=false`，hint 为「脱机登录，消息无法发送」，`peerSender` 不被调用。
- 已添加 tars：`@tars 你好` 走秘书、不调 `peerSender`；普通消息仍是脱机 hint。
- 脱机历史：对 `__offline__` 写入后再 `open`，能读回 SELF / ASSISTANT。

不测：真窗口勾选、真 WebSocket、安装包。
