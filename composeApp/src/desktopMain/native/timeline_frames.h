// Shared libmpv client worker. It never receives or seeks the main player's handle.
#pragma once
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace telumia_frames {
struct Api {
    mpv_handle *(*create)();
    int (*initialize)(mpv_handle *);
    void (*destroy)(mpv_handle *);
    int (*option)(mpv_handle *, const char *, const char *);
    int (*command)(mpv_handle *, const char **);
    int (*commandRet)(mpv_handle *, const char **, mpv_node *);
    int (*property)(mpv_handle *, const char *, mpv_format, void *);
    mpv_event *(*event)(mpv_handle *, double);
    void (*freeNode)(mpv_node *);
    void (*wakeup)(mpv_handle *);
    int (*optionNode)(mpv_handle *, const char *, mpv_format, void *);
};
static Api platformApi();
struct Worker {
    Api api;
    mpv_handle *mpv = nullptr;
    std::mutex operation;
    std::atomic<uint64_t> revision{0};
    std::string source;
    bool loaded = false;
    ~Worker() { if (mpv) api.destroy(mpv); }
};
static std::mutex registryMutex;
static std::unordered_map<jlong, std::shared_ptr<Worker>> registry;
static jlong nextId = 1;
static std::shared_ptr<Worker> find(jlong id) {
    std::lock_guard<std::mutex> guard(registryMutex);
    auto found = registry.find(id);
    return found == registry.end() ? nullptr : found->second;
}
static bool ready(Worker &worker, uint64_t revision) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
    while (worker.revision.load() == revision && std::chrono::steady_clock::now() < deadline) {
        auto *event = worker.api.event(worker.mpv, 0.025);
        if (!event) return false;
        if (event->event_id == 21) return event->error >= 0; // MPV_EVENT_PLAYBACK_RESTART
        if (event->event_id == 7 || event->event_id == 1) return false;
    }
    return false;
}
static void drain(Worker &worker) {
    for (int i = 0; i < 1024; ++i) {
        auto *event = worker.api.event(worker.mpv, 0);
        if (!event || event->event_id == 0) break;
    }
}
static mpv_node *field(mpv_node &node, const char *key) {
    if (node.format != MPV_FORMAT_NODE_MAP || !node.u.list || node.u.list->num < 0 || node.u.list->num > 32) return nullptr;
    auto *list = node.u.list;
    if (!list->keys || !list->values) return nullptr;
    for (int i = 0; i < list->num; ++i)
        if (list->keys[i] && std::string(list->keys[i]) == key) return &list->values[i];
    return nullptr;
}
static int64_t integer(mpv_node &node, const char *key) {
    auto *value = field(node, key);
    return value && value->format == MPV_FORMAT_INT64 ? value->u.int64 : -1;
}
static void appendInteger(std::vector<uint8_t> &bytes, uint64_t value, int count) {
    for (int i = count - 1; i >= 0; --i) bytes.push_back(static_cast<uint8_t>(value >> (i * 8)));
}
} // namespace telumia_frames

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_createTimelineWorker(
    JNIEnv *env, jobject, jstring source, jobjectArray headers) {
    using namespace telumia_frames;
    try {
        auto api = platformApi();
        if (!api.commandRet || !api.freeNode) return 0; // Unsupported runtime leaves playback intact.
        auto worker = std::make_shared<Worker>();
        worker->api = api;
        worker->source = TELUMIA_FRAME_UTF8(env, source);
        if (worker->source.empty() || worker->source.size() > 16384 || worker->source.find('\0') != std::string::npos) return 0;
        worker->mpv = api.create();
        if (!worker->mpv) return 0;
        const char *options[][2] = {
            {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"},
            {"vo", "null"}, {"pause", "yes"}, {"audio", "no"}, {"sub", "no"},
            {"hwdec", "no"}, {"vd-lavc-threads", "1"}, {"cache", "no"},
            {"demuxer-max-bytes", "1048576"}, {"demuxer-max-back-bytes", "0"},
            {"network-timeout", "4"}, {"vf", "scale=320:180:force_original_aspect_ratio=decrease"}
        };
        for (auto &option : options) if (api.option(worker->mpv, option[0], option[1]) < 0) return 0;
        std::vector<std::string> headerValues;
        size_t headerBytes = 0;
        const auto count = headers ? env->GetArrayLength(headers) : 0;
        if (count > 32) return 0;
        headerValues.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            auto line = static_cast<jstring>(env->GetObjectArrayElement(headers, i));
            auto text = TELUMIA_FRAME_UTF8(env, line);
            env->DeleteLocalRef(line);
            if (text.find_first_of("\r\n\0", 0, 3) != std::string::npos || text.size() > 4096) return 0;
            headerBytes += text.size();
            if (headerBytes > 16384) return 0;
            headerValues.push_back(std::move(text));
        }
        if (!headerValues.empty()) {
            // Pass a typed list instead of parsing CSV; punctuation remains part of each value.
            std::vector<mpv_node> nodes(headerValues.size());
            for (size_t i = 0; i < nodes.size(); ++i) { nodes[i].format = MPV_FORMAT_STRING; nodes[i].u.string = headerValues[i].data(); }
            mpv_node_list list{static_cast<int>(nodes.size()), nodes.data(), nullptr};
            mpv_node root{}; root.format = MPV_FORMAT_NODE_ARRAY; root.u.list = &list;
            if (api.optionNode(worker->mpv, "http-header-fields", MPV_FORMAT_NODE, &root) < 0) return 0;
        }
        if (api.initialize(worker->mpv) < 0) return 0;
        std::lock_guard<std::mutex> guard(registryMutex);
        auto id = nextId++;
        registry.emplace(id, worker);
        return id;
    } catch (...) { return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_cancelTimelineWorker(JNIEnv *, jobject, jlong id) {
    if (auto worker = telumia_frames::find(id)) { ++worker->revision; worker->api.wakeup(worker->mpv); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_disposeTimelineWorker(JNIEnv *, jobject, jlong id) {
    using namespace telumia_frames;
    std::shared_ptr<Worker> worker;
    { std::lock_guard<std::mutex> guard(registryMutex); auto found = registry.find(id);
      if (found == registry.end()) return; worker = found->second; registry.erase(found); }
    ++worker->revision;
    worker->api.wakeup(worker->mpv);
    // Capture holds a shared_ptr. Destruction cannot race its libmpv calls.
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_captureTimelineFrame(JNIEnv *env, jobject, jlong id, jlong positionMs) {
    using namespace telumia_frames;
    auto worker = find(id);
    if (!worker || positionMs < 0 || positionMs > 604800000) return nullptr;
    std::lock_guard<std::mutex> guard(worker->operation);
    const auto revision = worker->revision.load();
    try {
        if (!worker->loaded) {
            drain(*worker);
            const char *command[] = {"loadfile", worker->source.c_str(), "replace", nullptr};
            if (worker->api.command(worker->mpv, command) < 0 || !ready(*worker, revision)) return nullptr;
            worker->loaded = true;
        }
        drain(*worker);
        auto position = std::to_string(static_cast<double>(positionMs) / 1000);
        const char *seek[] = {"seek", position.c_str(), "absolute+exact", nullptr};
        if (worker->api.command(worker->mpv, seek) < 0 || !ready(*worker, revision)) return nullptr;
        mpv_node node{};
        struct FreeNode { Worker &worker; mpv_node &node; ~FreeNode() { worker.api.freeNode(&node); } } release{*worker, node};
        const char *capture[] = {"screenshot-raw", "video", "bgr0", nullptr};
        if (worker->api.commandRet(worker->mpv, capture, &node) < 0 || worker->revision.load() != revision) return nullptr;
        const auto width = integer(node, "w"), height = integer(node, "h"), stride = integer(node, "stride");
        auto *format = field(node, "format"), *data = field(node, "data");
        if (width < 1 || width > 320 || height < 1 || height > 180 || stride < width * 4 || stride > 4096 ||
            !format || format->format != MPV_FORMAT_STRING || !format->u.string || std::string(format->u.string) != "bgr0" ||
            !data || data->format != MPV_FORMAT_BYTE_ARRAY || !data->u.ba || !data->u.ba->data ||
            data->u.ba->size < static_cast<size_t>(stride * height)) return nullptr;
        double seconds = 0;
        if (worker->api.property(worker->mpv, "time-pos", MPV_FORMAT_DOUBLE, &seconds) < 0 || !std::isfinite(seconds) || seconds < 0 ||
            std::abs(seconds * 1000 - positionMs) > 1250 || worker->revision.load() != revision) return nullptr;
        std::vector<uint8_t> bytes{'T','F','R','1'};
        appendInteger(bytes, width, 4); appendInteger(bytes, height, 4); appendInteger(bytes, static_cast<uint64_t>(std::llround(seconds * 1000)), 8);
        auto *pixels = static_cast<uint8_t *>(data->u.ba->data);
        for (int64_t row = 0; row < height; ++row) bytes.insert(bytes.end(), pixels + row * stride, pixels + row * stride + width * 4);
        auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
        return result;
    } catch (...) { return nullptr; }
}
#undef TELUMIA_FRAME_UTF8
