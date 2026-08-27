#pragma once

#include <memory>
#include <string>
#include <unordered_set>
#include <mutex>

namespace kserver {

class Session;

class Room : public std::enable_shared_from_this<Room> {
public:
    static constexpr int MAX_USERS = 10;
    
    explicit Room(const std::string& im_code);
    ~Room();
    
    bool join(std::shared_ptr<Session> session);
    void leave(std::shared_ptr<Session> session);
    void broadcast(const std::string& message, std::shared_ptr<Session> exclude = nullptr);
    int user_count() const;
    bool empty() const;
    const std::string& im_code() const;
    
private:
    std::string im_code_;
    mutable std::mutex mutex_;
    std::unordered_set<std::shared_ptr<Session>> sessions_;
};

} // namespace kserver
