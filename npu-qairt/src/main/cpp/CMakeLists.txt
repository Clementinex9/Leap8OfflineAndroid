cmake_minimum_required(VERSION 3.22.1)
project(leap8_genie)

if(DEFINED HEXAGON_RUNTIME)
    add_library(leap8_hexagon_jni SHARED HexagonEssayBridge.cpp)
    target_compile_features(leap8_hexagon_jni PRIVATE cxx_std_17)
    target_compile_options(leap8_hexagon_jni PRIVATE -Wall -Wextra)
    target_link_options(leap8_hexagon_jni PRIVATE "-Wl,-z,max-page-size=16384")
    target_include_directories(leap8_hexagon_jni PRIVATE "${HEXAGON_RUNTIME}/include")
    target_link_libraries(leap8_hexagon_jni PRIVATE
        "${HEXAGON_RUNTIME}/lib/libllama.so"
        "${HEXAGON_RUNTIME}/lib/libggml.so"
        "${HEXAGON_RUNTIME}/lib/libggml-base.so"
        android log dl)
    return()
endif()

if(NOT DEFINED QAIRT_PATH OR NOT EXISTS "${QAIRT_PATH}/include/Genie/GenieDialog.h"
    OR NOT EXISTS "${QAIRT_PATH}/lib/aarch64-android/libGenie.so")
    message(FATAL_ERROR "QAIRT_PATH must point to Qualcomm AI Runtime SDK")
endif()

add_library(leap8_genie SHARED GenieEssayBridge.cpp)
target_compile_features(leap8_genie PRIVATE cxx_std_17)
target_compile_options(leap8_genie PRIVATE -Wall -Wextra)
target_link_options(leap8_genie PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(leap8_genie PRIVATE "${QAIRT_PATH}/include/Genie")
target_link_libraries(
    leap8_genie
    "${QAIRT_PATH}/lib/aarch64-android/libGenie.so"
    android
    log
)
