import { loadConanManifestDefaults } from "../repositories/conan.js";
import { loadRepositoryRecipes } from "../repositories/recipes.js";
import { applyHashRoute, sideGroupItems, switchView } from "./routes.js";
import {
  canAccessAdminView,
  canAdmin,
  hydrateSessionControls,
  loadCurrentSession,
  sessionState,
} from "./session.js";
import { loadBlobStores } from "../storage/blob-stores.js";

export async function bootstrap() {
  hydrateSessionControls();
  const session = await loadCurrentSession({ quiet: true });
  if (!session) {
    document.getElementById("loading-view").textContent = "Failed to load session";
    return;
  }
  document.querySelectorAll(".side-item").forEach((item) => {
    item.hidden = !canAccessAdminView(item.dataset.view);
  });
  document.querySelectorAll(".side-group").forEach((group) => {
    group.hidden = !Array.from(sideGroupItems(group.dataset.sideGroup)?.querySelectorAll(".side-item") || [])
      .some((item) => !item.hidden);
  });
  if (!canAdmin("nexus:*")) {
    sessionState.initialDataLoaded = true;
    if (!applyHashRoute()) {
      const fallback = ["security-scanning", "content-selectors", "proxy-settings"].find(canAccessAdminView);
      if (fallback) switchView(fallback, { replaceHash: true });
      else document.getElementById("loading-view").textContent = "No administration views are available.";
    }
    return;
  }
  const recipesPromise = Promise.all([loadRepositoryRecipes(), loadConanManifestDefaults()]);
  loadBlobStores();
  await recipesPromise;
  sessionState.initialDataLoaded = true;
  // Resolve the current URL only after authorization and recipe discovery. The initial HTML
  // stays on Loading, and navigation during startup must not restore an older/default route.
  if (!applyHashRoute()) switchView("repositories", { updateHash: false });
}
