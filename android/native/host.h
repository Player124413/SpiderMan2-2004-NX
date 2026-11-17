#pragma once
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

#include <SDL3/SDL.h>

namespace androidhost {
void Configure(const std::string& game_dir, uint32_t render_percent, uint32_t fps_cap, bool vsync, uint32_t aniso);
const std::filesystem::path& GameDirectory();
// Extract an owner-selected XDVDFS image into an empty staging folder. Empty means
// success; otherwise the returned text is safe to show in the Java launcher.
std::string ExtractDiscImage(const std::string& image_path, const std::string& destination_path);
void SetForeground(bool foreground);
}

namespace host {
SDL_Window* Window();
bool Foreground();
uint32_t SurfaceEpoch();
void WaitForeground();
void WindowPixels(int& width, int& height);
}

// The copied renderer exposes its settings hook through this tiny interface. Dynamic
// scaling can be added without changing the D3D8 bridge; user-selected render scale is
// applied immediately by VulkanBackend::ApplySettings.
namespace dynres {
void Frame();
}

namespace touch {
void Draw();
bool LoadSkin(std::vector<uint8_t>& rgba, uint32_t& width, uint32_t& height);
void SetSkinTexture(uint64_t texture);
}

namespace ui {
void OnEvent(const SDL_Event& event);
void NewFrame(float width, float height);
}
