# Frontend modules

Admin and Browse ship as static resources in their existing Maven JARs. Each workspace has a shell in `src/main/ui/index.html`, one HTML fragment per view under `views/`, and styles in the cascade order listed in `styles.css`. Maven assembles these files and bundles the native ES module entry with Rollup. The server serves one application script per workspace. Node and npm run on the build machine.

| Area | Source under the corresponding UI module |
| --- | --- |
| Admin navigation, permissions and startup | `js/shell/` and the `adminViews` registry in `routes.js` |
| Repository CRUD, Proxy field model, recipes, signing and Docker connectors | `js/repositories/` |
| Blob stores, usage, cleanup policies and runs | `js/storage/`, `js/cleanup/` |
| Identity, authentication providers, API keys and scanning | `js/security/`, `js/scanning/` |
| Migration, audit and appearance | `js/migration/`, `js/audit/`, `js/system/` |
| OHPM, routing rules, content selectors, outbound proxy and download offload | Named page modules under Admin `js/` |
| Browse routes, repository list and expandable tree | `js/shell/`, `js/repositories/`, `js/tree/` |
| Search, details, upload, user setup and tokens | `js/search/`, `js/details/`, `js/upload/`, `js/account/` |
| Format-specific usage examples | Browse `js/formats/` and the provider table in `js/details/usage.js` |
| Both shells | `admin-ui/src/main/ui/shared/` via `#ui-shared/` imports |
| Early theme selection, translations, CSRF, login dialog and top-bar search | Existing public scripts in `/login/assets/` and `/browse/assets/` |

When adding an Admin view, include its template in the shell, register its canonical hash, aliases and entry/exit callbacks, and give it a permission rule in the session module. Bind events once at startup. Keep request cancellation, poll timers and transient state with the feature that owns them. The service makes authorization decisions on each request.

For a Proxy field, update its template, the form's DOM binding and `proxy-form.js`. Verify that the read model and write payload preserve zero, empty, masked and explicitly cleared values. For a Browse format, add the provider to the format registry and exercise it against the existing server response.

## Build and test

With Node 22.12+ or 24 and npm on PATH, run `mvn -pl admin-ui,browse-ui -am test`. The build installs the locked Rollup dependency under each module's target directory. The two JUnit entry points discover all `src/test/js/*.test.js` suites. New model tests can directly import the authored modules. Older DOM fixtures run selected declarations against simulated browser state.

For a running development checkout, use `mvn -pl admin-ui,browse-ui process-resources` and refresh the browser. To run the packaged browser smoke test, install the dev dependencies with `npm ci --prefix scripts/ui` and Chromium with `(cd scripts/ui && npx --no-install playwright install chromium)`. Set `KKREPO_UI_URL`, `KKREPO_UI_USER` and `KKREPO_UI_PASSWORD`, then run `node scripts/ui/smoke.mjs`. The target needs a usable blob store (default name `default`) and an administrator. The test provisions and removes a uniquely named empty repository and a reader.

## Rolling deployments

The JS and CSS entry URLs include a digest of their packaged bytes. With a load balancer that can send successive requests to different releases, publish both sets of assets to the same static origin before rolling pods. The helper also understands the previous JavaScript layout and keeps files still referenced by the older shell.

```sh
python3 scripts/ui/stage-assets.py previous-server.jar /srv/kkrepo-ui
python3 scripts/ui/stage-assets.py candidate-server.jar /srv/kkrepo-ui
```

For example, configure the existing NGINX proxy with the following location and its usual upstream settings in the named fallback. The stage includes entry scripts, stylesheets and the themes and dictionaries selected at runtime. Keep the original MIME type configuration for JavaScript modules.

```nginx
# http context
map $arg_v $kkrepo_ui_version {
    default __unversioned;
    ~^[a-zA-Z0-9_-]+$ $arg_v;
}

# server context
location ~ ^/(admin|browse|login)/assets/.*\.(css|js)$ {
    root /srv/kkrepo-ui;
    try_files /$kkrepo_ui_version$uri @kkrepo_backend;
}
```

Run the helper on each static origin (or publish its output to shared storage). Stage and validate the candidate before routing HTML traffic to it. Retain the previous version through the client cache and rollback window. The helper is idempotent and detects conflicting content published at an existing URL. Single-release instances serve the same resources directly from the JAR.
