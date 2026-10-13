cmake_minimum_required(VERSION 3.18.1)

project("zenz_llama")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "disable examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "disable tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "disable server" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "static libs" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "disable openmp" FORCE)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        master
)
FetchContent_MakeAvailable(llama)

add_library(
    zenz_llama
    SHARED
    zenz_llama_jni.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    zenz_llama
    llama
    ${log-lib}
)
