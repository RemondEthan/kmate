#pragma once

/**
 * @file session.hpp
 * @brief WebSocket会话类定义
 *
 * 本文件定义了Session类，它管理单个客户端的WebSocket连接。
 * 每个连接的客户端都有一个对应的Session对象。
 *
 * Session的核心职责：
 * 1. 接受WebSocket连接（async_accept）
 * 2. 异步读取客户端消息（async_read）
 * 3. 异步发送消息给客户端（async_write）
 * 4. 处理消息（解析JSON、转发给房间）
 * 5. 管理连接生命周期（注册、断开）
 *
 * 异步I/O模型说明：
 * 所有网络操作都是异步的，通过回调函数处理结果。
 * 操作不会阻塞线程，而是注册到io_context，由事件循环调度执行。
 */

#include <boost/beast/core.hpp>       // beast核心：error_code、flat_buffer、tcp_stream
#include <boost/beast/websocket.hpp>  // WebSocket支持：stream、close_code等
#include <boost/asio/ip/tcp.hpp>      // TCP支持：socket、endpoint
#include <boost/asio/strand.hpp>      // 串行器，确保回调顺序执行（避免竞态）
#include <memory>                     // std::shared_ptr、std::enable_shared_from_this
#include <string>                     // std::string
#include <vector>                     // std::vector，用于发送队列

namespace kserver {

// 命名空间别名（与server.hpp一致）
namespace beast = boost::beast;
namespace websocket = beast::websocket;
namespace net = boost::asio;
using tcp = net::ip::tcp;

// 前向声明
class Server;
class Room;

/**
 * @class Session
 * @brief WebSocket会话类，管理单个客户端连接
 *
 * 生命周期：
 * 1. Server::do_accept() 创建Session
 * 2. Session::run() 启动异步操作
 * 3. 客户端发送register消息，Session加入房间
 * 4. 客户端发送text消息，Session转发给房间
 * 5. 连接断开时，Session从房间移除
 *
 * 线程安全：
 * - 所有异步回调都在io_context的线程中执行
 * - 通过net::post确保发送操作串行执行
 * - 房间操作由Room类保证线程安全
 *
 * 使用enable_shared_from_this：
 * 因为Session的生命周期由shared_ptr管理，不能直接用this。
 * 需要创建shared_ptr时，使用shared_from_this()获取。
 */
class Session : public std::enable_shared_from_this<Session> {
public:
    /**
     * @brief 构造函数
     * @param socket 已连接的TCP socket（已通过acceptor.accept()接受）
     * @param server 服务器对象的shared_ptr
     *
     * 注意：socket是通过std::move传入的，所有权转移给Session
     */
    Session(tcp::socket socket, std::shared_ptr<Server> server);

    /**
     * @brief 析构函数
     *
     * 如果Session还在房间中，自动离开
     */
    ~Session();

    /**
     * @brief 启动Session（开始异步操作）
     *
     * 执行流程：
     * 1. 设置WebSocket超时选项
     * 2. 异步接受WebSocket握手（async_accept）
     * 3. 握手成功后，开始异步读取消息
     */
    void run();

    /**
     * @brief 异步发送消息给客户端
     * @param message 要发送的字符串（通常是JSON）
     *
     * 线程安全：使用net::post确保发送操作串行执行
     */
    void send(const std::string& message);

    /**
     * @brief 获取用户名
     * @return 用户名字符串
     */
    const std::string& username() const;

    /**
     * @brief 获取IM_CODE（房间标识）
     * @return IM_CODE字符串
     */
    const std::string& im_code() const;

    /**
     * @brief 检查是否已注册（已加入房间）
     * @return true表示已注册，false表示未注册
     */
    bool is_registered() const;

private:
    // ========================================================================
    // 异步操作方法
    // ========================================================================

    /**
     * @brief 异步读取客户端消息
     *
     * 工作流程：
     * 1. 调用async_read()，等待客户端发送数据
     * 2. 数据到达后，回调函数被调用
     * 3. 将缓冲区数据转换为字符串
     * 4. 调用handle_message()处理消息
     * 5. 再次调用do_read()，继续读取下一条消息
     *
     * 注意：这是"读循环"模式，持续监听客户端消息
     */
    void do_read();

    /**
     * @brief 处理接收到的消息
     * @param message 原始消息字符串（JSON格式）
     *
     * 流程：
     * 1. 使用MessageParser解析JSON
     * 2. 根据消息类型分发处理（register或text）
     */
    void handle_message(const std::string& message);

    /**
     * @brief 处理注册消息
     * @param im_code 客户端请求加入的房间标识
     * @param username 客户端的用户名
     *
     * 流程：
     * 1. 检查是否已注册
     * 2. 获取或创建房间
     * 3. 尝试加入房间（可能失败，如房间已满）
     * 4. 标记为已注册
     */
    void handle_register(const std::string& im_code, const std::string& username);

    /**
     * @brief 处理文本消息
     * @param content 加密后的消息内容
     *
     * 流程：
     * 1. 检查是否已注册
     * 2. 构建TextMessage对象
     * 3. 调用room_->broadcast()转发给房间其他人
     */
    void handle_text(const std::string& content);

    /**
     * @brief 异步写入消息给客户端
     *
     * 工作流程：
     * 1. 从send_queue_取出一条消息
     * 2. 调用async_write()异步发送
     * 3. 发送完成后，回调函数被调用
     * 4. 继续发送下一条消息（如果队列不为空）
     *
     * 注意：使用队列确保消息按顺序发送
     */
    void do_write();

    /**
     * @brief 关闭连接
     * @param ec 错误码（如果有错误）
     *
     * 清理工作：
     * 1. 打印错误信息（如果有）
     * 2. 从房间移除
     * 3. 关闭WebSocket连接
     */
    void do_close(boost::beast::error_code ec);

    // ========================================================================
    // 成员变量
    // ========================================================================

    /**
     * @brief WebSocket流对象
     *
     * websocket::stream<tcp_stream> 是Boost.Beast的核心类型：
     * - tcp_stream: 底层TCP流，处理网络I/O
     * - websocket::stream: 在TCP上实现WebSocket协议
     *
     * 它封装了：
     * - WebSocket握手（HTTP Upgrade）
     * - 帧的解析和组装
     * - 消息的分片和重组
     * - 心跳（ping/pong）
     */
    websocket::stream<beast::tcp_stream> ws_;

    /**
     * @brief 读取缓冲区
     *
     * flat_buffer是Beast的缓冲区类型：
     * - 自动管理内存
     * - 支持数据追加
     * - 可以直接传递给async_read()
     *
     * 数据流：socket -> buffer -> 转换为string -> 处理
     */
    beast::flat_buffer buffer_;

    /**
     * @brief 服务器对象的引用
     *
     * 用于调用server_->get_or_create_room()获取房间
     */
    std::shared_ptr<Server> server_;

    /**
     * @brief 当前加入的房间
     *
     * 如果未注册，则为nullptr
     */
    std::shared_ptr<Room> room_;

    /**
     * @brief 客户端请求的IM_CODE（房间标识）
     */
    std::string im_code_;

    /**
     * @brief 客户端的用户名
     */
    std::string username_;

    /**
     * @brief 是否已注册（已加入房间）
     *
     * true: 已加入房间，可以发送消息
     * false: 未注册，只能发送register消息
     */
    bool registered_;

    /**
     * @brief 发送消息队列
     *
     * 存储待发送的消息（shared_ptr<string>）
     * 使用队列的原因：
     * - send()可能在任意线程调用
     * - async_write()必须串行执行
     * - 队列保证消息顺序发送
     */
    std::vector<std::shared_ptr<std::string>> send_queue_;

    /**
     * @brief 是否正在发送消息
     *
     * true: 正在执行do_write()，不需要启动新的do_write()
     * false: 空闲，有新消息时需要启动do_write()
     */
    bool writing_;
};

} // namespace kserver
