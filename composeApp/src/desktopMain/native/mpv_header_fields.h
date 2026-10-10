#pragma once
#include <jni.h>
#include <string>
#include <vector>

// libmpv copies the typed list synchronously. No CSV parsing or credential logging.
namespace telumia_headers {
inline bool validJavaHeaders(JNIEnv *env, jobjectArray headers) {
    if (!headers) return true;
    const jsize count = env->GetArrayLength(headers);
    for (jsize i = 0; i < count; ++i) {
        auto line = static_cast<jstring>(env->GetObjectArrayElement(headers, i));
        if (!line) return false;
        const jsize length = env->GetStringLength(line);
        const jchar *chars = env->GetStringChars(line, nullptr);
        if (!chars) { env->DeleteLocalRef(line); return false; }
        bool valid = true;
        for (jsize index = 0; index < length; ++index) {
            if (chars[index] == 0 || chars[index] == '\r' || chars[index] == '\n') { valid = false; break; }
        }
        env->ReleaseStringChars(line, chars);
        env->DeleteLocalRef(line);
        if (!valid) return false;
    }
    return true;
}
inline void rejectInvalidJavaHeaders(JNIEnv *env) {
    if (env->ExceptionCheck()) return;
    jclass error = env->FindClass("java/lang/IllegalStateException");
    if (error) { env->ThrowNew(error, "Invalid HTTP headers"); env->DeleteLocalRef(error); }
}
inline int apply(mpv_handle *handle, const std::vector<std::string> &headers,
                 int (*setOption)(mpv_handle *, const char *, mpv_format, void *)) {
    if (headers.empty()) return 0;
    std::vector<mpv_node> nodes(headers.size());
    for (size_t i = 0; i < nodes.size(); ++i) {
        nodes[i].format = MPV_FORMAT_STRING;
        nodes[i].u.string = const_cast<char *>(headers[i].c_str());
    }
    mpv_node_list list{static_cast<int>(nodes.size()), nodes.data(), nullptr};
    mpv_node root{};
    root.format = MPV_FORMAT_NODE_ARRAY;
    root.u.list = &list;
    return setOption(handle, "http-header-fields", MPV_FORMAT_NODE, &root);
}
}
