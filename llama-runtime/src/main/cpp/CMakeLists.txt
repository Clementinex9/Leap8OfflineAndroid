cmake_minimum_required(VERSION 3.31.6)
project(leap8_llama_jni VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Leap8 local LLM runtime currently supports arm64-v8a only")
endif()

set(VENDOR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor")
set(PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${PREBUILT_DIR}/libllama.so")

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION "${PREBUILT_DIR}/libggml.so")

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION "${PREBUILT_DIR}/libggml-base.so")

add_library(leap8_llama_jni SHARED leap8_llama_jni.cpp)
target_include_directories(leap8_llama_jni PRIVATE
    "${VENDOR_DIR}/include"
    "${VENDOR_DIR}/ggml/include"
)
target_link_libraries(leap8_llama_jni PRIVATE llama ggml ggml-base android log dl)
