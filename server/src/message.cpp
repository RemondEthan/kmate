/**
 * @file message.cpp
 * @brief MessageParser类的实现文件
 *
 * 本文件实现了消息解析和序列化功能：
 * 1. JSON字符串 -> Message对象（parse）
 * 2. Message对象 -> JSON字符串（to_string）
 * 3. 便捷工厂方法（error、room_joined等）
 *
 * nlohmann/json库说明：
 * - json::parse() 解析JSON字符串
 * - j["key"] 访问JSON字段
 * - j.value("key", default) 带默认值的访问
 * - j.dump() 序列化为JSON字符串
 * - j.contains() 检查字段是否存在
 */

#include <kserver/message.hpp>
#include <stdexcept>

namespace kserver {

// ============================================================================
// JSON解析
// ============================================================================

std::optional<Message> MessageParser::parse(const std::string& json_str) {
    try {
        /**
         * json::parse() 解析JSON字符串
         *
         * 如果JSON格式错误，会抛出json::exception
         * 所以用try-catch捕获
         */
        json j = json::parse(json_str);

        // 检查必要的字段是否存在
        if (!j.contains("type") || !j.contains("data")) {
            return std::nullopt;  // 缺少必要字段
        }

        // 提取type字段
        std::string type = j["type"].get<std::string>();
        // get<std::string>() 将JSON值转换为C++类型

        // 提取data字段
        json data = j["data"];

        // 根据type分发处理
        if (type == "register") {
            RegisterMessage msg;
            msg.type = MessageType::Register;
            msg.im_code = data["im_code"].get<std::string>();
            msg.username = data["username"].get<std::string>();
            return msg;
        }
        else if (type == "text") {
            TextMessage msg;
            msg.type = MessageType::Text;
            msg.content = data["content"].get<std::string>();
            // value()方法：如果字段不存在，返回默认值
            msg.username = data.value("username", "");
            return msg;
        }

        // 不支持的消息类型
        return std::nullopt;
    }
    catch (const json::exception&) {
        // JSON解析失败
        return std::nullopt;
    }
}

// ============================================================================
// JSON序列化
// ============================================================================

std::string MessageParser::to_string(const Message& msg) {
    /**
     * std::visit 访问variant中的值
     *
     * lambda参数const auto& m 是实际存储的消息类型
     * 通过std::is_same_v在编译时判断类型
     * if constexpr 确保只编译匹配的分支
     */
    return std::visit([](const auto& m) -> std::string {
        using T = std::decay_t<decltype(m)>;

        if constexpr (std::is_same_v<T, RegisterMessage>) {
            json j = {
                {"type", "register"},
                {"data", {{"im_code", m.im_code}, {"username", m.username}}}
            };
            return j.dump();  // dump()序列化为JSON字符串
        }
        else if constexpr (std::is_same_v<T, TextMessage>) {
            json j = {
                {"type", "text"},
                {"data", {{"content", m.content}, {"username", m.username}}}
            };
            return j.dump();
        }
        else if constexpr (std::is_same_v<T, ErrorMessage>) {
            json j = {
                {"type", "error"},
                {"data", {{"message", m.message}}}
            };
            return j.dump();
        }
        else if constexpr (std::is_same_v<T, RoomNotification>) {
            // 将枚举转换为对应的字符串
            std::string type_str;
            if (m.type == MessageType::PeerConnected) {
                type_str = "peer_connected";
            } else if (m.type == MessageType::PeerDisconnected) {
                type_str = "peer_disconnected";
            } else if (m.type == MessageType::RoomJoined) {
                type_str = "room_joined";
            } else {
                type_str = "room_left";
            }

            json j = {
                {"type", type_str},
                {"data", {{"username", m.username}, {"online_count", m.online_count}}}
            };
            return j.dump();
        }
    }, msg);  // 传入variant，让visit访问
}

// ============================================================================
// 便捷工厂方法
// ============================================================================

std::string MessageParser::peer_connected(const std::string& username, int online_count) {
    RoomNotification msg{MessageType::PeerConnected, username, online_count};
    return to_string(msg);
}

std::string MessageParser::peer_disconnected(const std::string& username) {
    RoomNotification msg{MessageType::PeerDisconnected, username, 0};
    return to_string(msg);
}

std::string MessageParser::error(const std::string& message) {
    ErrorMessage msg;
    msg.message = message;
    return to_string(msg);
}

std::string MessageParser::room_joined(const std::string& username, int online_count) {
    RoomNotification msg{MessageType::RoomJoined, username, online_count};
    return to_string(msg);
}

std::string MessageParser::room_left(const std::string& username, int online_count) {
    RoomNotification msg{MessageType::RoomLeft, username, online_count};
    return to_string(msg);
}

} // namespace kserver
