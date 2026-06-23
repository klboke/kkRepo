---
agent: agent
description: Summarize compatibility and distributed-state risks in a kkRepo change.
---

Review this kkRepo change for compatibility and distributed-state risk.

Change summary:
${input:summary:Paste the change summary here}

Diff excerpt:
${input:diff:Paste the relevant diff excerpt here}

Focus on:

1. Nexus and official protocol compatibility risks.
2. Multi-replica state, cache, lock, upload, delete, metadata, and permission correctness.
3. MySQL metadata consistency and OSS/S3 blob safety.
4. Missing tests or validation commands.
5. A concise merge-readiness recommendation.

Use concrete file and line references when available.
