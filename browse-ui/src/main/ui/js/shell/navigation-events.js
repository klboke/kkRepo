import { login } from "./session.js";
import { showRepositoryList } from "../repositories/list.js";
import { setSearchSubnavOpen } from "../search/formats.js";
import { applyHashRoute, showWelcome } from "./routes.js";
import { handleLoginSuccess } from "./start.js";
import { showUpload } from "../upload/catalog.js";

export function bindShellEvents() {
  document.querySelectorAll(".side-item").forEach((item) => {
    item.addEventListener("click", () => {
      const view = item.dataset.view;
      if (view === "search") {
        const subnav = document.getElementById("search-subnav");
        setSearchSubnavOpen(subnav ? subnav.hidden : true);
        return;
      }
      if (view === "browse") showRepositoryList();
      else if (view === "upload") showUpload();
      else if (view === "welcome") showWelcome();
    });
  });

  window.addEventListener("hashchange", applyHashRoute);

  window.addEventListener("popstate", applyHashRoute);

  window.addEventListener("kkrepo:login-success", handleLoginSuccess);
}
