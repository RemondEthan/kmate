# Encrypted Chat History Archive — Design Spec

Date: 2026-08-28
Status: Approved (brainstorming)

## 背景

客户端把每条消息做成一个 `MessageBubble` 堆在 `VBox` 里，列表只增不删。会话一长，界面和内存一起涨，出现卡顿。服务端不存聊天记录，关客户端历史就没了。

本 spec 在客户端增加**按 IM_CODE 分组的加密文本存档**，内存和界面默认只保留最近 100 条。

不在范围内：搜索、按日导出、多设备同步、服务端存历史、改口令后迁移旧档、列表虚拟化。

## 目标

- 内存与界面恒定 ≤ 100 个气泡，长会话不再越聊越卡。
- 历史落在本机加密文件里；密码正确才能解密，不正确整份文件不解密、不改写。
- 消息时间按本机发送/收到时刻记录，重启和上翻后仍正确。
- 档案密钥与当场 `padding` 脱钩：房间清空后 padding 变化，旧历史仍能解。
- 加密、写盘、解密、裁文件不在 JavaFX 线程上做。

## 存储布局

```
~/.kmate/history/<sha256(im_code) 的 hex>/messages.log
```

- 一个 IM_CODE 一份文件。换昵称仍读同一房间。
- 目录名用 SHA-256，避免 IM_CODE 出现在路径里，也避免 `/` 等字符破坏目录。
- 文件权限在 POSIX 上设为 `600`（仅属主读写）。Windows 走默认 ACL。
- UTF-8 文本，一行一条：

```
<Base64 校验密文>\n
<Base64 消息密文>\n
<Base64 消息密文>\n
```

第一行是文件头，不是消息。其后每一行是一条消息。

## 密钥

线路加密不变：`MD5(password + padding)`，`padding` 由服务端按场次下发（房间空了再进会变）。

档案使用**独立**的 `CryptoService` 实例，不用当场 padding：

```
archive_key = MD5(password + "|archive|" + im_code)
```

实现上即 `crypto.initialize(password, "|archive|" + imCode)`，算法与现有 `deriveKey` 相同。

文件头明文哨兵：`kmate-history-v1`。打开文件时用档案密钥解密第一行：

| 结果 | 行为 |
|------|------|
| 文件不存在 | 建目录、写入头、解锁，之后可追加 |
| 头解密成功 | 解锁；可解密正文、可追加 |
| 头解密失败（含 GCM 失败） | **不解密正文、不改文件、不追加**。界面当没有历史。本会话新消息只留内存 |
| 某行正文解不开或 JSON 不完整 | 跳过该行，不影响其它行 |

密码变了或文件不是这份密码写的，都属于「头解密失败」。不猜、不重试、不把乱码当消息。绝不把新密钥写进旧文件。

## 消息记录格式

加密前的明文 JSON（用现有 `Protocol.quote` 转义，不引入 JSON 库）：

```json
{"id":"...","sender":"PEER","from":"张三","timestamp":"2026-08-28T15:16:32","content":"..."}
```

| 字段 | 来源 |
|------|------|
| `id` | 现有 `Message.id`（UUID） |
| `sender` | `SELF` / `PEER` / `SYSTEM` |
| `from` | 发送者昵称；系统消息为空 |
| `timestamp` | 发出或收到时的本机 `LocalDateTime.now()`，`toString()` 为 ISO-8601 |
| `content` | 明文正文 |

时间与 padding 无关。协议没有服务端时间，档案不另存时区。气泡仍只显示 `HH:mm`。

SELF / PEER / SYSTEM 都归档。

## 内存窗口

| | 内存 / 界面 | 磁盘 |
|---|---|---|
| 上限 | 最近 **100** 条 | 最近 **2000** 条，且文件 ≤ **8MB** |
| 超限 | 从列表头丢掉最旧气泡 | 保留文件头 + 最旧行丢掉，直到两个上限都满足 |

裁磁盘**不解密**：一行一条，数行或看文件大小。后台读出要保留的行，写临时文件再替换 `messages.log`。

## 运行时行为

### 进房

`registered` 之后、进入 `ChatPane` 时，后台打开档案，只解密**最后 100 条**消息，一次 `Platform.runLater` 填进列表，再追加本会话的系统提示（「已加入房间…」）。

进房不扫整份文件的正文以外的用途（打开时只解文件头 + 最后 100 行）。

加载完成前到达的收/发消息先进入界面，加载完成时与档案结果合并，避免被 `setAll` 冲掉。

### 收发

自己发出、对方到达、系统行：立刻加入界面（若当前跟在最新）；同时把该条丢到后台线程加密并 `append` 一行。超过 100 条从列表头删气泡。

跟在最新 = 滚动条靠近底部。用户正在上翻看历史时，新消息只写盘，不改当前窗口，避免把正在看的气泡挤掉。滚回底部再加载最新 100 条。

### 上翻

滑到顶部（`vvalue ≤ 0.02`）且没有进行中的加载：后台按当前最上面一条的 `id`，再解更早的 **50** 条，插到列表头；同时从列表尾丢掉同样数量，界面仍 ≤ 100。

- 防抖：一次只跑一个加载任务。
- 插完后锚住原先第一条可见消息，不强制滚到底。
- `MessageListView` 现有「高度变了就 `vvalue=1`」只在跟在最新时生效。
- 列表监听必须处理删除和中间插入，不能只 `add`。
- 没有更早记录时停止请求。

### 线程

单线程守护池 `kmate-history` 串行执行：打开/校验头、追加、解密分页、裁文件。

JavaFX 线程只改 `ObservableList` 和气泡。`CryptoService` 档案实例只在该后台线程使用。

## 组件边界

```
ChatController  ──►  ChatHistory  ──►  HistoryCodec
                 │         │
                 │         └──►  CryptoService.forArchive(password, imCode)
                 └──►  MessageListView（订阅 messages，上翻 / 跟最新）
```

- `HistoryCodec`：`Message` ↔ JSON 明文。
- `ChatHistory`：文件、密钥校验、追加、按 id 向前翻、裁剪。可注入路径和磁盘上限，便于单测。
- `ChatController`：内存 100、跟最新 / 上翻、把收发丢给 `ChatHistory.appendAsync`。
- `ImClient` 暴露 `imCode()` / `password()`，供档案派生；线路 `CryptoService` 仍用 password + padding。
- 默认路径：`ChatHistory.defaultFile(imCode)`。

## 错误处理

- 写盘失败：打 `Diag.warn`，不挡聊天。
- 裁剪失败：下次追加再试。
- 密码不对：当没有历史，聊天照常。
- 坏行：跳过。

## 测试要点

- JSON 往返（含中文、换行、时间戳原样）。
- 正确密码能读；错误密码不解密、不改文件。
- 同一密码、不同 padding 的线路密钥解不开档案；档案密钥能解。
- 追加后 `loadNewest(100)` 顺序与时间正确。
- `loadOlderThan(id, 50)` 返回更早的一页。
- 超过磁盘条数上限时只留最新 N 条。
- 两个 IM_CODE 落到不同目录。
