import { ALL_SEARCH_FORMAT, COMPONENT_SEARCH_LIMIT, CUSTOM_SEARCH_FORMAT, DEFAULT_SEARCH_FORMAT, normalizeCustomSearchFormat, normalizeSearchFormat } from "../shell/routes.js";

function componentSearchParams(format, keyword, customFormat = DEFAULT_SEARCH_FORMAT) {
  const params = new URLSearchParams();
  const q = (keyword || "").trim();
  if (q) params.set("q", q);
  const normalizedFormat = normalizeSearchFormat(format);
  const effectiveFormat = normalizedFormat === CUSTOM_SEARCH_FORMAT
    ? normalizeCustomSearchFormat(customFormat)
    : normalizedFormat;
  if (effectiveFormat !== ALL_SEARCH_FORMAT) params.set("format", effectiveFormat);
  params.set("limit", String(COMPONENT_SEARCH_LIMIT));
  return params;
}

async function fetchSearchComponents(format, keyword, customFormat = DEFAULT_SEARCH_FORMAT, dockerCursor = null) {
  const params = componentSearchParams(format, keyword, customFormat);
  if (dockerCursor) params.set("dockerCursor", dockerCursor);
  const res = await fetch(`/internal/search/components?${params.toString()}`, {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Search failed: ${res.status}`);
  return res.json();
}

export { fetchSearchComponents };
