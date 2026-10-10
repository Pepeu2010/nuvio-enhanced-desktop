#pragma once
#include <string>
#include <vector>

// libmpv copies the typed list synchronously. No CSV parsing or credential logging.
namespace telumia_headers {
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
