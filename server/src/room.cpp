/**
 * @file room.cpp
 * @brief Room类的实现文件
 *
 * 本文件实现了聊天房间的所有功能：
 * 1. 用户加入/离开管理
 * 2. 消息广播
 * 3. Padding生成和管理
 * 4. 使用外部已分配的 User ID 做通知
 * 5. 线程安全操作
 *
 * 线程安全说明：
 * 所有方法都使用std::lock_guard加锁
 * 确保同一时间只有一个线程操作sessions_集合
 */

#include <kserver/room.hpp>
#include <kserver/session.hpp>
#include <kserver/message.hpp>
#include <kserver/debug.hpp>
#include <iostream>
#include <openssl/rand.h>  // RAND_bytes

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

Room::Room(const std::string& im_code)
    : im_code_(im_code)         // 初始化房间标识码
{
}

Room::~Room() = default;  // 使用编译器生成的默认析构函数

const std::string& Room::im_code() const {
    return im_code_;
}

// ============================================================================
// Padding生成
// ============================================================================

std::string Room::generate_padding() {
    // 生成8字节随机数据
    std::vector<unsigned char> random_bytes(8);
    if (RAND_bytes(random_bytes.data(), 8) != 1) {
        // 随机数生成失败，使用时间戳作为后备方案
        // 这种情况在实际中几乎不会发生
        std::cerr << "RAND_bytes failed, using fallback" << std::endl;
        auto now = std::chrono::system_clock::now().time_since_epoch().count();
        for (int i = 0; i < 8; ++i) {
            random_bytes[i] = static_cast<unsigned char>((now >> (i * 8)) & 0xFF);
        }
    }
    return to_base64(random_bytes);
}

std::string Room::to_base64(const std::vector<unsigned char>& data) {
    // 简单的Base64编码实现
    static const char encoding_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    int val = 0;
    int valb = -6;

    for (unsigned char c : data) {
        val = (val << 8) + c;
        valb += 8;
        while (valb >= 0) {
            result.push_back(encoding_table[(val >> valb) & 0x3F]);
            valb -= 6;
        }
    }
    if (valb > -6) {
        result.push_back(encoding_table[((val << 8) >> (valb + 8)) & 0x3F]);
    }
    while (result.size() % 4) {
        result.push_back('=');
    }
    return result;
}

// ============================================================================
// 用户加入房间
// ============================================================================

bool Room::join(std::shared_ptr<Session> session, int user_id, std::string& padding) {
    std::vector<std::shared_ptr<Session>> others;

    {
        std::lock_guard<std::mutex> lock(mutex_);

        if (static_cast<int>(sessions_.size()) >= MAX_USERS) {
            return false;
        }

        if (sessions_.empty()) {
            padding_ = generate_padding();
            std::cout << "Generated padding for room " << im_code_ << ": " << padding_ << std::endl;
        }

        padding = padding_;
        others.assign(sessions_.begin(), sessions_.end());
        sessions_.insert(session);
    }

    // 通知房间里已有的人：有新成员加入
    for (const std::shared_ptr<Session>& s : others) {
        std::cout << "Notify existing " << s->username()
                  << " that " << session->username() << " joined" << std::endl;
        debug_log("room", "peer_connected -> ", s->username(),
                  " joiner=", session->username(), " id=", user_id);
        s->send(MessageParser::peer_connected(user_id, session->username()));
    }
    // 通知新加入的人：房间里已经有谁（后登录客户端才能显示对方）
    for (const std::shared_ptr<Session>& s : others) {
        std::cout << "Notify joiner " << session->username()
                  << " of existing " << s->username() << std::endl;
        debug_log("room", "peer_connected -> ", session->username(),
                  " existing=", s->username(), " id=", s->user_id());
        session->send(MessageParser::peer_connected(s->user_id(), s->username()));
    }

    return true;
}

void Room::evict_username(const std::string& username) {
    std::vector<std::shared_ptr<Session>> victims;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const std::shared_ptr<Session>& s : sessions_) {
            if (s->username() == username) {
                victims.push_back(s);
            }
        }
    }
    for (const std::shared_ptr<Session>& s : victims) {
        leave(s);
        // close() 只是 post do_close，必须在此同步释放，否则重连声明旧号时仍占 live_ids_
        s->release_user_id();
        s->close();
    }
}

void Room::leave(std::shared_ptr<Session> session) {
    int leaving_user_id = 0;
    std::string leaving_username;
    std::vector<std::shared_ptr<Session>> others;
    bool last_user = false;

    {
        std::lock_guard<std::mutex> lock(mutex_);

        auto it = sessions_.find(session);
        if (it == sessions_.end()) {
            return;
        }

        leaving_user_id = session->user_id();
        leaving_username = session->username();
        sessions_.erase(it);
        others.assign(sessions_.begin(), sessions_.end());

        if (sessions_.empty()) {
            padding_.clear();
            last_user = true;
        }
    }

    for (const std::shared_ptr<Session>& s : others) {
        s->send(MessageParser::peer_disconnected(leaving_user_id, leaving_username));
    }

    std::cout << "User " << leaving_username << " (ID:" << leaving_user_id
              << ") left room " << im_code_
              << " (online: " << (others.size()) << ")" << std::endl;

    if (last_user) {
        std::cout << "Cleared padding for room " << im_code_ << std::endl;
    }
}

void Room::broadcast(const std::string& message, std::shared_ptr<Session> exclude) {
    std::vector<std::shared_ptr<Session>> targets;

    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const std::shared_ptr<Session>& s : sessions_) {
            if (s != exclude && s->is_registered()) {
                targets.push_back(s);
            }
        }
    }

    debug_log("send", "broadcast from=", exclude ? exclude->username() : "-",
              " targets=", targets.size(),
              " type=", json_type(message),
              " len=", message.size());
    for (const std::shared_ptr<Session>& s : targets) {
        debug_log("send", "broadcast -> ", s->username(), " id=", s->user_id());
        s->send(message);
    }
}

void Room::replay_avatars(std::shared_ptr<Session> to) {
    std::vector<std::string> frames;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const std::shared_ptr<Session>& s : sessions_) {
            if (s != to && !s->last_avatar().empty()) {
                frames.push_back(s->last_avatar());
            }
        }
    }
    debug_log("room", "replay avatars to=", to->username(), " count=", frames.size());
    for (const std::string& frame : frames) {
        to->send(frame);
    }
}

// ============================================================================
// 查询方法
// ============================================================================

int Room::user_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int>(sessions_.size());
}

bool Room::empty() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return sessions_.empty();
}

// ============================================================================
// 获取房间内所有会话
// ============================================================================

std::vector<std::shared_ptr<Session>> Room::get_sessions() const {
    std::lock_guard<std::mutex> lock(mutex_);
    // 返回sessions_集合的副本，避免锁竞争
    return std::vector<std::shared_ptr<Session>>(sessions_.begin(), sessions_.end());
}

} // namespace kserver
