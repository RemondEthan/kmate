#pragma once

#include <filesystem>
#include <functional>
#include <string>

namespace kserver {

class IdAllocator {
public:
    static constexpr int kFirstHuman = 200000;
    static constexpr int kSecretaryReserved = 100778;

    explicit IdAllocator(std::filesystem::path file);

    int assign(int claimed, const std::function<bool(int)>& in_use);
    int next() const { return next_; }

private:
    void load();
    void persist() const;

    std::filesystem::path file_;
    int next_ = kFirstHuman;
};

} // namespace kserver
