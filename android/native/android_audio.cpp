// SDL's Android audio backend (AAudio/OpenSL ES depending on the device) pulls the
// existing portable DirectSound stereo mixer. Keeping the callback pull-driven avoids
// audio drift and unnecessary producer threads.
#include <SDL3/SDL.h>

#include <algorithm>
#include <functional>
#include <memory>
#include <vector>

#include "audio/audio.h"
#include "core/log.h"

namespace audio {
namespace {
constexpr uint32_t kFrames = kRate / 100;  // 10 ms
struct Output {
    std::function<void(float*, uint32_t)> render;
    std::vector<float> samples = std::vector<float>(kFrames * 2);
};

void SDLCALL Feed(void* user, SDL_AudioStream* stream, int additional, int) {
    auto& output = *static_cast<Output*>(user);
    constexpr int bytes_per_frame = int(2 * sizeof(float));
    const uint32_t frames = uint32_t(std::max(additional, 0) / bytes_per_frame);
    uint32_t left = frames;
    while (left) {
        const uint32_t batch = std::min(left, kFrames);
        output.render(output.samples.data(), batch);
        SDL_PutAudioStreamData(stream, output.samples.data(), int(batch * bytes_per_frame));
        left -= batch;
    }
}
}  // namespace

void StartOutput(std::function<void(float* out, uint32_t frames)> render) {
    SDL_AudioSpec want{SDL_AUDIO_F32, 2, int(kRate)};
    auto* output = new Output{std::move(render)};  // SDL owns the callback lifetime until process exit.
    SDL_AudioStream* stream = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &want, Feed, output);
    if (!stream) {
        LOGE("audio: Android output unavailable: %s", SDL_GetError());
        delete output;
        return;
    }
    LOGI("audio: SDL stereo output via %s", SDL_GetCurrentAudioDriver());
    SDL_ResumeAudioStreamDevice(stream);
}
}  // namespace audio
