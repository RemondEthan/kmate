#include <kserver/session.hpp>
#include <kserver/room.hpp>
#include <kserver/message.hpp>
#include <kserver/server.hpp>
#include <iostream>

namespace kserver {

Session::Session(tcp::socket socket, std::shared_ptr<Server> server)
    : ws_(std::move(socket))
    , server_(server)
    , registered_(false)
    , writing_(false) {
}

Session::~Session() {
    if (registered_ && room_) {
        room_->leave(shared_from_this());
    }
}

const std::string& Session::username() const {
    return username_;
}

const std::string& Session::im_code() const {
    return im_code_;
}

bool Session::is_registered() const {
    return registered_;
}

void Session::run() {
    ws_.set_option(websocket::stream_base::timeout::suggested(beast::role_type::server));
    
    ws_.async_accept(
        [self = shared_from_this()](beast::error_code ec) {
            if (ec) {
                std::cerr << "Accept error: " << ec.message() << std::endl;
                return;
            }
            self->do_read();
        });
}

void Session::send(const std::string& message) {
    std::shared_ptr<std::string> msg = std::make_shared<std::string>(message);
    
    net::post(ws_.get_executor(),
        [self = shared_from_this(), msg]() {
            self->send_queue_.push_back(msg);
            if (!self->writing_) {
                self->writing_ = true;
                self->do_write();
            }
        });
}

void Session::do_read() {
    ws_.async_read(buffer_,
        [self = shared_from_this()](beast::error_code ec, std::size_t bytes_transferred) {
            if (ec) {
                self->do_close(ec);
                return;
            }
            
            std::string msg = beast::buffers_to_string(self->buffer_.data());
            self->buffer_.consume(bytes_transferred);
            
            self->handle_message(msg);
            self->do_read();
        });
}

void Session::handle_message(const std::string& message) {
    std::optional<Message> parsed = MessageParser::parse(message);
    if (!parsed) {
        send(MessageParser::error("Invalid message format"));
        return;
    }
    
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
    if (registered_) {
        send(MessageParser::error("Already registered"));
        return;
    }
    
    im_code_ = im_code;
    username_ = username;
    room_ = server_->get_or_create_room(im_code);
    
    if (!room_->join(shared_from_this())) {
        send(MessageParser::error("Room is full (max 10 users)"));
        ws_.close(websocket::close_code::normal);
        return;
    }
    
    registered_ = true;
    std::cout << "User " << username << " joined room " << im_code 
              << " (online: " << room_->user_count() << ")" << std::endl;
}

void Session::handle_text(const std::string& content) {
    if (!registered_) {
        send(MessageParser::error("Not registered"));
        return;
    }
    
    TextMessage msg;
    msg.type = MessageType::Text;
    msg.content = content;
    msg.username = username_;
    
    room_->broadcast(MessageParser::to_string(msg), shared_from_this());
}

void Session::do_write() {
    if (send_queue_.empty()) {
        writing_ = false;
        return;
    }
    
    std::shared_ptr<std::string> msg = send_queue_.front();
    send_queue_.erase(send_queue_.begin());
    
    ws_.async_write(net::buffer(*msg),
        [self = shared_from_this(), msg](beast::error_code ec, std::size_t) {
            if (ec) {
                self->do_close(ec);
                return;
            }
            self->do_write();
        });
}

void Session::do_close(boost::beast::error_code ec) {
    if (ec && ec != websocket::error::closed) {
        std::cerr << "Close error: " << ec.message() << std::endl;
    }
    
    if (registered_ && room_) {
        room_->leave(shared_from_this());
        registered_ = false;
    }
}

} // namespace kserver
