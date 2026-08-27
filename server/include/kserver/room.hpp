#pragma once

/**
 * @file room.hpp
 * @brief 聊天房间类定义
 *
 * 本文件定义了Room类，它管理同一IM_CODE下的所有用户。
 * 每个Room对应一个聊天群组，支持：
 * 1. 用户加入/离开
 * 2. 消息广播（发送给房间所有人）
 * 3. Padding生成和分发（用于密钥派生）
 * 4. User ID分配
 * 5. 线程安全操作
 *
 * 设计说明：
 * - 每个Room最多支持10个用户（MAX_USERS）
 * - 使用mutex保证多线程安全
 * - 使用unordered_set存储用户，自动去重
 * - Padding在首个用户加入时生成，最后一个用户离开时清理
 */

#include <memory>                   // std::shared_ptr
#include <string>                   // std::string
#include <unordered_set>            // std::unordered_set，哈希集合
#include <mutex>                    // std::mutex，互斥锁
#include <atomic>                   // std::atomic，原子操作
#include <vector>                   // std::vector

namespace kserver {

// 前向声明
class Session;

/**
 * @class Room
 * @brief 聊天房间类，管理同一IM_CODE下的所有用户
 *
 * 线程安全说明：
 * 所有公共方法都使用std::lock_guard加锁
 * 确保同一时间只有一个线程操作sessions_集合
 *
 * 使用场景：
 * 1. 用户调用join()加入房间，获取user_id和padding
 * 2. 用户调用leave()离开房间
 * 3. 用户发送消息时，调用broadcast()转发给其他人
 */
class Room : public std::enable_shared_from_this<Room> {
public:
    /**
     * @brief 房间最大用户数
     *
     * static constexpr 表示编译时常量
     * 所有Room对象共享这个值
     */
    static constexpr int MAX_USERS = 10;

    /**
     * @brief 构造函数
     * @param im_code 房间标识码
     * @param id_counter 自增ID计数器的引用
     *
     * 注意：id_counter由Server持有，所有Room共享
     */
    explicit Room(const std::string& im_code, std::atomic<int>& id_counter);

    /**
     * @brief 析构函数
     */
    ~Room();

    /**
     * @brief 用户加入房间
     * @param session 要加入的会话
     * @param user_id [out] 输出参数，返回分配的用户ID
     * @param padding [out] 输出参数，返回padding字符串
     * @return true表示成功，false表示房间已满
     *
     * 副作用：
     * - 分配user_id
     * - 生成/获取padding
     * - 向房间内其他用户发送peer_connected通知
     */
    bool join(std::shared_ptr<Session> session, int& user_id, std::string& padding);

    /**
     * @brief 用户离开房间
     * @param session 要离开的会话
     *
     * 副作用：
     * - 向房间内其他用户发送peer_disconnected通知
     * - 如果是最后一个用户，清理padding
     */
    void leave(std::shared_ptr<Session> session);

    /**
     * @brief 广播消息给房间内所有人
     * @param message 要发送的消息（JSON字符串）
     * @param exclude 要排除的用户（可选，用于排除发送者自己）
     *
     * 注意：
     * - 消息会发送给所有已注册的用户
     * - 可以通过exclude参数排除特定用户
     */
    void broadcast(const std::string& message, std::shared_ptr<Session> exclude = nullptr);

    /**
     * @brief 获取当前在线人数
     * @return 在线用户数
     */
    int user_count() const;

    /**
     * @brief 检查房间是否为空
     * @return true表示没有用户
     */
    bool empty() const;

    /**
     * @brief 获取房间标识码
     * @return IM_CODE字符串
     */
    const std::string& im_code() const;

    /**
     * @brief 获取房间内所有会话
     * @return 会话集合的副本
     *
     * 用于心跳检测，返回所有在线用户的Session
     */
    std::vector<std::shared_ptr<Session>> get_sessions() const;

private:
    /**
     * @brief 生成8字节随机padding
     * @return Base64编码的padding字符串
     *
     * 使用OpenSSL RAND_bytes生成随机数
     */
    std::string generate_padding();

    /**
     * @brief 将二进制数据转换为Base64字符串
     * @param data 二进制数据
     * @return Base64字符串
     */
    std::string to_base64(const std::vector<unsigned char>& data);

    /**
     * @brief 房间标识码（IM_CODE）
     *
     * 由客户端登录时提供，用于匹配聊天对象
     * 例如："OFFICE2024"、"FAMILY"
     */
    std::string im_code_;

    /**
     * @brief 互斥锁，保护sessions_的线程安全
     *
     * mutable允许在const成员函数中也能加锁
     * 因为user_count()和empty()是const函数，但也需要加锁
     */
    mutable std::mutex mutex_;

    /**
     * @brief 房间内的会话集合
     *
     * 使用unordered_set的原因：
     * - 自动去重（同一Session不会重复添加）
     * - O(1)的插入/删除/查找复杂度
     * - 存储shared_ptr，管理Session的生命周期
     */
    std::unordered_set<std::shared_ptr<Session>> sessions_;

    /**
     * @brief Padding字符串
     *
     * 首个用户加入时生成，最后一个用户离开时清理
     * 同一IM_CODE的所有用户共享此padding
     */
    std::string padding_;

    /**
     * @brief 自增ID计数器的引用
     *
     * 由Server持有，所有Room共享
     * 每次有新用户加入时，原子递增
     */
    std::atomic<int>& id_counter_;
};

} // namespace kserver
