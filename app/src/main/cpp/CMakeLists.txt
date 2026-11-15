cmake_minimum_required(VERSION 3.31.6)
project(leap8_core VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Leap8 core native engine supports arm64-v8a only")
endif()

add_library(leap8_core SHARED leap8_core.cpp)
target_compile_options(leap8_core PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    -Wall
    -Wextra
    -Werror=return-type
)
target_link_options(leap8_core PRIVATE
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384"
)
target_link_libraries(leap8_core PRIVATE android log)
