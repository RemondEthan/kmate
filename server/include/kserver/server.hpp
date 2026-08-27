#pragma once

/**
 * @file server.hpp
 * @brief IM Server 主服务器类定义
 *
 * 本文件定义了Server类，它是整个IM服务器的核心，负责：
 * 1. 监听指定端口，接受WebSocket连接
 * 2. 为每个连接创建Session会话
 * 3. 管理聊天房间（Room）的创建和销毁
 *
 * 核心依赖库说明：
 * - Boost.Beast: 基于Boost.Asio的HTTP/WebSocket库，提供协议解析
 * - Boost.Asio: C++异步I/O库，处理网络、定时器等异步操作
 *
 * 异步编程模型：
 * 本服务器采用"异步事件循环"模式，所有网络操作都是非阻塞的。
 * io_context是事件循环的核心，它管理所有异步操作的调度。
 */

#include <boost/beast/core.hpp>        // Beast核心功能：error_code、flat_buffer等
#include <boost/beast/websocket.hpp>   // WebSocket协议支持
#include <boost/asio/ip/tcp.hpp>       // TCP协议支持（ip::tcp::acceptor、socket）
#include <boost/asio/steady_timer.hpp> // 定时器，用于周期性任务（如清理空房间）
#include <memory>                      // std::shared_ptr、std::make_shared
#include <string>                      // std::string
#include <unordered_map>               // std::unordered_map，哈希表容器
#include <mutex>                       // std::mutex，互斥锁，保证线程安全

namespace kserver {

// ============================================================================
// 命名空间别名：简化Boost类型名称，让代码更易读
// ============================================================================

namespace beast = boost::beast;         // beast 代替 boost::beast
namespace websocket = beast::websocket; // websocket 代替 beast::websocket
namespace net = boost::asio;            // net 代替 boost::asio
using tcp = net::ip::tcp;              // tcp 代替 net::ip::tcp

// 前向声明：告诉编译器这些类存在，但具体定义在其他文件中
class Session;  // WebSocket会话类，管理单个客户端连接
class Room;     // 聊天房间类，管理同一IM_CODE下的所有用户

/**
 * @class Server
 * @brief IM服务器主类，管理WebSocket连接和房间
 *
 * 使用示例：
 * @code
 *   Server server(3000);  // 创建服务器，监听3000端口
 *   server.run();         // 启动服务器（阻塞，直到调用stop）
 * @endcode
 *
 * 设计模式：
 * - 使用enable_shared_from_this，允许对象安全地创建shared_ptr
 *   因为Server的生命周期由shared_ptr管理，不能直接用this
 */
class Server : public std::enable_shared_from_this<Server> {
public:
    /**
     * @brief 构造函数，初始化服务器
     * @param port 监听的端口号（如3000）
     *
     * 初始化列表说明：
     * - ioc_(): 默认构造io_context，它是异步事件循环的核心
     * - acceptor_(ioc_, tcp::endpoint(tcp::v4(), port)): 创建TCP监听器
     *   - tcp::v4() 表示使用IPv4
     *   - tcp::endpoint 绑定IP和端口
     * - cleanup_timer_(ioc_, std::chrono::seconds(30)): 30秒定时器
     */
    explicit Server(unsigned short port);

    /**
     * @brief 析构函数，停止服务器
     */
    ~Server();

    /**
     * @brief 启动服务器（阻塞运行）
     *
     * 执行流程：
     * 1. 打印启动信息
     * 2. do_accept() - 开始接受WebSocket连接
     * 3. cleanup_empty_rooms() - 启动空房间清理定时器
     * 4. ioc_.run() - 进入事件循环，处理所有异步操作
     *
     * 注意：此函数会阻塞，直到调用stop()
     */
    void run();

    /**
     * @brief 停止服务器
     *
     * 通过停止io_context的事件循环来终止服务器
     */
    void stop();

    /**
     * @brief 获取或创建聊天房间
     * @param im_code 房间标识码（由客户端登录时提供）
     * @return 房间的shared_ptr
     *
     * 线程安全：使用mutex保护rooms_的访问
     */
    std::shared_ptr<Room> get_or_create_room(const std::string& im_code);

private:
    /**
     * @brief 异步接受新的WebSocket连接
     *
     * 工作原理：
     * 1. acceptor_.async_accept() 启动异步等待
     * 2. 当有新连接时，回调函数被调用
     * 3. 回调中创建Session，处理该连接
     * 4. 再次调用do_accept()，继续接受下一个连接
     *
     * 注意：这是"回调链"模式，每个连接处理完后继续监听
     */
    void do_accept();

    /**
     * @brief 定期清理空房间
     *
     * 每30秒检查一次，删除没有用户的房间
     * 使用定时器实现周期性任务
     */
    void cleanup_empty_rooms();

    // ========================================================================
    // 成员变量
    // ========================================================================

    /**
     * @brief 异步事件循环的核心
     *
     * io_context是Boost.Asio的核心，它：
     * - 管理所有异步操作（网络、定时器等）
     * - 调度回调函数的执行
     * - 提供事件循环（run方法）
     *
     * 类比：相当于一个"任务调度器"，所有异步操作都注册到这里
     */
    net::io_context ioc_;

    /**
     * @brief TCP监听器，接受新的网络连接
     *
     * acceptor负责：
     * - 绑定到指定端口
     * - 监听连接请求
     * - 异步接受连接（async_accept）
     *
     * 类比：相当于餐厅的"迎宾员"，负责接待新客人
     */
    tcp::acceptor acceptor_;

    /**
     * @brief 定时器，用于周期性清理空房间
     *
     * steady_timer是基于单调时钟的定时器，不受系统时间调整影响
     * 用于实现"每30秒清理一次空房间"的功能
     */
    net::steady_timer cleanup_timer_;

    /**
     * @brief 房间映射表：IM_CODE -> Room对象
     *
     * 存储所有活跃的聊天房间
     * 键是IM_CODE（如"OFFICE2024"），值是Room对象
     */
    std::unordered_map<std::string, std::shared_ptr<Room>> rooms_;

    /**
     * @brief 互斥锁，保护rooms_的线程安全
     *
     * 因为多个线程可能同时访问rooms_（如添加/删除房间），
     * 需要用mutex确保同一时间只有一个线程操作
     *
     * mutable允许在const成员函数中也能加锁
     */
    mutable std::mutex rooms_mutex_;
};

} // namespace kserver
