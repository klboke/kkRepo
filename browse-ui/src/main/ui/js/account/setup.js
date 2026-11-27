import { responseMessage } from "../shell/http.js";
import { AUTH_SNAPSHOT_KEY, adminBootstrapStatus } from "../shell/session.js";

async function fetchAdminBootstrapStatus() {
  const res = await fetch("/internal/security/bootstrap", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Failed to load administrator bootstrap status: ${res.status}`);
  return res.json();
}

function renderAdminBootstrap() {
  const panel = document.getElementById("admin-bootstrap-panel");
  if (!panel) return;
  const required = Boolean(adminBootstrapStatus && adminBootstrapStatus.required);
  panel.hidden = !required;
  const userInput = document.getElementById("admin-bootstrap-user");
  if (userInput && adminBootstrapStatus) {
    userInput.value = `${adminBootstrapStatus.source || "Local"}/${adminBootstrapStatus.userId || "admin"}`;
  }
  const anonymousInput = document.getElementById("admin-bootstrap-anonymous-enabled");
  if (anonymousInput && adminBootstrapStatus) {
    anonymousInput.checked = Boolean(adminBootstrapStatus.anonymousAccessEnabled);
  }
}

function setAdminBootstrapStatus(message, type = "") {
  const status = document.getElementById("admin-bootstrap-status");
  if (!status) return;
  status.textContent = message || "";
  status.classList.toggle("error", type === "error");
  status.classList.toggle("ok", type === "ok");
}

async function submitAdminBootstrap(event) {
  event.preventDefault();
  const passwordInput = document.getElementById("admin-bootstrap-password");
  const confirmInput = document.getElementById("admin-bootstrap-password-confirm");
  const anonymousInput = document.getElementById("admin-bootstrap-anonymous-enabled");
  const submitButton = document.getElementById("admin-bootstrap-submit");
  const password = passwordInput ? passwordInput.value : "";
  const passwordConfirm = confirmInput ? confirmInput.value : "";
  const minLength = adminBootstrapStatus && adminBootstrapStatus.minPasswordLength
    ? adminBootstrapStatus.minPasswordLength
    : 8;

  if (password.length < minLength) {
    setAdminBootstrapStatus(`Password must be at least ${minLength} characters.`, "error");
    passwordInput?.focus();
    return;
  }
  if (password !== passwordConfirm) {
    setAdminBootstrapStatus("Password confirmation does not match.", "error");
    confirmInput?.focus();
    return;
  }

  submitButton.disabled = true;
  setAdminBootstrapStatus("Creating administrator...");
  try {
    const createResponse = await fetch("/internal/security/bootstrap/admin", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      cache: "no-store",
      body: JSON.stringify({
        password,
        passwordConfirm,
        anonymousAccessEnabled: Boolean(anonymousInput && anonymousInput.checked),
      }),
    });
    if (!createResponse.ok) {
      throw new Error(await responseMessage(createResponse, `Administrator setup failed: ${createResponse.status}`));
    }
    const loginResponse = await fetch("/internal/security/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      cache: "no-store",
      body: JSON.stringify({
        username: `${adminBootstrapStatus?.source || "Local"}/${adminBootstrapStatus?.userId || "admin"}`,
        password,
        returnTo: "/admin/#admin/repository/repositories",
      }),
    });
    if (!loginResponse.ok) {
      throw new Error(await responseMessage(loginResponse, `Administrator created, but login failed: ${loginResponse.status}`));
    }
    const loginResult = await loginResponse.json();
    sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
    setAdminBootstrapStatus("Administrator created.", "ok");
    window.location.href = loginResult.returnTo || "/admin/#admin/repository/repositories";
  } catch (error) {
    setAdminBootstrapStatus(error.message || "Administrator setup failed.", "error");
    submitButton.disabled = false;
  }
}

export {
  renderAdminBootstrap,
  submitAdminBootstrap,
};
