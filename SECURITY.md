# Security Policy

## Reporting a Vulnerability

Please report exploitable security vulnerabilities privately through GitHub Security Advisories:

https://github.com/klboke/nexus-plus/security/advisories/new

Do not disclose exploitable details in public issues, pull requests, or discussions before a fix is available.

When reporting, include:

- Affected nexus-plus version or commit.
- Deployment mode and relevant security configuration.
- Steps to reproduce or proof-of-concept details.
- Impact assessment, including whether authentication, authorization, repository content, tokens, credentials, or migration data are affected.
- Logs or HTTP traces with secrets redacted.

## Supported Versions

nexus-plus is early-stage open source software. Security fixes target the latest `main` branch unless a release branch is explicitly announced.

| Version | Supported |
| --- | --- |
| `main` | Yes |
| Older commits or unannounced branches | No |

## Public Issues

Use public issues for ordinary bugs, compatibility differences, feature requests, and documentation problems. If an issue could enable unauthorized access, credential exposure, token leakage, privilege escalation, repository-content disclosure, or remote code execution, report it privately first.
