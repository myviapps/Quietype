cmake_minimum_required(VERSION 3.22.1)
project(humanrewrite LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

# llama.cpp source: git clone https://github.com/ggml-org/llama.cpp .tools/llama.cpp
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../.tools/llama.cpp)

# Pinned: the JNI code targets this exact llama.cpp API. Checkout with
#   git -C .tools/llama.cpp checkout 661643e43079a4ee6faab4c1895291767b67ea8d
set(LLAMA_PINNED_COMMIT 661643e43079a4ee6faab4c1895291767b67ea8d)
execute_process(COMMAND git -C ${LLAMA_SRC} rev-parse HEAD OUTPUT_VARIABLE LLAMA_HEAD OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
if(NOT LLAMA_HEAD STREQUAL LLAMA_PINNED_COMMIT)
    message(FATAL_ERROR "llama.cpp is at '${LLAMA_HEAD}', expected ${LLAMA_PINNED_COMMIT}")
endif()

# Static library only: no tools, server, tests or network code inside the keyboard.
foreach(opt BUILD_SHARED_LIBS LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES
            LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_OPENSSL GGML_NATIVE GGML_OPENMP GGML_LLAMAFILE)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()

if(ANDROID_ABI STREQUAL "x86_64")
    # ponytail: assumes AVX2, true for emulators on any recent PC; a real x86 phone without it would crash.
    foreach(opt GGML_AVX GGML_AVX2 GGML_F16C GGML_FMA)
        set(${opt} ON CACHE BOOL "" FORCE)
    endforeach()
endif()

add_subdirectory(${LLAMA_SRC} llama.cpp)

add_library(humanrewrite SHARED humanrewrite_llama.cpp)
target_link_libraries(humanrewrite llama log)
