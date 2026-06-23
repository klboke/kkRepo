---
applyTo: "protocol-*/**,compat-test/**,server/src/main/java/com/github/klboke/kkrepo/server/**/*Service.java,server/src/main/java/com/github/klboke/kkrepo/server/**/*Controller.java"
---

# Protocol Compatibility Instructions

- Check the official protocol and Nexus behavior before changing hosted, proxy, or group repository behavior.
- Keep URL layout compatible with `/repository/<repo>/...` unless a protocol-specific endpoint requires another layout.
- For hosted writes, preserve write policy, checksum, metadata, and error response semantics.
- For proxy repositories, treat upstream status, headers, cache TTLs, negative cache, conditional requests, and body-read failures carefully.
- For group repositories, preserve member ordering and deterministic conflict handling.
- Add or update black-box compatibility tests under `compat-test` when protocol behavior changes.
- Do not mark protocol work complete until real client behavior or compatibility tests cover the changed path.
