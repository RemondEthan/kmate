#pragma once

#include <atomic>
#include <chrono>
#include <ctime>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <utility>

namespace kserver {

inline std::atomic<bool>& debug_flag() {
    static std::atomic<bool> flag{false};
    return flag;
}

inline void set_debug(bool on) {
    debug_flag().store(on, std::memory_order_relaxed);
}

inline bool debug_enabled() {
    return debug_flag().load(std::memory_order_relaxed);
}

inline std::string json_type(const std::string& json) {
    const std::string key = "\"type\"";
    auto pos = json.find(key);
    if (pos == std::string::npos) {
        return "-";
    }
    pos = json.find(':', pos + key.size());
    if (pos == std::string::npos) {
        return "-";
    }
    pos = json.find('"', pos + 1);
    if (pos == std::string::npos) {
        return "-";
    }
    auto end = json.find('"', pos + 1);
    if (end == std::string::npos) {
        return "-";
    }
    return json.substr(pos + 1, end - pos - 1);
}

template<typename... Args>
inline void debug_log(const char* tag, Args&&... args) {
    if (!debug_enabled()) {
        return;
    }
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  now.time_since_epoch())
                  .count() % 1000;
    auto t = std::chrono::system_clock::to_time_t(now);
    std::tm tm{};
#if defined(_WIN32)
    localtime_s(&tm, &t);
#else
    localtime_r(&t, &tm);
#endif
    std::ostringstream oss;
    oss << std::put_time(&tm, "%H:%M:%S") << '.'
        << std::setfill('0') << std::setw(3) << ms
        << " [dbg] [" << tag << "] ";
    (oss << ... << std::forward<Args>(args));
    std::cerr << oss.str() << std::endl;
}

} // namespace kserver
