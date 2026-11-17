#include <algorithm>
#include <cctype>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <string>
#include <string_view>
#include <vector>

#include "kernel/disc.h"

namespace fs = std::filesystem;

namespace {

constexpr uint32_t kCopyBuffer = 1u << 20;
constexpr int kMaxDepth = 48;

bool SafeName(std::string_view name) {
    return !name.empty() && name != "." && name != ".." && name.find('/') == std::string_view::npos &&
           name.find('\\') == std::string_view::npos && name.find('\0') == std::string_view::npos;
}

bool SameAscii(std::string_view left, std::string_view right) {
    if (left.size() != right.size()) return false;
    for (size_t i = 0; i < left.size(); ++i)
        if (std::tolower(uint8_t(left[i])) != std::tolower(uint8_t(right[i]))) return false;
    return true;
}

std::string DisplayName(const std::string& name) {
    std::string output;
    output.reserve(name.size());
    for (unsigned char c : name) output += std::isprint(c) ? char(c) : '?';
    return output;
}

bool CopyEntry(const kernel::Disc& disc, const kernel::Disc::Entry& entry, const fs::path& output, std::vector<uint8_t>& buffer,
               uint64_t& files, uint64_t& bytes, int depth, std::string& error) {
    if (depth > kMaxDepth || !SafeName(entry.name)) {
        error = "The disc image contains an unsafe or excessively nested filename.";
        return false;
    }
    // Android filesystems are case-sensitive, whereas Xbox paths are not. Canonicalise
    // the two root names the launcher/runtime must open literally.
    const std::string output_name = depth == 0 && SameAscii(entry.name, "default.xbe") ? "default.xbe"
                                  : depth == 0 && SameAscii(entry.name, "data")        ? "data"
                                                                                    : entry.name;
    const fs::path target = output / fs::u8path(output_name);
    std::error_code ec;
    if (entry.dir) {
        if (!fs::create_directory(target, ec) && ec) {
            error = "A folder from the disc image could not be created.";
            return false;
        }
        for (const kernel::Disc::Entry& child : entry.children)
            if (!CopyEntry(disc, child, target, buffer, files, bytes, depth + 1, error)) return false;
        return true;
    }

    const fs::path part = target.string() + ".part";
    std::ofstream stream(part, std::ios::binary | std::ios::trunc);
    if (!stream) {
        error = "A game file could not be created. Check storage access and free space.";
        return false;
    }
    uint64_t offset = 0;
    while (offset < entry.size) {
        const uint32_t wanted = uint32_t(std::min<uint64_t>(buffer.size(), uint64_t(entry.size) - offset));
        const uint32_t got = disc.Read(entry, offset, buffer.data(), wanted);
        if (got != wanted) {
            stream.close();
            std::error_code ignored;
            fs::remove(part, ignored);
            error = "The disc image is incomplete or could not be read.";
            return false;
        }
        stream.write(reinterpret_cast<const char*>(buffer.data()), got);
        if (!stream) {
            stream.close();
            std::error_code ignored;
            fs::remove(part, ignored);
            error = "Writing a game file failed. Check free space.";
            return false;
        }
        offset += got;
    }
    stream.close();
    if (!stream) {
        std::error_code ignored;
        fs::remove(part, ignored);
        error = "Writing a game file failed. Check free space.";
        return false;
    }
    fs::rename(part, target, ec);
    if (ec) {
        std::error_code ignored;
        fs::remove(part, ignored);
        error = "A game file could not be finalized.";
        return false;
    }
    ++files;
    bytes += entry.size;
    return true;
}

}  // namespace

namespace androidhost {

std::string ExtractDiscImage(const std::string& image_path, const std::string& destination_path) {
    if (image_path.empty() || destination_path.empty()) return "The selected image or destination folder is empty.";

    const fs::path image = fs::u8path(image_path);
    const fs::path destination = fs::u8path(destination_path);
    std::error_code ec;
    if (!fs::is_regular_file(image, ec)) return "The temporary disc image could not be found.";
    if (fs::exists(destination, ec)) return "The temporary extraction folder already exists.";

    kernel::Disc disc;
    std::string disc_error;
    if (!disc.Open(image, disc_error)) return "This is not a usable original-Xbox disc image: " + DisplayName(disc_error);
    const kernel::Disc::Entry* root = disc.Find(std::string_view());
    const kernel::Disc::Entry* xbe = disc.Find("default.xbe");
    const kernel::Disc::Entry* data = disc.Find("data");
    if (!root || !root->dir || !xbe || xbe->dir || !data || !data->dir)
        return "This image does not contain both default.xbe and the data folder required by Spider-Man 2.";

    if (!fs::create_directories(destination, ec) || ec) return "The temporary game folder could not be created.";
    bool complete = false;
    const auto remove_incomplete = [&] {
        if (!complete) {
            std::error_code ignored;
            fs::remove_all(destination, ignored);
        }
    };

    std::vector<uint8_t> buffer(kCopyBuffer);
    uint64_t files = 0, bytes = 0;
    std::string error;
    for (const kernel::Disc::Entry& entry : root->children) {
        if (!CopyEntry(disc, entry, destination, buffer, files, bytes, 0, error)) {
            remove_incomplete();
            return error;
        }
    }
    if (!fs::is_regular_file(destination / "default.xbe", ec) || !fs::is_directory(destination / "data", ec)) {
        remove_incomplete();
        return "The disc image did not unpack the required default.xbe and data folder.";
    }

    complete = true;
    return {};
}

}  // namespace androidhost
