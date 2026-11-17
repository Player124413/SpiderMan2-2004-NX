#pragma once
#include <atomic>
#include <cstdint>

namespace game {
// The Android renderer needs a handful of presentation toggles. They deliberately
// stay small and device-safe; controls live in the Java launcher and the game keeps
// its original Spider-Man options and save data.
struct AndroidOptions {
    bool fog = true;
    bool bloom = true;
    uint32_t anti_aliasing = 1;  // FXAA at presentation, no expensive supersampling by default
    bool hdr = false;
    uint32_t screen_filter = 0;
};
AndroidOptions& Opt();
float Aspect();
void WaitWhileUnfocused();
}
