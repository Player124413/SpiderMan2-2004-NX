#include "android_options.h"

#include <algorithm>
#include <chrono>
#include <thread>

#include "app/app.h"
#include "host.h"

namespace game {

AndroidOptions& Opt() {
    static AndroidOptions options;
    return options;
}

float Aspect() {
    app::Mode mode = app::Output();
    return std::max(float(mode.width) / float(std::max(mode.height, 1u)), 4.0f / 3.0f);
}

void WaitWhileUnfocused() {
    // Android destroys/recreates a Vulkan surface while an app is backgrounded. Do
    // not let the guest submit into it; the event loop wakes this exact wait on resume.
    if (!host::Foreground()) host::WaitForeground();
}

}  // namespace game
