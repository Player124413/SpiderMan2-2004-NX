#include "host.h"

#include <SDL3/SDL.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "app/app.h"
#include "core/log.h"
#include "image_symbols.h"
#include "kernel/crypto.h"

namespace fs = std::filesystem;

extern const char kImageSha1[];

namespace {
SDL_Window* g_window = nullptr;
std::atomic<bool> g_foreground{true};
std::atomic<uint32_t> g_surface_epoch{0};
std::mutex g_focus_lock;
std::condition_variable g_focus_cv;
std::mutex g_config_lock;
std::condition_variable g_config_cv;
bool g_configured = false;
fs::path g_game_dir;


std::string Sha1(const std::vector<uint8_t>& data) {
    alignas(8) uint8_t context[128];
    uint8_t digest[20];
    crypto::ShaInit(context);
    crypto::ShaUpdate(context, data.data(), data.size());
    crypto::ShaFinal(context, digest);
    static constexpr char digits[] = "0123456789abcdef";
    std::string text;
    text.reserve(40);
    for (uint8_t byte : digest) {
        text += digits[byte >> 4];
        text += digits[byte & 15];
    }
    return text;
}

bool ReadFile(const fs::path& path, std::vector<uint8_t>& out) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) return false;
    std::streamsize size = file.tellg();
    if (size <= 0 || size > 64 * 1024 * 1024) return false;
    out.resize(size_t(size));
    file.seekg(0);
    return bool(file.read(reinterpret_cast<char*>(out.data()), size));
}

app::Mode ScreenMode() {
    int width = 0, height = 0;
    host::WindowPixels(width, height);
    if (width > 0 && height > 0) return {uint32_t(width), uint32_t(height)};
    const SDL_DisplayMode* mode = SDL_GetCurrentDisplayMode(SDL_GetPrimaryDisplay());
    if (!mode) return {1920, 1080};
    const uint32_t width_px = uint32_t(mode->w * mode->pixel_density);
    const uint32_t height_px = uint32_t(mode->h * mode->pixel_density);
    return {std::max(width_px, height_px), std::min(width_px, height_px)};
}

}  // namespace

namespace androidhost {

void Configure(const std::string& game_dir, uint32_t render_percent, uint32_t fps_cap, bool vsync, uint32_t aniso) {
    {
        std::lock_guard lock(g_config_lock);
        g_game_dir = fs::u8path(game_dir);
        g_configured = true;
    }
    g_config_cv.notify_all();
    app::Settings& settings = app::Get();
    settings.render_percent = std::clamp(render_percent, 40u, 150u);
    settings.fps_cap = std::min(fps_cap, 240u);
    settings.vsync = vsync;
    settings.aniso = std::min(aniso, 16u);
}

const fs::path& GameDirectory() { return g_game_dir; }

void SetForeground(bool foreground) {
    {
        std::lock_guard lock(g_focus_lock);
        if (foreground && !g_foreground) ++g_surface_epoch;
        g_foreground = foreground;
    }
    g_focus_cv.notify_all();
    LOGI("android: %s", foreground ? "foreground" : "background");
}

}  // namespace androidhost

namespace host {
SDL_Window* Window() { return g_window; }
bool Foreground() { return g_foreground.load(); }
uint32_t SurfaceEpoch() { return g_surface_epoch.load(); }
void WaitForeground() {
    std::unique_lock lock(g_focus_lock);
    g_focus_cv.wait(lock, [] { return g_foreground.load(); });
}
void WindowPixels(int& width, int& height) {
    width = height = 0;
    if (g_window) SDL_GetWindowSizeInPixels(g_window, &width, &height);
}
}  // namespace host

namespace app {

Settings& Get() {
    static Settings settings = [] {
        Settings initial;
        // 720p-class internal rendering balances image quality and phone thermals.
        initial.render_percent = 67;
        initial.fps_cap = 60;
        initial.vsync = true;
        initial.aniso = 4;
        return initial;
    }();
    return settings;
}

void Load() {}
void Save() {}
void ApplyDisplay() {}
Mode Desktop() { return ScreenMode(); }
Mode Output() { return ScreenMode(); }
bool Widescreen() {
    Mode mode = Output();
    return mode.width * 2 >= mode.height * 3;
}
bool WideClose() { return false; }
float RenderScale(uint32_t guest_height) {
    const float pixels = Output().height * (Get().render_percent / 100.0f);
    return guest_height ? std::max(pixels / guest_height, 0.1f) : 1.0f;
}

fs::path ExeDir() {
    static const fs::path dir = [] {
        const char* external = SDL_GetAndroidExternalStoragePath();
        const char* internal = SDL_GetAndroidInternalStoragePath();
        fs::path path = external ? fs::u8path(external) : internal ? fs::u8path(internal) : fs::path(".");
        std::error_code error;
        fs::create_directories(path, error);
        return path;
    }();
    return dir;
}

int Instance() { return 0; }
void* Window() { return g_window; }
std::recursive_mutex& UiLock() {
    static std::recursive_mutex lock;
    return lock;
}
void Pace() {
    using clock = std::chrono::steady_clock;
    static thread_local clock::time_point next = clock::now();
    const uint32_t cap = Get().fps_cap;
    if (!cap) {
        next = clock::now();
        return;
    }
    const auto frame = std::chrono::nanoseconds(1'000'000'000ull / cap);
    const auto now = clock::now();
    next += frame;
    if (next < now - frame) next = now;
    while (clock::now() + std::chrono::milliseconds(2) < next) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    while (clock::now() < next) std::this_thread::yield();
}

bool OpenSplash() { return false; }
void SplashFirstFrame() {}
void CloseSplash() {}
void OpenGameWindow(bool) {}
bool StartHandover() { return false; }
bool HandingOver() { return false; }
void FinishHandover() {}

void LogSettings() {
    const Settings& settings = Get();
    LOGR("Android settings: render %u%%, cap %u, vsync %s, anisotropic %u", settings.render_percent, settings.fps_cap,
         settings.vsync ? "on" : "off", settings.aniso);
}

void LogSystem() {
    Mode mode = Output();
    LOGR("%ls (%s), Android runtime", kGame.title, IMAGE_XBE);
    LOGR("Storage: %s", ExeDir().string().c_str());
    LOGR("Screen: %ux%u", mode.width, mode.height);
    LogSettings();
}

bool FindGame(GameFiles& game) {
    fs::path folder;
    {
        std::unique_lock lock(g_config_lock);
        // SDLActivity may start native main while GameActivity is still completing
        // onCreate. Wait once instead of scanning a stale/default location.
        if (!g_config_cv.wait_for(lock, std::chrono::seconds(10), [] { return g_configured; })) {
            LOGE("game: launcher configuration did not arrive");
            return false;
        }
        folder = g_game_dir;
    }
    if (folder.empty()) {
        LOGE("game: launcher provided an empty game directory");
        return false;
    }
    std::vector<uint8_t> xbe;
    const fs::path image = folder / IMAGE_XBE;
    if (!ReadFile(image, xbe)) {
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Spider-Man 2 game files not found",
                                 "Put default.xbe and the data folder from your own Xbox disc in the selected SpiderMan2Xbox folder.",
                                 g_window);
        return false;
    }
    const std::string found = Sha1(xbe);
    if (found != kImageSha1) {
        std::string message = "This default.xbe is a different release than the one used for this APK.\n\nExpected: " +
                              std::string(kImageSha1) + "\nFound: " + found;
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Unsupported game release", message.c_str(), g_window);
        return false;
    }
    std::error_code error;
    if (!fs::is_directory(folder / "data", error) && !fs::is_directory(folder / "DATA", error)) {
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Game data is incomplete",
                                 "default.xbe was found, but its data folder is missing. Copy the complete extracted game folder.", g_window);
        return false;
    }
    LOGR("Game: %s, SHA-1 %s", image.string().c_str(), found.c_str());
    game = {folder, std::move(xbe)};
    return true;
}

}  // namespace app

namespace dynres {
void Frame() {}
}  // namespace dynres

namespace androidhost_internal {
void SetWindow(SDL_Window* window) { g_window = window; }
}
