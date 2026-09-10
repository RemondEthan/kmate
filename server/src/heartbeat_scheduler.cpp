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
#include <kserver/debug.hpp>
#include <kserver/server.hpp>
#include <kserver/session.hpp>
#include <iostream>

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

HeartbeatScheduler::HeartbeatScheduler(net::io_context& ioc, std::shared_ptr<Server> server)
    : timer_(std::make_unique<net::steady_timer>(ioc))
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

    auto server = server_.lock();
    if (!server) {
        running_ = false;
        return;
    }

    auto now = std::chrono::steady_clock::now();

    debug_log("hb", "tick timeout=", HEARTBEAT_TIMEOUT_SECONDS, "s");

    std::vector<std::shared_ptr<Session>> timed_out_sessions = server->get_timed_out_sessions(
        now, std::chrono::seconds(HEARTBEAT_TIMEOUT_SECONDS));

    debug_log("hb", "timeout_hits=", timed_out_sessions.size());

    for (std::shared_ptr<Session>& session : timed_out_sessions) {
        std::cout << "Heartbeat timeout for user " << session->username()
                  << " (ID:" << session->user_id() << ")" << std::endl;
        debug_log("hb", "kick user=", session->username(),
                  " id=", session->user_id(),
                  " registered=", session->is_registered());
        session->close();
    }

    schedule_next_check();
}

void HeartbeatScheduler::schedule_next_check() {
    if (!running_) {
        return;
    }

    timer_->expires_after(std::chrono::seconds(CHECK_INTERVAL_SECONDS));

    timer_->async_wait([self = shared_from_this()](boost::system::error_code ec) {
        if (ec) {
            return;
        }
        self->check_heartbeats();
    });
}

} // namespace kserver
