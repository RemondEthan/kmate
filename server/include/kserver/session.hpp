#pragma once

#include <boost/beast/core.hpp>
#include <boost/beast/websocket.hpp>
#include <boost/asio/ip/tcp.hpp>
#include <boost/asio/strand.hpp>
#include <memory>
#include <string>
#include <vector>

namespace kserver {

namespace beast = boost::beast;
namespace websocket = beast::websocket;
namespace net = boost::asio;
using tcp = net::ip::tcp;

class Server;
class Room;

class Session : public std::enable_shared_from_this<Session> {
public:
    Session(tcp::socket socket, std::shared_ptr<Server> server);
    ~Session();
    
    void run();
    void send(const std::string& message);
    const std::string& username() const;
    const std::string& im_code() const;
    bool is_registered() const;
    
private:
    void do_read();
    void handle_message(const std::string& message);
    void handle_register(const std::string& im_code, const std::string& username);
    void handle_text(const std::string& content);
    void do_write();
    void do_close(boost::beast::error_code ec);
    
    websocket::stream<beast::tcp_stream> ws_;
    beast::flat_buffer buffer_;
    std::shared_ptr<Server> server_;
    std::shared_ptr<Room> room_;
    std::string im_code_;
    std::string username_;
    bool registered_;
    std::vector<std::shared_ptr<std::string>> send_queue_;
    bool writing_;
};

} // namespace kserver
