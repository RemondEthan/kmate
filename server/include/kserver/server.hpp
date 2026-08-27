#pragma once

#include <boost/beast/core.hpp>
#include <boost/beast/websocket.hpp>
#include <boost/asio/ip/tcp.hpp>
#include <boost/asio/steady_timer.hpp>
#include <memory>
#include <string>
#include <unordered_map>
#include <mutex>

namespace kserver {

namespace beast = boost::beast;
namespace websocket = beast::websocket;
namespace net = boost::asio;
using tcp = net::ip::tcp;

class Session;
class Room;

class Server : public std::enable_shared_from_this<Server> {
public:
    explicit Server(unsigned short port);
    ~Server();
    
    void run();
    void stop();
    std::shared_ptr<Room> get_or_create_room(const std::string& im_code);
    
private:
    void do_accept();
    void cleanup_empty_rooms();
    
    net::io_context ioc_;
    tcp::acceptor acceptor_;
    net::steady_timer cleanup_timer_;
    std::unordered_map<std::string, std::shared_ptr<Room>> rooms_;
    mutable std::mutex rooms_mutex_;
};

} // namespace kserver
