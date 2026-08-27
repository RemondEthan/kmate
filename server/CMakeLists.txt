cmake_minimum_required(VERSION 3.16)
project(kserver VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(FetchContent)

FetchContent_Declare(
    json
    GIT_REPOSITORY https://github.com/nlohmann/json.git
    GIT_TAG v3.11.3
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(json)

find_package(Boost 1.70 REQUIRED COMPONENTS system)
find_package(OpenSSL REQUIRED)
find_package(Threads REQUIRED)

add_executable(kserver
    src/main.cpp
    src/server.cpp
    src/session.cpp
    src/room.cpp
    src/message.cpp
)

target_include_directories(kserver PRIVATE include)
target_link_libraries(kserver PRIVATE
    Boost::system
    OpenSSL::SSL
    OpenSSL::Crypto
    Threads::Threads
    nlohmann_json::nlohmann_json
)

if(MSVC)
    target_compile_options(kserver PRIVATE /W4)
else()
    target_compile_options(kserver PRIVATE -Wall -Wextra -Wpedantic)
endif()
