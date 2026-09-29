# Local LLM Phase 0 bench (docs/LLM_PHASE0.md §4).
# llama.cpp is the git submodule at ./llama.cpp, pinned to one commit; the pin is
# recorded in docs/LLM_PHASE0.md and in every result file (llama.cpp commit).
cmake_minimum_required(VERSION 3.22.1)
project(llmbench LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp submodule is missing: run `git submodule update --init` (see docs/LLM_PHASE0.md)")
endif()

add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)

add_library(llmbench SHARED llmbench.cpp)
target_include_directories(llmbench PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include ${LLAMA_SRC}/common)
target_link_libraries(llmbench PRIVATE llama-common llama ggml log android)
