#include <kserver/room.hpp>
#include <kserver/session.hpp>
#include <kserver/message.hpp>
#include <iostream>

namespace kserver {

Room::Room(const std::string& im_code)
    : im_code_(im_code) {
}

Room::~Room() = default;

const std::string& Room::im_code() const {
    return im_code_;
}

bool Room::join(std::shared_ptr<Session> session) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    if (static_cast<int>(sessions_.size()) >= MAX_USERS) {
        return false;
    }
    
    for (const std::shared_ptr<Session>& s : sessions_) {
        if (s != session) {
            s->send(MessageParser::room_joined(session->username(), static_cast<int>(sessions_.size()) + 1));
        }
    }
    
    sessions_.insert(session);
    return true;
}

void Room::leave(std::shared_ptr<Session> session) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    sessions_.erase(session);
    
    for (const std::shared_ptr<Session>& s : sessions_) {
        s->send(MessageParser::room_left(session->username(), static_cast<int>(sessions_.size())));
    }
    
    std::cout << "User " << session->username() << " left room " << im_code_
              << " (online: " << sessions_.size() << ")" << std::endl;
}

void Room::broadcast(const std::string& message, std::shared_ptr<Session> exclude) {
    std::lock_guard<std::mutex> lock(mutex_);
    
    for (const std::shared_ptr<Session>& s : sessions_) {
        if (s != exclude && s->is_registered()) {
            s->send(message);
        }
    }
}

int Room::user_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int>(sessions_.size());
}

bool Room::empty() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return sessions_.empty();
}

} // namespace kserver
