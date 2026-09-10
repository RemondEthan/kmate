/**
 * @file server.cpp
 * @brief Server类的实现文件
 *
 * 本文件实现了IM服务器的核心功能：
 * 1. 异步接受WebSocket连接
 * 2. 管理聊天房间的生命周期
 * 3. 处理异步事件循环
 * 4. 集成心跳检测
 */

#include <kserver/server.hpp>
#include <kserver/session.hpp>
#include <kserver/room.hpp>
#include <kserver/heartbeat_scheduler.hpp>
#include <kserver/debug.hpp>
#include <boost/asio/error.hpp>
#include <csignal>
#include <iostream>

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

Server::Server(unsigned short port, std::filesystem::path id_file)
    : ioc_()
    , acceptor_(ioc_, tcp::endpoint(tcp::v4(), port))
    , cleanup_timer_(ioc_, std::chrono::seconds(30))
    , signals_(ioc_, SIGINT, SIGTERM)
    , id_allocator_(std::move(id_file))
{
}

Server::~Server() {
    stop();  // 析构时自动停止服务器
}

// ============================================================================
// 启动和停止
// ============================================================================

void Server::run() {
    std::cout << "KServer starting on port " << acceptor_.local_endpoint().port() << std::endl;

    heartbeat_scheduler_ = std::make_shared<HeartbeatScheduler>(ioc_, shared_from_this());

    signals_.async_wait([this](beast::error_code ec, int) {
        if (ec) {
            return;
        }
        std::cout << "Signal received, stopping..." << std::endl;
        stop();
    });

    do_accept();
    cleanup_empty_rooms();
    heartbeat_scheduler_->start();

    ioc_.run();
}

void Server::stop() {
    if (heartbeat_scheduler_) {
        heartbeat_scheduler_->stop();
    }
    beast::error_code ec;
    acceptor_.close(ec);
    cleanup_timer_.cancel(ec);
    signals_.cancel(ec);
    ioc_.stop();
}

// ============================================================================
// 异步接受连接
// ============================================================================

void Server::do_accept() {
    /**
     * async_accept - 异步接受一个新的TCP连接
     *
     * 工作流程：
     * 1. 调用async_accept()，注册回调函数
     * 2. 程序继续执行（不阻塞），ioc_.run()开始处理事件
     * 3. 当有客户端连接时，回调函数被调用
     * 4. 回调中处理新连接，然后再次调用do_accept()
     *
     * 参数说明：
     * - [this](beast::error_code ec, tcp::socket socket) 是回调lambda
     *   - ec: 错误码，如果出错则包含错误信息
     *   - socket: 新连接的socket对象（可以理解为"连接通道"）
     */
    acceptor_.async_accept(
        [this](beast::error_code ec, tcp::socket socket) {
            if (ec) {
                if (ec == net::error::operation_aborted) {
                    return;
                }
                std::cerr << "Accept error: " << ec.message() << std::endl;
                do_accept();
                return;
            }

            std::cout << "New connection from "
                      << socket.remote_endpoint().address().to_string() << std::endl;

            std::shared_ptr<Session> session = std::make_shared<Session>(
                std::move(socket), shared_from_this());
            {
                std::lock_guard<std::mutex> lock(rooms_mutex_);
                sessions_.push_back(session);
            }
            session->run();

            do_accept();
        });
}

// ============================================================================
// 房间管理
// ============================================================================

std::shared_ptr<Room> Server::get_or_create_room(const std::string& im_code) {
    /**
     * std::lock_guard - RAII风格的锁管理
     *
     * 工作原理：
     * - 构造时自动加锁（lock）
     * - 析构时自动解锁（unlock）
     * - 即使抛出异常也能保证解锁（异常安全）
     *
     * 类比：相当于进房间时"上锁"，出房间时"自动解锁"
     */
    std::lock_guard<std::mutex> lock(rooms_mutex_);

    // 在哈希表中查找房间
    std::unordered_map<std::string, std::shared_ptr<Room>>::iterator it = rooms_.find(im_code);
    if (it != rooms_.end()) {
        return it->second;  // 找到房间，返回
    }

    // 没找到，创建新房间（发号由 Server::allocate_id 负责）
    std::shared_ptr<Room> room = std::make_shared<Room>(im_code);
    rooms_[im_code] = room;  // 存入哈希表
    std::cout << "Created room: " << im_code << std::endl;
    return room;
}

int Server::allocate_id(int claimed) {
    std::lock_guard<std::mutex> lock(rooms_mutex_);
    int id = id_allocator_.assign(claimed, [this](int x) {
        return live_ids_.count(x) > 0;
    });
    live_ids_.insert(id);
    return id;
}

void Server::release_id(int id) {
    std::lock_guard<std::mutex> lock(rooms_mutex_);
    live_ids_.erase(id);
}

// ============================================================================
// 心跳检测支持
// ============================================================================

std::vector<std::shared_ptr<Session>> Server::get_timed_out_sessions(
    std::chrono::steady_clock::time_point now,
    std::chrono::steady_clock::duration timeout)
{
    std::vector<std::shared_ptr<Session>> timed_out_sessions;
    int live = 0;

    std::lock_guard<std::mutex> lock(rooms_mutex_);

    for (auto it = sessions_.begin(); it != sessions_.end(); ) {
        std::shared_ptr<Session> session = it->lock();
        if (!session) {
            it = sessions_.erase(it);
            continue;
        }
        ++live;
        auto idle_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                           now - session->last_active_time())
                           .count();
        debug_log("hb", "probe user=", session->username(),
                  " id=", session->user_id(),
                  " reg=", session->is_registered(),
                  " open=", session->is_open(),
                  " idle_ms=", idle_ms);
        if (session->is_open() && (now - session->last_active_time() > timeout)) {
            timed_out_sessions.push_back(session);
        }
        ++it;
    }

    debug_log("hb", "live=", live, " stale=", timed_out_sessions.size());
    return timed_out_sessions;
}

// ============================================================================
// 空房间清理
// ============================================================================

void Server::cleanup_empty_rooms() {
    /**
     * async_wait - 异步等待定时器
     *
     * 工作原理：
     * 1. 注册回调函数，等待指定时间
     * 2. 时间到后，回调函数被调用
     * 3. 回调中重置定时器，再次等待（实现周期性任务）
     *
     * 参数说明：
     * - [this](beast::error_code ec) 是回调lambda
     *   - ec: 错误码，定时器取消时会有错误
     */
    cleanup_timer_.async_wait([this](beast::error_code ec) {
        if (ec) {
            return;  // 定时器被取消或出错
        }

        // 在作用域内加锁，确保解锁
        {
            std::lock_guard<std::mutex> lock(rooms_mutex_);

            // 遍历所有房间，删除空房间
            // 注意：不能在范围for循环中删除元素，所以使用迭代器
            for (std::unordered_map<std::string, std::shared_ptr<Room>>::iterator it = rooms_.begin();
                 it != rooms_.end(); ) {
                if (it->second->empty()) {
                    std::cout << "Removing empty room: " << it->first << std::endl;
                    it = rooms_.erase(it);  // erase返回下一个迭代器
                } else {
                    ++it;  // 只有不删除时才递增
                }
            }
        }

        // 重置定时器，30秒后再执行
        cleanup_timer_.expires_after(std::chrono::seconds(30));
        cleanup_empty_rooms();  // 递归调用，形成周期性任务
    });
}

} // namespace kserver
