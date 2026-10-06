(function () {
  installCsrfFetch();

  function installCsrfFetch() {
    if (window.__nexusPlusCsrfFetchInstalled) return;
    window.__nexusPlusCsrfFetchInstalled = true;
    const nativeFetch = window.fetch.bind(window);
    window.fetch = (input, init = {}) => {
      const method = String(init.method || "GET").toUpperCase();
      if (["POST", "PUT", "PATCH", "DELETE", "MKCOL"].includes(method) && sameOrigin(input)) {
        const token = csrfToken();
        if (token) {
          const headers = new Headers(init.headers || {});
          headers.set("X-Nexus-Plus-CSRF-Token", token);
          init = { ...init, headers };
        }
      }
      return nativeFetch(input, init);
    };
  }

  function sameOrigin(input) {
    const url = typeof input === "string" ? input : input.url;
    return new URL(url, window.location.origin).origin === window.location.origin;
  }

  function csrfToken() {
    return document.cookie
      .split(";")
      .map((part) => part.trim())
      .find((part) => part.startsWith("KKREPO_CSRF="))
      ?.substring("KKREPO_CSRF=".length) || "";
  }
})();
