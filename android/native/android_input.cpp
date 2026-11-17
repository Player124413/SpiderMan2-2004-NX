// Physical SDL controllers plus the Java touch overlay as Xbox controller port 0.
#include <SDL3/SDL.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <iterator>
#include <mutex>

#include "app/app.h"
#include "core/input.h"
#include "core/log.h"

namespace {

struct VirtualPad {
    std::atomic<uint16_t> buttons{0};
    std::array<std::atomic<uint8_t>, 8> analog{};
    std::atomic<int16_t> lx{0}, ly{0}, rx{0}, ry{0};
    std::atomic<bool> active{false};
};
VirtualPad g_touch;

std::mutex g_pads_lock;
std::array<SDL_Gamepad*, 4> g_pads{};

constexpr int kStickMax = 32767;
int16_t Axis(float value) {
    const float clamped = std::clamp(value, -1.0f, 1.0f);
    return int16_t(std::lround(clamped * kStickMax));
}

void AddPad(SDL_JoystickID id) {
    for (SDL_Gamepad* pad : g_pads)
        if (pad && SDL_GetGamepadID(pad) == id) return;
    auto slot = std::find(g_pads.begin(), g_pads.end(), nullptr);
    if (slot == g_pads.end()) return;
    *slot = SDL_OpenGamepad(id);
    const char* name = *slot ? SDL_GetGamepadName(*slot) : SDL_GetError();
    LOGI("input: gamepad %d %s: %s", int(slot - g_pads.begin()) + 1, *slot ? "connected" : "open failed", name ? name : "");
}

void RemovePad(SDL_JoystickID id) {
    for (SDL_Gamepad*& pad : g_pads) {
        if (!pad || SDL_GetGamepadID(pad) != id) continue;
        SDL_CloseGamepad(pad);
        pad = nullptr;
    }
}

void CopyTouch(input::Pad& out) {
    if (!g_touch.active.load(std::memory_order_relaxed)) return;
    out.connected = true;
    out.buttons |= g_touch.buttons.load(std::memory_order_relaxed);
    for (size_t i = 0; i < std::size(out.analog); ++i)
        out.analog[i] = std::max(out.analog[i], g_touch.analog[i].load(std::memory_order_relaxed));
    auto stronger = [](int16_t& into, int16_t value) {
        if (std::abs(int(value)) > std::abs(int(into))) into = value;
    };
    stronger(out.lx, g_touch.lx.load(std::memory_order_relaxed));
    stronger(out.ly, g_touch.ly.load(std::memory_order_relaxed));
    stronger(out.rx, g_touch.rx.load(std::memory_order_relaxed));
    stronger(out.ry, g_touch.ry.load(std::memory_order_relaxed));
}

void ReadGamepad(SDL_Gamepad* pad, input::Pad& out) {
    if (!pad) return;
    out.connected = true;
    constexpr SDL_GamepadButton kButtons[] = {SDL_GAMEPAD_BUTTON_DPAD_UP, SDL_GAMEPAD_BUTTON_DPAD_DOWN,
                                               SDL_GAMEPAD_BUTTON_DPAD_LEFT, SDL_GAMEPAD_BUTTON_DPAD_RIGHT,
                                               SDL_GAMEPAD_BUTTON_START, SDL_GAMEPAD_BUTTON_BACK,
                                               SDL_GAMEPAD_BUTTON_LEFT_STICK, SDL_GAMEPAD_BUTTON_RIGHT_STICK};
    for (int i = 0; i < 8; ++i) out.buttons |= uint16_t(SDL_GetGamepadButton(pad, kButtons[i]) << i);
    constexpr SDL_GamepadButton kFace[] = {SDL_GAMEPAD_BUTTON_SOUTH, SDL_GAMEPAD_BUTTON_EAST, SDL_GAMEPAD_BUTTON_WEST,
                                            SDL_GAMEPAD_BUTTON_NORTH, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER,
                                            SDL_GAMEPAD_BUTTON_LEFT_SHOULDER};
    for (int i = 0; i < 6; ++i) out.analog[i] = SDL_GetGamepadButton(pad, kFace[i]) ? 0xFF : 0;
    auto axis = [pad](SDL_GamepadAxis axis_id) { return int(SDL_GetGamepadAxis(pad, axis_id)); };
    out.analog[6] = uint8_t(std::max(axis(SDL_GAMEPAD_AXIS_LEFT_TRIGGER), 0) >> 7);
    out.analog[7] = uint8_t(std::max(axis(SDL_GAMEPAD_AXIS_RIGHT_TRIGGER), 0) >> 7);
    out.lx = int16_t(axis(SDL_GAMEPAD_AXIS_LEFTX));
    out.ly = int16_t(-1 - axis(SDL_GAMEPAD_AXIS_LEFTY));
    out.rx = int16_t(axis(SDL_GAMEPAD_AXIS_RIGHTX));
    out.ry = int16_t(-1 - axis(SDL_GAMEPAD_AXIS_RIGHTY));
}

}  // namespace

namespace input {

void Init() {
    std::lock_guard lock(g_pads_lock);
    int count = 0;
    if (SDL_JoystickID* ids = SDL_GetGamepads(&count)) {
        for (int i = 0; i < count; ++i) AddPad(ids[i]);
        SDL_free(ids);
    }
}

void OnPadEvent(const SDL_Event& event) {
    std::lock_guard lock(g_pads_lock);
    if (event.type == SDL_EVENT_GAMEPAD_ADDED) AddPad(event.gdevice.which);
    if (event.type == SDL_EVENT_GAMEPAD_REMOVED) RemovePad(event.gdevice.which);
}

Pad Poll(int port) {
    Pad result;
    {
        std::lock_guard lock(g_pads_lock);
        if (port >= 0 && port < int(g_pads.size())) ReadGamepad(g_pads[size_t(port)], result);
    }
    if (port == 0) CopyTouch(result);
    if (app::kGame.pad) app::kGame.pad(port, result);
    return result;
}

void Rumble(int port, uint16_t left, uint16_t right) {
    if (app::kGame.rumble) app::kGame.rumble(port, left, right);
    std::lock_guard lock(g_pads_lock);
    if (port >= 0 && port < int(g_pads.size()) && g_pads[size_t(port)]) SDL_RumbleGamepad(g_pads[size_t(port)], left, right, 0);
}

}  // namespace input

// Button ids deliberately mirror the Java NativeBridge constants: 0..7 are the
// analogue Xbox buttons, 8..15 are the Xbox digital button bits.
extern "C" JNIEXPORT void JNICALL Java_com_spiderman2_android_NativeBridge_nativeSetButton(JNIEnv*, jclass, jint id,
                                                                                               jboolean down) {
    if (id < 0 || id >= 16) return;
    g_touch.active = true;
    if (id < 8) {
        g_touch.analog[size_t(id)] = down ? 0xFF : 0;
        return;
    }
    const uint16_t bit = uint16_t(1u << (id - 8));
    uint16_t old = g_touch.buttons.load();
    do {
        const uint16_t next = down ? uint16_t(old | bit) : uint16_t(old & ~bit);
        if (g_touch.buttons.compare_exchange_weak(old, next)) break;
    } while (true);
}

extern "C" JNIEXPORT void JNICALL Java_com_spiderman2_android_NativeBridge_nativeSetStick(JNIEnv*, jclass, jint stick,
                                                                                              jfloat x, jfloat y) {
    g_touch.active = true;
    if (stick == 0) {
        g_touch.lx = Axis(x);
        g_touch.ly = Axis(y);
    } else if (stick == 1) {
        g_touch.rx = Axis(x);
        g_touch.ry = Axis(y);
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_spiderman2_android_NativeBridge_nativeReleaseTouch(JNIEnv*, jclass) {
    g_touch.buttons = 0;
    for (auto& analog : g_touch.analog) analog = 0;
    g_touch.lx = g_touch.ly = g_touch.rx = g_touch.ry = 0;
}
