cmake_minimum_required(VERSION 3.16)
project(kserver VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# 本地依赖
set(THIRD_PARTY_DIR ${CMAKE_SOURCE_DIR}/third_party)

# OpenSSL
find_package(OpenSSL REQUIRED)
find_package(Threads REQUIRED)

# 可执行文件
add_executable(kserver
    src/main.cpp
    src/server.cpp
    src/session.cpp
    src/room.cpp
    src/message.cpp
)

target_include_directories(kserver PRIVATE 
    include
    ${THIRD_PARTY_DIR}
)
target_link_libraries(kserver PRIVATE
    OpenSSL::SSL
    OpenSSL::Crypto
    Threads::Threads
)

if(MSVC)
    target_compile_options(kserver PRIVATE /W4)
else()
    target_compile_options(kserver PRIVATE -Wall -Wextra -Wpedantic)
endif()
