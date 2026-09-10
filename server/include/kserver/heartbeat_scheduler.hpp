#pragma once

/**
 * @file heartbeat_scheduler.hpp
 * @brief 心跳调度器类定义
 *
 * 本文件定义了HeartbeatScheduler类，负责检测和清理超时的WebSocket连接。
 *
 * 心跳机制说明：
 * - 客户端发送消息时，服务器更新last_active_time
 * - 心跳调度器定期检查所有连接的活跃时间
 * - 如果超过HEARTBEAT_TIMEOUT未收到消息，自动断开连接
 *
 * 配置参数：
 * - CHECK_INTERVAL: 检查间隔（5秒）
 * - HEARTBEAT_TIMEOUT: 超时时间（90秒）
 *
 * 与Java版本的对应关系：
 * - Java: HeartbeatScheduler + ScheduledExecutorService
 * - C++: HeartbeatScheduler + boost::asio::steady_timer
 */

#include <boost/asio/steady_timer.hpp>  // boost::asio::steady_timer
#include <boost/asio/io_context.hpp>    // boost::asio::io_context
#include <memory>                       // std::shared_ptr
#include <chrono>                       // std::chrono
#include <functional>                   // std::function

namespace kserver {

namespace net = boost::asio;

// 前向声明
class Session;
class Server;

/**
 * @class HeartbeatScheduler
 * @brief 心跳调度器，定期检查并清理超时连接
 *
 * 工作流程：
 * 1. Server启动时创建HeartbeatScheduler
 * 2. 调度器每5秒检查一次所有连接
 * 3. 如果连接超过90秒未收到消息，自动断开
 * 4. 断开时触发peer_disconnected通知
 *
 * 线程安全：
 * - 所有操作都在io_context的线程中执行
 * - 通过回调函数与Server/Session交互
 */
class HeartbeatScheduler : public std::enable_shared_from_this<HeartbeatScheduler> {
public:
    /**
     * @brief 检查间隔（5秒）
     *
     * 每5秒检查一次所有连接的活跃时间
     */
    static constexpr int CHECK_INTERVAL_SECONDS = 5;

    /**
     * @brief 超时时间（90秒）
     *
     * 如果超过90秒未收到消息，断开连接。
     * 客户端后台定时器在 Windows 上可能被节流，15 秒太紧。
     */
    static constexpr int HEARTBEAT_TIMEOUT_SECONDS = 90;

    /**
     * @brief 构造函数
     * @param ioc io_context引用
     * @param server 服务器对象的shared_ptr
     */
    HeartbeatScheduler(net::io_context& ioc, std::shared_ptr<Server> server);

    /**
     * @brief 析构函数
     */
    ~HeartbeatScheduler();

    /**
     * @brief 启动心跳检查
     *
     * 开始周期性检查超时连接
     */
    void start();

    /**
     * @brief 停止心跳检查
     *
     * 停止定时器
     */
    void stop();

private:
    /**
     * @brief 执行一次心跳检查
     *
     * 检查所有连接的活跃时间，断开超时连接
     */
    void check_heartbeats();

    /**
     * @brief 安排下一次检查
     *
     * 重置定时器，等待下一个检查间隔
     */
    void schedule_next_check();

    std::unique_ptr<net::steady_timer> timer_;
    std::weak_ptr<Server> server_;
    bool running_;
};

} // namespace kserver
