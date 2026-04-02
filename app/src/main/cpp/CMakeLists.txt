cmake_minimum_required(VERSION 3.22.1)
project("llama_jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    llama_jni
    SHARED
    llama_jni.cpp
)

find_library(log-lib log)

# ==============================
# IMPORTANT
# ==============================
# Option A:
#   Add llama.cpp source files directly here.
# Option B:
#   Add llama.cpp as a subdirectory and link to its native targets.
#
# Example if vendored under:
#   src/main/cpp/llama.cpp/
#
# If you vendor llama.cpp into src/main/cpp/llama.cpp and it provides a CMake
# target (or CMakeLists), you can enable integration by uncommenting the
# add_subdirectory line below. When enabled, this will also define
# HAVE_LLAMA_CPP for the compilation so the JNI file compiles the real
# integration code instead of the placeholder.
#
# Example usage:
#   git clone https://github.com/ggerganov/llama.cpp.git src/main/cpp/llama.cpp
#   # then uncomment the two lines below and build the app
#
if(EXISTS "${CMAKE_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(STATUS "llama.cpp detected under ${CMAKE_SOURCE_DIR}/llama.cpp - enabling integration")
    add_subdirectory("${CMAKE_SOURCE_DIR}/llama.cpp" llama_cpp_build)
    # define a compile flag so the JNI C++ file compiles the real integration branch
    target_compile_definitions(llama_jni PRIVATE HAVE_LLAMA_CPP)
    # link against the llama target produced by upstream; the target name may
    # vary depending on the version - check the llama.cpp CMake files and
    # replace 'llama' with the correct target if different.
    target_link_libraries(llama_jni PRIVATE llama)
    # If Vulkan is available on the NDK/toolchain, enable ggml/vulkan support
    # (this is a best-effort compile flag; upstream ggml/llama.cpp must support
    # a Vulkan backend for this to have effect).
    target_compile_definitions(llama_jni PRIVATE GGML_USE_VULKAN)
    target_link_libraries(llama_jni PRIVATE vulkan)
else()
    message(STATUS "llama.cpp not detected - building llama_jni with placeholder JNI implementation")
endif()
#
# The exact target name can differ depending on the upstream version you vendor.
# Check the upstream CMake files after cloning llama.cpp.
# ==============================

target_link_libraries(
    llama_jni
    PRIVATE
    ${log-lib}
)

