cmake_minimum_required(VERSION 3.22)
project(nanollm_gpu CXX C)

# The optional GPU build of the language model layer: the same nanollm.cpp as the CPU library, but with llama.cpp's Vulkan backend compiled in and
# its own copy of llama.cpp and ggml (hidden, so it cannot clash with the CPU library's). It is only loaded when the user turns the GPU on.
set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)
set(LLAMA_DIR ${ROOT}/llama.cpp)
set(THIRD_PARTY ${ROOT}/third_party)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Vulkan: the NDK ships the loader library and the shader compiler; the newer C++ headers (vulkan.hpp) come from the submodule.
set(Vulkan_GLSLC_EXECUTABLE ${CMAKE_ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc CACHE FILEPATH "" FORCE)
find_library(NDK_VULKAN_LIBRARY vulkan REQUIRED)
set(Vulkan_LIBRARY ${NDK_VULKAN_LIBRARY} CACHE FILEPATH "" FORCE)
set(Vulkan_INCLUDE_DIR ${THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
set(SPIRV-Headers_DIR ${CMAKE_CURRENT_SOURCE_DIR}/cmake/SPIRV-Headers CACHE PATH "" FORCE)

# ggml-vulkan includes spirv/unified1/spirv.hpp but does not link the package it finds, so make the vendored headers visible everywhere.
include_directories(${THIRD_PARTY}/SPIRV-Headers/include)

add_subdirectory(${LLAMA_DIR} llama)

add_library(nanollm_gpu SHARED ../cpp/nanollm.cpp)
target_compile_definitions(nanollm_gpu PRIVATE NANO_JNI_CLASS=NativeLlmGpu NANO_GPU=1)
target_compile_options(nanollm_gpu PRIVATE -fvisibility=hidden)
target_include_directories(nanollm_gpu PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include)
target_link_libraries(nanollm_gpu PRIVATE llama ggml log android)
target_link_options(nanollm_gpu PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,-s)
