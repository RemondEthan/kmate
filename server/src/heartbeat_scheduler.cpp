/**
 * @file heartbeat_scheduler.cpp
 * @brief HeartbeatScheduler类的实现文件
 *
 * 本文件实现了心跳检测功能：
 * 1. 定期检查所有连接的活跃时间
 * 2. 断开超时的连接
 * 3. 通知房间内其他用户
 *
 * 与Java版本的对应关系：
 * - Java: HeartbeatScheduler.run() 使用ScheduledExecutorService
 * - C++: HeartbeatScheduler::start() 使用boost::asio::steady_timer
 */

#include <kserver/heartbeat_scheduler.hpp>
#include <kserver/server.hpp>
#include <kserver/session.hpp>
#include <iostream>

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

HeartbeatScheduler::HeartbeatScheduler(net::io_context& ioc, std::shared_ptr<Server> server)
    : ioc_(ioc)
    , timer_(std::make_unique<net::steady_timer>(ioc))
    , server_(server)
    , running_(false)
{
}

HeartbeatScheduler::~HeartbeatScheduler() {
    stop();
}

// ============================================================================
// 启动和停止
// ============================================================================

void HeartbeatScheduler::start() {
    if (running_) {
        return;  // 已经在运行
    }

    running_ = true;
    std::cout << "Heartbeat scheduler started (interval: "
              << CHECK_INTERVAL_SECONDS << "s, timeout: "
              << HEARTBEAT_TIMEOUT_SECONDS << "s)" << std::endl;

    schedule_next_check();
}

void HeartbeatScheduler::stop() {
    running_ = false;
    if (timer_) {
        // 取消定时器
        boost::system::error_code ec;
        timer_->cancel(ec);
    }
}

// ============================================================================
// 心跳检查
// ============================================================================

void HeartbeatScheduler::check_heartbeats() {
    if (!running_) {
        return;
    }

    // 获取当前时间
    auto now = std::chrono::steady_clock::now();

    // 获取所有超时的连接
    std::vector<std::shared_ptr<Session>> timed_out_sessions = server_->get_timed_out_sessions(
        now, std::chrono::seconds(HEARTBEAT_TIMEOUT_SECONDS));

    // 断开超时的连接
    for (std::shared_ptr<Session>& session : timed_out_sessions) {
        std::cout << "Heartbeat timeout for user " << session->username()
                  << " (ID:" << session->user_id() << ")" << std::endl;

        // 关闭WebSocket连接
        // 这会触发Session::do_close()，自动从房间移除
        session->send("");  // 发送空消息触发关闭
        // 注意：实际断开由Session的读写错误处理
    }

    // 安排下一次检查
    schedule_next_check();
}

void HeartbeatScheduler::schedule_next_check() {
    if (!running_) {
        return;
    }

    // 重置定时器
    timer_->expires_after(std::chrono::seconds(CHECK_INTERVAL_SECONDS));

    // 异步等待
    timer_->async_wait([this](boost::system::error_code ec) {
        if (ec) {
            // 定时器被取消或出错
            return;
        }

        check_heartbeats();
    });
}

} // namespace kserver
