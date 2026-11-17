#include "host.h"

#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>

#include <imgui.h>

#include "app/app.h"
#include "core/input.h"
#include "core/log.h"
#include "image_symbols.h"
#include "kernel/kernel.h"

namespace androidhost_internal {
void SetWindow(SDL_Window* window);
}
namespace input {
void OnPadEvent(const SDL_Event& event);
}

namespace {


void InitImGui() {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.ConfigFlags |= ImGuiConfigFlags_IsTouchScreen;
    io.BackendPlatformName = "spiderman2_android";
}

void Dispatch(const SDL_Event& event) {
    switch (event.type) {
        case SDL_EVENT_QUIT:
        case SDL_EVENT_TERMINATING:
            logx::Flush();
            kernel::Shutdown(0);
            return;
        case SDL_EVENT_WILL_ENTER_BACKGROUND:
            androidhost::SetForeground(false);
            return;
        case SDL_EVENT_DID_ENTER_FOREGROUND:
            androidhost::SetForeground(true);
            return;
        case SDL_EVENT_GAMEPAD_ADDED:
        case SDL_EVENT_GAMEPAD_REMOVED:
            input::OnPadEvent(event);
            return;
        case SDL_EVENT_LOW_MEMORY:
            LOGW("android: system reported low memory");
            return;
        default:
            return;
    }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL Java_com_spiderman2_android_NativeBridge_nativeConfigure(
    JNIEnv* env, jclass, jstring game_directory, jint render_percent, jint fps_cap, jboolean vsync, jint aniso) {
    const char* chars = game_directory ? env->GetStringUTFChars(game_directory, nullptr) : nullptr;
    androidhost::Configure(chars ? chars : "", uint32_t(std::max(render_percent, 1)), uint32_t(std::max(fps_cap, 0)),
                           vsync == JNI_TRUE, uint32_t(std::max(aniso, 0)));
    if (chars) env->ReleaseStringUTFChars(game_directory, chars);
}

extern "C" JNIEXPORT void JNICALL Java_com_spiderman2_android_NativeBridge_nativeSetForeground(JNIEnv*, jclass, jboolean foreground) {
    androidhost::SetForeground(foreground == JNI_TRUE);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_spiderman2_android_NativeBridge_nativeRuntimeSummary(JNIEnv* env, jclass) {
    const char* summary = "ARM64 · Vulkan 1.1 · SDL3 · native recompilation";
    return env->NewStringUTF(summary);
}

int main(int, char**) {
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, "0");
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_GAMEPAD | SDL_INIT_AUDIO)) {
        __android_log_print(ANDROID_LOG_ERROR, "SM2Android", "SDL init failed: %s", SDL_GetError());
        return 1;
    }

    SDL_Window* window = SDL_CreateWindow("Spider-Man 2", 0, 0,
                                          SDL_WINDOW_FULLSCREEN | SDL_WINDOW_VULKAN | SDL_WINDOW_HIGH_PIXEL_DENSITY);
    if (!window) {
        __android_log_print(ANDROID_LOG_ERROR, "SM2Android", "window creation failed: %s", SDL_GetError());
        return 1;
    }
    androidhost_internal::SetWindow(window);
    logx::Init((app::ExeDir() / "logs").string(), app::kGame.log);
    app::Load();
    if (app::kGame.init) app::kGame.init();
    app::LogSystem();

    app::GameFiles game;
    if (!app::FindGame(game)) return 1;
    InitImGui();
    input::Init();
    if (app::kGame.window) app::kGame.window(window);
    if (!kernel::Boot(std::move(game.xbe), IMAGE_XBE, game.dir.string(), (app::ExeDir() / "hdd").string())) {
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Spider-Man 2", "The game could not start. See Android/data logs.", window);
        return 1;
    }

    for (;;) {
        SDL_Event event;
        if (SDL_WaitEventTimeout(&event, 100)) {
            Dispatch(event);
            while (SDL_PollEvent(&event)) Dispatch(event);
        }
    }
}
