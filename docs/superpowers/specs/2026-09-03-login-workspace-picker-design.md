# 登录页选择秘书工作区 — Design Spec

Date: 2026-09-03
Status: Approved

## 背景

独立 kelsy 登录页已有「工作区路径」+「浏览」。kmate 把秘书知识库写死在 `~/.kmate/kelsy/workspace`，换目录只能改 `config.json`。登录页需要能选。

## 目标

- 登录页增加与独立 kelsy 相同的一行：标签「工作区路径」、文本框、按钮「浏览」。
- 预填 `~/.kmate/kelsy/config.json` 的 `workspaceDir`；没有或为空则用 `~/.kmate/kelsy/workspace`。
- 「浏览」打开 `DirectoryChooser`（标题「选择工作区」）。当前路径若是已有目录，作为初始目录。
- 连接 / 进入时把所选路径写入同一份 `config.json` 的 `workspaceDir`，**保留**已有 `model`（含 apiKey）及其他字段。
- 文本框为空时按默认路径保存并使用。不要求目录事先存在；进房后仍走现有 `WorkspaceSeeder`。
- 脱机、在线都用这一行。未添加秘书时也保存路径，添加后立刻用这份目录。
- `KelsyRuntime` 用配置里的 `workspaceDir` 做知识库 / MEMORY，不再永远用 `KelsyPaths.defaults()` 的固定 workspace。`config.json` 位置不变（`~/.kmate/kelsy/config.json`）。

## 不在范围内

- 最近路径下拉、多工作区列表。
- 校验目录必须已存在、必须可写（除写入 `config.json` 失败时给登录错误）。
- 把路径写进 Preferences / `SaveLastLoginService`。
- 改独立 kelsy 仓库、改 server、改聊天历史房间路径。

## 登录

在用户名+头像那一行下面、脱机勾选上面，插入工作区行。样式跟现有字段：`login-field-label`、`login-field`，浏览按钮用独立 kelsy 的 `login-browse`（`login.css` 补一条即可）。

`LoginController.Input` 增加 `workspaceDir`。`validate`：**不**因路径为空失败；空则视为默认。在线仍只校验 IP / 端口 / IM_CODE / 口令 / 用户名。

`save`：在现有 Preferences 保存之外，调用 `ConfigLoader` 写回 `workspaceDir`。先 `peek`，再 `new KelsyConfig(current.model(), chosen, current.lastUsername(), …)` 只换路径（`lastUsername` 可顺便写成当前用户名）。没有 `config.json` 时 `ensure` 出模板再改 `workspaceDir`。

写入失败：登录页显示错误（如「无法写入配置」），不进房、不开始握手。

## 进房

`KelsyRuntime.shared(username)`：

1. `paths = KelsyPaths.defaults()`（config / legacy 仍按 home）。
2. `workspace = ConfigLoader.peek(paths).workspacePath()`。
3. `open` 时用「同一 config，workspace 换成 peek 的路径」。

`KelsyPaths` 增加能换 workspace、不动 config 的构造或 `withWorkspace(Path)`。

若进程里已有 `shared` 实例且 workspace 与本次不同：先 `shutdown` 再按新路径 `open`（同一 JVM 回到登录再进房时才用到）。

`LocalAssistantService` 已读 `KelsyConfig.workspacePath()`；`ensureAssistant` 的 `loadOrThrow` 会带上刚写入的 `workspaceDir`。seed / `KnowledgeStore` 必须走 `paths.workspace()`，与配置一致。

未添加秘书时不创建 Agent，但路径已落盘。点「添加秘书」后 `shared` 读到的就是这份目录。

## 数据流

```
LoginPane 工作区文本 / 浏览
  → validate（空 → 默认）
  → ConfigLoader.save workspaceDir（保留 model）
  → ChatController / KelsyRuntime.shared
       peek.workspacePath()
       seed + KnowledgeStore + Assistant
```

## 测试

不启 JavaFX、不弹系统选目录框：

- `ConfigLoader.save`：改 `workspaceDir` 后 `peek` 读回；`model.apiKey` 仍在。
- `KelsyPaths.withWorkspace`（或等价）：config 路径不变，workspace 换成给定目录。
- `LoginController.validate`：工作区空、在线/脱机，现有校验不变。
- `KelsyRuntime`：peek 为自定义目录时，`paths().workspace()` 指向该目录（可用 `open` + 临时 home，先写入 config）。

不测：真窗口点浏览、安装包。
