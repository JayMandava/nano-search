cmake_minimum_required(VERSION 3.22)
project(nanollm CXX C)

# llama.cpp lives next to the app project: <root>/llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../llama.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama)

# whisper.cpp for on-device speech-to-text. Its source is compiled straight into this library and uses the
# same ggml as llama.cpp above (one copy, no symbol clashes); it builds against it unchanged.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../whisper.cpp)

add_library(nanollm SHARED nanollm.cpp whisper_jni.cpp ${WHISPER_DIR}/src/whisper.cpp ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp)
target_compile_definitions(nanollm PRIVATE WHISPER_VERSION="local")
target_include_directories(nanollm PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include ${WHISPER_DIR}/include ${WHISPER_DIR}/src)
target_link_libraries(nanollm PRIVATE llama ggml log android)
