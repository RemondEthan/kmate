package com.glodon.mordor.kmate.service;

import com.glodon.mordor.kmate.model.Message;
import com.glodon.mordor.kmate.model.Sender;

import java.time.LocalDateTime;

/**
 * Message ↔ 档案明文 JSON。不引入第三方 JSON 库。
 *
 * 档案流程：Message → encode → JSON 串 → crypto.encrypt → 写文件。
 *           文件 → 读一行 → crypto.decrypt → JSON 串 → decode → Message。
 *
 * 这里只负责"明文 JSON ↔ Message"，加密由 CryptoService 包裹在外。
 * 用 Protocol.quote / Protocol.stringField 而不是新写一份 JSON 工具，
 * 避免重复实现转义/解析逻辑（参见 Protocol 的 Javadoc 关于为什么不引第三方库）。
 */
public final class HistoryCodec {

    // 工具类私有构造器。
    private HistoryCodec() {}

    /**
     * 把一条 Message 编码成 JSON 字符串。字段顺序固定，便于 diff 和调试。
     *
     * @param message 待编码的消息
     * @return 形如 {"id":"...","sender":"SELF","from":"me","timestamp":"2024-...","content":"..."}
     */
    public static String encode(Message message) {
        // 4 字段构造器没传 from，这里取出来可能是 null，统一空串避免写 null 字面量。
        String from = message.from() == null ? "" : message.from();
        return "{\"id\":" + Protocol.quote(message.id())
                + ",\"sender\":" + Protocol.quote(message.sender().name())
                + ",\"from\":" + Protocol.quote(from)
                + ",\"timestamp\":" + Protocol.quote(message.timestamp().toString())
                + ",\"content\":" + Protocol.quote(message.content())
                + "}";
    }

    /**
     * 把 JSON 字符串解码回 Message。
     *
     * @throws IllegalArgumentException 字段缺失、enum 名不认识、LocalDateTime 解析失败时抛出
     */
    public static Message decode(String json) {
        String id = Protocol.stringField(json, "id");
        String sender = Protocol.stringField(json, "sender");
        String from = Protocol.stringField(json, "from");
        String timestamp = Protocol.stringField(json, "timestamp");
        String content = Protocol.stringField(json, "content");
        // 必填字段缺失 → 这条记录废了；调用方通常 catch 后跳过这一行。
        if (id == null || sender == null || timestamp == null || content == null) {
            throw new IllegalArgumentException("incomplete history record");
        }
        try {
            return new Message(
                    id,
                    Sender.valueOf(sender),          // enum 名称匹配；不认识抛 IllegalArgumentException
                    content,
                    LocalDateTime.parse(timestamp),  // "yyyy-MM-ddTHH:mm:ss[.SSS]" 格式
                    from == null ? "" : from);
        } catch (RuntimeException e) {
            // 把各种解析异常统一包成 IllegalArgumentException 抛上去，调用方 catch 一处即可。
            throw new IllegalArgumentException("invalid history record", e);
        }
    }
}
