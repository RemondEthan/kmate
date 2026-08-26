#include <kserver/message.hpp>
#include <stdexcept>

namespace kserver {

std::optional<Message> MessageParser::parse(const std::string& json_str) {
    try {
        json j = json::parse(json_str);
        
        if (!j.contains("type") || !j.contains("data")) {
            return std::nullopt;
        }
        
        std::string type = j["type"].get<std::string>();
        json data = j["data"];
        
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
            msg.username = data.value("username", "");
            return msg;
        }
        
        return std::nullopt;
    }
    catch (const json::exception&) {
        return std::nullopt;
    }
}

std::string MessageParser::to_string(const Message& msg) {
    return std::visit([](const auto& m) -> std::string {
        using T = std::decay_t<decltype(m)>;
        
        if constexpr (std::is_same_v<T, RegisterMessage>) {
            json j = {
                {"type", "register"},
                {"data", {{"im_code", m.im_code}, {"username", m.username}}}
            };
            return j.dump();
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
    }, msg);
}

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
