cmake_minimum_required(VERSION 3.22.1)
project(ime_engines C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 内部 C/C++ シンボルが .so 外部に露出（エクスポート）するのを防ぐ設定
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

include(FetchContent)

# ----------------------------------------------------
# 1. Zenz (ensan-hcl/llama.cpp カスタマイズ版)
# ----------------------------------------------------
message(STATUS "Fetching Zenz llama.cpp source...")
FetchContent_Declare(
    llama_zenz
    GIT_REPOSITORY https://github.com/ensan-hcl/llama.cpp
    GIT_TAG        master
)

FetchContent_GetProperties(llama_zenz)
if(NOT llama_zenz_POPULATED)
    FetchContent_Populate(llama_zenz)
endif()

add_library(zenz_engine SHARED
    zenz_jni.cpp
    ${llama_zenz_SOURCE_DIR}/llama.cpp
    ${llama_zenz_SOURCE_DIR}/ggml.c
    ${llama_zenz_SOURCE_DIR}/ggml-alloc.c
    ${llama_zenz_SOURCE_DIR}/ggml-backend.c
    ${llama_zenz_SOURCE_DIR}/ggml-quants.c
    ${llama_zenz_SOURCE_DIR}/unicode.cpp
    ${llama_zenz_SOURCE_DIR}/unicode-data.cpp
)

target_include_directories(zenz_engine PRIVATE
    ${llama_zenz_SOURCE_DIR}
    ${llama_zenz_SOURCE_DIR}/include
    ${llama_zenz_SOURCE_DIR}/ggml/include
)

target_link_libraries(zenz_engine
    log
    android
)

target_link_options(zenz_engine PRIVATE
    "-Wl,--exclude-libs,ALL"
)

# ----------------------------------------------------
# 2. Original llama.cpp (ggerganov/llama.cpp オリジナル版 - Jinen/Karukan等で利用)
# ----------------------------------------------------
message(STATUS "Fetching Original llama.cpp source...")
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp
    GIT_TAG        master
)

FetchContent_GetProperties(llama_cpp)
if(NOT llama_cpp_POPULATED)
    FetchContent_Populate(llama_cpp)
endif()

if(EXISTS "${llama_cpp_SOURCE_DIR}/src/llama-version.h.in")
    configure_file(
        "${llama_cpp_SOURCE_DIR}/src/llama-version.h.in"
        "${llama_cpp_BINARY_DIR}/llama-version.h"
    )
endif()

if(EXISTS "${llama_cpp_SOURCE_DIR}/ggml/src/ggml-version.h.in")
    configure_file(
        "${llama_cpp_SOURCE_DIR}/ggml/src/ggml-version.h.in"
        "${llama_cpp_BINARY_DIR}/ggml-version.h"
    )
endif()

file(GLOB_RECURSE LLAMA_CPP_SRC_FILES "${llama_cpp_SOURCE_DIR}/src/*.cpp")

file(GLOB_RECURSE LLAMA_CPP_GGML_CPU_SRCS
    "${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu/*.cpp"
    "${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu/*.c"
)
list(FILTER LLAMA_CPP_GGML_CPU_SRCS EXCLUDE REGEX ".*/kleidiai/.*")
list(FILTER LLAMA_CPP_GGML_CPU_SRCS EXCLUDE REGEX ".*/spacemit/.*")
list(FILTER LLAMA_CPP_GGML_CPU_SRCS EXCLUDE REGEX ".*/amx/.*")
list(FILTER LLAMA_CPP_GGML_CPU_SRCS EXCLUDE REGEX ".*/arch/.*")

set(LLAMA_CPP_ARCH_SRCS "")
if (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    file(GLOB LLAMA_CPP_ARCH_SRCS
        "${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu/arch/arm/*.c"
        "${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu/arch/arm/*.cpp"
    )
elseif (ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    file(GLOB LLAMA_CPP_ARCH_SRCS
        "${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu/arch/x86/*.c"
        "${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu/arch/x86/*.cpp"
    )
endif()

add_library(llama_engine SHARED
    llama_jni.cpp
    ${LLAMA_CPP_SRC_FILES}
    ${LLAMA_CPP_GGML_CPU_SRCS}
    ${LLAMA_CPP_ARCH_SRCS}
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml.c
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml.cpp
    ${llama_cpp_SOURCE_DIR}/ggml/src/gguf.cpp
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-alloc.c
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-backend.cpp
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-backend-reg.cpp
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-backend-meta.cpp
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-backend-dl.cpp
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-opt.cpp
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-quants.c
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-threading.cpp
)

target_include_directories(llama_engine PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/ggml/src
    ${llama_cpp_SOURCE_DIR}/ggml/src/ggml-cpu
    ${llama_cpp_SOURCE_DIR}/src
    ${llama_cpp_BINARY_DIR}
)

target_compile_definitions(llama_engine PRIVATE
    _GNU_SOURCE
)

target_link_libraries(llama_engine
    log
    android
    dl
)

target_link_options(llama_engine PRIVATE
    "-Wl,--exclude-libs,ALL"
)

# ----------------------------------------------------
# 3. Model Auto-Downloads (Assets)
# ----------------------------------------------------
set(ASSETS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../assets")
file(MAKE_DIRECTORY "${ASSETS_DIR}")

# 3.1 Zenz Model Auto-Download
set(ZENZ_MODEL_NAME "zenz-v3.2-xsmall.gguf")
set(ZENZ_MODEL_URL "https://huggingface.co/ensan-hcl/zenz-v3.2-xsmall-gguf/resolve/main/zenz-v3.2-xsmall.gguf")
set(ZENZ_MODEL_PATH "${ASSETS_DIR}/${ZENZ_MODEL_NAME}")

if(NOT EXISTS "${ZENZ_MODEL_PATH}")
    message(STATUS "Zenz model not found at ${ZENZ_MODEL_PATH}. Downloading from Hugging Face...")
    file(DOWNLOAD
        "${ZENZ_MODEL_URL}"
        "${ZENZ_MODEL_PATH}"
        SHOW_PROGRESS
        STATUS ZENZ_DL_STATUS
        TLS_VERIFY ON
    )
    list(GET ZENZ_DL_STATUS 0 ZENZ_DL_CODE)
    if(NOT ZENZ_DL_CODE EQUAL 0)
        message(WARNING "Failed to download Zenz model (${ZENZ_DL_STATUS}). Please download manually.")
        if(EXISTS "${ZENZ_MODEL_PATH}")
            file(REMOVE "${ZENZ_MODEL_PATH}")
        endif()
    else()
        message(STATUS "Successfully downloaded Zenz model to ${ZENZ_MODEL_PATH}")
    endif()
endif()

# 3.2 Jinen Model Auto-Download
set(JINEN_MODEL_NAME "jinen-v2-xsmall-Q5_K_M.gguf")
set(JINEN_MODEL_URL "https://huggingface.co/togatogah/jinen-v2-xsmall.gguf/resolve/main/jinen-v2-xsmall-Q5_K_M.gguf")
set(JINEN_MODEL_PATH "${ASSETS_DIR}/${JINEN_MODEL_NAME}")

if(NOT EXISTS "${JINEN_MODEL_PATH}")
    message(STATUS "Jinen model not found at ${JINEN_MODEL_PATH}. Downloading from Hugging Face...")
    file(DOWNLOAD
        "${JINEN_MODEL_URL}"
        "${JINEN_MODEL_PATH}"
        SHOW_PROGRESS
        STATUS JINEN_DL_STATUS
        TLS_VERIFY ON
    )
    list(GET JINEN_DL_STATUS 0 JINEN_DL_CODE)
    if(NOT JINEN_DL_CODE EQUAL 0)
        message(WARNING "Failed to download Jinen model (${JINEN_DL_STATUS}). Please download manually.")
        if(EXISTS "${JINEN_MODEL_PATH}")
            file(REMOVE "${JINEN_MODEL_PATH}")
        endif()
    else()
        message(STATUS "Successfully downloaded Jinen model to ${JINEN_MODEL_PATH}")
    endif()
endif()
