# 输入框 @ 成员选择 — Design Spec

Date: 2026-09-04
Status: Approved

## 背景

输入框只能靠点击左侧秘书行，在**开头**插入 `@昵称 `。`KelsyMention` / `KelsySendRouter` 只认句首提及，用来把话交给秘书。用户希望像微信一样：在输入框打 `@` 就下拉当前房间可选的人。

## 目标

- 打出合格的 `@` 后，在输入框上方弹出成员名单。
- 名单：当前房间 `members` 里**不是自己**的人；已添加秘书则包含，显示**当前昵称**。
- 在 `@` 后继续打字：按昵称**前缀**过滤（大小写不敏感）。
- 选中后在**光标处**把这次 `@` + 已打前缀换成 `@昵称 `（昵称后一个空格）。
- 一句里可以多次 `@`。
- 弹层打开时回车选人、不发送；Esc 只关弹层，留下已打的 `@张`。

## 不在范围内

- 气泡里高亮 @、服务端推送/@所有人。
- 改 `KelsyMention` / `KelsySendRouter`（句首 `@秘书` 才转秘书）。
- 改点左侧秘书行的 `insertMention`（仍插到开头）。
- 改加密协议、独立 kelsy 仓库。
- 「无结果」空态（筛空则关弹层）。

## 何时弹出

看光标前的文本，找**最后一个未完成的提及**：从光标往左，直到空白（或开头）为止的一段。该段必须以 `@` 开头，且 `@` 本身在整段开头或紧跟空白。

弹出：这段是 `@` 或 `@` + 不含空白的查询串。

不弹出：

- 没有这样的 `@`（含已完成的 `@张三 ` 之后继续打字）
- `@` 前面是非空白（如 `a@b`）
- 候选人过滤后为空（关掉已打开的弹层）

发出消息、Esc、点弹层外（`autoHide`）、删掉该 `@`：关闭。

## 名单与过滤

来源：`ChatController.getMembers()` 的当前快照（与左侧成员列表同一份）。

- 去掉 `self == true`
- 秘书用 `RoomMember.isKelsy()`，昵称为 `username`（与 `secretaryNickname()` 一致）
- 查询串为空：全部候选人
- 查询串非空：`username` 对查询串前缀匹配，`String.regionMatches(true, …)`
- 排序：秘书（若在）第一，其余保持 `members` 原顺序

零命中：关闭弹层，不画空列表。

## 插入

替换区间：该 `@` 的下标（含）到光标（不含）。替换为 `"@" + 昵称 + " "`。光标移到替换后文本末尾。

`KelsyMention.insert(nickname)` 已是 `@昵称 `，插入结果与它对齐。

句首若因此变成 `@秘书昵称 `，现有路由不变。句中 `@同事` 只是正文。

## 弹层 UI

`Popup`（参照 `EmojiPopover`）：`autoHide`，贴输入框**上方**、左对齐。白底、细边、轻阴影。最多约 6 行高，超出滚动。

每行：`AvatarView` 28px + 昵称。秘书用 `avatarOfSecretary()`，其他人用 `avatarOf(username)`。当前项浅灰底。

键鼠：

- ↑↓：循环高亮
- 回车：插入当前高亮并关闭；弹层开着时消费回车，不走 `TextField.onAction` 发送
- Esc：关闭，不改文本
- 点击一行：等同回车
- 鼠标移入一行：改高亮

与表情互斥：打开 @ 名单时关表情弹层；打开表情时关 @ 名单。

样式写在 `chat.css`（如 `.mention-popup` / `.mention-row` / `.mention-row-active`），不要大段 inline。

## 代码拆分

| 单元 | 职责 |
|---|---|
| `MentionQuery` | 纯函数：文本+光标 → 可选的 `@` 起点与查询串；成员列表+查询 → 候选人。无 JavaFX |
| `MentionPopover` | `Popup` + 列表 + 键盘/点击；插入回调 |
| `InputBar` | 听 `text`/`caret`，调用 Query，显隐 Popover；构造时注入 members 与头像查找 |
| `ChatPane` | 把 `controller.getMembers()` / `avatarOf` / `avatarOfSecretary` 传给 `InputBar` |

不改 `ImClient`、消息气泡、`KelsyMention` 判定。

## 数据流

```
输入 / 光标变化
  → MentionQuery.parse(text, caret)
  → 无 token：hide
  → 有 token：filter(members, query)
       → 空：hide
       → 非空：show Popup，↑↓/点/回车
            → 替换 [at, caret) 为 @昵称␣
```

## 测试

不启 JavaFX Stage、不弹系统窗：

- `MentionQuery.parse`：开头 `@`、空白后 `@`、`a@b` 不触发、`@张三 ` 后再打不触发、光标在 `@张|三` 查询为 `张`
- `MentionQuery.candidates`：去掉自己；秘书第一；前缀大小写不敏感；空查询=全员；零命中=空列表
- 插入后字符串：`hello @张` + 选「张三」→ `hello @张三 `
- 句首 `@tars `（或当前秘书昵称）仍 `KelsyMention.isMention == true`

`InputBar` 的 Popup 位置、回车不误发：实现后手工点一遍（房间里自己、一位同事、秘书；句中 `@`；`a@b`；Esc；与表情互斥）。

## 风险（接受）

昵称含空格时，前缀过滤和「空白结束提及」会在第一个空格处截断。当前房间昵称按单段显示名处理，不在本期支持空格名。
