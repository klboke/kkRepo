# kkrepo-script-host

JSR-223 host for maintenance and cleanup scripts.

## Script contract

A script is loaded once and must define:

- `candidates(requestJson)` returning a JSON array of cleanup candidates:

  ```json
  [{"repository":"repo","path":"snapshots/a","reason":"stale","sizeBytes":42}]
  ```

- `run(requestJson)` returning a maintenance result:

  ```json
  {"proposed":1,"deleted":1,"messages":["ok"]}
  ```

The Java side is `CleanupRule` or `MaintenanceTask` from `kkrepo-plugin-api`.

## Engine

The host is engine-agnostic and uses `ScriptEngineManager`. The production distribution is expected
to add GraalJS (`org.graalvm.js:js-scriptengine`) and use the `js` / `graal.js` engine name. The
unit test uses a fake JSR-223 engine so the build does not depend on a full JavaScript runtime.

## Safety

In-process JSR-223 scripts are trusted code. For untrusted scripts, run them in a separate process
(the `scanner-adapter` model) with a narrow HTTP/gRPC contract and explicit capabilities. Always
enforce timeouts, output-size limits, dry-run defaults and audit for destructive maintenance.
