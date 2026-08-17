(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.kkrepoGlobalComponentSearch = api;
  if (root.document && root.location) api.bind(root.document, root.location);
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
  const SEARCH_ROUTE = "/browse/#browse/search/custom";

  function normalizeKeyword(value) {
    return String(value || "").trim();
  }

  function target(keyword) {
    const normalized = normalizeKeyword(keyword);
    if (!normalized) return null;
    return `${SEARCH_ROUTE}?${new URLSearchParams({ q: normalized }).toString()}`;
  }

  function keywordFromHash(hash) {
    const value = String(hash || "").replace(/^#/, "");
    const separator = value.indexOf("?");
    if (separator === -1) return "";
    return normalizeKeyword(new URLSearchParams(value.slice(separator + 1)).get("q"));
  }

  function setKeyword(documentRef, keyword) {
    const input = documentRef.querySelector("[data-global-component-search] input[name='q']");
    if (input) input.value = normalizeKeyword(keyword);
  }

  function bind(documentRef, locationRef) {
    const form = documentRef.querySelector("[data-global-component-search]");
    const input = form?.querySelector("input[name='q']");
    if (!form || !input) return;
    const initialKeyword = keywordFromHash(locationRef.hash);
    if (initialKeyword) input.value = initialKeyword;
    form.addEventListener("submit", (event) => {
      const destination = target(input.value);
      event.preventDefault();
      if (!destination) {
        input.focus();
        return;
      }
      locationRef.assign(destination);
    });
  }

  return { bind, keywordFromHash, normalizeKeyword, setKeyword, target };
}));
