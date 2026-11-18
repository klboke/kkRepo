# Docker HTTPS connector mapping

Reference: Nexus Repository 3.94.0 REST schema (`/service/rest/swagger.json`).

| Nexus UI | Nexus REST Docker attributes | kkRepo repository Docker attributes |
| --- | --- | --- |
| HTTP connector | `httpPort` | `connectorEnabled`, `connectorPort` (existing API) |
| HTTPS connector | `httpsPort` | `httpsConnectorEnabled`, `httpsConnectorPort` |
| Server TLS | deployment Jetty keystore | named Spring SSL bundle, `tlsBundle` reference |

HTTP and HTTPS may coexist on different ports. Nexus repository requests do not
contain certificates/private keys. Nexus's SSL Certificates screen manages
outbound trust, not the server identity. kkRepo likewise keeps inbound bundles
separate from outbound CA trust. A public URL advertises an external address;
it does not turn a listener into HTTPS.

References:
- https://help.sonatype.com/en/docker-registry.html
- https://help.sonatype.com/en/configuring-ssl.html
- https://support.sonatype.com/hc/en-us/articles/115013153887-Docker-Repository-Configuration-and-Client-Connection
- https://distribution.github.io/distribution/spec/api/

Run `DockerRegistryBlackBoxCompatibilityTest` against HTTPS connector base URLs
with a JVM truststore containing the test CA. The suite exercises the same V2
status/header, upload, checksum and download contracts as HTTP. Certificate
validation must remain enabled. `DockerConnectorRuntimeTest` separately covers
actual Tomcat HTTP/TLS listeners, reconciliation and fail-closed behavior.

## Packaged runtime check

`python3 scripts/ci/check-docker-tls.py --image <candidate>` runs on both JVM and
Native candidates in CI. It checks PEM trust, independent listeners, secure bearer
realms and upload Locations, automatic reconciliation on two replicas, OCI
push/pull, Skopeo copy, and connector removal. An isolated shared file volume is
used only as test blob storage; production uses shared object storage.

For a writable Nexus HTTPS reference, add `--nexus-url https://<host>:<port>` and
`--nexus-ca <reference-ca.pem>` (plus `--nexus-username` / `--nexus-password` when
needed). Use a disposable repository: this probe writes `tls/probe:latest`.
Nexus 3.68.1 was verified with `httpPort=5000`, `httpsPort=5443`, a deployment
keystore and both connectors enabled; upload statuses, HTTPS Location, downloaded
bytes and manifest digest matched the packaged kkRepo JVM candidate.
