import { renderRepoList, toggleRepositorySort } from "./list.js";
import { state } from "../tree/browser.js";

export function bindRepositoriesEvents() {
  document.getElementById("repository-filter").addEventListener("input", () => {
    if (state.mode === "repos") renderRepoList();
  });

  document.querySelectorAll("[data-repo-sort]").forEach((button) => {
    button.addEventListener("click", () => toggleRepositorySort(button.dataset.repoSort));
  });
}
