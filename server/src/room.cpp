/**
 * @file room.cpp
 * @brief Room类的实现文件
 *
 * 本文件实现了聊天房间的所有功能：
 * 1. 用户加入/离开管理
 * 2. 消息广播
 * 3. Padding生成和管理
 * 4. User ID分配
 * 5. 线程安全操作
 *
 * 线程安全说明：
 * 所有方法都使用std::lock_guard加锁
 * 确保同一时间只有一个线程操作sessions_集合
 */

#include <kserver/room.hpp>
#include <kserver/session.hpp>
#include <kserver/message.hpp>
#include <iostream>
#include <openssl/rand.h>  // RAND_bytes

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

Room::Room(const std::string& im_code, std::atomic<int>& id_counter)
    : im_code_(im_code)         // 初始化房间标识码
    , id_counter_(id_counter)   // 引用Server的ID计数器
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

bool Room::join(std::shared_ptr<Session> session, int& user_id, std::string& padding) {
    /**
     * std::lock_guard - RAII风格的锁管理
     *
     * 构造时加锁，析构时解锁
     * 即使函数抛出异常，也能保证解锁（异常安全）
     */
    std::lock_guard<std::mutex> lock(mutex_);

    // 检查是否达到最大用户数
    // static_cast<int> 将size_t转换为int，避免有符号/无符号比较警告
    if (static_cast<int>(sessions_.size()) >= MAX_USERS) {
        return false;  // 房间已满
    }

    // 分配user_id
    user_id = id_counter_++;

    // 如果是首个用户，生成padding
    if (sessions_.empty()) {
        padding_ = generate_padding();
        std::cout << "Generated padding for room " << im_code_ << ": " << padding_ << std::endl;
    }

    // 返回padding
    padding = padding_;

    // 通知房间内其他用户
    for (const std::shared_ptr<Session>& s : sessions_) {
        if (s != session) {
            s->send(MessageParser::peer_connected(user_id, session->username()));
        }
    }

    // 将新用户插入集合
    sessions_.insert(session);
    id_to_session_[user_id] = session;

    return true;
}

// ============================================================================
// 用户离开房间
// ============================================================================

void Room::leave(std::shared_ptr<Session> session) {
    std::lock_guard<std::mutex> lock(mutex_);

    // 记录离开的用户信息
    int leaving_user_id = session->user_id();
    std::string leaving_username = session->username();

    // 从集合中移除用户
    sessions_.erase(session);
    id_to_session_.erase(leaving_user_id);

    // 通知房间内其他用户
    for (const std::shared_ptr<Session>& s : sessions_) {
        s->send(MessageParser::peer_disconnected(leaving_user_id, leaving_username));
    }

    std::cout << "User " << leaving_username << " (ID:" << leaving_user_id
              << ") left room " << im_code_
              << " (online: " << sessions_.size() << ")" << std::endl;

    // 如果是最后一个用户，清理padding
    if (sessions_.empty()) {
        padding_.clear();
        std::cout << "Cleared padding for room " << im_code_ << std::endl;
    }
}

// ============================================================================
// 消息广播
// ============================================================================

void Room::broadcast(const std::string& message, std::shared_ptr<Session> exclude) {
    std::lock_guard<std::mutex> lock(mutex_);

    /**
     * 遍历所有用户，发送消息
     *
     * 条件：
     * - s != exclude: 排除指定用户（通常是发送者自己）
     * - s->is_registered(): 只发送给已注册的用户
     *
     * 注意：broadcast可能在其他线程被调用
     * Session::send()内部使用net::post确保线程安全
     */
    for (const std::shared_ptr<Session>& s : sessions_) {
        if (s != exclude && s->is_registered()) {
            s->send(message);
        }
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
