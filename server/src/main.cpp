#include <iostream>
#include <cstdlib>
#include <memory>
#include <kserver/server.hpp>

int main(int argc, char* argv[]) {
    std::cout << "KServer v1.0.0" << std::endl;

    unsigned short port = 3000;
    if (argc > 1) {
        port = static_cast<unsigned short>(std::atoi(argv[1]));
    }

    try {
        // 必须使用shared_ptr创建Server，因为需要使用shared_from_this()
        std::shared_ptr<kserver::Server> server = std::make_shared<kserver::Server>(port);
        server->run();
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        return 1;
    }

    return 0;
}
