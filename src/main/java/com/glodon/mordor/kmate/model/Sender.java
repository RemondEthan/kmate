package com.glodon.mordor.kmate.model;

/**
 * 消息发送方身份。
 *
 * 三种取值对应消息气泡在 UI 中的不同样式:
 *   - SELF:   我方发出的消息,气泡靠右,蓝色调(对应 CSS .bubble-self)
 *   - PEER:   对方发来的消息,气泡靠左,灰色调(对应 CSS .bubble-peer)
 *   - SYSTEM: 系统提示(如"对方已加入"、"连接已断开"),居中显示无头像(对应 CSS .bubble-system)
 *
 * 这是 Java 普通的 enum,没有 JavaFX 特有概念;
 * JavaFX 中 enum 常作为 switch 的模式匹配标签使用(参见 ChatController.onEvent 的 switch)。
 */
public enum Sender { SELF, PEER, SYSTEM }