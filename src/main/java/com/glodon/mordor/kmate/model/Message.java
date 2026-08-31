package com.glodon.mordor.kmate.model;

import java.time.LocalDateTime;

/**
 * 一条聊天消息的不可变快照(record)。
 *
 * Java record(Java 14+ 正式特性):自动生成构造器、访问器(id()/sender()/...)、equals、hash、toString;
 * 所有字段隐式 final,字段本身 private。
 *
 * 字段含义:
 *   - id:        消息唯一 ID,本地生成的 UUID。聊天过程中产生的消息用 UUID.randomUUID(),
 *                从历史档案里读出来的消息用当时写入的 ID;
 *                用于 {@link com.glodon.mordor.kmate.service.ChatHistory#loadOlderThan}
 *                的按 ID 锚定翻页。
 *   - sender:    消息归属(自己 / 对方 / 系统),见 {@link Sender}。
 *   - content:   消息文本(解密后的明文)。SYSTEM 类消息可能含"[错误] xxx"这类前缀。
 *   - timestamp: 消息创建时间。注意 LocalDateTime 没有时区,只用于 UI 显示,不参与排序。
 *   - from:      消息作者的用户名(SELF 消息 = 自己用户名;PEER 消息 = 对方用户名;
 *                SYSTEM 消息 = "")。从历史档案解码时用来决定 PEER 消息显示谁的头像。
 */
public record Message(
        String id,
        Sender sender,
        String content,
        LocalDateTime timestamp,
        String from
) {
    /**
     * 4 字段便捷构造器:from 默认空串。
     * Java record 的"紧凑构造器"语法:参数列表不同,委托给 canonical 构造器。
     * 这样老调用点不需要关心 from 字段。
     */
    public Message(String id, Sender sender, String content, LocalDateTime timestamp) {
        this(id, sender, content, timestamp, "");
    }
}
