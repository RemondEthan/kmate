#pragma once

#include <string>
#include <optional>
#include <variant>
#include <nlohmann/json.hpp>

namespace kserver {

using json = nlohmann::json;

enum class MessageType {
    Register,
    Text,
    PeerConnected,
    PeerDisconnected,
    Error,
    RoomJoined,
    RoomLeft
};

struct RegisterMessage {
    MessageType type = MessageType::Register;
    std::string im_code;
    std::string username;
};

struct TextMessage {
    MessageType type = MessageType::Text;
    std::string content;
    std::string username;
};

struct ErrorMessage {
    MessageType type = MessageType::Error;
    std::string message;
};

struct RoomNotification {
    MessageType type;
    std::string username;
    int online_count;
};

using Message = std::variant<
    RegisterMessage,
    TextMessage,
    ErrorMessage,
    RoomNotification
>;

class MessageParser {
public:
    static std::optional<Message> parse(const std::string& json_str);
    static std::string to_string(const Message& msg);
    static std::string peer_connected(const std::string& username, int online_count);
    static std::string peer_disconnected(const std::string& username);
    static std::string error(const std::string& message);
    static std::string room_joined(const std::string& username, int online_count);
    static std::string room_left(const std::string& username, int online_count);
};

} // namespace kserver
