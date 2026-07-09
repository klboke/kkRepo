# Support

Thanks for trying kkrepo. This page explains where to ask questions, report bugs, and disclose security issues.

## Community Support

- Use GitHub issues for reproducible bugs, compatibility differences, feature requests, and documentation problems.
- Use the [kkrepo Telegram group](https://t.me/+UbIsTKXTzxBhYjFl) for community discussion and usage questions.
- Before opening an issue, check the README, build/deployment guide, compatibility testing guide, and troubleshooting guide.

## Issue Types

Use the closest matching issue template:

- Bug report: a reproducible kkrepo behavior problem.
- Nexus compatibility issue: a client-visible difference between Nexus and kkrepo.
- Feature request: a new repository format, operation, migration behavior, or administration workflow.

Please include the kkrepo version or commit, deployment mode, repository format, relevant client command or HTTP request, expected behavior, actual behavior, and sanitized logs when useful.

## Security Issues

Do not report exploitable security vulnerabilities in public issues, pull requests, or discussions.

Follow [SECURITY.md](SECURITY.md) and report privately through GitHub Security Advisories:

https://github.com/klboke/kkrepo/security/advisories/new

Security-sensitive examples include authentication bypass, authorization bypass, token or credential disclosure, repository-content disclosure, privilege escalation, remote code execution, and vulnerabilities in migration flows that expose source Nexus data.

## Support Scope

kkrepo is early-stage open source software. The project maintainers try to respond to public issues and pull requests, but no response time or production support SLA is promised through the public repository.

For production deployments, keep your own MySQL backups, object-storage backup or versioning policy, rollback plan, and upgrade validation process. Public support cannot recover private deployment data.
