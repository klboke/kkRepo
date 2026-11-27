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
    const element = ext === "js" ? 'script[type="module"][src]' : 'link[rel="stylesheet"][href]';
    const url = await page.locator(element).evaluateAll((elements, suffix) =>
      elements.map(item => item.src || item.href).find(value => new URL(value).pathname.endsWith(suffix)), "/" + app + "." + ext);
    assert.ok(url, "Missing " + app + "." + ext);
    const response = await page.request.get(url);
    assert.ok(response.ok());
    const digest = createHash("sha256").update(await response.body()).digest("hex").slice(0, 16);
    assert.equal(new URL(url).searchParams.get("v"), digest, "Served asset must match the version in its HTML");
  }
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
  await page.goto(origin + "/browse/");
  await expect(page.locator("#welcome-view")).toBeVisible();
  await verifyEntryAssets(page, "browse");
  await login(page, username, password, adminRoute);
  await expect(page.locator("#repositories-view")).toBeVisible();
  await verifyEntryAssets(page, "admin");
  const edit = page.locator('.edit-repository-button[data-name="' + repo + '"]');
  await page.setViewportSize({ width: 390, height: 844 });
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
    ["system/ui-settings", "ui-settings"],
  ]) {
    await page.goto(origin + "/admin/#admin/" + hash);
    await expect(page.locator("#" + view + "-view")).toBeVisible();
  }
  for (const view of ["search", "upload", "browse"]) {
    await page.goto(origin + "/browse/#browse/" + view);
    await expect(page.locator("#" + view + "-view")).toBeVisible();
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
