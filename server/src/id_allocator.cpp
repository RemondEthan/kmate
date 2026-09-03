#include <kserver/id_allocator.hpp>
#include <climits>
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
        bool wrote = false;
        {
            std::ofstream out(tmp, std::ios::trunc);
            if (!out) {
                std::cerr << "id_counter write open failed: " << tmp << std::endl;
                return;
            }
            out << next_ << '\n';
            wrote = static_cast<bool>(out.good());
            if (!wrote) {
                std::cerr << "id_counter write failed: " << tmp << std::endl;
            }
        }
        if (!wrote) {
            std::error_code ec;
            std::filesystem::remove(tmp, ec);
            return;
        }
#if defined(_WIN32)
        if (std::filesystem::exists(file_)) {
            std::filesystem::remove(file_);
        }
#endif
        std::filesystem::rename(tmp, file_);
    } catch (const std::exception& e) {
        std::cerr << "id_counter persist failed: " << e.what() << std::endl;
    }
}

int IdAllocator::assign(int claimed, const std::function<bool(int)>& in_use) {
    const bool ok = claimed >= kFirstHuman
            && claimed < INT_MAX
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
