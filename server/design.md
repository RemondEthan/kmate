# KServer 设计文档

## 1. 概述

KServer 是 kmate 项目的 IM 服务端，基于 C++17 + Boost.Beast/Asio 实现。负责：

- WebSocket 连接管理
- 房间（Room）管理
- 用户注册与 ID 分发
- Padding 分发（用于 AES-256-GCM 密钥派生）
- 消息转发（服务端不解密）
- 心跳检测与超时断连

客户端使用本协议与服务端通信，所有消息基于 **WebSocket 文本帧 + JSON**。

---

## 2. 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                      Client                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐  │
│  │  UI Layer   │    │  Crypto     │    │  WS Client  │  │
│  │             │    │  (AES-GCM)  │    │  (Connect)  │  │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘  │
│         │                  │                  │          │
│         └──────────────────┴──────────────────┘          │
└──────────────────────┬──────────────────────────────────┘
                       │ WebSocket (wss://)
                       ▼
┌─────────────────────────────────────────────────────────┐
│                     KServer                             │
│  ┌─────────────────────────────────────────────────┐    │
│  │              Boost.Asio IO Context               │    │
│  │  ┌───────────┐  ┌───────────┐  ┌─────────────┐  │    │
│  │  │ Acceptor  │  │ Session   │  │   Timer     │  │    │
│  │  │ (port)    │  │ Manager   │  │ (heartbeat) │  │    │
│  │  └─────┬─────┘  └─────┬─────┘  └──────┬──────┘  │    │
│  │        │              │               │          │    │
│  │        ▼              ▼               ▼          │    │
│  │  ┌─────────────────────────────────────────┐    │    │
│  │  │           Room Manager                  │    │    │
│  │  │  Room A (im_code: OFFICE2024)           │    │    │
│  │  │  Room B (im_code: FAMILY)               │    │    │
│  │  └─────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────┘    │
│                                                         │
│  加密策略：服务端不持有密钥，仅转发 Base64 编码的密文      │
│  密钥派生：客户端本地用 MD5(password+padding) 派生        │
└─────────────────────────────────────────────────────────┘
```

### 核心组件

| 组件 | 文件 | 职责 |
|------|------|------|
| `Server` | server.hpp/cpp | 监听端口、管理房间、心跳调度 |
| `Session` | session.hpp/cpp | WebSocket 连接、消息收发、注册状态 |
| `Room` | room.hpp/cpp | 房间用户管理、Padding 分发、广播 |
| `MessageParser` | message.hpp/cpp | JSON 序列化/反序列化 |
| `HeartbeatScheduler` | heartbeat_scheduler.hpp/cpp | 定时检查超时连接 |

---

## 3. 连接与通信协议

### 3.1 传输层

- **协议：** WebSocket (RFC 6455)
- **默认端口：** 3000
- **数据格式：** 文本帧（Text Frame），内容为 JSON 字符串
- **编码：** UTF-8

### 3.2 连接建立流程

```
Client                                          Server
  │                                                │
  │  ──────── WebSocket Handshake ───────────────> │
  │  <─────── 101 Switching Protocols ──────────── │
  │                                                │
  │  ──────── {"type":"register",...} ───────────> │
  │  <─────── {"type":"registered",...} ────────── │
  │                                                │
  │  ═══════ 连接就绪，可以收发消息 ════════════════ │
```

### 3.3 消息方向

| 消息类型 | 方向 | 说明 |
|----------|------|------|
| `register` | Client → Server | 注册请求 |
| `registered` | Server → Client | 注册成功响应 |
| `text` | Client → Server | 发送消息（密文） |
| `text` | Server → Client | 转发消息（密文） |
| `peer_connected` | Server → Client | 新用户加入通知 |
| `peer_disconnected` | Server → Client | 用户离开通知 |
| `error` | Server → Client | 错误通知 |

---

## 4. 消息格式（JSON）

### 4.1 通用结构

所有消息遵循统一格式：

```json
{
  "type": "<消息类型>",
  "data": { ... }
}
```

### 4.2 完整消息类型

#### 4.2.1 注册请求（Client → Server）

客户端连接后必须首先发送此消息。

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

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `im_code` | string | 是 | 房间标识码，同一 im_code 的用户进入同一房间 |
| `username` | string | 是 | 用户昵称，用于显示 |
| `user_id` | int | 否 | 客户端声明的旧号；省略或 `0` 表示首次登录，由服务端新发号 |

**错误情况：**

```json
{"type":"error","data":{"message":"Already registered"}}
{"type":"error","data":{"message":"Room is full (max 10 users)"}}
```

#### 4.2.2 注册成功响应（Server → Client）

服务端分配 user_id 并返回 padding，用于客户端本地派生 AES 密钥。

```json
{
  "type": "registered",
  "data": {
    "user_id": 200000,
    "padding": "aB3dE5gH"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | int | 服务端分配的用户 ID。真人以回包为准，从 200000 起；`100778` 保留给客户端秘书。声明合法则沿用，否则发新号 |
| `padding` | string | Base64 编码的 8 字节随机数据，同一房间所有用户共享 |

**密钥派生方式：**

```
MD5(password + padding) → 32 字节 hex → 截取前 32 字节作为 AES-256-GCM 密钥
```

> 注意：padding 由服务端生成，同一 IM_CODE 的所有用户共享相同的 padding。password 仅客户端持有，服务端永不接触。

#### 4.2.3 发送消息（Client → Server）

客户端发送加密后的密文。

```json
{
  "type": "text",
  "data": {
    "content": "base64_ciphertext_here",
    "username": "Alice"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | string | AES-256-GCM 加密后的 Base64 密文，格式：`Base64(IV + ciphertext + auth_tag)` |
| `username` | string | 发送者昵称 |

**加密参数：**

| 参数 | 值 |
|------|-----|
| 算法 | AES-256-GCM |
| 密钥长度 | 32 字节 |
| IV 长度 | 12 字节（随机生成） |
| Auth Tag 长度 | 16 字节 |
| 密文格式 | `IV(12) + ciphertext + auth_tag(16)` |

> 服务端不解密，直接将 content 字段转发给房间内其他用户。

#### 4.2.4 转发消息（Server → Client）

服务端将密文转发给房间内其他用户（排除发送者自己）。

```json
{
  "type": "text",
  "data": {
    "content": "base64_ciphertext_here",
    "username": "Alice"
  }
}
```

格式与发送消息相同，客户端收到后使用共享密钥解密。

#### 4.2.5 新用户加入通知（Server → Client）

当有新用户加入同一房间时，服务端通知现有用户。

```json
{
  "type": "peer_connected",
  "data": {
    "user_id": 200001,
    "username": "Bob"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | int | 新用户的 ID |
| `username` | string | 新用户的昵称 |

> 客户端收到此消息后，应使用已有的 padding 和新用户的 password 重新派生密钥（如果需要与新用户通信）。

#### 4.2.6 用户离开通知（Server → Client）

当用户离开房间或超时断连时，服务端通知剩余用户。

```json
{
  "type": "peer_disconnected",
  "data": {
    "user_id": 200001,
    "username": "Bob"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | int | 离开用户的 ID |
| `username` | string | 离开用户的昵称 |

#### 4.2.7 错误消息（Server → Client）

```json
{
  "type": "error",
  "data": {
    "message": "错误描述"
  }
}
```

---

## 5. 服务端行为规范

### 5.1 用户 ID 分配

- 真人 `user_id` 从 **200000** 起发，全局（跨房间）单调
- `100778` 保留给客户端秘书，服务端永不发出
- `register` 可带可选 `user_id`：合法（`≥ 200000`、非 `100778`、当前无其他在线会话占用）则沿用；否则新发号
- 水位文件：与 kserver **可执行文件同目录**的 `id_counter`（一行十进制 = 下一个将分配的号）
- 已发出的号不回绕；旧进程的 `1…199999` 不再通过「带着 id 登录」回收

### 5.2 Padding 机制

| 事件 | 行为 |
|------|------|
| 首个用户加入房间 | 生成 8 字节随机 padding，Base64 编码 |
| 后续用户加入 | 返回已有 padding |
| 最后一个用户离开 | 清空 padding |
| 再次有用户加入 | 重新生成 padding |

**Padding 生成：**

- 使用 OpenSSL `RAND_bytes` 生成 8 字节随机数
- Base64 编码后传输
- 备用方案：RAND_bytes 失败时使用时间戳

### 5.3 消息广播

```
Client A ──text──> Server ──text──> Client B
                   │
                   └────text────> Client C
```

- 服务端收到 Client A 的消息后，转发给房间内**除 A 以外**的所有已注册用户
- 转发内容与收到的内容**完全相同**（包括 content 字段）
- 未注册的用户不会收到任何消息

### 5.4 房间管理

| 参数 | 值 |
|------|-----|
| 最大用户数 | 10 |
| 空房间清理间隔 | 30 秒 |
| 房间创建时机 | 首个用户请求加入时 |
| 房间删除时机 | 最后一个用户离开后 30 秒内清理 |

### 5.5 心跳检测

| 参数 | 值 |
|------|-----|
| 检查间隔 | 5 秒 |
| 超时阈值 | 15 秒 |

**工作原理：**

1. 服务端每 5 秒检查所有在线用户的 `last_active_time`
2. 如果 `当前时间 - last_active_time > 15 秒`，判定为超时
3. 超时后服务端关闭该 WebSocket 连接
4. 触发 `leave()` 流程，通知房间内其他用户

**更新 `last_active_time` 的时机：**

- 收到任意消息时（`handle_message` 入口处）

> 客户端不需要主动发送心跳包。只要客户端有消息收发，`last_active_time` 就会更新。如果客户端长时间无消息，服务端会主动断开。

---

## 6. 加密方案

### 6.1 密钥派生

```
password = "用户输入的密码"  (客户端本地)
padding  = "服务端返回的 Base64 字符串"

key_material = MD5(password + padding)  →  32 字节 hex 字符串
aes_key      = key_material[0:32]       →  32 字节（用于 AES-256-GCM）
```

### 6.2 加密过程

```
plaintext    = "原始消息"
aes_key      = MD5(password + padding)[0:32]
iv           = 随机生成 12 字节

ciphertext, auth_tag = AES-GCM-Encrypt(aes_key, iv, plaintext)

output = Base64(iv + ciphertext + auth_tag)
```

### 6.3 解密过程

```
decoded      = Base64Decode(output)
iv           = decoded[0:12]
auth_tag     = decoded[-16:]
ciphertext   = decoded[12:-16]

plaintext    = AES-GCM-Decrypt(aes_key, iv, ciphertext, auth_tag)
```

### 6.4 密钥同步

- 所有加入同一房间的用户共享相同的 `padding`
- 每个用户使用自己的 `password` + 共享的 `padding` 派生 `aes_key`
- 新用户加入时发送 `peer_connected` 通知
- 新用户离开时发送 `peer_disconnected` 通知

> **注意：** 密钥与用户身份无关，只与 password + padding 相关。如果某个用户退出，其他用户无需更换密钥。

---

## 7. 错误处理

### 7.1 服务端错误响应

| 错误场景 | 响应 |
|----------|------|
| 已注册用户再次注册 | `{"type":"error","data":{"message":"Already registered"}}` |
| 房间已满 | `{"type":"error","data":{"message":"Room is full (max 10 users)"}}` |
| 未注册用户发送消息 | 连接关闭（不发送错误消息） |

### 7.2 连接断开处理

**服务端主动断开：**

- 心跳超时：服务端关闭连接
- 房间已满：发送错误后关闭连接

**客户端断开：**

- 服务端检测到连接关闭后，自动调用 `room_->leave()`
- 通知房间内其他用户 `peer_disconnected`

### 7.3 异常处理

- WebSocket 读取错误（EOF/连接重置）：视为正常断开，不打印错误日志
- 其他错误：打印错误日志并清理

---

## 8. 完整交互示例

### 8.1 场景：Alice 和 Bob 加入同一房间

```
Alice                         Server                          Bob
  │                             │                               │
  │── WS Connect ──────────────>│                               │
  │<─ WS Accept ───────────────│                               │
  │                             │                               │
  │── {"type":"register",       │                               │
  │    "data":{                 │                               │
  │      "im_code":"CHAT01",    │                               │
  │      "username":"Alice"     │                               │
  │    }} ─────────────────────>│                               │
  │                             │                               │
  │<─ {"type":"registered",     │                               │
  │    "data":{                 │                               │
  │      "user_id":200000,      │                               │
  │      "padding":"aB3dE5gH"  │                               │
  │    }} ─────────────────────│                               │
  │                             │                               │
  │                             │<─ WS Connect ────────────────│
  │                             │<─ WS Accept ─────────────────│
  │                             │                               │
  │                             │<─ {"type":"register",         │
  │                             │    "data":{                   │
  │                             │      "im_code":"CHAT01",      │
  │                             │      "username":"Bob"         │
  │                             │    }} ────────────────────────│
  │                             │                               │
  │                             │── {"type":"peer_connected",   │
  │<───────────────────────────│    "data":{                   │
  │                             │      "user_id":200001,        │
  │                             │      "username":"Bob"         │
  │                             │    }} ───────────────────────>│
  │                             │                               │
  │                             │── {"type":"registered",       │
  │                             │    "data":{                   │
  │                             │      "user_id":200001,        │
  │                             │      "padding":"aB3dE5gH"    │
  │                             │    }} ───────────────────────>│
  │                             │                               │
```

### 8.2 场景：Alice 发送加密消息给 Bob

```
Alice                         Server                          Bob
  │                             │                               │
  │  Alice 本地加密:            │                               │
  │  aes_key = MD5("pass" +     │                               │
  │           "aB3dE5gH")       │                               │
  │  ciphertext = AES-GCM(...)  │                               │
  │                             │                               │
  │── {"type":"text",           │                               │
  │    "data":{                 │                               │
  │      "content":"U2FsdGVk..."│                               │
  │      "username":"Alice"     │                               │
  │    }} ─────────────────────>│                               │
  │                             │                               │
  │                             │── {"type":"text",             │
  │                             │    "data":{                   │
  │                             │      "content":"U2FsdGVk..." │
  │                             │      "username":"Alice"       │
  │                             │    }} ───────────────────────>│
  │                             │                               │
  │                             │  Bob 本地解密:                │
  │                             │  aes_key = MD5("pass" +       │
  │                             │           "aB3dE5gH")         │
  │                             │  plaintext = AES-GCM-Dec(...) │
```

---

## 9. API 参考

### 9.1 客户端发送的消息

| 类型 | 格式 | 说明 |
|------|------|------|
| `register` | `{"type":"register","data":{"im_code":"...","username":"...","user_id":N}}` | 注册到房间（`user_id` 可选） |
| `text` | `{"type":"text","data":{"content":"...","username":"..."}}` | 发送加密消息 |

### 9.2 服务端返回的消息

| 类型 | 格式 | 说明 |
|------|------|------|
| `registered` | `{"type":"registered","data":{"user_id":N,"padding":"..."}}` | 注册成功 |
| `text` | `{"type":"text","data":{"content":"...","username":"..."}}` | 收到转发消息 |
| `peer_connected` | `{"type":"peer_connected","data":{"user_id":N,"username":"..."}}` | 新用户加入 |
| `peer_disconnected` | `{"type":"peer_disconnected","data":{"user_id":N,"username":"..."}}` | 用户离开 |
| `error` | `{"type":"error","data":{"message":"..."}}` | 错误 |

---

## 10. 客户端实现清单

### 10.1 必须实现

- [ ] WebSocket 连接管理
- [ ] 注册流程（发送 `register`，处理 `registered`）
- [ ] AES-256-GCM 加密（使用 MD5(password+padding) 派生密钥）
- [ ] AES-256-GCM 解密
- [ ] 发送加密消息（`text` 类型）
- [ ] 接收并解密转发消息
- [ ] 处理 `peer_connected` / `peer_disconnected` 通知

### 10.2 可选实现

- [ ] 自动重连机制
- [ ] 离线消息队列
- [ ] 消息已读回执
- [ ] 用户列表展示

---

## 11. 协议常量汇总

| 常量 | 值 | 说明 |
|------|-----|------|
| 默认端口 | 3000 | 服务端监听端口 |
| 最大用户数 | 10 | 单个房间最大容量 |
| 心跳检查间隔 | 5 秒 | 检查超时的频率 |
| 心跳超时阈值 | 15 秒 | 超过此时间无消息则断开 |
| 空房间清理间隔 | 30 秒 | 清理无用户的房间 |
| User ID 起始值 | 200000 | 真人从 200000 起；`100778` 保留给秘书；水位见 `id_counter` |
| Padding 长度 | 8 字节 | Base64 编码后约 12 字符 |
| AES IV 长度 | 12 字节 | GCM 推荐值 |
| AES Auth Tag | 16 字节 | GCM 认证标签 |
| WebSocket 超时 | 30 秒 | 握手和空闲超时 |
