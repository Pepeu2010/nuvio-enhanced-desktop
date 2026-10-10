#pragma once
#include <cmath>
#include <cstdint>
#include <limits>
#include <sstream>
#include <string>

namespace telumia_chapters {
using ReadProperty = int (*)(mpv_handle *, const char *, mpv_format, void *);
using FreeNode = void (*)(mpv_node *);
inline std::string quoted(const std::string &value) {
    static const char hex[] = "0123456789abcdef";
    std::string result = "\"";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') { result += '\\'; result += c; }
        else if (c < 32) { result += "\\u00"; result += hex[c >> 4]; result += hex[c & 15]; }
        else result += c;
    }
    return result + "\"";
}
inline std::string snapshot(mpv_handle *mpv, ReadProperty property, FreeNode freeNode) {
    const std::string empty = "{\"chapters\":[],\"complete\":false}";
    if (!mpv || !property || !freeNode) return empty;
    mpv_node root{};
    if (property(mpv, "chapter-list", MPV_FORMAT_NODE, &root) < 0) return empty;
    struct Release { mpv_node *root; FreeNode free; ~Release() { free(root); } } release{&root, freeNode};
    try {
        if (root.format != MPV_FORMAT_NODE_ARRAY || !root.u.list || root.u.list->num < 0 ||
            (root.u.list->num && !root.u.list->values)) return empty;
        bool complete = root.u.list->num <= 512;
        std::ostringstream rows;
        bool comma = false;
        for (int index = 0; index < root.u.list->num && index < 512; ++index) {
            const mpv_node &row = root.u.list->values[index];
            if (row.format != MPV_FORMAT_NODE_MAP || !row.u.list || row.u.list->num < 0 ||
                row.u.list->num > 64 || !row.u.list->values || !row.u.list->keys) { complete = false; continue; }
            double seconds = -1;
            std::string title;
            for (int field = 0; field < row.u.list->num; ++field) {
                const char *key = row.u.list->keys[field];
                if (!key) continue;
                const mpv_node &value = row.u.list->values[field];
                if (std::string(key) == "time") {
                    if (value.format == MPV_FORMAT_DOUBLE) seconds = value.u.double_;
                    else if (value.format == MPV_FORMAT_INT64) seconds = static_cast<double>(value.u.int64);
                } else if (std::string(key) == "title" && value.format == MPV_FORMAT_STRING && value.u.string) {
                    size_t length = 0;
                    while (length <= 1024 && value.u.string[length]) ++length;
                    if (length <= 1024) title.assign(value.u.string, length);
                }
            }
            if (!std::isfinite(seconds) || seconds < 0 ||
                seconds >= static_cast<double>(std::numeric_limits<int64_t>::max() / 1000 - 1)) {
                complete = false; continue;
            }
            if (comma) rows << ',';
            comma = true;
            rows << "{\"index\":" << index << ",\"startMs\":" << std::llround(seconds * 1000.0)
                 << ",\"title\":" << quoted(title) << '}';
        }
        return "{\"chapters\":[" + rows.str() + "],\"complete\":" + (complete ? "true}" : "false}");
    } catch (...) { return empty; }
}
inline jbyteArray bytes(JNIEnv *env, const std::string &json) {
    if (json.size() > 1024 * 1024) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(json.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(json.size()),
        reinterpret_cast<const jbyte *>(json.data()));
    return result;
}
}
