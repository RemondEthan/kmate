/**
 * @file room.cpp
 * @brief Room类的实现文件
 *
 * 本文件实现了聊天房间的所有功能：
 * 1. 用户加入/离开管理
 * 2. 消息广播
 * 3. 线程安全操作
 *
 * 线程安全说明：
 * 所有方法都使用std::lock_guard加锁
 * 确保同一时间只有一个线程操作sessions_集合
 */

#include <kserver/room.hpp>
#include <kserver/session.hpp>
#include <kserver/message.hpp>
#include <iostream>

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

Room::Room(const std::string& im_code)
    : im_code_(im_code)  // 初始化房间标识码
{
}

Room::~Room() = default;  // 使用编译器生成的默认析构函数

const std::string& Room::im_code() const {
    return im_code_;
}

// ============================================================================
// 用户加入房间
// ============================================================================

bool Room::join(std::shared_ptr<Session> session) {
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

    /**
     * 通知房间内其他用户
     *
     * 遍历所有已存在的用户，发送room_joined通知
     * 注意：先通知再插入，这样新用户不会收到自己的加入通知
     */
    for (const std::shared_ptr<Session>& s : sessions_) {
        if (s != session) {  // 排除自己
            // sessions_.size() + 1 是因为还没插入新用户
            s->send(MessageParser::room_joined(session->username(), static_cast<int>(sessions_.size()) + 1));
        }
    }

    // 将新用户插入集合
    // unordered_set的insert会自动去重
    sessions_.insert(session);
    return true;
}

// ============================================================================
// 用户离开房间
// ============================================================================

void Room::leave(std::shared_ptr<Session> session) {
    std::lock_guard<std::mutex> lock(mutex_);

    // 从集合中移除用户
    sessions_.erase(session);

    // 通知房间内其他用户
    // 此时sessions_.size()已经是移除后的数量
    for (const std::shared_ptr<Session>& s : sessions_) {
        s->send(MessageParser::room_left(session->username(), static_cast<int>(sessions_.size())));
    }

    std::cout << "User " << session->username() << " left room " << im_code_
              << " (online: " << sessions_.size() << ")" << std::endl;
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

} // namespace kserver
