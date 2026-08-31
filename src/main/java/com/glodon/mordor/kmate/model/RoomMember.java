package com.glodon.mordor.kmate.model;

/**
 * 聊天室成员条目,用于左侧"成员列表"(RoomMemberList)的每一行。
 *
 * 字段含义:
 *   - userId:   服务端分配的用户 ID(由 KServer 在 register 时返回的 user_id)。
 *               SELF 用固定 -1 表示(本地从未注册到服务端、只是 UI 上的"自己")。
 *   - username: 显示用的用户名。
 *   - self:     true 表示是自己(UI 上会加"(我)"后缀、点击可换头像);false 表示是对方。
 *
 * 同样是 Java record,所有访问器由编译器自动生成。
 */
public record RoomMember(int userId, String username, boolean self) {
}
