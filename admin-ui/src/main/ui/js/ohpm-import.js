import { escapeHtml } from "./controls/display.js";
import { responseErrorMessage } from "./shell/http.js";

const api = "/internal/ohpm/imports";
const el = (name) => document.getElementById(`ohpm-import-${name}`);
let report = null, busy = false, stopped = false, initialized = false;

function groupFiles(files) {
  const byPath = new Map();
  for (const file of files) {
    const path = file.webkitRelativePath || file.name;
    if (byPath.has(path)) throw new Error(`Duplicate source path: ${path}`);
    byPath.set(path, file);
  }
  const entries = [], usedHsp = new Set(), usedMetadata = new Set();
  for (const [path, file] of byPath) {
    if (!/\.(har|tgz|tar\.gz)$/.test(path)) continue;
    const basename = path.replace(/\.(har|tgz|tar\.gz)$/, "");
    const bundle = /\.(tgz|tar\.gz)$/.test(path);
    const hsp = byPath.get(`${basename}.hsp`);
    if (bundle && hsp) throw new Error(`Bundle has an extra HSP: ${path}`);
    if (hsp) usedHsp.add(hsp);
    const metadata = byPath.get(`${basename}.metadata.json`);
    if (metadata) usedMetadata.add(metadata);
    entries.push({ sourceKey: path, asset: file, hsp, metadata });
  }
  for (const [path, file] of byPath) {
    if (path.endsWith(".hsp") && !usedHsp.has(file)) throw new Error(`Missing interface HAR for: ${path}`);
    if (path.endsWith(".metadata.json") && !usedMetadata.has(file)) throw new Error(`No archive matches: ${path}`);
  }
  if (!entries.length) throw new Error("Select a directory containing HAR files or complete HSP tgz bundles.");
  if (entries.length > 1000) throw new Error("Use at most 1000 versions per import job.");
  return entries;
}

async function form(entry) {
  const body = new FormData();
  body.append("sourceKey", entry.sourceKey);
  body.append("asset", entry.asset, entry.asset.name);
  if (entry.hsp) body.append("hsp", entry.hsp, entry.hsp.name);
  if (entry.metadata) {
    if (entry.metadata.size > 16 * 1024 * 1024) throw new Error("Source metadata exceeds 16 MiB.");
    const metadata = JSON.parse(await entry.metadata.text());
    if (typeof metadata.name !== "string" || !metadata.name) throw new Error("Source metadata requires a package name.");
    body.append("metadata", entry.metadata, entry.metadata.name);
    body.append("packageName", metadata.name);
  }
  return body;
}

async function request(path, method = "GET", body) {
  const multipart = body instanceof FormData;
  const response = await fetch(path, {
    method, cache: "no-store",
    headers: body && !multipart ? { "Content-Type": "application/json" } : {},
    body: body ? multipart ? body : JSON.stringify(body) : undefined
  });
  if (!response.ok) throw new Error(await responseErrorMessage(response));
  return response.json();
}

function options(select, values, empty) {
  const previous = select.value;
  select.replaceChildren();
  if (empty) select.add(new Option(empty, ""));
  for (const { value, text, id } of values) {
    const option = new Option(text, value);
    if (id != null) option.dataset.id = String(id);
    select.add(option);
  }
  if (values.some(option => option.value === previous)) select.value = previous;
}

function controls() {
  const state = report?.job.state;
  const directory = (report?.job.source || el("source").value) === "DIRECTORY";
  const planning = !report || state === "PLANNING";
  const applying = state === "READY" || state === "APPLYING";
  const files = el("files").files;
  el("source").disabled = busy || Boolean(report);
  el("directory").hidden = !directory || !(planning || applying);
  el("registry").hidden = directory || !planning;
  el("proxy").disabled = busy || Boolean(report);
  el("selection").textContent = files.length ? `${files[0].webkitRelativePath.split("/")[0]} · ${files.length} files`
    : report ? "Select the original directory to continue." : "No directory selected";
  el("plan").hidden = Boolean(report);
  el("continue").hidden = state !== "PLANNING";
  el("apply").hidden = !applying;
  el("apply").textContent = state === "APPLYING" ? "Resume import" : "Start import";
  const inputReady = directory ? files.length > 0 : Boolean(el("proxy").value && el("packages").value.trim());
  el("plan").disabled = el("continue").disabled = busy || !el("target").value || !inputReady;
  el("apply").disabled = busy || (directory && !files.length);
  el("hint").textContent = planning ? "Verify archives and save a plan without writing target packages."
    : applying ? "Existing versions and conflicting tags are never overwritten." : "";
}

function render() {
  const result = el("result");
  el("report").hidden = !report;
  controls();
  if (!report) return;
  const counts = new Map();
  for (const item of report.items) counts.set(item.state, (counts.get(item.state) || 0) + 1);
  const stages = { PLANNING: "Dry run in progress", READY: "Dry run complete", APPLYING: "Import in progress",
    COMPLETED: "Import complete", CANCELLED: "Job cancelled" };
  el("summary").textContent = [stages[report.job.state] || report.job.state,
    ...[...counts].map(([state, count]) => `${count} ${state.toLowerCase()}`)].join(" · ");
  const rows = report.items.map((item) => `<tr><td>${escapeHtml(item.packageName || item.plan.sourceKey)}</td>
    <td>${escapeHtml(item.version || "")}</td><td>${escapeHtml(item.state)}</td>
    <td>${escapeHtml(item.detail)}</td></tr>`).join("");
  result.innerHTML = `<p>Job ${escapeHtml(report.job.id)} · ${escapeHtml(report.job.state)} · ${report.items.length} items</p>
    <table class="nx-table compact"><thead><tr><th>Package / source</th><th>Version</th><th>State</th><th>Result</th></tr></thead>
    <tbody>${rows}</tbody></table>${report.manualActions.map((item) =>
      `<p>${escapeHtml(item.state)}: ${escapeHtml(item.detail)}</p>`).join("")}`;
}

async function refreshReport(id) {
  report = await request(`${api}/${encodeURIComponent(id)}`);
  el("source").value = report.job.source;
  if (report.job.source === "REGISTRY") {
    const source = [...el("proxy").options].find(option => option.dataset.id === String(report.job.sourceRepositoryId));
    el("proxy").value = source?.value || "";
  }
  render();
}

async function refreshJobs() {
  const jobs = !el("target").value ? [] : await request(`${api}?repository=${encodeURIComponent(el("target").value)}`);
  options(el("jobs"), jobs.map((job) => ({ value: job.id, text: `${job.createdAt} · ${job.source} · ${job.state} · ${job.id}` })),
    "New import");
  if (report && jobs.some((job) => job.id === report.job.id)) {
    el("jobs").value = report.job.id;
    await refreshReport(report.job.id);
  } else {
    report = null;
    el("jobs").value = "";
    render();
  }
}

async function run(action, stoppable = false) {
  if (busy) return;
  busy = true; stopped = false;
  el("form").querySelectorAll("input, select, textarea, button").forEach((element) => { element.disabled = true; });
  el("stop").hidden = !stoppable;
  el("stop").disabled = !stoppable;
  el("status").textContent = "Working…";
  try {
    await action();
    el("status").textContent = stopped && report?.job.state !== "COMPLETED" ? "Stopped. Saved items can be resumed." : "";
  } catch (error) {
    if (stoppable && report) {
      try { await refreshJobs(); } catch { /* Keep the original operation error. */ }
    }
    el("status").textContent = error.message;
  } finally {
    busy = false;
    el("form").querySelectorAll("input, select, textarea, button").forEach((element) => { element.disabled = false; });
    el("stop").hidden = true;
    controls();
  }
}

async function plan(continueExisting) {
  const source = continueExisting && report ? report.job.source : el("source").value;
  const entries = source === "DIRECTORY" ? groupFiles(el("files").files) : [];
  const packages = [...new Set(el("packages").value.split(/\s+/).filter(Boolean))];
  if (source === "REGISTRY" && !packages.length) throw new Error("Enter explicit source package names.");
  if (continueExisting) {
    if (!report || report.job.state !== "PLANNING") throw new Error("Select a job still in PLANNING.");
  } else {
    const job = await request(api, "POST", { repository: el("target").value, source,
      sourceRepository: source === "REGISTRY" ? el("proxy").value : null });
    await refreshReport(job.id);
  }
  const id = report.job.id;
  for (const entry of entries) {
    if (stopped) break;
    el("status").textContent = `Verifying ${entry.sourceKey}`;
    await request(`${api}/${id}/directory`, "POST", await form(entry));
    await refreshReport(id);
  }
  for (const name of source === "REGISTRY" ? packages : []) {
    if (stopped) break;
    const versions = await request(`${api}/${id}/source-versions?name=${encodeURIComponent(name)}`);
    for (const version of versions) {
      if (stopped) break;
      el("status").textContent = `Verifying ${name}@${version}`;
      await request(`${api}/${id}/registry`, "POST", { name, version });
      await refreshReport(id);
    }
  }
  if (!stopped) await request(`${api}/${id}/state`, "POST", { state: "READY" });
  await refreshJobs();
}

async function apply() {
  if (!report || !["READY", "APPLYING"].includes(report.job.state)) {
    throw new Error("Select a completed dry-run plan or an unfinished import.");
  }
  const entries = report.job.source === "DIRECTORY" ? groupFiles(el("files").files) : [];
  const byPath = new Map(entries.map((entry) => [entry.sourceKey, entry]));
  const id = report.job.id;
  if (report.job.state === "READY") await request(`${api}/${id}/state`, "POST", { state: "APPLYING" });
  for (const item of report.items) {
    if (stopped) break;
    if (["IMPORTED", "SKIPPED"].includes(item.state) || !item.packageName) continue;
    el("status").textContent = `Importing ${item.packageName}@${item.version}`;
    const endpoint = `${api}/${id}/items/${item.key}`;
    if (report.job.source === "DIRECTORY") {
      const entry = byPath.get(item.plan.sourceKey);
      if (!entry) throw new Error(`Select the original source directory. Missing: ${item.plan.sourceKey}`);
      await request(`${endpoint}/directory`, "POST", await form(entry));
    } else {
      await request(`${endpoint}/registry`, "POST");
    }
    await refreshReport(id);
  }
  if (report.items.every((item) => ["IMPORTED", "SKIPPED"].includes(item.state))) {
    await request(`${api}/${id}/state`, "POST", { state: "COMPLETED" });
  }
  await refreshJobs();
}

function download() {
  if (!report) return;
  const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: "application/json" }));
  const link = document.createElement("a");
  link.href = url; link.download = `ohpm-import-${report.job.id}.json`; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

async function load() {
  if (!initialized) {
    initialized = true;
    el("form").addEventListener("submit", (event) => event.preventDefault());
    el("choose").addEventListener("click", () => el("files").click());
    el("files").addEventListener("change", controls);
    el("source").addEventListener("change", controls);
    el("proxy").addEventListener("change", controls);
    el("packages").addEventListener("input", controls);
    el("plan").addEventListener("click", () => run(() => plan(false), true));
    el("continue").addEventListener("click", () => run(() => plan(true), true));
    el("apply").addEventListener("click", () => run(apply, true));
    el("stop").addEventListener("click", () => { stopped = true; el("stop").disabled = true; });
    el("refresh").addEventListener("click", () => run(refreshJobs));
    el("download").addEventListener("click", download);
    el("jobs").addEventListener("change", () => run(async () => {
      report = null;
      render();
      if (el("jobs").value) await refreshReport(el("jobs").value);
    }));
    el("target").addEventListener("change", () => run(async () => {
      report = null; render(); await refreshJobs();
    }));
  }
  if (busy) return;
  await run(async () => {
    const repositories = await request("/internal/repositories?purpose=admin");
    for (const [name, type] of [["target", "hosted"], ["proxy", "proxy"]]) {
      options(el(name), repositories.filter((repository) => repository.format === "ohpm"
        && repository.type.toLowerCase() === type).map((repository) => ({ value: repository.name, text: repository.name, id: repository.id })));
    }
    await refreshJobs();
  });
}

export { load, groupFiles, form };
