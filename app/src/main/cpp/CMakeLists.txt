cmake_minimum_required(VERSION 3.22.1)
project(vidscript_whisper)

# whisper.cpp viene scaricato da scripts/setup.sh (o dal workflow GitHub) in questa cartella.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp non trovato: esegui scripts/setup.sh (o setup.ps1) prima di compilare.")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(vidscript_whisper SHARED jni.cpp)
target_link_libraries(vidscript_whisper whisper android log)
target_compile_options(vidscript_whisper PRIVATE -O3)
