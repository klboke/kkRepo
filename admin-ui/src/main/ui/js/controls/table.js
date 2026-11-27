function updateTableSortHeaders(kind, sort) {
  document.querySelectorAll(`[data-${kind}-sort]`).forEach((button) => {
    const active = button.dataset[`${kind}Sort`] === sort.key;
    const direction = active ? sort.direction : null;
    const indicator = button.querySelector(".repo-sort-indicator");
    button.classList.toggle("is-active", active);
    const label = button.querySelector("span:last-child").textContent;
    button.setAttribute("aria-label", `${label} sort ${!active ? "none" : direction === "asc" ? "ascending" : "descending"}`);
    button.closest("th").setAttribute(
      "aria-sort",
      !active ? "none" : direction === "asc" ? "ascending" : "descending",
    );
    if (indicator) {
      indicator.classList.add("lucide-icon");
      indicator.classList.toggle("icon-arrow-up-down", !active);
      indicator.classList.toggle("icon-arrow-up", active && direction === "asc");
      indicator.classList.toggle("icon-arrow-down", active && direction === "desc");
    }
  });
}

export {
  updateTableSortHeaders,
};
