import { fetchSearchComponents } from "./api.js";
import { lucideIcon } from "../controls/icons.js";
import { escapeHtml } from "../details/format.js";
import { formatsState, selectSearchFormat, setSearchSubnavOpen } from "./formats.js";
import {
  DEFAULT_SEARCH_FORMAT,
  componentBrowsePath,
  normalizeCustomSearchFormat,
  normalizeSearchFormat,
  pushBrowseRoute,
  searchHash,
  switchView,
} from "../shell/routes.js";
import { showRepositoryTree } from "../tree/browser.js";

let componentsCache = [];

let searchRequestSeq = 0;

function activateSearch(
    format = DEFAULT_SEARCH_FORMAT,
    syncHash = true,
    customFormat = formatsState.activeCustomSearchFormat) {
  const normalized = normalizeSearchFormat(format);
  formatsState.activeCustomSearchFormat = normalizeCustomSearchFormat(customFormat);
  if (syncHash) pushBrowseRoute(searchHash(normalized, "", formatsState.activeCustomSearchFormat));
  setSearchSubnavOpen(true);
  switchView("search");
  selectSearchFormat(normalized, formatsState.activeCustomSearchFormat);
  return normalized;
}

function showSearch(
    format = DEFAULT_SEARCH_FORMAT,
    syncHash = true,
    keyword = null,
    customFormat = formatsState.activeCustomSearchFormat) {
  if (keyword !== null) document.getElementById("component-keyword").value = keyword;
  renderSearch(
      activateSearch(format, syncHash, customFormat),
      formatsState.activeCustomSearchFormat);
}

function openSearchResult(component) {
  if (!component || !component.repository) return;
  showRepositoryTree(component.repository, true, componentBrowsePath(component), component.details?.sourceRepository || "");
}

async function renderSearch(
    format = DEFAULT_SEARCH_FORMAT,
    customFormat = formatsState.activeCustomSearchFormat,
    dockerCursor = null,
    previousRows = []) {
  const seq = ++searchRequestSeq;
  const normalizedFormat = normalizeSearchFormat(format);
  formatsState.activeCustomSearchFormat = normalizeCustomSearchFormat(customFormat);
  const keyword = document.getElementById("component-keyword").value.trim().toLowerCase();
  const target = `/browse/${searchHash(normalizedFormat, keyword, formatsState.activeCustomSearchFormat)}`;
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  if (current !== target) window.history.replaceState(null, "", target);
  window.kkrepoGlobalComponentSearch?.setKeyword(document, keyword);
  document.getElementById("component-table").innerHTML =
    '<tr><td colspan="7" class="muted-row">Searching...</td></tr>';
  let payload;
  try {
    payload = await fetchSearchComponents(
        normalizedFormat,
        keyword,
        formatsState.activeCustomSearchFormat,
        dockerCursor);
  } catch (error) {
    if (seq !== searchRequestSeq) return;
    document.getElementById("component-total").textContent = "0";
    document.getElementById("component-table").innerHTML =
      `<tr><td colspan="7" class="muted-row">Search failed: ${escapeHtml(error.message)}</td></tr>`;
    return;
  }
  if (seq !== searchRequestSeq) return;
  const rows = [...previousRows, ...(payload.items || [])];
  componentsCache = rows;
  document.getElementById("component-total").textContent = rows.length.toLocaleString("en-US");
  if (!rows.length) {
    document.getElementById("component-table").innerHTML =
      `<tr><td colspan="7" class="muted-row">${payload.dockerCursor ? "No matching components in this page." : "No components found."}</td></tr>`;
  } else {
    document.getElementById("component-table").innerHTML = rows.map((component, index) => {
      const browsePath = componentBrowsePath(component);
      const title = browsePath
        ? `Open ${component.repository}/${browsePath} in Browse`
        : `Open ${component.repository} in Browse`;
      return `
      <tr class="component-result-row" data-component-result-index="${index}" tabindex="0" title="${escapeHtml(title)}">
        <td class="icon-column">${lucideIcon("package-search", "component-icon")}</td>
        <td>${escapeHtml(component.name)}</td>
        <td>${component.group ? escapeHtml(component.group) : lucideIcon("circle-slash", "health-muted")}</td>
        <td>${escapeHtml(component.version)}</td>
        <td>${escapeHtml(component.format)}</td>
        <td>${escapeHtml(component.repository)}${component.details?.sourceRepository && component.details.sourceRepository !== component.repository
          ? ` <span class="health-muted">(${escapeHtml(component.details.sourceRepository)})</span>` : ""}</td>
        <td class="expand-column">${lucideIcon("chevron-right")}</td>
      </tr>
    `;
    }).join("");
  }
  if (payload.dockerCursor) {
    const table = document.getElementById("component-table");
    table.insertAdjacentHTML("beforeend", '<tr><td colspan="7"><button type="button" id="continue-docker-search">Continue Docker search</button></td></tr>');
    document.getElementById("continue-docker-search").addEventListener("click", () => {
      const unchanged = document.getElementById("component-keyword").value.trim().toLowerCase() === keyword;
      return renderSearch(normalizedFormat, formatsState.activeCustomSearchFormat,
          unchanged ? payload.dockerCursor : null, unchanged ? rows : []);
    });
  }
  document.querySelectorAll("#component-table .component-result-row").forEach((tr) => {
    const result = rows[Number(tr.dataset.componentResultIndex)];
    tr.addEventListener("click", () => openSearchResult(result));
    tr.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      openSearchResult(result);
    });
  });
}

export {
  renderSearch,
  showSearch,
};
