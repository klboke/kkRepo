/*
 * Staging view for the administration shell.
 *
 * Rendered inside the shared admin page: navigation, account menu, theme, i18n and table/form
 * primitives all come from the shell. Only the raw payload stays behind a collapsed section.
 */
(function (root) {
  "use strict";

  const API = "/internal/staging";
  const el = (id) => document.getElementById(id);
  const text = (value) => (value == null || value === "" ? "—" : String(value));
  const esc = (value) => (typeof escapeHtml === "function"
    ? escapeHtml(value == null ? "" : String(value))
    : String(value == null ? "" : value));

  const state = {
    sessions: [],
    selected: null,
    detail: null,
    loading: 0,
    bound: false,
  };

  async function errorMessage(response) {
    try {
      const body = await response.text();
      if (!body) return `${response.status} ${response.statusText}`;
      try {
        const parsed = JSON.parse(body);
        if (parsed && parsed.message) return parsed.message;
        if (parsed && parsed.error) return parsed.error;
      } catch {
        /* Plain-text error body. */
      }
      return body.trim() || `${response.status} ${response.statusText}`;
    } catch {
      return `${response.status} ${response.statusText}`;
    }
  }

  async function request(url, method = "GET", body) {
    const response = await fetch(url, {
      method,
      cache: "no-store",
      credentials: "same-origin",
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw new Error(await errorMessage(response));
    const content = await response.text();
    return content ? JSON.parse(content) : null;
  }

  function status(message, tone = "") {
    const region = el("staging-status");
    if (!region) return;
    region.textContent = message || "";
    region.className = tone === "error" ? "form-note staging-error" : "form-note";
  }

  function formatBytes(value) {
    if (value == null || value === "") return "—";
    const size = Number(value);
    if (!Number.isFinite(size) || size < 0) return "—";
    if (size < 1024) return `${size} B`;
    const units = ["KiB", "MiB", "GiB", "TiB"];
    let scaled = size / 1024;
    let unit = 0;
    while (scaled >= 1024 && unit < units.length - 1) {
      scaled /= 1024;
      unit += 1;
    }
    return `${scaled.toFixed(scaled >= 10 ? 0 : 1)} ${units[unit]}`;
  }

  function badge(value) {
    const name = String(value || "").toLowerCase().replace(/[^a-z0-9]+/g, "-");
    return `<span class="staging-state staging-state-${esc(name)}">${esc(text(value))}</span>`;
  }

  function approval(session) {
    const attributes = session.attributes || {};
    if (attributes.approvedByUserId) return `approved by ${attributes.approvedByUserId}`;
    if (attributes.approvalRequired === true) return "required — pending";
    return "not required";
  }

  const ACTIONS = {
    OPEN: [["preflight", "Preflight"], ["approve", "Approve"], ["close", "Close"], ["cancel", "Cancel"]],
    FROZEN: [["preflight", "Preflight"], ["approve", "Approve"], ["cancel", "Cancel"]],
    PREFLIGHTED: [["promote", "Promote"], ["approve", "Approve"], ["preflight", "Re-preflight"], ["cancel", "Cancel"]],
    PROMOTING: [],
    PROMOTED: [["compensate", "Compensate"]],
    FAILED: [["cancel", "Cancel"]],
    CANCELLED: [],
  };

  function actionButton(action, label, id, primary = false) {
    const tone = primary ? "" : " secondary";
    const danger = action === "cancel" || action === "delete" ? " staging-danger" : "";
    return `<button class="create-button${tone}${danger}" type="button" data-staging-action="${esc(action)}" data-staging-id="${esc(id)}">${esc(label)}</button>`;
  }

  function renderSessions() {
    const body = el("staging-session-table");
    if (!body) return;
    const filter = (el("staging-filter")?.value || "").trim().toLowerCase();
    const visible = state.sessions.filter((view) => {
      const session = view.session || {};
      const haystack = [
        session.name,
        session.state,
        session.ownerUserId,
        ...(view.repositories || []).map((repository) => repository.name),
      ].join(" ").toLowerCase();
      return !filter || haystack.includes(filter);
    });
    if (!visible.length) {
      body.innerHTML = '<tr><td colspan="7" class="form-note">No staging sessions match the filter.</td></tr>';
      return;
    }
    body.innerHTML = visible.map((view) => {
      const session = view.session || {};
      const repositories = (view.repositories || [])
        .map((repository) => repository.name || `#${repository.repositoryId}`)
        .join(", ");
      const actions = (ACTIONS[session.state] || [])
        .map(([action, label]) => actionButton(action, label, session.id, action === "promote"))
        .concat([actionButton("view", "View", session.id)])
        .concat(["PROMOTED", "FAILED", "CANCELLED"].includes(session.state)
          ? [actionButton("delete", "Delete", session.id)] : [])
        .join("");
      return `<tr class="${state.selected === session.id ? "is-selected" : ""}">
        <td>${esc(text(session.name))}</td>
        <td>${badge(session.state)}</td>
        <td>${esc(text(session.ownerUserId))}</td>
        <td class="staging-wrap">${esc(text(repositories))}</td>
        <td>${esc(view.itemCount)}</td>
        <td>${esc(session.expiresAt ? formatInstant(session.expiresAt) : "")}</td>
        <td class="staging-actions">${actions}</td>
      </tr>`;
    }).join("");
  }

  function renderMetrics(metrics) {
    const target = el("staging-metrics");
    if (!target) return;
    if (!metrics) {
      target.innerHTML = '<p class="form-note">Metrics are available to staging administrators.</p>';
      return;
    }
    const group = (label, values) => {
      const entries = Object.entries(values || {});
      return `<div><span>${esc(label)}</span><strong>${entries.reduce((sum, [, count]) => sum + Number(count || 0), 0)}</strong>`
        + (entries.length
          ? `<p class="form-note">${entries.map(([name, count]) => `${esc(name)}: ${esc(count)}`).join(" · ")}</p>`
          : '<p class="form-note">None</p>')
        + "</div>";
    };
    target.innerHTML = `<div class="summary-grid">
      ${group("Sessions", metrics.sessionsByState)}
      ${group("Staged items", metrics.itemsByStatus)}
      ${group("Group changes", metrics.groupChangesByStatus)}
      <div><span>Observed</span><strong class="staging-observed">${esc(formatInstant(metrics.observedAt))}</strong></div>
    </div>`;
  }

  function summaryItem(label, value) {
    return `<div><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`;
  }

  function renderDetail() {
    const detail = state.detail;
    if (!detail) return;
    const view = detail.session;
    const session = view.session || {};
    const known = new Map((view.repositories || []).map((repository) => [repository.repositoryId, repository.name]));
    el("staging-detail-title").textContent = `Session ${session.name || session.id}`;
    el("staging-summary").innerHTML = `<div class="summary-grid">
      ${summaryItem("State", session.state || "")}
      ${summaryItem("Owner", `${session.ownerSource || ""} ${session.ownerUserId || ""}`.trim())}
      ${summaryItem("Revision", text(session.revision))}
      ${summaryItem("Staged items", view.itemCount)}
      ${summaryItem("Created", formatInstant(session.createdAt))}
      ${summaryItem("Expires", formatInstant(session.expiresAt))}
      ${summaryItem("Approval", approval(session))}
      ${summaryItem("Promotion", session.promotionId ? `#${session.promotionId}` : "none")}
    </div>`;

    el("staging-repository-table").innerHTML = (view.repositories || []).map((repository) =>
      `<tr><td>${esc(repository.repositoryId)}</td><td>${esc(text(repository.name))}</td><td>${esc(text(repository.format))}</td></tr>`
    ).join("") || '<tr><td colspan="3" class="form-note">No repositories attached.</td></tr>';

    const items = detail.items || [];
    el("staging-item-table").innerHTML = items.map((item) =>
      `<tr>
        <td class="staging-wrap"><code>${esc(item.logicalPath)}</code></td>
        <td>${esc(text(known.get(item.repositoryId) || `#${item.repositoryId}`))}</td>
        <td>${badge(item.action)}</td>
        <td>${badge(item.status)}</td>
        <td>${esc(formatBytes(item.size))}</td>
        <td>${esc(text(item.contentType))}</td>
      </tr>`
    ).join("") || '<tr><td colspan="6" class="form-note">No staged items.</td></tr>';

    const groups = detail.groups || [];
    el("staging-group-table").innerHTML = groups.map((change) =>
      `<tr>
        <td>${esc(text(change.groupRepository))}</td>
        <td>${esc(text(change.memberRepository))}</td>
        <td>${badge(change.action)}</td>
        <td>${esc(text(change.position))}</td>
        <td>${badge(change.status)}</td>
      </tr>`
    ).join("") || '<tr><td colspan="5" class="form-note">No group membership changes.</td></tr>';

    renderReport(detail.report);
    el("staging-raw").textContent = JSON.stringify(detail, null, 2);
    el("staging-detail").hidden = false;
  }

  function renderReport(report) {
    const target = el("staging-report");
    if (!target) return;
    if (!report) {
      target.innerHTML = '<p class="form-note">No preflight or promotion report yet. Preflight a session to validate it against the live repositories.</p>';
      return;
    }
    const rows = Array.isArray(report.items) ? report.items : [];
    const conflicts = rows.filter((row) => {
      const result = String(row.result || "").toLowerCase();
      return result.includes("conflict") || result === "failed";
    });
    const resultRows = rows.map((row) =>
      `<tr>
        <td>${esc(text(row.groupRepositoryId ? row.groupRepositoryId : row.repositoryId))}</td>
        <td class="staging-wrap"><code>${esc(text(row.path || "group membership"))}</code></td>
        <td>${badge(row.action)}</td>
        <td>${badge(row.result)}</td>
        <td class="staging-wrap">${esc(text(row.message))}</td>
      </tr>`
    ).join("");
    target.innerHTML = `<div class="summary-grid">
        ${summaryItem("Result", report.promotable ? "promotable" : "blocked")}
        ${summaryItem("State", text(report.state))}
        ${summaryItem("Conflicts", String(conflicts.length))}
        ${summaryItem("Manifest", text(report.manifestSha256))}
      </div>
      ${report.error ? `<p class="form-note staging-error">${esc(report.error)}</p>` : ""}
      <div class="nx-table-frame">
        <table class="nx-table">
          <thead><tr><th>Repository</th><th>Path</th><th>Action</th><th>Result</th><th>Detail</th></tr></thead>
          <tbody>${resultRows || '<tr><td colspan="5" class="form-note">The report has no rows.</td></tr>'}</tbody>
        </table>
      </div>`;
  }

  async function loadDetail(id) {
    const sequence = ++state.loading;
    state.selected = id;
    renderSessions();
    try {
      const [session, items, groups, report] = await Promise.all([
        request(`${API}/sessions/${id}`),
        request(`${API}/sessions/${id}/items`),
        request(`${API}/sessions/${id}/groups`).catch(() => []),
        request(`${API}/sessions/${id}/report`).catch(() => null),
      ]);
      if (sequence !== state.loading) return;
      state.detail = {session, items, groups, report};
      renderDetail();
    } catch (error) {
      if (sequence !== state.loading) return;
      status(`Unable to load session ${id}: ${error.message}`, "error");
    }
  }

  async function loadMetrics() {
    try {
      renderMetrics(await request(`${API}/metrics`));
    } catch {
      // The metrics endpoint requires staging administration; the list still works for owners.
      renderMetrics(null);
    }
  }

  async function load() {
    bind();
    status("Loading staging sessions…");
    try {
      const selected = el("staging-state")?.value || "";
      const query = new URLSearchParams({limit: "100"});
      if (selected) query.set("state", selected);
      state.sessions = await request(`${API}/sessions?${query.toString()}`);
      renderSessions();
      status("");
      await loadMetrics();
    } catch (error) {
      status(`Unable to load staging sessions: ${error.message}`, "error");
    }
  }

  async function perform(action, id) {
    const session = state.sessions.find((view) => view.session?.id === id)?.session || {id};
    if (action === "delete" || action === "cancel" || action === "compensate") {
      const confirmed = await confirmAction(action, session);
      if (!confirmed) return;
    }
    if (action === "view") {
      await loadDetail(id);
      return;
    }
    const verb = {
      preflight: "Preflight", promote: "Promotion", close: "Close", cancel: "Cancellation",
      compensate: "Compensation", delete: "Deletion", approve: "Approval",
    }[action] || action;
    status(`${verb} in progress for ${session.name || `session ${id}`}…`);
    try {
      if (action === "delete") {
        await request(`${API}/sessions/${id}`, "DELETE");
        state.selected = null;
        state.detail = null;
        el("staging-detail").hidden = true;
      } else {
        await request(`${API}/sessions/${id}/${action}`, "POST");
      }
      if (typeof showToast === "function") {
        showToast(`${verb} completed for ${session.name || `session ${id}`}.`, "success");
      }
      await load();
      if (action !== "delete" && state.detail) await loadDetail(id);
    } catch (error) {
      status(`${verb} failed: ${error.message}`, "error");
    }
  }

  async function confirmAction(action, session) {
    const copy = {
      delete: ["Delete this staging session?", "The session and every staged reference are removed. Promoted content is not affected.", "Delete"],
      cancel: ["Cancel this staging session?", "Staged content is discarded; the live repositories are not touched.", "Cancel session"],
      compensate: ["Create a compensation session?", "A new open session is created with the inverse of the promoted change set for review.", "Compensate"],
    }[action];
    if (!copy) return true;
    if (typeof window.kkrepoConfirmDelete === "function") {
      return window.kkrepoConfirmDelete({
        title: copy[0],
        description: copy[1],
        details: [{label: "Session", value: session.name || `#${session.id}`}],
        confirmLabel: copy[2],
        confirmationText: action === "delete" ? (session.name || String(session.id)) : null,
      });
    }
    return window.confirm(`${copy[0]}\n\n${copy[1]}`);
  }

  function bind() {
    if (state.bound) return;
    state.bound = true;
    el("staging-refresh").addEventListener("click", () => load());
    el("staging-filter").addEventListener("input", renderSessions);
    el("staging-state").addEventListener("change", () => load());
    el("staging-detail-close").addEventListener("click", () => {
      state.detail = null;
      state.selected = null;
      el("staging-detail").hidden = true;
      renderSessions();
    });
    el("staging-create").addEventListener("click", openCreate);
    el("staging-session-form").addEventListener("submit", save);
    el("staging-session-table").addEventListener("click", (event) => {
      const button = event.target.closest("button[data-staging-action]");
      if (!button) return;
      perform(button.dataset.stagingAction, Number(button.dataset.stagingId));
    });
  }

  function openCreate() {
    el("staging-session-form").reset();
    el("staging-session-retention").value = "7";
    el("staging-session-error").textContent = "";
    el("staging-session-token").hidden = true;
    el("staging-session-token").textContent = "";
    if (typeof openFormModal === "function") openFormModal("staging-session-form", "staging-session-name");
  }

  function closeCreate() {
    if (typeof closeFormModal === "function") closeFormModal("staging-session-form");
  }

  async function save(event) {
    event.preventDefault();
    const form = el("staging-session-form");
    if (!form.reportValidity()) return;
    const repositories = el("staging-session-repositories").value
      .split(",").map((value) => value.trim()).filter(Boolean);
    if (!repositories.length) {
      el("staging-session-error").textContent = "Add at least one repository.";
      return;
    }
    const save = el("staging-session-save");
    save.disabled = true;
    el("staging-session-error").textContent = "";
    try {
      const body = {
        name: el("staging-session-name").value.trim(),
        repositories,
        retentionDays: Number(el("staging-session-retention").value || 7),
        approvalRequired: el("staging-session-approval").checked,
      };
      const response = await fetch(`${API}/sessions`, {
        method: "POST",
        credentials: "same-origin",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(body),
      });
      if (!response.ok) throw new Error(await errorMessage(response));
      const token = response.headers.get("X-KkRepo-Staging-Token");
      const created = await response.json();
      await load();
      if (token && created?.session?.id) {
        el("staging-session-token").hidden = false;
        el("staging-session-token").textContent =
          `Staging token (shown once): ${token}. Use it as X-KkRepo-Staging-Token.`;
        await loadDetail(created.session.id);
        return;
      }
      closeCreate();
      if (typeof showToast === "function") {
        showToast(`Staging session ${created?.session?.name || ""} created.`, "success");
      }
    } catch (error) {
      el("staging-session-error").textContent = error.message;
    } finally {
      save.disabled = false;
    }
  }

  root.KkStaging = {load};
  if (typeof module !== "undefined") module.exports = {ACTIONS, approval, formatBytes};
})(typeof window === "undefined" ? globalThis : window);
