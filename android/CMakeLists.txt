# Spider-Man 2 (Xbox) as an Android arm64 library.
#
# The legal owner's XBE is recompiled on the build machine first.  Nothing derived
# from the game is committed: generated/game and assets/default.xbe are ignored by
# the repository root.  SDL owns the Android surface; our Vulkan backend owns the
# recompiled Xbox D3D8 calls.
cmake_minimum_required(VERSION 3.22)
project(spiderman2_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(SM2_REPO ${CMAKE_CURRENT_LIST_DIR}/..)
set(SDK ${SM2_REPO}/sdk)
set(NATIVE ${CMAKE_CURRENT_LIST_DIR}/native)
set(SDL_DIR ${CMAKE_CURRENT_LIST_DIR}/third_party/SDL)

set(XBE_GENERATED ${SM2_REPO}/generated/game CACHE PATH "Generated game code from xbe_recomp")
set(XBE_IMAGE ${SM2_REPO}/assets/default.xbe CACHE FILEPATH "The owned XBE used to produce generated/game")
if(NOT EXISTS ${XBE_GENERATED}/x86_func_mapping.cpp OR NOT EXISTS ${XBE_IMAGE})
    message(FATAL_ERROR
        "Android runtime needs generated/game and assets/default.xbe. Run: cmake -S tools/xbe -B build-tools -G Ninja && cmake --build build-tools && build-tools/xbe_recomp config/game.toml --xdk sdk/xdk")
endif()
if(NOT EXISTS ${SDL_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "SDL3 is missing. Run ./gradlew assembleDebug once so the fetchSDL task checks it out.")
endif()

file(STRINGS ${SM2_REPO}/config/game.toml partitions_line REGEX "^partitions *=")
string(REGEX MATCH "[0-9]+" partition_count "${partitions_line}")
if(NOT partition_count)
    message(FATAL_ERROR "Could not read partitions from config/game.toml")
endif()
math(EXPR last_partition "${partition_count} - 1")
set(GENERATED_SOURCES ${XBE_GENERATED}/x86_func_mapping.cpp)
foreach(partition RANGE ${last_partition})
    list(APPEND GENERATED_SOURCES ${XBE_GENERATED}/x86_recomp.${partition}.cpp)
endforeach()
file(SHA1 ${XBE_IMAGE} xbe_sha1)
file(CONFIGURE OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/image_sha1.cpp
     CONTENT "extern const char kImageSha1[] = \"${xbe_sha1}\";\n")

# SDL's Java activity is included by the Gradle module. Its C side is built here.
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(SDL_RENDER OFF CACHE BOOL "" FORCE)
set(SDL_GPU OFF CACHE BOOL "" FORCE)
set(SDL_CAMERA OFF CACHE BOOL "" FORCE)
add_subdirectory(${SDL_DIR} ${CMAKE_CURRENT_BINARY_DIR}/SDL EXCLUDE_FROM_ALL)

include(FetchContent)
set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
set(ENABLE_HLSL ON CACHE BOOL "" FORCE)
set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
set(ENABLE_SPVREMAPPER OFF CACHE BOOL "" FORCE)
set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_EXTERNAL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(glslang GIT_REPOSITORY https://github.com/KhronosGroup/glslang.git GIT_TAG 16.0.0 GIT_SHALLOW TRUE)
FetchContent_Declare(imgui GIT_REPOSITORY https://github.com/ocornut/imgui.git GIT_TAG v1.91.0 GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(glslang imgui)

# The portable Xbox runtime, excluding the Windows host and D3D12 backend. Android replaces
# those with the SDL/Vulkan host below; its DirectSound HLE is retained and gets an SDL output.
file(GLOB RUNTIME_SOURCES ${SDK}/runtime/*/*.cpp)
list(FILTER RUNTIME_SOURCES EXCLUDE REGEX
    "/runtime/app/|/runtime/core/input\\.cpp$|/runtime/gpu/(d3d12|render_thread)\\.cpp$|/runtime/audio/xaudio2\\.cpp$|/runtime/xdk/d3d8\\.cpp$")
file(GLOB GAME_SOURCES CONFIGURE_DEPENDS ${SM2_REPO}/src/*/*.cpp)
set(ANDROID_SOURCES
    ${NATIVE}/android_main.cpp
    ${NATIVE}/android_host.cpp
    ${NATIVE}/android_input.cpp
    ${NATIVE}/android_audio.cpp
    ${NATIVE}/android_options.cpp
    ${NATIVE}/android_ui_stubs.cpp
    ${NATIVE}/renderer/vulkan.cpp
    ${NATIVE}/renderer/vk_shader.cpp
    ${NATIVE}/renderer/d3d8.cpp
    ${NATIVE}/renderer/render_thread.cpp)
set(IMGUI_SOURCES
    ${imgui_SOURCE_DIR}/imgui.cpp
    ${imgui_SOURCE_DIR}/imgui_draw.cpp
    ${imgui_SOURCE_DIR}/imgui_tables.cpp
    ${imgui_SOURCE_DIR}/imgui_widgets.cpp
    ${imgui_SOURCE_DIR}/backends/imgui_impl_vulkan.cpp)

add_library(main SHARED ${RUNTIME_SOURCES} ${GAME_SOURCES} ${ANDROID_SOURCES} ${GENERATED_SOURCES}
            ${IMGUI_SOURCES} ${CMAKE_CURRENT_BINARY_DIR}/image_sha1.cpp)
target_include_directories(main PRIVATE
    ${NATIVE}
    ${NATIVE}/renderer
    ${SDK}/runtime
    ${SDK}/harness/compat
    ${SM2_REPO}/src
    ${XBE_GENERATED}
    ${CMAKE_CURRENT_BINARY_DIR}
    ${glslang_SOURCE_DIR})
target_include_directories(main SYSTEM PRIVATE ${imgui_SOURCE_DIR})
target_compile_options(main PRIVATE -fno-strict-aliasing -fsigned-char -ffunction-sections -fdata-sections)
# Generated code is large and has no useful individual diagnostics. Keep it aggressively optimised even in a debug APK.
set_source_files_properties(${GENERATED_SOURCES} PROPERTIES COMPILE_OPTIONS "-O2;-w;-fno-strict-aliasing;-fsigned-char")
set_source_files_properties(${NATIVE}/renderer/vk_shader.cpp PROPERTIES COMPILE_DEFINITIONS ENABLE_HLSL)
target_link_options(main PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(main PRIVATE SDL3::SDL3 glslang glslang-default-resource-limits vulkan android log)
