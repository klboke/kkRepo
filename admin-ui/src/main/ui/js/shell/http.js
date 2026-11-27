import { showToast } from "../controls/toast.js";
import { authRequiredWelcome, updateSessionControls } from "./session.js";

async function responseErrorMessage(response) {
  if (response.status === 401 || response.status === 403) {
    window.location.href = authRequiredWelcome();
    return "Authentication required.";
  }
  try {
    const text = await response.text();
    if (!text) return `HTTP ${response.status}`;
    try {
      const payload = JSON.parse(text);
      return payload.message || payload.error || text.trim() || `HTTP ${response.status}`;
    } catch (parseError) {
      return text.trim() || `HTTP ${response.status}`;
    }
  } catch (error) {
    return `HTTP ${response.status}`;
  }
}

async function fetchJson(path, fallback, errorLabel) {
  try {
    const response = await fetch(path, { cache: "no-store" });
    if (response.status === 401 || response.status === 403) {
      updateSessionControls(null);
      window.location.href = authRequiredWelcome();
      return fallback;
    }
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    return await response.json();
  } catch (error) {
    showToast(`${errorLabel}: ${error.message}`, "error");
    return fallback;
  }
}

export {
  fetchJson,
  responseErrorMessage,
};
