cmake_minimum_required(VERSION 3.22)
project(nikita_tokenizer LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(sentencepiece
    URL "https://codeload.github.com/google/sentencepiece/tar.gz/refs/tags/v0.2.1"
    URL_HASH SHA256=c1a59e9259c9653ad0ade653dadff074cd31f0a6ff2a11316f67bee4189a8f1b)
FetchContent_MakeAvailable(sentencepiece)

add_library(nikita_sentencepiece SHARED tokenizer.cpp)
target_include_directories(nikita_sentencepiece PRIVATE "${sentencepiece_SOURCE_DIR}/src")
target_link_libraries(nikita_sentencepiece PRIVATE sentencepiece-static)
if(ANDROID)
    target_link_libraries(nikita_sentencepiece PRIVATE log)
    target_link_options(nikita_sentencepiece PRIVATE
        "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "-Wl,--exclude-libs,ALL")
else()
    find_package(JNI REQUIRED)
    target_include_directories(nikita_sentencepiece PRIVATE ${JNI_INCLUDE_DIRS})
endif()
