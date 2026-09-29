# Mac-side accuracy executor for Local LLM Phase 0 (docs/LLM_PHASE0.md §7.2): the same pinned
# llama.cpp submodule, CPU only (Metal off), the same prompts / grammar / templates / dataset as the
# S20 bench, the same result JSONL. Build:
#   cmake -S tools/llm-eval/host -B tools/llm-eval/host/build -DCMAKE_BUILD_TYPE=Release \
#     -DCMAKE_C_COMPILER=/Library/Developer/CommandLineTools/usr/bin/clang \
#     -DCMAKE_CXX_COMPILER=/Library/Developer/CommandLineTools/usr/bin/clang++ \
#     -DCMAKE_OSX_SYSROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk
#   cmake --build tools/llm-eval/host/build -j8
cmake_minimum_required(VERSION 3.22)
project(llmbench_host LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../llmbench/src/main/cpp/llama.cpp)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)
add_executable(llmbench_host llmbench_host.cpp)
target_include_directories(llmbench_host PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include ${LLAMA_SRC}/common ${LLAMA_SRC}/vendor)
target_link_libraries(llmbench_host PRIVATE llama-common llama ggml)
