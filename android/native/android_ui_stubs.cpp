#include "host.h"

#include <imgui.h>

namespace touch {
void Draw() {}
bool LoadSkin(std::vector<uint8_t>&, uint32_t&, uint32_t&) { return false; }
void SetSkinTexture(uint64_t) {}
}  // namespace touch

namespace ui {
void OnEvent(const SDL_Event&) {}
void NewFrame(float width, float height) {
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(width, height);
    io.DeltaTime = 1.0f / 60.0f;
}
}  // namespace ui
