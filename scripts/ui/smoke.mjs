import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { chromium, expect } from "@playwright/test";

const origin = process.env.KKREPO_UI_URL || "http://127.0.0.1:18090";
const username = process.env.KKREPO_UI_USER || "admin";
const password = process.env.KKREPO_UI_PASSWORD;
if (!password) throw new Error("Set KKREPO_UI_PASSWORD to run the live browser suite.");
const repo = "ui-proxy-" + randomUUID();
const reader = "ui-reader-" + randomUUID();
const readerPassword = randomUUID();
const store = process.env.KKREPO_UI_BLOB_STORE || "default";
const adminRoute = "/admin/#admin/repository/repositories";
const cleanup = [];
const pageErrors = [];

async function api(path, method, body) {
  const response = await fetch(origin + path, {
    method, headers: {
      Authorization: "Basic " + Buffer.from(username + ":" + password).toString("base64"),
      "Content-Type": "application/json",
    },
    body: body && JSON.stringify(body),
  });
  assert.ok(response.ok, method + " " + path + " returned " + response.status);
}

async function verifyEntryAssets(page, app) {
  for (const ext of ["js", "css"]) {
    const path = "/assets/" + app + "." + ext + "?";
    const asset = ext === "js" ? page.locator(`script[type="module"][src*="${path}"]`)
      : page.locator(`link[rel="stylesheet"][href*="${path}"]`);
    await expect(asset).toHaveCount(1);
    const url = await asset.evaluate(element => element.src || element.href);
    assert.equal(new URL(url).pathname, "/" + app + "/assets/" + app + "." + ext);
    const response = await page.request.get(url);
    assert.ok(response.ok());
    const digest = createHash("sha256").update(await response.body()).digest("hex").slice(0, 16);
    assert.equal(new URL(url).searchParams.get("v"), digest, "Served asset must match the version in its HTML");
  }
}

async function verifyPageAlignment(view, fullWidthBlocks, leftAlignedControls) {
  const heading = await view.locator(".page-heading").boundingBox();
  const icon = await view.locator(".heading-icon").boundingBox();
  for (const selector of [...fullWidthBlocks, ...leftAlignedControls]) {
    const box = await view.locator(selector).boundingBox();
    assert.ok(box, selector + " is visible");
    assert.ok(Math.abs(box.x - icon.x) <= 1, selector + " aligns with the heading icon");
    if (fullWidthBlocks.includes(selector)) {
      assert.ok(Math.abs(box.x + box.width - heading.x - heading.width) <= 1,
        selector + " aligns with the heading's right edge");
    }
  }
}

async function verifyAnonymousEditor(page) {
  const endpoint = "**/internal/security/anonymous";
  const accountEndpoint = "**/internal/security/anonymous/account";
  let settings = { enabled: true, userSource: "Local", userId: "guest-reader", realmName: "ImportedRealm" };
  let settingsUnavailable = true;
  const writes = [];
  let finishSave;
  const saveGate = new Promise(resolve => { finishSave = resolve; });
  await page.route(endpoint, route => {
    assert.equal(route.request().method(), "GET");
    return route.fulfill(settingsUnavailable ? { status: 503, body: "Unavailable" } : { json: settings });
  });
  await page.route(accountEndpoint, async route => {
    assert.equal(route.request().method(), "PUT");
    writes.push(route.request().postDataJSON());
    if (writes.length === 1) return route.fulfill({ status: 503, body: "Unavailable" });
    await saveGate;
    settings = { ...settings, ...writes.at(-1) };
    return route.fulfill({ json: settings });
  });
  try {
    await page.goto(origin + "/admin/#admin/security/anonymous");
    const view = page.locator("#security-anonymous-view");
    const toggle = view.getByRole("switch", { name: "Allow anonymous repository reads" });
    const user = view.getByLabel("User ID", { exact: true });
    const realm = view.locator("#security-anonymous-realm-id");
    const retry = view.getByRole("button", { name: "Retry" });
    const discard = view.getByRole("button", { name: "Discard changes" });
    const save = view.getByRole("button", { name: "Save changes" });
    const status = view.locator("#security-anonymous-edit-status");
    await expect(view.getByText("Failed to load anonymous settings", { exact: true })).toBeVisible();
    await expect(save).toBeHidden();
    await view.locator("form").evaluate(form => form.requestSubmit());
    assert.equal(writes.length, 0);
    settingsUnavailable = false;
    await retry.click();
    await expect(toggle).toBeFocused();
    await expect(toggle).toBeChecked();
    await expect(user).toHaveValue("guest-reader");
    await expect(realm).toHaveText("ImportedRealm");
    await expect(view.getByRole("combobox")).toHaveCount(0);
    await expect(view.getByRole("textbox")).toHaveCount(1);
    await expect(save).toBeDisabled();
    await toggle.press("Space");
    await expect(status).toHaveText("Save to apply changes.");
    await user.fill(" ");
    await save.click();
    await expect(user).toHaveAttribute("aria-invalid", "true");
    await expect(user).toBeFocused();
    await discard.click();
    await expect(user).toHaveValue("guest-reader");
    await expect(toggle).toBeChecked();
    await expect(save).toBeDisabled();
    assert.equal(writes.length, 0);
    await toggle.press("Space");
    await save.click();
    await expect(status).toHaveText("Save failed");
    assert.deepEqual(writes[0], { enabled: false, userId: "guest-reader" });
    await expect(toggle).not.toBeChecked();
    // A compatibility client can update the realm while this editor is open.
    settings.realmName = "LdapRealm";
    await user.fill("guest-preview");
    await save.click();
    await expect(toggle).toBeDisabled();
    await expect.poll(() => writes.length).toBe(2);
    await view.locator("form").evaluate(form => form.requestSubmit());
    assert.equal(writes.length, 2);
    finishSave();
    await expect(status).toHaveText("Anonymous access saved.");
    await expect(save).toBeDisabled();
    assert.deepEqual(writes[1], { enabled: false, userId: "guest-preview" });
    await expect(realm).toHaveText("LdapRealm");
    assert.deepEqual(settings, { enabled: false, userSource: "Local", userId: "guest-preview", realmName: "LdapRealm" });
    await page.reload();
    await expect(toggle).not.toBeChecked();
    await expect(user).toHaveValue("guest-preview");
    await expect(realm).toHaveText("LdapRealm");
  } finally {
    finishSave();
    await page.unroute(endpoint);
    await page.unroute(accountEndpoint);
  }
}

async function verifyRealmEditor(page) {
  const endpoint = "**/internal/security/realms";
  const fixtures = [
    { realmId: "local", type: "LOCAL", name: "Local", enabled: true, priority: 7 },
    { realmId: "ldap", type: "LDAP", name: "Build directory", enabled: true, priority: 40, attributes: { source: "build-users" } },
    { realmId: "oidc", type: "OIDC", name: "OIDC", enabled: true, priority: 120 },
  ];
  const writes = [];
  await page.route(endpoint, route => {
    if (route.request().method() === "GET") return route.fulfill({ json: fixtures });
    assert.equal(route.request().method(), "PUT");
    const commands = route.request().postDataJSON();
    writes.push(commands);
    if (writes.length === 1) return route.fulfill({ status: 503, json: { message: "Try again" } });
    for (const command of commands) Object.assign(fixtures.find(row => row.realmId === command.realmId), command);
    return route.fulfill({ json: fixtures });
  });
  try {
    await page.goto(origin + "/admin/#admin/security/realms");
    const view = page.locator("#security-realms-view");
    const save = view.getByRole("button", { name: "Save changes" });
    const active = view.locator("#realm-enabled-list .realm-identity strong");
    await expect(active).toHaveText(["Local", "Build directory", "OIDC"]);
    await expect(save).toBeDisabled();
    await expect(view.getByText("build-users")).toBeVisible();
    await expect(view.locator('[data-realm-id="local"]').getByRole("switch")).toHaveCount(0);
    const oidcSwitch = view.getByRole("switch", { name: "Use for sign-in OIDC", exact: true });
    await expect(oidcSwitch).toBeChecked();
    const up = view.getByRole("button", { name: "Move up Build directory" });
    await up.press("Enter");
    await expect(active.first()).toHaveText("Build directory");
    await expect(view.getByRole("button", { name: "Move down Build directory" })).toBeFocused();
    await view.getByRole("button", { name: "Discard changes" }).click();
    await expect(active.first()).toHaveText("Local");
    await expect(save).toBeDisabled();
    await up.click();
    await oidcSwitch.press("Space");
    await expect(view.locator("#realm-disabled-list .realm-identity strong")).toHaveText("OIDC");
    await expect(oidcSwitch).not.toBeChecked();
    await expect(oidcSwitch).toBeFocused();
    await expect(view.locator("#realm-announcement")).toHaveText("OIDC · Disabled realms");
    assert.equal(writes.length, 0);
    await save.click();
    await expect(view.locator("#realm-edit-status")).toHaveText("Save to apply changes.");
    await expect(save).toBeEnabled();
    await save.click();
    await expect(view.locator("#realm-edit-status")).toHaveText("Realms saved.");
    const expected = [
      { realmId: "ldap", priority: 7 }, { realmId: "local", priority: 40 },
      { realmId: "oidc", priority: 120, enabled: false },
    ];
    assert.deepEqual(writes, [expected, expected]);
    await page.reload();
    await expect(active).toHaveText(["Build directory", "Local"]);
    await expect(save).toBeDisabled();
    await oidcSwitch.click();
    await expect(active).toHaveText(["Build directory", "Local", "OIDC"]);
    await expect(oidcSwitch).toBeChecked();
    await expect(oidcSwitch).toBeFocused();
    await expect(view.locator("#realm-announcement")).toHaveText("OIDC · Authentication order");
    await oidcSwitch.click();
    await expect(save).toBeDisabled();

    fixtures.find(row => row.realmId === "local").priority = 7;
    await page.reload();
    await expect(active).toHaveText(["Build directory", "Local"]);
    await view.getByRole("button", { name: "Move up Local", exact: true }).click();
    await save.click();
    await expect(view.locator("#realm-edit-status")).toHaveText("Realms saved.");
    assert.deepEqual(writes[2], [{ realmId: "ldap", priority: 8 }]);
    await page.reload();
    await expect(active).toHaveText(["Local", "Build directory"]);
  } finally {
    await page.unroute(endpoint);
  }
}

async function verifyControlIcons(page) {
  const missingFilterIcons = await page.locator('input[type="search"]').evaluateAll(inputs => inputs
    .filter(input => input.closest(".filter-box") || input.placeholder.startsWith("Filter"))
    .filter(input => !input.previousElementSibling?.matches('.lucide-icon[aria-hidden="true"]'))
    .map(input => input.id || input.placeholder));
  assert.deepEqual(missingFilterIcons, [], "Filter fields have a leading icon");
  const missingCreateIcons = await page.locator(".action-bar button, .security-scan-list-toolbar button").evaluateAll(buttons =>
    buttons.filter(button => button.textContent.trim().startsWith("Create ") && !button.querySelector(".icon-plus"))
      .map(button => button.id));
  assert.deepEqual(missingCreateIcons, [], "Create actions have a plus icon");
}

async function login(page, user, secret, target) {
  const options = page.waitForResponse(r => r.url().endsWith("/internal/security/login/options"));
  await page.goto(origin + "/browse/?login=1&returnTo=" + encodeURIComponent(target) + "#browse/welcome");
  if ((await (await options).json()).oidcEnabled) await page.locator("#login-tab-password").click();
  await page.locator(".login-dialog-username:visible").fill(user);
  await page.locator(".login-dialog-password:visible").fill(secret);
  await page.locator(".login-dialog-submit:visible").click();
}

const browser = await chromium.launch({ executablePath: process.env.KKREPO_CHROMIUM || undefined });
try {
  await api("/internal/repositories", "POST", {
    name: repo, recipe: "raw-proxy", online: true, blobStoreName: store, strictContentTypeValidation: true,
    proxy: { remoteUrl: "https://example.com/", contentMaxAgeMinutes: 60, metadataMaxAgeMinutes: 60, autoBlock: true },
  });
  cleanup.push("/internal/repositories/" + repo);
  await api("/internal/security/users", "POST", {
    source: "Local", userId: reader, firstName: "UI", lastName: "Reader", email: reader + "@example.invalid",
    status: "ACTIVE", password: readerPassword, roles: ["nx-anonymous"],
  });
  cleanup.push("/internal/security/users/Local/" + reader);

  const page = await browser.newPage();
  page.on("pageerror", error => pageErrors.push(error.message));
  await page.addInitScript(() => {
    window.workspaceSwitchAnimated = false;
    const animate = Element.prototype.animate;
    Element.prototype.animate = function (...args) {
      const animation = animate.apply(this, args);
      if (this.classList.contains("workspace-marker")) {
        window.workspaceSwitchAnimated = true;
        const frames = animation.effect.getKeyframes();
        window.workspaceSwitchKeepsShape = frames.every(frame => frame.clipPath === frames[0].clipPath);
      }
      return animation;
    };
  });
  await page.goto(origin + "/browse/");
  await expect(page.locator("#welcome-view")).toBeVisible();
  await verifyEntryAssets(page, "browse");
  await verifyControlIcons(page);
  await page.setViewportSize({ width: 390, height: 844 });
  const browseNav = page.locator("#navigation-toggle");
  await browseNav.click();
  await page.locator('[aria-controls="search-subnav"]').click();
  await expect(page.locator("#search-subnav")).toBeVisible();
  await page.locator('[data-search-format="npm"]').click();
  await expect(page.locator("#search-view")).toBeVisible();
  await expect(browseNav).toHaveAttribute("aria-expanded", "false");
  await expect(browseNav).toBeFocused();
  await page.setViewportSize({ width: 820, height: 844 });
  await page.goto(origin + "/browse/");
  await expect(page.locator("#welcome-view")).toBeVisible();
  await page.locator('[aria-controls="search-subnav"]').click();
  const header = await page.locator(".nx-topbar").boundingBox();
  const signIn = await page.locator(".nx-topbar .account-login").boundingBox();
  assert.ok(signIn && signIn.y + signIn.height <= header.y + header.height,
    "The tablet header contains the complete sign-in button");
  await page.locator('[data-search-format="maven2"]').click();
  await expect(page.locator("#search-view")).toBeVisible();
  await login(page, username, password, adminRoute);
  await expect(page.locator("#repositories-view")).toBeVisible();
  await verifyEntryAssets(page, "admin");
  await verifyControlIcons(page);
  const workspaces = page.locator(".workspace-tabs");
  await page.emulateMedia({ reducedMotion: "no-preference" });
  const underline = workspaces.locator(".workspace-marker");
  const departure = await underline.boundingBox();
  let releaseBrowse;
  const bundleGate = new Promise(resolve => { releaseBrowse = resolve; });
  await page.route("**/browse/assets/browse.js?*", async route => {
    await bundleGate;
    await route.continue();
  }, { times: 1 });
  try {
    await workspaces.getByRole("link", { name: "Browse" }).click({ noWaitAfter: true });
    await expect(workspaces.locator('a[aria-current="page"]')).toHaveText("Browse");
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const firstPaint = await underline.boundingBox();
    assert.ok(Math.abs(firstPaint.x - departure.x) < 1 && Math.abs(firstPaint.width - departure.width) < 1,
      "The destination keeps the departing underline in place before its application bundle loads");
  } finally {
    releaseBrowse();
  }
  await expect.poll(() => page.evaluate(() => window.workspaceSwitchAnimated)).toBe(true);
  assert.equal(await page.evaluate(() => window.workspaceSwitchKeepsShape), true, "The underline stays flat throughout the switch");
  await page.emulateMedia({ reducedMotion: "reduce" });
  await workspaces.getByRole("link", { name: "Admin" }).press("Enter");
  await expect(workspaces.locator('a[aria-current="page"]')).toHaveText("Admin");
  await page.waitForLoadState("domcontentloaded");
  assert.equal(await page.evaluate(() => window.workspaceSwitchAnimated), false, "Reduced motion switches immediately");
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await expect(page.locator("#repositories-view")).toBeVisible();
  const repositoryGroup = page.locator('[data-side-group="repository"]');
  const currentChild = page.locator('#side-group-repository-items .is-active');
  await expect(currentChild).toBeVisible();
  await repositoryGroup.press("Space");
  await expect(repositoryGroup).toHaveAttribute("aria-expanded", "false");
  await expect(repositoryGroup).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
  await expect(repositoryGroup).toHaveCSS("box-shadow", "none");
  await expect(repositoryGroup).toHaveCSS("outline-style", "solid");
  await repositoryGroup.press("Enter");
  await expect(currentChild).toBeVisible();
  const adminIcons = await page.locator("#workspace-navigation .side-item").evaluateAll(items =>
    items.map(item => getComputedStyle(item.querySelector(".side-item-icon")).maskImage));
  assert.ok(adminIcons.length > 0 && !adminIcons.includes("none"));
  assert.equal(new Set(adminIcons).size, adminIcons.length, "Each Admin menu entry has a distinct icon");
  const filter = page.locator("#repository-filter");
  const filterFrame = page.locator(".filter-box").filter({ has: filter });
  await page.locator("#create-repository-button").focus();
  const idleShadow = await filterFrame.evaluate(frame => getComputedStyle(frame).boxShadow);
  await page.keyboard.press("Tab");
  await expect(filter).toBeFocused();
  await expect(filter).toHaveCSS("outline-style", "none");
  await expect(filterFrame).not.toHaveCSS("box-shadow", idleShadow);
  await page.emulateMedia({ forcedColors: "active" });
  await expect(filterFrame).toHaveCSS("outline-style", "solid");
  await expect(filter).toHaveCSS("outline-style", "none");
  await page.emulateMedia({ forcedColors: "none" });
  const edit = page.locator('.edit-repository-button[data-name="' + repo + '"]');
  for (const width of [1920, 1440, 1024, 820, 390]) {
    await page.setViewportSize({ width, height: 844 });
    await verifyPageAlignment(page.locator("#repositories-view"),
      ["#repository-usage-summary", ".fixed-table-frame"], ["#create-repository-button"]);
  }
  const nav = page.locator("#navigation-toggle");
  await nav.click();
  await expect(page.locator("#workspace-navigation")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(nav).toBeFocused();
  assert.ok(await page.locator(".nx-topbar").evaluate(bar => bar.clientHeight < 120),
    "The mobile header leaves room for repository rows");
  await page.setViewportSize({ width: 1024, height: 768 });
  const table = page.locator("#repositories-view .fixed-table-frame");
  await expect(edit).toBeVisible();
  assert.ok(await table.evaluate(frame => frame.scrollWidth <= frame.clientWidth + 1), "Compact rows fit the viewport");
  await page.locator("#repository-all-columns").check();
  await expect(page.locator("#repositories-view th").getByText("Blob store", { exact: true })).toBeVisible();
  await page.locator("#repository-all-columns").uncheck();
  await page.locator("#create-repository-button").click();
  await page.locator("#repository-recipe-trigger").click();
  await page.locator("#repository-recipe-search").press("End");
  const active = page.locator("#repository-recipe-options .is-active");
  assert.ok(await active.evaluate(option => {
    const rect = option.getBoundingClientRect();
    return option.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
  }), "Last recipe is reachable outside the modal scroll clipping");
  await page.keyboard.press("Escape");
  await expect(page.locator("#repository-recipe-trigger")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.locator("#create-repository-button")).toBeFocused();
  await edit.click();
  const age = page.locator("#repository-content-max-age");
  await expect(age).toHaveValue("60");
  await age.fill("73");
  const saved = page.waitForResponse(r => r.url().endsWith("/internal/repositories/" + repo) && r.request().method() === "PUT");
  await page.locator("#save-repository-button").click();
  assert.ok((await saved).ok(), "Proxy update must succeed");
  await page.reload();
  await edit.click();
  await expect(age).toHaveValue("73");
  await page.keyboard.press("Escape");
  await expect(page.locator("#repository-form-modal")).toBeHidden();

  for (const [hash, view] of [
    ["repository/cleanup/runs", "cleanup-policies"], ["security/artifact-scanning/tasks", "security-scanning"],
    ["repository/blobstores", "blobstores"], ["migration/repository-data", "repository-data-migration"],
    ["migration/ohpm", "ohpm-import"], ["system/ui-settings", "ui-settings"],
  ]) {
    await page.goto(origin + "/admin/#admin/" + hash);
    await expect(page.locator("#" + view + "-view")).toBeVisible();
    if (hash.startsWith("migration/")) {
      const menuIcon = page.locator(`.side-item[data-view="${view}"] .side-item-icon`);
      const mask = await menuIcon.evaluate(icon => getComputedStyle(icon).maskImage);
      await expect(page.locator("#" + view + "-view .heading-icon .lucide-icon")).toHaveCSS("mask-image", mask);
    }
    if (view === "cleanup-policies") {
      await verifyPageAlignment(page.locator("#cleanup-policies-view"),
        [".cleanup-tabs", ".cleanup-tab-panel.is-active .nx-table-frame"], []);
    }
  }
  await verifyRealmEditor(page);
  await verifyAnonymousEditor(page);
  await page.goto(origin + "/admin/#admin/repository/docker");
  const dockerView = page.locator("#docker-registry-view");
  await expect(dockerView.locator("#docker-connector-summary")).toContainText("Active ports");
  for (const width of [1920, 1440, 1024, 820, 390]) {
    await page.setViewportSize({ width, height: 844 });
    await verifyPageAlignment(dockerView, [
      ".docker-section-head:has(#docker-connectors-title)", ".nx-table-frame",
      ".docker-section-head:has(#docker-transfers-title)", "#docker-transfer-summary",
      ".docker-details > details:first-child", ".docker-details > details:last-child",
    ], []);
  }
  const diagnostics = dockerView.locator("details").filter({ has: page.locator("#docker-node-id") });
  await diagnostics.locator("summary").press("Enter");
  await expect(diagnostics.locator("#docker-node-id")).toBeVisible();
  await expect(diagnostics.locator("#docker-node-id")).not.toHaveText("—");
  const cacheButton = dockerView.locator("#docker-cache-clear-button");
  await dockerView.locator("summary").filter({ hasText: "Cache maintenance" }).press("Space");
  let cacheRequests = 0;
  const cacheEndpoint = "**/internal/docker/cache/clear";
  await page.route(cacheEndpoint, route => {
    assert.equal(route.request().method(), "POST");
    cacheRequests++;
    return route.fulfill({ json: { cleared: true, repositories: 3 } });
  });
  try {
    await cacheButton.press("Enter");
    const dialog = page.getByRole("dialog", { name: "Clear cache for all Docker repositories?" });
    await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(cacheButton).toBeFocused();
    assert.equal(cacheRequests, 0);
    await cacheButton.click();
    await dialog.getByRole("button", { name: "Clear Docker cache" }).click();
    await expect.poll(() => cacheRequests).toBe(1);
    await expect(cacheButton).toBeEnabled();
  } finally {
    await page.unroute(cacheEndpoint);
  }
  await page.setViewportSize({ width: 1024, height: 620 });
  await page.goto(origin + "/admin/#admin/system/download-offload");
  const offloadEnabled = page.locator("#offload-enabled");
  await expect(offloadEnabled).toBeEnabled();
  await offloadEnabled.check();
  const preset = page.locator("#offload-method-trigger");
  const options = page.locator("#offload-method-popover");
  await preset.click();
  await expect(options).toBeVisible();
  const initialPreset = await preset.boundingBox();
  const initialOptions = await options.boundingBox();
  await page.locator(".nx-content").evaluate(content => content.scrollBy(0, 90));
  const scrolledPreset = await preset.boundingBox();
  const scrolledOptions = await options.boundingBox();
  assert.ok(scrolledPreset.y < initialPreset.y, "The settings page scrolls");
  assert.ok(Math.abs((scrolledOptions.y - initialOptions.y) - (scrolledPreset.y - initialPreset.y)) <= 1,
    "Proxy preset options move with the trigger");
  await preset.press("Escape");
  await expect(options).toBeHidden();

  for (const view of ["search", "upload", "browse"]) {
    await page.goto(origin + "/browse/#browse/" + view);
    await expect(page.locator("#" + view + "-view")).toBeVisible();
    if (view === "search") {
      await verifyPageAlignment(page.locator("#search-view"), [".result-note", ".nx-table-frame"],
        [".search-form > label"]);
    }
  }

  // Exercise a theme and dictionary in this browser context only.
  await page.evaluate(() => window.kkrepoI18n.ready());
  const theme = await page.evaluate(() => {
    window.kkrepoTheme.applyTheme("indigo", ["default", "indigo"]);
    return document.getElementById("kkrepo-theme-stylesheet").href;
  });
  assert.equal(new URL(theme).searchParams.get("v"), await page.locator("html").getAttribute("data-theme-version"));
  assert.ok((await page.request.get(theme)).ok());
  await page.evaluate(() => window.kkrepoI18n.setDefaultLanguage("fr"));
  const dictionary = page.locator('script[src*="/login/assets/i18n/fr.js"]');
  await expect(dictionary).toBeAttached();
  const localeUrl = await dictionary.evaluate(element => element.src);
  assert.equal(new URL(localeUrl).searchParams.get("v"), await page.locator("html").getAttribute("data-dictionary-version"));
  assert.ok((await page.request.get(localeUrl)).ok());

  const limited = await browser.newPage();
  limited.on("pageerror", error => pageErrors.push(error.message));
  await login(limited, reader, readerPassword, "/browse/#browse/welcome");
  await expect(limited.locator("#current-user")).toHaveText(reader);
  await limited.goto(origin + adminRoute);
  await expect(limited).toHaveURL(/\/browse\//);
  await expect(limited.locator("#admin-workspace-link")).toBeHidden();
  assert.deepEqual(pageErrors, []);
  console.log("Passed anonymous Browse, login return, Proxy save and reload, modal Escape, Admin and Browse navigation, theme and locale loading, and restricted Admin deep link.");
} finally {
  await browser.close();
  const results = await Promise.allSettled(cleanup.map(path => api(path, "DELETE")));
  for (const result of results) if (result.status === "rejected") throw result.reason;
}
