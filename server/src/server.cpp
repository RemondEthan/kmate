#include <kserver/server.hpp>
#include <kserver/session.hpp>
#include <kserver/room.hpp>
#include <iostream>

namespace kserver {

Server::Server(unsigned short port)
    : ioc_()
    , acceptor_(ioc_, tcp::endpoint(tcp::v4(), port))
    , cleanup_timer_(ioc_, std::chrono::seconds(30)) {
}

Server::~Server() {
    stop();
}

void Server::run() {
    std::cout << "KServer starting on port " << acceptor_.local_endpoint().port() << std::endl;
    do_accept();
    cleanup_empty_rooms();
    ioc_.run();
}

void Server::stop() {
    ioc_.stop();
}

void Server::do_accept() {
    acceptor_.async_accept(
        [this](beast::error_code ec, tcp::socket socket) {
            if (ec) {
                std::cerr << "Accept error: " << ec.message() << std::endl;
            } else {
                std::cout << "New connection from " 
                          << socket.remote_endpoint().address().to_string() << std::endl;
                
                std::shared_ptr<Session> session = std::make_shared<Session>(
                    std::move(socket), shared_from_this());
                session->run();
            }
            
            do_accept();
        });
}

std::shared_ptr<Room> Server::get_or_create_room(const std::string& im_code) {
    std::lock_guard<std::mutex> lock(rooms_mutex_);
    
    std::unordered_map<std::string, std::shared_ptr<Room>>::iterator it = rooms_.find(im_code);
    if (it != rooms_.end()) {
        return it->second;
    }
    
    std::shared_ptr<Room> room = std::make_shared<Room>(im_code);
    rooms_[im_code] = room;
    std::cout << "Created room: " << im_code << std::endl;
    return room;
}

void Server::cleanup_empty_rooms() {
    cleanup_timer_.async_wait([this](beast::error_code ec) {
        if (ec) {
            return;
        }
        
        {
            std::lock_guard<std::mutex> lock(rooms_mutex_);
            for (std::unordered_map<std::string, std::shared_ptr<Room>>::iterator it = rooms_.begin();
                 it != rooms_.end(); ) {
                if (it->second->empty()) {
                    std::cout << "Removing empty room: " << it->first << std::endl;
                    it = rooms_.erase(it);
                } else {
                    ++it;
                }
            }
        }
        
        cleanup_timer_.expires_after(std::chrono::seconds(30));
        cleanup_empty_rooms();
    });
}

} // namespace kserver
