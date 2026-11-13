# kkrepo-script-host

JSR-223 host for maintenance, cleanup and repository protocol scripts.

## Script contract

A script is loaded once and may define:

- `candidates(requestJson)` returning a JSON array of cleanup candidates:

  ```json
  [{"repository":"repo","path":"snapshots/a","reason":"stale","sizeBytes":42}]
  ```

- `run(requestJson)` returning a maintenance result:

  ```json
  {"proposed":1,"deleted":1,"messages":["ok"]}
  ```

- `handle(requestJson)` returning a neutral repository response:

  ```json
  {"status":200,"headers":{"Content-Type":["text/plain"]},"body":"b2s="}
  ```

  `body` is base64-encoded because the Java response type is `byte[]`.

The Java side is `CleanupRule`, `MaintenanceTask` or `RepositoryProtocolHandler` from
`kkrepo-plugin-api`.

## Module descriptor

The server scans `kkrepo.scripts.directory` (default `scripts`) for `module.json` files:

```json
{
  "id": "raw-policy",
  "language": "js",
  "entrypoint": "handler.js",
  "capabilities": ["cleanup.rule", "maintenance.task", "repository.handler"],
  "routes": [
    {"format": "raw", "type": "HOSTED", "method": "GET", "pathPattern": "**", "order": 100}
  ]
}
```

`language` defaults to `kkrepo.scripts.language` (`js`). Enabled with
`kkrepo.scripts.enabled=true`.

## Engine

The host is engine-agnostic and uses `ScriptEngineManager`. The production distribution adds GraalJS
(`org.graalvm.js:js-scriptengine`) and uses the `js` / `graal.js` engine name. The unit test uses a
fake JSR-223 engine so the core contract does not depend on a full JavaScript runtime; an
integration test runs a real GraalJS script.

## Safety

In-process JSR-223 scripts are trusted code. For untrusted scripts, run them in a separate process
(the `scanner-adapter` model) with a narrow HTTP/gRPC contract and explicit capabilities. Always
enforce timeouts, output-size limits, dry-run defaults and audit for destructive maintenance. The
server keeps repository resolution, routing policy, locks, quotas and deletion ownership; scripts
only propose candidates or return neutral responses.
