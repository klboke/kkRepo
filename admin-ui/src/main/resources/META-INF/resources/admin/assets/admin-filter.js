(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  } else {
    root.kkrepoAdminFilters = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function normalizeFilter(filter) {
    return String(filter ?? "").trim().toLowerCase().replace(/\*+/g, "*");
  }

  function searchableText(values) {
    const raw = Array.isArray(values) ? values : [values];
    return raw.map((value) => String(value ?? "").toLowerCase()).join(" ");
  }

  function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function wildcardRegExp(filter) {
    return new RegExp(filter.split("*").map(escapeRegExp).join(".*"));
  }

  function matchesFilter(values, filter) {
    const normalized = normalizeFilter(filter);
    if (!normalized) return true;
    const text = searchableText(values);
    if (!normalized.includes("*")) return text.includes(normalized);
    return wildcardRegExp(normalized).test(text);
  }

  return {
    matchesFilter
  };
});
