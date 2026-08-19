(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.kkrepoProductUpdate = api;
  if (root.document && typeof root.fetch === "function") {
    api.bind(root.document, root);
  }
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
  const ENDPOINT = "/internal/version-update";
  const POLL_INTERVAL_MS = 30_000;
  const RELEASE_PATH_PREFIX = "/klboke/kkrepo/releases/tag/";

  function releaseDetails(payload) {
    if (!payload || payload.status !== "ok" || payload.updateAvailable !== true) return null;
    const latestVersion = String(payload.latestVersion || "").trim();
    const releaseUrl = String(payload.releaseUrl || "").trim();
    if (!latestVersion || latestVersion.length > 64 || !releaseUrl) return null;
    try {
      const parsed = new URL(releaseUrl);
      if (parsed.protocol !== "https:"
          || parsed.hostname.toLowerCase() !== "github.com"
          || !parsed.pathname.toLowerCase().startsWith(RELEASE_PATH_PREFIX)) {
        return null;
      }
    } catch {
      return null;
    }
    return { latestVersion, releaseUrl };
  }

  function updateMessage(latestVersion) {
    return `New version v${latestVersion} available. View release.`;
  }

  function render(link, details, windowRef) {
    const englishMessage = updateMessage(details.latestVersion);
    const translate = windowRef.kkrepoI18n?.text;
    const message = typeof translate === "function"
      ? translate(englishMessage)
      : englishMessage;
    link.setAttribute("href", details.releaseUrl);
    link.setAttribute("aria-label", message);
    const tooltip = link.querySelector("[data-product-update-tooltip]");
    if (tooltip) tooltip.textContent = message;
    link.hidden = false;
  }

  function hide(link) {
    link.hidden = true;
    link.removeAttribute("href");
  }

  function bind(documentRef, windowRef) {
    const link = documentRef.querySelector("[data-product-update]");
    const version = documentRef.querySelector("[data-current-version]")
        ?.dataset.currentVersion?.trim();
    if (!link || !version) return null;

    let inFlight = false;
    let latestDetails = null;
    async function check() {
      if (inFlight) return false;
      inFlight = true;
      try {
        const response = await windowRef.fetch(
          `${ENDPOINT}?currentVersion=${encodeURIComponent(version)}`,
          {
            cache: "no-store",
            headers: { Accept: "application/json" }
          }
        );
        if (!response.ok) return false;
        const payload = await response.json();
        if (payload.status !== "ok") return false;
        if (payload.updateAvailable === false) {
          latestDetails = null;
          hide(link);
          return true;
        }
        const nextDetails = releaseDetails(payload);
        if (!nextDetails) return false;
        latestDetails = nextDetails;
        render(link, latestDetails, windowRef);
        return true;
      } catch {
        return false;
      } finally {
        inFlight = false;
      }
    }

    const initialCheck = check();
    const intervalId = windowRef.setInterval(() => {
      void check();
    }, POLL_INTERVAL_MS);
    windowRef.addEventListener?.("kkrepo:i18n-change", () => {
      if (latestDetails) render(link, latestDetails, windowRef);
    });
    return { check, initialCheck, intervalId };
  }

  return {
    bind,
    pollIntervalMs: POLL_INTERVAL_MS,
    releaseDetails,
    updateMessage
  };
}));
