import { activeSearchFormat, bindCustomSearchFormatCombobox, formatsState } from "./formats.js";
import { renderSearch, showSearch } from "./results.js";

export function bindSearchEvents() {
  document.querySelectorAll(".side-subitem").forEach((item) => {
    item.addEventListener("click", () => {
      showSearch(item.dataset.searchFormat);
    });
  });

  bindCustomSearchFormatCombobox();

  document.getElementById("component-search-form").addEventListener("submit", (event) => {
    event.preventDefault();
    renderSearch(activeSearchFormat, formatsState.activeCustomSearchFormat);
  });
}
