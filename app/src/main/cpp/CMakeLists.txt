cmake_minimum_required(VERSION 3.22.1)
project(jetmeal_whisper LANGUAGES C CXX)

# Reproducible third-party native dependency. The whisper.cpp repository is
# fetched during Android builds, not bundled into the APK or copied as a binary.
# Pin revision and recurse into the vendored ggml submodule.
include(FetchContent)
set(FETCHCONTENT_UPDATES_DISCONNECTED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(whisper_src
  GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
  GIT_TAG 0c7be696371069e0baa2515ed7aa08d5a5473aa6
  GIT_SHALLOW FALSE
  GIT_SUBMODULES_RECURSE TRUE
  GIT_PROGRESS FALSE)
FetchContent_MakeAvailable(whisper_src)

add_library(jetmeal_whisper SHARED whisper_jni.cpp)
target_compile_features(jetmeal_whisper PRIVATE cxx_std_17)
target_link_libraries(jetmeal_whisper PRIVATE whisper log)
target_compile_options(jetmeal_whisper PRIVATE -Wall -Wextra)
