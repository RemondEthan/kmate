# 持久用户 ID、秘书昵称与按 ID 头像 — Design Spec

Date: 2026-09-03
Status: Approved

## 背景

秘书嵌入群聊之后，头像按**用户名**索引。同名对端会抢走秘书图，或解码失败（日志里出现 `avatar decode failed user=kelsy`）。对端离开时客户端丢掉头像缓存，时间线重建后旧气泡只剩字母。

`user_id` 目前是进程内从 1 自增，重连和新号，server 重启再从 1 数。同一人两次登录对不上同一个头像键。

秘书显示名写死为 `tars`，无法按房间起名。

## 目标

- 真人 `user_id` 从 **200000** 起发，跨重启不回绕；客户端首次拿到号后落盘，下次 `register` 带着来，**同一 IM_CODE + 用户名**尽量沿用同一号。
- 头像缓存按 **`userId`** 索引。秘书本机固定 **100778**，永不按显示名取秘书头像。
- 对端离开：成员列表去掉；**已有气泡的头像留下**。
- 添加秘书时为本房间设**昵称**。只有行首 `@当前昵称` 算提及。`@tars` 仅当昵称仍是 `tars` 时有效。

## 不在范围内

- 服务端存「用户名 ↔ id」总表。
- 拒绝昵称与在线用户名撞车，也不加字数 / 字符集规则。
- 改写旧历史里的 `from`（过去写成 `kelsy` / `tars` 的行保持原样）。
- 给聊天正文帧补 `user_id`。
- 脱机房间向 server 要号；脱机自己仍是 `-1`，秘书仍是 `100778`。
- 独立 kelsy 仓库、安装包形态、口令 / `padding` / 加密。
- 换电脑或清 Preferences 后仍拿回旧号。

## ID 空间

| 值 | 谁 | 谁分配 | 说明 |
|---|---|---|---|
| `-1` | 自己 | 仅客户端 | 不出现在 `register` 的声明里 |
| `100778` | 本机秘书 | 仅客户端 | server **永不**发出此号；客户端声明此号视为无效 |
| `≥ 200000` | 真人 | server | 全局（跨房间）单调，不复用已发出的号 |

旧进程发过的 `1…199999`（除 `100778`）不再通过「带着 id 登录」回收。没带合法声明的连接一律拿新号（`≥ 200000`）。

`RoomMember.KELSY_ID` 从 `-2` 改为 `100778`。`isKelsy()` 只比 `userId`。成员工厂仍可用，显示名改为**当前房间昵称**，不再写死 `tars`。`SECRETARY_NAME = "tars"` 只表示**默认昵称**。

## 协议

`register` 增加可选整数 `user_id`。缺省、`0`、非正数：按「首次」处理。

```json
{
  "type": "register",
  "data": {
    "im_code": "OFFICE2024",
    "username": "Alice",
    "user_id": 200003
  }
}
```

`registered` 形状不变，`user_id` 以 **server 回包为准**（可能因冲突改发新号）。客户端每次握手成功都用回包装的号覆盖本地记录。

旧客户端不传 `user_id`：新 server 发 `≥ 200000` 的新号。旧 server 忽略多余字段，仍按自己的计数器发号；新客户端以回包为准改写本地。两端都升完，同一安装、同一 `IM_CODE`+用户名才会稳定。

`server/design.md` 中「从 1 开始自增」改为本 spec 的规则。

## Server：发号与落盘

### 水位文件

- 路径：与 kserver **可执行文件同目录**的 `id_counter`（本地构建即 `server/dist/id_counter`）。
- 内容：一行十进制整数 = **下一个**将分配的号。
- 启动：文件不存在、空、非数字 → `200000`。读到的值 `< 200000` → 用 `200000`（避免旧计数器 1、2、3 继续发）。
- 每次真正发出新号，或因声明抬高水位后，立刻写回。写失败只打日志，**不**拒绝入房。
- 写入：先写 `id_counter.tmp` 再 rename，避免半截文件。
- **不**持久化用户名映射。只记发到哪了。

### 分配（全局一把锁）

`evict_username` 仍先发生，再分配。同一用户名重连时，旧会话已不占号。

声明合法的条件（同时满足）：

1. `user_id ≥ 200000`
2. `user_id ≠ 100778`
3. 当前**任意房间**没有别的在线会话占用该号

合法 → 沿用；若 `user_id ≥ next`，则 `next = user_id + 1` 并落盘。

不合法或未声明 → `user_id = next++`，落盘。

房间仍最多 10 人；满员错误文案不变。

### 实现落点

- `Server`：启动读文件；提供「声明或新号」接口（扫描在线 id + 递增 + 写盘）。`id_counter_` 初值改为读盘结果，不再写死 `1`。
- `Room::join`：改为接收已定好的 `user_id`，不再自己 `id_counter_++`。
- `Session::handle_register`：解析可选 `user_id`，向 Server 要号后再 `join`。
- `RegisterMessage` / `MessageParser`：读取可选 `user_id`。

## 客户端：记住自己的号

新建小服务（Preferences，不写口令），键为：

```text
uid.<sha256(imCode)>.<username>
```

与 `KelsyRoomSettings` 一样用 `ChatHistory.sha256Hex`。**不含** host/port。换服务器但同一 `IM_CODE`+用户名会声明同一个号；若该号在线被别人占用，server 改发新号，客户端以回包覆盖。

- `connect` 前：有记录则 `Protocol.register(imCode, username, userId)`，否则不带字段。
- 收到 `registered`：写入（或覆盖）该键。
- 脱机登录：不读不写此键。

不要塞进 `SaveLastLoginService` 那组「上次登录」键，避免和单行用户名缠在一起。

## 头像按 userId

### 缓存

`ChatController.peerAvatars` 与 `ImClient.avatars` 改为 `Map<Integer, Image>` / `Map<Integer, byte[]>`，键为 `userId`。

另存 `lastSeenIds: username → userId`：在 `PeerJoined`、`PeerAvatar` 时写入或覆盖；**离开不删**。用于旧气泡：`Message.from` 仍是用户名，用这张表找回 id 再取图。

解析顺序（`avatarOf` / 气泡）：

1. `Sender.ASSISTANT` 或成员行 `userId == 100778` → 只走秘书本地头像（`KelsyRoomSettings.avatarPath`），**禁止**用显示名去 `peerAvatars` 里找。
2. `from` 等于当前登录用户名 → `AppState.avatar()`。
3. 其余：`lastSeenIds.get(from)` → `peerAvatars.get(id)`；没有 id 或没有图 → 字母回退。

不要再写 `username.equalsIgnoreCase("tars")` / 当前昵称 就当秘书。对端就叫 `tars` 也只用他的真人 id。

### 离开（方案 B）

`PeerLeft`：

- 从当前成员列表 / `peers`（在线表）去掉，系统提示「… 已离开」不变。
- **不** `peerAvatars.remove`，**不** `lastSeenIds.remove`，`ImClient.avatars` 也不因离开删除。

对方用同一 id 再上线：头像键不变，缓存可被新 `PeerAvatar` 覆盖。

新会话 `connect` 仍清空 `ImClient` 的 roster/avatars（会重放**当前在线**头像）。`ChatController` 进房新建，`lastSeenIds` **不**跨进房、**不**写入 Preferences。自己重登时，不在线的人的历史气泡先字母回退；对方上线（同一 id + 头像重放）后再对上。不把对端头像字节落到本机。

### 成员列表与气泡

- 成员：自己（`-1`）→ 当前在线对端 →（若已添加）秘书 `100778` + 当前昵称。
- 点秘书行：插入 `@当前昵称 `（末尾空格）。
- `MessageListView` 秘书气泡用 `100778` 取头像，显示名用当前昵称。
- 对端气泡：`from` → `lastSeenIds` → 图。

## 秘书昵称

`KelsyRoomSettings` 增加按 `imCode` 哈希的 `nick.<hash>`，与 `on.` / `avatar.` 并列。脱机 `__offline__` 同一套键。

- `enable(imCode, avatarPath, nickname)`：写入开关、头像路径、昵称。
- 昵称：`strip` 后若空，视为 `tars`。不校验长度、字符、是否与在线用户重名。
- `disable`：去掉 `on`、`avatar`、`nick`（再添加从默认 `tars` 开始）。
- `nickname(imCode)`：未添加或键空 → `"tars"`。

### 添加 UI

「添加 tars」改为「添加秘书」。现有选头像之后，再弹本机输入（`TextInputDialog` 即可）：标题/说明为秘书昵称，预填 `tars`。取消整段添加（不 `enable`）。头像选了但对话取消：不落盘。

无单独「改名」。要改：移除再添加。

### 提及

`KelsyMention` 以**当前昵称**为唯一词（大小写不敏感，与现在相同）：去掉行首空白后，整段等于 `@昵称`，或 `@昵称` + 空白。

- 昵称 `Ada`：`@Ada 你好`、`  @ada 帮我` 算；`@tars`、`@kelsy`、句中 `请 @Ada`、`@Adafoo` 不算。
- 路由、斜杠命令、忙线拦截仍只认这条提及。
- `INSERT` 随昵称变。
- 新秘书气泡 / 历史里新写入的 ASSISTANT `from` 用当前昵称。旧行不改；展示用当前昵称，头像仍走 `100778`。

## 数据流

```
首次登录
  register(im_code, username)          // 无 user_id
  ← registered(user_id=200000, padding)
  Preferences 写入 uid.<hash>.<name>=200000

再次登录
  register(..., user_id=200000)
  ← registered(200000, ...)            // 或冲突时新号
  覆盖 Preferences

离开
  成员列表删行
  peerAvatars[200000] / lastSeenIds 保留
  旧气泡仍按 from→id 取图
```

## 测试

不启 JavaFX、不连真集群即可覆盖的部分：

- `KelsyMention`：自定义昵称的正反例；默认 `tars`；`@kelsy` 永远不是提及（除非有人把昵称设成 `kelsy`）。
- `KelsyRoomSettings`：昵称按房间隔离；空昵称回落 `tars`；`disable` 清掉 nick。
- 本机 uid Preferences：按 imCode+用户名隔离；`registered` 覆盖旧值。
- `Protocol.register`：有 id 时带 `user_id`；无 id 时 JSON 不含该键。
- `avatarOf`：秘书只认 `100778`；同名对端走真人 id；离开后仍能靠 `lastSeenIds` 取到缓存图。
- `ChatController`：`PeerLeft` 后成员变少，`peerAvatars` 仍有该 id。
- 脱机：不写 uid；秘书 id 仍是 `100778`；`@昵称` 与线上同一套 settings。

Server（若无现成 C++ 单测，实现时加可单测的分配函数，或用临时 `id_counter` 路径起进程测）：

- 无文件 → 第一人 `200000`，文件变成 `200001`。
- 声明 `200005` 且空闲 → 沿用，水位至少 `200006`。
- 声明 `100778` / `7` / 负数 → 忽略，发下一个 `≥ 200000` 的号。
- 声明的号已在另一房间在线 → 改发新号。
- 文件里是旧值 `42` → 启动按 `200000` 发。

不测：真窗口选图、真两台客户端对打安装包。

## 风险

- 只升一端时，号会漂，头像键仍可能对不上；文档写明两端一起升。
- 不存映射：别人离线时声明你的号，server 会接受。本产品房间口令共享，不做防冒充。
- 昵称与用户名撞车时，行首 `@昵称` 进秘书，不是 bug。
