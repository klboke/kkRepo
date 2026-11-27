import { responseErrorMessage } from "../shell/http.js";

async function repositoryRequest(path, options) {
  const response = await fetch(path, options);
  if (!response.ok) throw new Error(await responseErrorMessage(response));
  return response;
}

export async function listRepositories() {
  const response = await repositoryRequest("/internal/repositories?purpose=admin", { cache: "no-store" });
  return response.json();
}

export async function listRepositoryRecipes() {
  const response = await repositoryRequest("/internal/repositories/recipes", { cache: "no-store" });
  return response.json();
}

export function persistRepository(name, payload) {
  const creating = name == null;
  return repositoryRequest(creating ? "/internal/repositories" : `/internal/repositories/${encodeURIComponent(name)}`, {
    method: creating ? "POST" : "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
}

export function removeRepository(name) {
  return repositoryRequest(`/internal/repositories/${encodeURIComponent(name)}`, { method: "DELETE" });
}
