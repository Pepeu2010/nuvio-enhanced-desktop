/* Telumia timeline projection. Provider text is always rendered with textContent. */
(function (root) {
  "use strict";
  const kinds = new Set(["INTRO", "RECAP", "CREDITS", "POST_CREDITS", "CHAPTER", "BOOKMARK"]);
  const normalize = input => {
    if (!Array.isArray(input)) return [];
    const result = [];
    for (const item of input.slice(0, 256)) {
      if (!item || !kinds.has(item.kind) || typeof item.startFraction !== "number" ||
          !Number.isFinite(item.startFraction) || item.startFraction < 0 || item.startFraction > 1 ||
          typeof item.label !== "string" || !item.label.trim()) continue;
      const end = item.endFraction;
      if (end !== null && end !== undefined && (typeof end !== "number" || !Number.isFinite(end) || end <= item.startFraction || end > 1)) continue;
      result.push({ id: String(item.id || "").slice(0, 1280), kind: item.kind,
        startFraction: item.startFraction, endFraction: end ?? null,
        label: item.label.trim().slice(0, 256), providerId: String(item.providerId || "").slice(0, 256) });
    }
    return result;
  };
  const createRenderer = (layer, summary, seek) => {
    let previous = "";
    return input => {
      const markers = normalize(input);
      const signature = JSON.stringify(markers);
      if (signature === previous) return;
      previous = signature;
      const document = layer.ownerDocument;
      const fragment = document.createDocumentFragment();
      markers.forEach(marker => {
        const element = document.createElement("span");
        element.className = "timed-marker";
        element.dataset.kind = marker.kind;
        element.style.left = `${marker.startFraction * 100}%`;
        element.style.width = marker.endFraction === null ? "0px" : `${(marker.endFraction - marker.startFraction) * 100}%`;
        element.title = marker.label;
        fragment.appendChild(element);
      });
      layer.replaceChildren(fragment);
      layer.hidden = markers.length === 0;
      summary.textContent = markers.slice(0, 12).map(marker => marker.label).join(" · ");
      if (markers.length) seek.setAttribute("aria-describedby", summary.id);
      else seek.removeAttribute("aria-describedby");
    };
  };
  const api = Object.freeze({ normalize, createRenderer });
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TelumiaTimedMetadata = api;
})(typeof globalThis === "object" ? globalThis : this);
