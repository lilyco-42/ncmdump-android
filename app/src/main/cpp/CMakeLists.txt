cmake_minimum_required(VERSION 4.0)

project("ncmcore" C CXX)

add_library(
    ncmcore
    SHARED
    ncmcrypt.cpp
    aes.cpp
    cJSON.cpp
    jni_bridge.cpp
)

target_include_directories(
    ncmcore
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(
    ncmcore
    PRIVATE
    -fexceptions
    -frtti
)

find_library(
    log-lib
    log
)

target_link_libraries(
    ncmcore
    ${log-lib}
)
