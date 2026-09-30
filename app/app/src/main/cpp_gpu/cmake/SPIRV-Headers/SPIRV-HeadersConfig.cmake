# ggml-vulkan looks for an installed SPIRV-Headers package. The headers are vendored as a submodule, so describe them here instead.
get_filename_component(_spirv_root "${CMAKE_CURRENT_LIST_DIR}/../../../../../../../third_party/SPIRV-Headers" ABSOLUTE)
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${_spirv_root}/include")
endif()
set(SPIRV-Headers_FOUND TRUE)
