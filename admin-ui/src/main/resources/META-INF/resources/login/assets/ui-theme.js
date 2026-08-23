(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root && root.document) root.kkrepoTheme = api.bind(root.document, root);
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  const STORAGE_KEY = "kkrepo.uiSettings";
  const DEFAULT_THEME = "default";
  const THEME_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;

  function normalizeSupportedThemes(value) {
    const themes = Array.isArray(value)
      ? value.map((theme) => String(theme || "").trim().toLowerCase())
        .filter((theme) => THEME_ID_PATTERN.test(theme))
      : [];
    if (!themes.includes(DEFAULT_THEME)) themes.unshift(DEFAULT_THEME);
    return [...new Set(themes)];
  }

  function normalizeTheme(value, supportedThemes) {
    const themes = normalizeSupportedThemes(supportedThemes);
    const normalized = String(value || "").trim().toLowerCase();
    return THEME_ID_PATTERN.test(normalized) && themes.includes(normalized)
      ? normalized
      : DEFAULT_THEME;
  }

  function stylesheetHref(theme) {
    return `/browse/assets/themes/${encodeURIComponent(theme)}.css?v=20260824-ui-themes-1`;
  }

  function bind(documentRef, windowRef) {
    const rootElement = documentRef.documentElement;
    const stylesheet = documentRef.getElementById("kkrepo-theme-stylesheet");
    let currentTheme = DEFAULT_THEME;

    function applyTheme(value, supportedThemes) {
      const normalized = normalizeTheme(value, supportedThemes);
      currentTheme = normalized;
      rootElement?.setAttribute("data-theme", normalized);
      if (stylesheet) {
        stylesheet.onerror = normalized === DEFAULT_THEME
          ? null
          : () => applyTheme(DEFAULT_THEME, [DEFAULT_THEME]);
        stylesheet.setAttribute("href", stylesheetHref(normalized));
      }
      return normalized;
    }

    function applySettings(settings) {
      return applyTheme(settings?.defaultTheme, settings?.supportedDefaultThemes);
    }

    try {
      applySettings(JSON.parse(windowRef.localStorage.getItem(STORAGE_KEY) || "{}"));
    } catch {
      applyTheme(DEFAULT_THEME, [DEFAULT_THEME]);
    }

    return {
      applySettings,
      applyTheme,
      currentTheme: () => currentTheme,
      stylesheetHref
    };
  }

  return {
    DEFAULT_THEME,
    bind,
    normalizeSupportedThemes,
    normalizeTheme,
    stylesheetHref
  };
});
