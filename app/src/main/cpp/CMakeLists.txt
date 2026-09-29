# MemoRipple's local-LLM bridge (docs/LOCAL_LLM_RUNTIME.md). The only source of llama.cpp is the
# pinned submodule under llmbench/ (b11039, 4fea119de); the product links it statically into one
# small library and adds nothing of the Phase 0 bench. CPU only, arm64-v8a only, armv8.2-a with
# dotprod + fp16 (the Kotlin side refuses to call in on a CPU without asimddp).
cmake_minimum_required(VERSION 3.22.1)
project(memoripple_llm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../llmbench/src/main/cpp/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp submodule is missing: run `git submodule update --init` (docs/LOCAL_LLM_RUNTIME.md)")
endif()

add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)

add_library(memoripple_llm SHARED memoripple_llm.cpp)
target_include_directories(memoripple_llm PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include ${LLAMA_SRC}/common)
target_link_libraries(memoripple_llm PRIVATE llama-common llama ggml log android)
