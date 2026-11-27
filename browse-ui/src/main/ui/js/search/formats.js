import { formatIconName } from "../controls/icons.js";
import { escapeHtml } from "../details/format.js";
import {
  CUSTOM_SEARCH_FORMAT,
  normalizeCustomSearchFormat,
  normalizeSearchFormat,
  searchFormatLabel,
  searchPageTitle,
} from "../shell/routes.js";

let activeSearchFormat = "maven2";

const formatsState = {
  activeCustomSearchFormat: "maven2",
};

let activeCustomSearchFormatOption = null;

function setSearchSubnavOpen(open) {
  const toggle = document.querySelector('.side-item[data-view="search"]');
  const subnav = document.getElementById("search-subnav");
  if (!toggle || !subnav) return;
  toggle.classList.toggle("is-expanded", open);
  toggle.setAttribute("aria-expanded", open ? "true" : "false");
  subnav.hidden = !open;
}

function clearSearchFormatSelection() {
  document.querySelectorAll(".side-subitem").forEach((entry) => {
    entry.classList.remove("is-active");
  });
}

function customSearchFormatOptionElements() {
  return Array.from(document.querySelectorAll("[data-custom-search-format]"));
}

function visibleCustomSearchFormatOptionElements() {
  return customSearchFormatOptionElements().filter((option) => !option.hidden);
}

function setActiveCustomSearchFormatOption(format, scroll = false) {
  const filter = document.getElementById("component-custom-format-filter");
  activeCustomSearchFormatOption = format || null;
  let activeOption = null;
  customSearchFormatOptionElements().forEach((option) => {
    const active = !option.hidden
      && option.dataset.customSearchFormat === activeCustomSearchFormatOption;
    option.classList.toggle("is-active", active);
    if (active) activeOption = option;
  });
  if (activeOption) {
    filter.setAttribute("aria-activedescendant", activeOption.id);
    if (scroll) activeOption.scrollIntoView({ block: "nearest" });
  } else {
    filter.removeAttribute("aria-activedescendant");
  }
}

function filterCustomSearchFormatOptions() {
  const filter = document.getElementById("component-custom-format-filter");
  const empty = document.getElementById("component-custom-format-empty");
  const query = filter.value.trim().toLowerCase();
  customSearchFormatOptionElements().forEach((option) => {
    const searchable = `${option.dataset.customSearchFormat} ${option.textContent}`.toLowerCase();
    option.hidden = Boolean(query) && !searchable.includes(query);
  });
  const visible = visibleCustomSearchFormatOptionElements();
  empty.hidden = visible.length > 0;
  const currentActive = visible.find(
      (option) => option.dataset.customSearchFormat === activeCustomSearchFormatOption);
  const selected = visible.find(
      (option) => option.dataset.customSearchFormat === formatsState.activeCustomSearchFormat);
  setActiveCustomSearchFormatOption(
      (currentActive || selected || visible[0])?.dataset.customSearchFormat || null);
}

function syncCustomSearchFormatCombobox() {
  const select = document.getElementById("component-custom-format");
  const value = document.getElementById("component-custom-format-value");
  const normalized = normalizeCustomSearchFormat(formatsState.activeCustomSearchFormat);
  formatsState.activeCustomSearchFormat = normalized;
  select.value = normalized;
  value.innerHTML = `
    <span class="format-logo format-logo-${formatIconName(normalized)}" aria-hidden="true"></span>
    <span>${escapeHtml(searchFormatLabel(normalized))}</span>
  `;
  customSearchFormatOptionElements().forEach((option) => {
    option.setAttribute(
        "aria-selected",
        String(option.dataset.customSearchFormat === normalized));
  });
}

function openCustomSearchFormatCombobox() {
  const trigger = document.getElementById("component-custom-format-trigger");
  const popover = document.getElementById("component-custom-format-popover");
  const filter = document.getElementById("component-custom-format-filter");
  if (!popover.hidden) return;
  syncCustomSearchFormatCombobox();
  filter.value = "";
  activeCustomSearchFormatOption = formatsState.activeCustomSearchFormat;
  filterCustomSearchFormatOptions();
  popover.hidden = false;
  trigger.setAttribute("aria-expanded", "true");
  filter.setAttribute("aria-expanded", "true");
  setTimeout(() => {
    filter.focus();
    setActiveCustomSearchFormatOption(activeCustomSearchFormatOption, true);
  }, 0);
}

function closeCustomSearchFormatCombobox(focusTrigger = false) {
  const trigger = document.getElementById("component-custom-format-trigger");
  const popover = document.getElementById("component-custom-format-popover");
  const filter = document.getElementById("component-custom-format-filter");
  popover.hidden = true;
  trigger.setAttribute("aria-expanded", "false");
  filter.setAttribute("aria-expanded", "false");
  filter.removeAttribute("aria-activedescendant");
  activeCustomSearchFormatOption = null;
  if (focusTrigger) trigger.focus();
}

function toggleCustomSearchFormatCombobox() {
  const popover = document.getElementById("component-custom-format-popover");
  if (popover.hidden) openCustomSearchFormatCombobox();
  else closeCustomSearchFormatCombobox(true);
}

function selectCustomSearchFormat(format) {
  const select = document.getElementById("component-custom-format");
  select.value = normalizeCustomSearchFormat(format);
  select.dispatchEvent(new Event("change", { bubbles: true }));
  closeCustomSearchFormatCombobox(true);
}

function moveActiveCustomSearchFormatOption(offset) {
  const options = visibleCustomSearchFormatOptionElements();
  if (options.length === 0) return;
  let currentIndex = options.findIndex(
      (option) => option.dataset.customSearchFormat === activeCustomSearchFormatOption);
  if (currentIndex < 0) currentIndex = offset > 0 ? -1 : 0;
  const nextIndex = (currentIndex + offset + options.length) % options.length;
  setActiveCustomSearchFormatOption(options[nextIndex].dataset.customSearchFormat, true);
}

function bindCustomSearchFormatCombobox() {
  const root = document.getElementById("component-custom-format-combobox");
  const select = document.getElementById("component-custom-format");
  const trigger = document.getElementById("component-custom-format-trigger");
  const filter = document.getElementById("component-custom-format-filter");
  const options = document.getElementById("component-custom-format-options");

  syncCustomSearchFormatCombobox();
  customSearchFormatOptionElements().forEach((option) => { option.tabIndex = -1; });
  trigger.addEventListener("click", toggleCustomSearchFormatCombobox);
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      openCustomSearchFormatCombobox();
      return;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      closeCustomSearchFormatCombobox(true);
    }
  });
  select.addEventListener("change", (event) => {
    formatsState.activeCustomSearchFormat = normalizeCustomSearchFormat(event.target.value);
    syncCustomSearchFormatCombobox();
  });
  filter.addEventListener("input", filterCustomSearchFormatOptions);
  filter.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      moveActiveCustomSearchFormatOption(event.key === "ArrowDown" ? 1 : -1);
      return;
    }
    if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      const visible = visibleCustomSearchFormatOptionElements();
      const target = event.key === "Home" ? visible[0] : visible.at(-1);
      setActiveCustomSearchFormatOption(target?.dataset.customSearchFormat || null, true);
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      if (activeCustomSearchFormatOption) {
        selectCustomSearchFormat(activeCustomSearchFormatOption);
      }
      return;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      closeCustomSearchFormatCombobox(true);
      return;
    }
    if (event.key === "Tab") closeCustomSearchFormatCombobox();
  });
  options.addEventListener("mousemove", (event) => {
    const option = event.target.closest("[data-custom-search-format]");
    if (option) setActiveCustomSearchFormatOption(option.dataset.customSearchFormat);
  });
  options.addEventListener("click", (event) => {
    const option = event.target.closest("[data-custom-search-format]");
    if (option) selectCustomSearchFormat(option.dataset.customSearchFormat);
  });
  document.addEventListener("click", (event) => {
    if (!root.contains(event.target)) closeCustomSearchFormatCombobox();
  });
}

function selectSearchFormat(format, customFormat = formatsState.activeCustomSearchFormat) {
  activeSearchFormat = normalizeSearchFormat(format);
  formatsState.activeCustomSearchFormat = normalizeCustomSearchFormat(customFormat);
  document.getElementById("component-search-title").textContent =
    searchPageTitle(activeSearchFormat);
  const customField = document.getElementById("component-custom-format-field");
  if (customField) customField.hidden = activeSearchFormat !== CUSTOM_SEARCH_FORMAT;
  syncCustomSearchFormatCombobox();
  if (activeSearchFormat !== CUSTOM_SEARCH_FORMAT) closeCustomSearchFormatCombobox();
  document.querySelectorAll(".side-subitem").forEach((entry) => {
    entry.classList.toggle("is-active", entry.dataset.searchFormat === activeSearchFormat);
  });
}

export {
  activeSearchFormat,
  bindCustomSearchFormatCombobox,
  clearSearchFormatSelection,
  formatsState,
  selectSearchFormat,
  setSearchSubnavOpen,
};
