#pragma once

/**
 * @file message.hpp
 * @brief 消息类型定义和解析器
 *
 * 本文件定义了IM服务器使用的所有消息类型：
 * 1. RegisterMessage - 客户端注册（加入房间）
 * 2. RegisteredMessage - 服务器确认注册（返回user_id和padding）
 * 3. TextMessage - 文本消息（加密后转发）
 * 4. ErrorMessage - 错误通知
 * 5. RoomNotification - 房间状态通知（加入/离开）
 *
 * JSON消息格式：
 * {
 *   "type": "register|registered|text|error|peer_connected|peer_disconnected",
 *   "data": { ... }
 * }
 *
 * C++17特性说明：
 * - std::variant: 类型安全的联合体，可以存储多种类型之一
 * - std::optional: 可选值容器，可以表示"有值"或"无值"
 * - std::visit: 访问variant中的值
 * - if constexpr: 编译时条件判断
 */

#include <string>                   // std::string
#include <optional>                 // std::optional
#include <variant>                  // std::variant
#include <nlohmann/json.hpp>        // nlohmann/json库，JSON解析

namespace kserver {

// 类型别名，简化JSON类型名称
using json = nlohmann::json;

/**
 * @enum MessageType
 * @brief 消息类型枚举
 *
 * 用于在代码中标识消息类型
 * 注意：与JSON中的type字符串对应
 */
enum class MessageType {
    Register,           // 客户端注册消息
    Registered,         // 服务器确认注册（返回user_id和padding）
    Text,               // 文本消息（加密后）
    PeerConnected,      // 对方已连接
    PeerDisconnected,   // 对方已断开
    Error               // 错误消息
};

/**
 * @struct RegisterMessage
 * @brief 注册消息，客户端加入房间时发送
 *
 * JSON格式：
 * {
 *   "type": "register",
 *   "data": {
 *     "im_code": "OFFICE2024",
 *     "username": "Alice"
 *   }
 * }
 */
struct RegisterMessage {
    MessageType type = MessageType::Register;  // 消息类型
    std::string im_code;                       // 房间标识码
    std::string username;                      // 用户名
};

/**
 * @struct RegisteredMessage
 * @brief 注册确认消息，服务器返回给客户端
 *
 * JSON格式：
 * {
 *   "type": "registered",
 *   "data": {
 *     "user_id": 1,
 *     "padding": "Base64编码的8字节随机值"
 *   }
 * }
 *
 * 说明：
 * - user_id: 服务器分配的用户ID，用于标识用户
 * - padding: 8字节随机值，用于密钥派生
 *   - 同一IM_CODE的所有用户共享此padding
 *   - 客户端使用 password + padding 派生AES密钥
 */
struct RegisteredMessage {
    MessageType type = MessageType::Registered;  // 消息类型
    int user_id;                                 // 服务器分配的用户ID
    std::string padding;                         // Base64编码的padding
};

/**
 * @struct TextMessage
 * @brief 文本消息，包含加密后的内容
 *
 * JSON格式：
 * {
 *   "type": "text",
 *   "data": {
 *     "content": "base64加密的密文",
 *     "username": "Alice"
 *   }
 * }
 *
 * 注意：content字段是加密后的Base64字符串
 * 只有密码正确的客户端才能解密
 */
struct TextMessage {
    MessageType type = MessageType::Text;  // 消息类型
    std::string content;                   // 加密后的消息内容
    std::string username;                  // 发送者用户名
};

/**
 * @struct ErrorMessage
 * @brief 错误消息，服务器通知客户端错误信息
 *
 * JSON格式：
 * {
 *   "type": "error",
 *   "data": {
 *     "message": "Room is full"
 *   }
 * }
 */
struct ErrorMessage {
    MessageType type = MessageType::Error;  // 消息类型
    std::string message;                    // 错误描述
};

/**
 * @struct RoomNotification
 * @brief 房间状态通知
 *
 * 用于通知客户端有人加入/离开房间
 *
 * JSON格式（加入）：
 * {
 *   "type": "peer_connected",
 *   "data": {
 *     "user_id": 2,
 *     "username": "Bob"
 *   }
 * }
 *
 * JSON格式（离开）：
 * {
 *   "type": "peer_disconnected",
 *   "data": {
 *     "user_id": 2,
 *     "username": "Bob"
 *   }
 * }
 */
struct RoomNotification {
    MessageType type;           // 消息类型（PeerConnected/PeerDisconnected）
    int user_id;                // 相关用户ID
    std::string username;       // 相关用户名
};

/**
 * @brief Message类型定义
 *
 * std::variant 可以存储多种类型之一：
 * - RegisterMessage
 * - RegisteredMessage
 * - TextMessage
 * - ErrorMessage
 * - RoomNotification
 *
 * 类似于"类型安全的联合体"
 * 只能存储其中一种类型，通过std::visit访问
 */
using Message = std::variant<
    RegisterMessage,
    RegisteredMessage,
    TextMessage,
    ErrorMessage,
    RoomNotification
>;

/**
 * @class MessageParser
 * @brief 消息解析器，负责JSON与Message的转换
 *
 * 所有方法都是静态的，不需要实例化
 *
 * 使用示例：
 * @code
 *   // 解析JSON字符串
 *   std::optional<Message> msg = MessageParser::parse(json_str);
 *   if (msg) {
 *       // 使用std::visit处理消息
 *   }
 *
 *   // 创建错误消息
 *   std::string err = MessageParser::error("Something went wrong");
 * @endcode
 */
class MessageParser {
public:
    /**
     * @brief 解析JSON字符串为Message
     * @param json_str JSON字符串
     * @return 解析成功返回Message，失败返回空optional
     *
     * 支持的消息类型：
     * - "register" -> RegisterMessage
     * - "text" -> TextMessage
     * - 其他类型返回nullopt
     */
    static std::optional<Message> parse(const std::string& json_str);

    /**
     * @brief 将Message转换为JSON字符串
     * @param msg Message对象
     * @return JSON字符串
     *
     * 使用std::visit根据实际类型分发处理
     */
    static std::string to_string(const Message& msg);

    // ========================================================================
    // 便捷工厂方法
    // ========================================================================

    /**
     * @brief 创建注册确认消息
     * @param user_id 服务器分配的用户ID
     * @param padding Base64编码的padding
     */
    static std::string registered(int user_id, const std::string& padding);

    /**
     * @brief 创建"对方已连接"通知
     * @param user_id 对方用户ID
     * @param username 对方用户名
     */
    static std::string peer_connected(int user_id, const std::string& username);

    /**
     * @brief 创建"对方已断开"通知
     * @param user_id 对方用户ID
     * @param username 对方用户名
     */
    static std::string peer_disconnected(int user_id, const std::string& username);

    /**
     * @brief 创建错误消息
     * @param message 错误描述
     */
    static std::string error(const std::string& message);
};

} // namespace kserver
