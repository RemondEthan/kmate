#include <iostream>
#include <cstdlib>
#include <memory>
#include <string>
#include <kserver/debug.hpp>
#include <kserver/server.hpp>

namespace {

void print_usage(const char* argv0) {
    std::cerr << "Usage: " << argv0 << " [port] [--debug|-d]\n"
              << "  port      listen port (default 3000)\n"
              << "  --debug   verbose heartbeat / send / recv logs on stderr\n";
}

struct Options {
    unsigned short port = 3000;
    bool debug = false;
};

Options parse_args(int argc, char* argv[]) {
    Options opt;
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--debug" || arg == "-d") {
            opt.debug = true;
            continue;
        }
        if (arg == "--help" || arg == "-h") {
            print_usage(argv[0]);
            std::exit(0);
        }
        char* end = nullptr;
        long port = std::strtol(arg.c_str(), &end, 10);
        if (end != arg.c_str() && *end == '\0' && port > 0 && port <= 65535) {
            opt.port = static_cast<unsigned short>(port);
            continue;
        }
        std::cerr << "Unknown argument: " << arg << std::endl;
        print_usage(argv[0]);
        std::exit(2);
    }
    return opt;
}

} // namespace

int main(int argc, char* argv[]) {
    Options opt = parse_args(argc, argv);
    std::cout << "KServer v1.0.0" << std::endl;
    kserver::set_debug(opt.debug);
    if (opt.debug) {
        std::cerr << "debug logging enabled (heartbeat, send, recv)" << std::endl;
    }

    try {
        std::shared_ptr<kserver::Server> server = std::make_shared<kserver::Server>(opt.port);
        server->run();
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << std::endl;
        return 1;
    }

    return 0;
}
