/**
 * @file session.cpp
 * @brief Session类的实现文件
 *
 * 本文件实现了WebSocket会话的所有功能：
 * 1. WebSocket握手和连接管理
 * 2. 异步读取消息
 * 3. 异步发送消息
 * 4. 消息处理（注册、文本转发）
 *
 * 关键概念：
 * - 所有async_*操作都是非阻塞的
 * - 回调函数在io_context的线程中执行
 * - 使用shared_from_this()确保对象生命周期安全
 */

#include <kserver/session.hpp>
#include <kserver/room.hpp>
#include <kserver/message.hpp>
#include <kserver/server.hpp>
#include <boost/asio/error.hpp>
#include <boost/beast/core/error.hpp>
#include <iostream>

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

Session::Session(tcp::socket socket, std::shared_ptr<Server> server)
    : ws_(std::move(socket))  // 移动socket的所有权给WebSocket流
    , server_(server)          // 弱引用服务器
    , user_id_(0)              // 初始用户ID为0（未分配）
    , registered_(false)       // 初始状态：未注册
    , last_active_time_(std::chrono::steady_clock::now())  // 初始化活跃时间
    , writing_(false)          // 初始状态：未在发送
    , closing_(false)
    , stop_reading_(false)
    , close_after_flush_(false)
{
}

Session::~Session() = default;

void Session::close() {
    net::post(ws_.get_executor(), [self = shared_from_this()]() {
        self->do_close(beast::error_code{});
    });
}

// ============================================================================
// 公共接口方法
// ============================================================================

const std::string& Session::username() const {
    return username_;
}

const std::string& Session::im_code() const {
    return im_code_;
}

int Session::user_id() const {
    return user_id_;
}

bool Session::is_registered() const {
    return registered_;
}

std::chrono::steady_clock::time_point Session::last_active_time() const {
    return last_active_time_;
}

void Session::update_active_time() {
    last_active_time_ = std::chrono::steady_clock::now();
}

bool Session::is_open() const {
    return ws_.is_open();
}

// ============================================================================
// 启动Session
// ============================================================================

void Session::run() {
    // design.md §11：握手与空闲超时 30 秒。关闭 keep_alive_pings，
    // 避免 pong 续命导致应用层 15 秒心跳无法踢掉静默连接。
    websocket::stream_base::timeout timeout{};
    timeout.handshake_timeout = std::chrono::seconds(30);
    timeout.idle_timeout = std::chrono::seconds(30);
    timeout.keep_alive_pings = false;
    ws_.set_option(timeout);

    /**
     * 异步接受WebSocket握手
     *
     * WebSocket连接建立过程：
     * 1. TCP连接建立（已由acceptor完成）
     * 2. HTTP Upgrade请求（WebSocket握手）
     * 3. 服务器响应101 Switching Protocols
     * 4. WebSocket连接建立完成
     *
     * async_accept()处理步骤2-3
     */
    ws_.async_accept(
        [self = shared_from_this()](beast::error_code ec) {
            if (ec) {
                std::cerr << "Accept error: " << ec.message() << std::endl;
                return;  // 握手失败，关闭Session
            }
            self->do_read();  // 握手成功，开始读取消息
        });
    // 注意：lambda捕获了self（shared_ptr），确保Session在回调期间不会被销毁
}

// ============================================================================
// 发送消息
// ============================================================================

void Session::send(const std::string& message) {
    /**
     * 创建消息的shared_ptr副本
     *
     * 为什么用shared_ptr？
     * - 消息可能在异步操作完成前被修改
     * - shared_ptr确保消息在发送期间保持有效
     */
    std::shared_ptr<std::string> msg = std::make_shared<std::string>(message);

    /**
     * net::post - 将任务投递到io_context执行
     *
     * 为什么需要post？
     * - send()可能在任意线程调用（如其他Session的回调）
     * - async_write()必须在io_context的线程中调用
     * - post确保任务在正确的线程执行
     *
     * 类比：相当于"排队"，确保操作按顺序执行
     */
    net::post(ws_.get_executor(),
        [self = shared_from_this(), msg]() {
            if (self->closing_) {
                return;
            }
            self->send_queue_.push_back(msg);

            // 如果当前没有在发送，启动发送循环
            if (!self->writing_) {
                self->writing_ = true;
                self->do_write();
            }
        });
}

// ============================================================================
// 异步读取消息
// ============================================================================

void Session::do_read() {
    /**
     * async_read - 异步读取一条完整的WebSocket消息
     *
     * 工作原理：
     * 1. 将数据读入buffer_
     * 2. 读取完成后（收到完整消息），回调函数被调用
     * 3. 回调中处理数据，然后继续读取下一条
     *
     * 注意：async_read会读取一条完整消息（可能包含多个帧）
     * 如果消息太大，会自动分片读取
     */
    ws_.async_read(buffer_,
        [self = shared_from_this()](beast::error_code ec, std::size_t bytes_transferred) {
            if (ec) {
                self->do_close(ec);  // 读取出错，关闭连接
                return;
            }

            /**
             * 将缓冲区数据转换为字符串
             *
             * beast::buffers_to_string() 将buffer中的数据复制到string
             * bytes_transferred 是实际读取的字节数
             */
            std::string msg = beast::buffers_to_string(self->buffer_.data());
            self->buffer_.consume(bytes_transferred);  // 消费已读取的数据

            self->handle_message(msg);  // 处理消息
            if (!self->closing_ && !self->stop_reading_) {
                self->do_read();  // 继续读取下一条消息（读循环）
            }
        });
}

// ============================================================================
// 消息处理
// ============================================================================

void Session::handle_message(const std::string& message) {
    // 更新活跃时间（用于心跳检测）
    update_active_time();

    /**
     * 解析JSON消息
     *
     * MessageParser::parse() 返回 optional<Message>
     * - 如果解析成功，返回包含Message的optional
     * - 如果解析失败，返回空的optional
     */
    std::optional<Message> parsed = MessageParser::parse(message);
    if (!parsed) {
        send(MessageParser::error("Invalid message format"));
        return;
    }

    /**
     * std::visit - 访问variant中的值
     *
     * Message是variant类型，包含多种消息结构体
     * std::visit使用lambda根据实际类型分发处理
     *
     * if constexpr 是编译时条件判断
     * std::is_same_v 检查类型是否相同
     */
    std::visit([this](const auto& msg) {
        using T = std::decay_t<decltype(msg)>;

        if constexpr (std::is_same_v<T, RegisterMessage>) {
            handle_register(msg.im_code, msg.username);
        }
        else if constexpr (std::is_same_v<T, TextMessage>) {
            handle_text(msg.content);
        }
        else if constexpr (std::is_same_v<T, ErrorMessage>) {
            send(MessageParser::to_string(msg));
        }
    }, *parsed);
}

void Session::handle_register(const std::string& im_code, const std::string& username) {
    if (registered_) {
        send(MessageParser::error("Already registered"));
        return;
    }

    if (im_code.empty() || username.empty()) {
        send(MessageParser::error("Missing im_code or username"));
        return;
    }

    auto server = server_.lock();
    if (!server) {
        do_close(beast::error_code{});
        return;
    }

    im_code_ = im_code;
    username_ = username;

    std::shared_ptr<Room> room = server->get_or_create_room(im_code);
    room_ = room;

    std::string padding;
    if (!room->join(shared_from_this(), user_id_, padding)) {
        room_.reset();
        send(MessageParser::error("Room is full (max 10 users)"));
        stop_reading_ = true;
        close_after_flush_ = true;
        return;
    }

    registered_ = true;
    send(MessageParser::registered(user_id_, padding));

    std::cout << "User " << username << " (ID:" << user_id_ << ") joined room " << im_code
              << " (online: " << room->user_count() << ")" << std::endl;
}

void Session::handle_text(const std::string& content) {
    if (!registered_) {
        // design.md §7.1：未注册发消息则关闭连接，不回 error
        do_close(beast::error_code{});
        return;
    }

    auto room = room_.lock();
    if (!room) {
        do_close(beast::error_code{});
        return;
    }

    // 转发时使用注册时的 username，忽略客户端 text 里自带的字段
    TextMessage msg;
    msg.type = MessageType::Text;
    msg.content = content;
    msg.username = username_;

    room->broadcast(MessageParser::to_string(msg), shared_from_this());
}

// ============================================================================
// 异步写入消息
// ============================================================================

void Session::do_write() {
    if (closing_) {
        writing_ = false;
        send_queue_.clear();
        return;
    }

    if (send_queue_.empty()) {
        writing_ = false;
        if (close_after_flush_) {
            do_close(beast::error_code{});
        }
        return;
    }

    std::shared_ptr<std::string> msg = send_queue_.front();
    send_queue_.pop_front();

    /**
     * async_write - 异步写入数据
     *
     * net::buffer(*msg) 创建缓冲区视图（不复制数据）
     * 数据在msg的shared_ptr中管理
     */
    ws_.async_write(net::buffer(*msg),
        [self = shared_from_this(), msg](beast::error_code ec, std::size_t) {
            if (ec) {
                self->do_close(ec);  // 写入出错
                return;
            }
            self->do_write();  // 继续写下一条（写循环）
        });
}

// ============================================================================
// 关闭连接
// ============================================================================

void Session::do_close(boost::beast::error_code ec) {
    if (closing_) {
        return;
    }
    const bool graceful = close_after_flush_;
    closing_ = true;
    stop_reading_ = true;
    close_after_flush_ = false;

    if (ec && !is_benign_disconnect(ec)) {
        std::cerr << "Close error: " << ec.message() << std::endl;
    }

    if (registered_) {
        if (auto room = room_.lock()) {
            room->leave(shared_from_this());
        }
        registered_ = false;
    }

    writing_ = false;
    send_queue_.clear();

    if (!ws_.is_open()) {
        return;
    }

    if (graceful) {
        // 房间已满：error 帧已写出，再走 WebSocket close handshake
        ws_.async_close(websocket::close_code::normal,
            [self = shared_from_this()](beast::error_code) {});
    } else {
        // 有未完成的 async_read 时不能再 async_close（会与读操作冲突），直接关 TCP
        beast::error_code ignored;
        beast::get_lowest_layer(ws_).socket().close(ignored);
    }
}

bool Session::is_benign_disconnect(beast::error_code ec) {
    return ec == websocket::error::closed
        || ec == net::error::eof
        || ec == net::error::connection_reset
        || ec == net::error::connection_aborted
        || ec == net::error::operation_aborted
        || ec == beast::error::timeout;
}

} // namespace kserver
