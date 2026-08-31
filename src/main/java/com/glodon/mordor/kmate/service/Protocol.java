package com.glodon.mordor.kmate.service;

/**
 * 与 KServer 对齐的 JSON 文本协议（WebSocket 文本帧）。
 * 不引入第三方 JSON 库，便于 jlink 打包。
 *
 * 协议格式（参考 server/design.md）：
 *   客户端 → 服务端:
 *     register: {"type":"register","data":{"im_code":"X","username":"Y"}}
 *     text:     {"type":"text","data":{"content":"<cipher>","username":"Y"}}
 *     avatar:   {"type":"avatar","data":{"content":"<cipher>"}}
 *
 *   服务端 → 客户端:
 *     registered: {"type":"registered","data":{"user_id":1,"padding":"abc"}}
 *     text:       {"type":"text","data":{"username":"peer","content":"<cipher>"}}
 *     avatar:     {"type":"avatar","data":{"username":"peer","content":"<cipher>"}}
 *     peer_connected / peer_disconnected: data.user_id + data.username
 *     error:      {"type":"error","data":{"message":"..."}}
 *
 * 这里没有用第三方 JSON 库（Gson/Jackson），全靠手写 string/object/int 解析。
 * 原因是 jlink 打包时第三方 JSON 库会带一坨依赖，体积太大；
 * 这个项目只需解析固定几种字段，自己写 100 多行代码可控且精简。
 *
 * 解析约定：所有方法对找不到字段或格式异常都返回"空值"，不抛异常——
 * 协议层宁可丢字段也不能让一条陌生消息搞挂整个 WebSocket 监听器。
 */
public final class Protocol {

    // 工具类私有构造器，禁止实例化。
    private Protocol() {}

    /**
     * 解析一条入站消息后的扁平结果。Service 层根据 type 分发，忽略不认识的字段。
     * record：所有字段 + 构造器 + 访问器由编译器生成。
     */
    public record Incoming(
            String type,      // "register" / "text" / "avatar" / "peer_connected" / "error" 等
            String content,   // text/avatar 帧里的密文（Base64），由 CryptoService 解密
            String username,  // 消息作者用户名
            String padding,   // register 帧里服务端给的 padding，用于本地派生 AES 密钥
            String message,   // error 帧里的错误描述
            int userId        // register / peer_connected / peer_disconnected 里的整数 ID
    ) {}

    /**
     * 构造 register 帧。客户端首次连接后立即发送。
     */
    public static String register(String imCode, String username) {
        return "{\"type\":\"register\",\"data\":{\"im_code\":"
                + quote(imCode) + ",\"username\":" + quote(username) + "}}";
    }

    /**
     * 构造 text 帧。content 已经是加密后的 Base64 密文，这里只负责 JSON 包装。
     */
    public static String text(String content, String username) {
        return "{\"type\":\"text\",\"data\":{\"content\":"
                + quote(content) + ",\"username\":" + quote(username) + "}}";
    }

    /**
     * 构造 avatar 帧。同样 content 是加密后的 Base64。
     */
    public static String avatar(String content) {
        return "{\"type\":\"avatar\",\"data\":{\"content\":" + quote(content) + "}}";
    }

    /**
     * 把一条 JSON 文本解析成 Incoming。
     * 容错策略：所有字段缺失/格式错都返回空串/0，不抛异常。
     *
     * "data" 子对象可能不存在（少数服务端消息把字段直接放在顶层），
     * 所以先尝试在 data 里找字段，找不到再退到顶层 json 里找。
     */
    public static Incoming parse(String json) {
        if (json == null || json.isBlank()) {
            return new Incoming("", "", "", "", "", 0);
        }
        String type = stringField(json, "type");
        String data = objectField(json, "data");
        String src = data != null ? data : json;
        return new Incoming(
                type == null ? "" : type,
                nullToEmpty(stringField(src, "content")),
                nullToEmpty(stringField(src, "username")),
                nullToEmpty(stringField(src, "padding")),
                nullToEmpty(stringField(src, "message")),
                intField(src, "user_id")
        );
    }

    /**
     * 把一个 Java 字符串转义成 JSON 字符串字面量（不含外层花括号）。
     * 处理：双引号 → \"、反斜杠 → \\、控制字符 → \\uXXXX，其他原样。
     * null → ""。
     */
    static String quote(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // 控制字符（除 \n\r\t 外）必须 \\uXXXX 转义，否则 JSON 解析会报错。
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 取出 "key":{...} 整个对象子串（含外层花括号）。
     * 用深度计数处理花括号嵌套；字符串内的花括号不算。
     *
     * 返回的子串可以直接再次传入 stringField / intField 来读内层字段。
     */
    private static String objectField(String json, String key) {
        int start = indexOfKey(json, key);
        if (start < 0) {
            return null;
        }
        int i = skipWs(json, start);
        if (i >= json.length() || json.charAt(i) != '{') {
            return null;
        }
        int depth = 1;
        boolean inStr = false;
        boolean escape = false;
        for (int j = i + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (inStr) {
                // 在字符串内部的花括号不计入深度；处理转义避免 \" 误关字符串。
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(i, j + 1);
                }
            }
        }
        return null;
    }

    /**
     * 取出 "key":"value" 中的 value（不含外层引号）。
     * 解转义：\n \r \t \" \\，其他 \X 原样。
     */
    static String stringField(String json, String key) {
        int start = indexOfKey(json, key);
        if (start < 0) {
            return null;
        }
        int i = skipWs(json, start);
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int j = i + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (escape) {
                // 当前字符是上一字符反斜杠之后的字符。
                sb.append(switch (c) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> c;
                });
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    /**
     * 取出 "key":<number> 中的整数值。格式异常返回 0。
     */
    private static int intField(String json, String key) {
        int start = indexOfKey(json, key);
        if (start < 0) {
            return 0;
        }
        int i = skipWs(json, start);
        int j = i;
        // 可选的负号。
        if (j < json.length() && json.charAt(j) == '-') {
            j++;
        }
        while (j < json.length() && Character.isDigit(json.charAt(j))) {
            j++;
        }
        // 没匹配到任何数字（只有负号、或空）→ 返回 0。
        if (j == i || (j == i + 1 && json.charAt(i) == '-')) {
            return 0;
        }
        try {
            return Integer.parseInt(json.substring(i, j));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 在 json 里找 "key"，并确认后面跟着 ":"（避免 "user_id" 误匹配 "id"）。
     * 返回冒号之后的位置（即 value 的起点，可能还有空白）。
     */
    private static int indexOfKey(String json, String key) {
        String needle = "\"" + key + "\"";
        int from = 0;
        while (from < json.length()) {
            int at = json.indexOf(needle, from);
            if (at < 0) {
                return -1;
            }
            int after = skipWs(json, at + needle.length());
            if (after < json.length() && json.charAt(after) == ':') {
                return skipWs(json, after + 1);
            }
            // 不是我们要的 key，继续往后找（处理 key 是另一个 key 的前缀的情况）。
            from = at + needle.length();
        }
        return -1;
    }

    private static int skipWs(String json, int i) {
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }
}
