(function () {
  installCsrfFetch();

  const DEFAULT_RETURN_TO = "/browse/#browse/welcome";
  let modal;
  let returnTo = DEFAULT_RETURN_TO;

  function installCsrfFetch() {
    if (window.__nexusPlusCsrfFetchInstalled) return;
    window.__nexusPlusCsrfFetchInstalled = true;
    const nativeFetch = window.fetch.bind(window);
    window.fetch = (input, init = {}) => {
      const method = String(init.method || "GET").toUpperCase();
      if (["POST", "PUT", "PATCH", "DELETE", "MKCOL"].includes(method) && sameOrigin(input)) {
        const token = csrfToken();
        if (token) {
          const headers = new Headers(init.headers || {});
          headers.set("X-Nexus-Plus-CSRF-Token", token);
          init = { ...init, headers };
        }
      }
      return nativeFetch(input, init);
    };
  }

  function sameOrigin(input) {
    const url = typeof input === "string" ? input : input.url;
    return new URL(url, window.location.origin).origin === window.location.origin;
  }

  function csrfToken() {
    return document.cookie
      .split(";")
      .map((part) => part.trim())
      .find((part) => part.startsWith("KKREPO_CSRF="))
      ?.substring("KKREPO_CSRF=".length) || "";
  }

  function safeReturnTo(value) {
    if (!value || !value.startsWith("/") || value.startsWith("//")) {
      return DEFAULT_RETURN_TO;
    }
    return value;
  }

  function ensureModal() {
    if (modal) return modal;
    const root = document.createElement("div");
    root.className = "login-modal-backdrop";
    root.hidden = true;
    root.innerHTML = `
      <section class="login-dialog" role="dialog" aria-modal="true" aria-labelledby="login-dialog-title">
        <header class="login-dialog-header">
          <h2 class="login-dialog-title" id="login-dialog-title">Sign in</h2>
          <button class="login-dialog-close" type="button" aria-label="Close">×</button>
        </header>
        <div class="login-dialog-tabs" role="tablist" aria-label="Sign-in method" hidden>
          <button class="login-dialog-tab" id="login-tab-oidc" type="button" role="tab" aria-controls="login-panel-oidc" aria-selected="false">OIDC</button>
          <button class="login-dialog-tab" id="login-tab-password" type="button" role="tab" aria-controls="login-panel-password" aria-selected="false">Local / LDAP</button>
        </div>
        <div class="login-dialog-oidc-panel" id="login-panel-oidc" role="tabpanel" aria-labelledby="login-tab-oidc" hidden>
          <button class="login-dialog-primary login-dialog-oidc-button" type="button">Sign in with SSO</button>
        </div>
        <form class="login-dialog-form" id="login-panel-password" role="tabpanel" aria-labelledby="login-tab-password" novalidate>
          <label>
            <span>Username</span>
            <input class="login-dialog-username" name="username" type="text" autocomplete="username" required>
          </label>
          <label>
            <span>Password</span>
            <input class="login-dialog-password" name="password" type="password" autocomplete="current-password" required>
          </label>
          <div class="login-dialog-error" role="alert" hidden></div>
          <div class="login-dialog-actions">
            <button class="login-dialog-primary login-dialog-submit" type="submit">Sign in</button>
          </div>
        </form>
      </section>
    `;
    document.body.appendChild(root);
    modal = {
      root,
      form: root.querySelector(".login-dialog-form"),
      username: root.querySelector(".login-dialog-username"),
      password: root.querySelector(".login-dialog-password"),
      submit: root.querySelector(".login-dialog-submit"),
      tabs: root.querySelector(".login-dialog-tabs"),
      oidcTab: root.querySelector("#login-tab-oidc"),
      passwordTab: root.querySelector("#login-tab-password"),
      oidcPanel: root.querySelector("#login-panel-oidc"),
      oidc: root.querySelector(".login-dialog-oidc-button"),
      error: root.querySelector(".login-dialog-error"),
      close: root.querySelector(".login-dialog-close"),
    };
    modal.close.addEventListener("click", close);
    modal.root.addEventListener("click", (event) => {
      if (event.target === modal.root) close();
    });
    modal.form.addEventListener("submit", submitPasswordLogin);
    modal.oidc.addEventListener("click", () => {
      window.location.href = `/internal/security/oidc/login?returnTo=${encodeURIComponent(returnTo)}`;
    });
    modal.oidcTab.addEventListener("click", () => setLoginMethod("oidc", { focus: true }));
    modal.passwordTab.addEventListener("click", () => setLoginMethod("password", { focus: true }));
    modal.tabs.addEventListener("keydown", (event) => {
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        setLoginMethod(event.target === modal.oidcTab ? "password" : "oidc", { focus: true });
      }
    });
    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && !modal.root.hidden) close();
    });
    window.kkrepoI18n?.apply?.(root);
    return modal;
  }

  function setLoginMethod(method, options = {}) {
    const dialog = ensureModal();
    const oidcActive = method === "oidc" && !dialog.tabs.hidden;
    dialog.oidcTab.classList.toggle("is-active", oidcActive);
    dialog.oidcTab.setAttribute("aria-selected", String(oidcActive));
    dialog.oidcTab.tabIndex = oidcActive ? 0 : -1;
    dialog.passwordTab.classList.toggle("is-active", !oidcActive);
    dialog.passwordTab.setAttribute("aria-selected", String(!oidcActive));
    dialog.passwordTab.tabIndex = oidcActive ? -1 : 0;
    dialog.oidcPanel.hidden = !oidcActive;
    dialog.form.hidden = oidcActive;
    clearError();
    if (options.focus) {
      const target = oidcActive ? dialog.oidc : dialog.username;
      setTimeout(() => target.focus(), 0);
    }
  }

  function setOidcEnabled(enabled, options = {}) {
    const dialog = ensureModal();
    dialog.tabs.hidden = !enabled;
    if (enabled) {
      setLoginMethod("oidc", options);
    } else {
      dialog.oidcPanel.hidden = true;
      dialog.form.hidden = false;
      dialog.oidcTab.setAttribute("aria-selected", "false");
      dialog.passwordTab.setAttribute("aria-selected", "true");
      dialog.oidcTab.tabIndex = -1;
      dialog.passwordTab.tabIndex = 0;
      if (options.focus) {
        setTimeout(() => dialog.username.focus(), 0);
      }
    }
  }

  function showError(message) {
    const dialog = ensureModal();
    dialog.error.textContent = message;
    dialog.error.hidden = false;
  }

  function clearError() {
    const dialog = ensureModal();
    dialog.error.textContent = "";
    dialog.error.hidden = true;
  }

  async function loadLoginOptions() {
    const dialog = ensureModal();
    setOidcEnabled(false);
    try {
      const response = await fetch("/internal/security/login/options", {
        headers: { Accept: "application/json" },
        cache: "no-store",
      });
      if (!response.ok) {
        setOidcEnabled(false, { focus: !dialog.root.hidden });
        return;
      }
      const options = await response.json();
      dialog.passwordTab.textContent = options.ldapEnabled ? "Local / LDAP" : "Local";
      window.kkrepoI18n?.apply?.(dialog.root);
      setOidcEnabled(Boolean(options.oidcEnabled), { focus: !dialog.root.hidden });
    } catch {
      setOidcEnabled(false, { focus: !dialog.root.hidden });
    }
  }

  async function submitPasswordLogin(event) {
    event.preventDefault();
    const dialog = ensureModal();
    clearError();
    const username = dialog.username.value.trim();
    const password = dialog.password.value;
    if (!username || !password) {
      showError("Username and password are required.");
      return;
    }
    dialog.submit.disabled = true;
    try {
      const response = await fetch("/internal/security/login", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ username, password, returnTo }),
        cache: "no-store",
      });
      if (response.status === 401 || response.status === 403) {
        showError("Invalid username or password.");
        return;
      }
      if (!response.ok) {
        showError(`Sign in failed: HTTP ${response.status}`);
        return;
      }
      const result = await response.json();
      const target = safeReturnTo(result.returnTo);
      if (target === `${window.location.pathname}${window.location.search}${window.location.hash}`) {
        window.location.reload();
        return;
      }
      window.location.href = target;
    } catch (error) {
      showError(`Sign in failed: ${error.message}`);
    } finally {
      dialog.submit.disabled = false;
    }
  }

  function open(nextReturnTo) {
    const dialog = ensureModal();
    returnTo = safeReturnTo(nextReturnTo);
    dialog.form.reset();
    clearError();
    dialog.root.hidden = false;
    setOidcEnabled(false);
    loadLoginOptions();
  }

  function close() {
    if (!modal) return;
    modal.root.hidden = true;
  }

  window.nexusPlusLogin = { open, close };
})();
