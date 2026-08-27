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
#include <iostream>

namespace kserver {

// ============================================================================
// 构造函数和析构函数
// ============================================================================

Session::Session(tcp::socket socket, std::shared_ptr<Server> server)
    : ws_(std::move(socket))  // 移动socket的所有权给WebSocket流
    , server_(server)          // 保存服务器引用
    , user_id_(0)              // 初始用户ID为0（未分配）
    , registered_(false)       // 初始状态：未注册
    , last_active_time_(std::chrono::steady_clock::now())  // 初始化活跃时间
    , writing_(false)          // 初始状态：未在发送
{
    // std::move(socket) 将socket的所有权转移给ws_
    // 移动后socket变为无效，不能再使用
}

Session::~Session() {
    // 析构时如果还在房间中，自动离开
    if (registered_ && room_) {
        room_->leave(shared_from_this());
    }
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
    /**
     * 设置WebSocket超时选项
     *
     * timeout::suggested() 返回推荐的超时设置：
     * - 空闲超时: 60秒
     * - 点对点消息超时: 300秒
     * - 关闭超时: 300秒
     *
     * role_type::server 表示这是服务器端
     */
    ws_.set_option(websocket::stream_base::timeout::suggested(beast::role_type::server));

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
            self->do_read();  // 继续读取下一条消息（读循环）
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
    }, *parsed);
}

void Session::handle_register(const std::string& im_code, const std::string& username) {
    // 防止重复注册
    if (registered_) {
        send(MessageParser::error("Already registered"));
        return;
    }

    im_code_ = im_code;
    username_ = username;

    // 获取或创建房间
    room_ = server_->get_or_create_room(im_code);

    // 分配user_id并获取padding
    std::string padding;
    if (!room_->join(shared_from_this(), user_id_, padding)) {
        // 房间已满
        send(MessageParser::error("Room is full (max 10 users)"));
        ws_.close(websocket::close_code::normal);  // 关闭WebSocket
        return;
    }

    registered_ = true;

    // 发送registered消息（含user_id和padding）
    send(MessageParser::registered(user_id_, padding));

    std::cout << "User " << username << " (ID:" << user_id_ << ") joined room " << im_code
              << " (online: " << room_->user_count() << ")" << std::endl;
}

void Session::handle_text(const std::string& content) {
    if (!registered_) {
        send(MessageParser::error("Not registered"));
        return;
    }

    // 构建文本消息
    TextMessage msg;
    msg.type = MessageType::Text;
    msg.content = content;  // 注意：content是加密后的密文
    msg.username = username_;

    // 广播给房间其他人（排除自己）
    room_->broadcast(MessageParser::to_string(msg), shared_from_this());
}

// ============================================================================
// 异步写入消息
// ============================================================================

void Session::do_write() {
    // 检查发送队列是否为空
    if (send_queue_.empty()) {
        writing_ = false;  // 标记为空闲
        return;
    }

    // 取出队首消息
    std::shared_ptr<std::string> msg = send_queue_.front();
    send_queue_.erase(send_queue_.begin());

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
    // 只打印非正常的关闭错误
    if (ec && ec != websocket::error::closed) {
        std::cerr << "Close error: " << ec.message() << std::endl;
    }

    // 如果已注册，从房间移除
    if (registered_ && room_) {
        room_->leave(shared_from_this());
        registered_ = false;
    }
}

} // namespace kserver
