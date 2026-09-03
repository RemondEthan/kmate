#include <kserver/id_allocator.hpp>
#include <fstream>
#include <iostream>
#include <sstream>

namespace kserver {

IdAllocator::IdAllocator(std::filesystem::path file)
    : file_(std::move(file))
{
    load();
}

void IdAllocator::load() {
    next_ = kFirstHuman;
    std::ifstream in(file_);
    if (!in) {
        return;
    }
    int value = 0;
    if (!(in >> value)) {
        return;
    }
    if (value > kFirstHuman) {
        next_ = value;
    }
}

void IdAllocator::persist() const {
    try {
        if (!file_.parent_path().empty()) {
            std::filesystem::create_directories(file_.parent_path());
        }
        auto tmp = file_;
        tmp += ".tmp";
        {
            std::ofstream out(tmp, std::ios::trunc);
            if (!out) {
                std::cerr << "id_counter write open failed: " << tmp << std::endl;
                return;
            }
            out << next_ << '\n';
        }
        std::filesystem::rename(tmp, file_);
    } catch (const std::exception& e) {
        std::cerr << "id_counter persist failed: " << e.what() << std::endl;
    }
}

int IdAllocator::assign(int claimed, const std::function<bool(int)>& in_use) {
    const bool ok = claimed >= kFirstHuman
            && claimed != kSecretaryReserved
            && !(in_use && in_use(claimed));
    if (ok) {
        if (claimed >= next_) {
            next_ = claimed + 1;
            persist();
        }
        return claimed;
    }
    int id = next_++;
    persist();
    return id;
}

} // namespace kserver
