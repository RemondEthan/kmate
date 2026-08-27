/**
 * @file server.cpp
 * @brief Server类的实现文件
 *
 * 本文件实现了IM服务器的核心功能：
 * 1. 异步接受WebSocket连接
 * 2. 管理聊天房间的生命周期
 * 3. 处理异步事件循环
 */

#include <kserver/server.hpp>
#include <kserver/session.hpp>
#include <kserver/room.hpp>
#include <iostream>

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

Server::Server(unsigned short port)
    : ioc_()                          // 默认构造io_context（事件循环核心）
    , acceptor_(ioc_, tcp::endpoint(tcp::v4(), port))  // 创建TCP监听器
    , cleanup_timer_(ioc_, std::chrono::seconds(30))   // 30秒清理定时器
{
    // acceptor_初始化说明：
    // - tcp::v4() 表示使用IPv4协议
    // - tcp::endpoint(port) 创建端点，绑定到所有网络接口的指定端口
    // - 相当于在端口上"开门"，等待客户端连接
}

Server::~Server() {
    stop();  // 析构时自动停止服务器
}

// ============================================================================
// 启动和停止
// ============================================================================

void Server::run() {
    std::cout << "KServer starting on port " << acceptor_.local_endpoint().port() << std::endl;

    do_accept();           // 开始接受WebSocket连接（异步）
    cleanup_empty_rooms(); // 启动空房间清理定时器

    /**
     * ioc_.run() - 进入事件循环（阻塞）
     *
     * 这是程序的"心脏"，它会：
     * 1. 等待异步操作完成（如新连接、数据到达、定时器触发）
     * 2. 调用对应的回调函数
     * 3. 重复1-2，直到调用ioc_.stop()
     *
     * 类比：相当于一个"消息泵"，不断处理各种事件
     */
    ioc_.run();
}

void Server::stop() {
    ioc_.stop();  // 停止事件循环，run()会返回
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
                std::cerr << "Accept error: " << ec.message() << std::endl;
                return;  // 出错时不再继续监听
            }

            std::cout << "New connection from "
                      << socket.remote_endpoint().address().to_string() << std::endl;

            /**
             * 创建Session对象处理这个连接
             *
             * std::move(socket) - 移动socket的所有权给Session
             *   - socket不能复制，只能移动（move语义）
             *   - 移动后原socket对象变为无效
             *
             * shared_from_this() - 获取Server的shared_ptr
             *   - 因为Server继承了enable_shared_from_this
             *   - 这样Session可以安全地引用Server
             */
            std::shared_ptr<Session> session = std::make_shared<Session>(
                std::move(socket), shared_from_this());
            session->run();  // 启动Session的异步读写

            do_accept();  // 继续接受下一个连接（回调链）
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

    // 没找到，创建新房间
    std::shared_ptr<Room> room = std::make_shared<Room>(im_code);
    rooms_[im_code] = room;  // 存入哈希表
    std::cout << "Created room: " << im_code << std::endl;
    return room;
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
