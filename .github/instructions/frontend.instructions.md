---
applyTo: "admin-ui/**,browse-ui/**"
---

# Frontend Instructions

- Admin UI should be operational and information-dense. Prioritize repository state, permissions, storage health, migration progress, and audit visibility.
- Browse UI should be fast, clear, and predictable for artifact discovery and download workflows.
- Avoid decorative marketing layouts in operational screens.
- Preserve existing static-resource structure served by Spring Boot.
- If static assets are changed, run resource processing or a server compile so `target/classes` reflects the change before live verification.
- Keep text concise and domain-specific. Do not add visible instructions for obvious UI controls.
