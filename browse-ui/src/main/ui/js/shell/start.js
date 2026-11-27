import { fetchRepositories, listState } from "../repositories/list.js";
import { applyHashRoute, showWelcome } from "./routes.js";
import {
  applyUiContext,
  currentReturnTo,
  fetchUiContext,
  hydrateAuthSnapshot,
  openPendingLoginIfRequested,
  safeLocalReturnTo,
} from "./session.js";
import { catalogState, fetchUploadSpecs } from "../upload/catalog.js";

let initialDataLoaded = false;

let discoveryVersion = 0;

async function bootstrap() {
  const version = ++discoveryVersion;
  applyHashRoute();
  hydrateAuthSnapshot();

  const contextPromise = fetchUiContext()
    .then((context) => {
      if (version !== discoveryVersion) return false;
      applyUiContext(context);
      return true;
    })
    .catch(() => false);
  const repositoriesPromise = Promise.all([
      fetchRepositories(),
      fetchUploadSpecs(),
    ])
    .then(([repositories, uploadSpecs]) => {
      if (version !== discoveryVersion) return false;
      listState.repositoriesCache = repositories;
      catalogState.uploadSpecsCache = uploadSpecs;
      return true;
    })
    .catch((error) => {
      if (version !== discoveryVersion) return false;
      listState.repositoriesCache = [];
      catalogState.uploadSpecsCache = new Map();
      document.getElementById("repository-table").innerHTML =
        `<tr><td colspan="6" class="muted-row">Failed to load repositories: ${error.message}</td></tr>`;
      return false;
    });

  await Promise.all([contextPromise, repositoriesPromise]);
  if (version !== discoveryVersion) return;
  initialDataLoaded = true;
  // Re-read the current hash: the user may have navigated while the requests were pending.
  if (!applyHashRoute()) showWelcome(false);
  openPendingLoginIfRequested();
}

async function handleLoginSuccess(event) {
  // A successful login starts a newer discovery. Its data and route must not wait for,
  // or be overwritten by, an earlier anonymous bootstrap or login refresh.
  const version = ++discoveryVersion;
  const target = safeLocalReturnTo(event.detail?.returnTo) || currentReturnTo();
  try {
    const [context, repositories, uploadSpecs] = await Promise.all([
      fetchUiContext(),
      fetchRepositories(),
      fetchUploadSpecs(),
    ]);
    if (version !== discoveryVersion) return;
    applyUiContext(context);
    listState.repositoriesCache = repositories;
    catalogState.uploadSpecsCache = uploadSpecs;
    catalogState.uploadRepositoriesLoaded = false;
    catalogState.uploadRepositoriesCache = [];
    initialDataLoaded = true;
    if (!applyHashRoute()) showWelcome(false);
  } catch (error) {
    if (version !== discoveryVersion) return;
    window.location.href = target;
    return;
  }
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  if (target !== current) window.location.href = target;
}

export {
  bootstrap,
  handleLoginSuccess,
  initialDataLoaded,
};
