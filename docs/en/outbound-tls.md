# Custom CA certificates for outbound HTTPS

Set `KKREPO_TLS_CA_CERTIFICATES` to trust internal certificate authorities for HTTPS S3/MinIO blob stores, `oss-native` blob stores, and upstream repository requests, including HTTP CONNECT and SOCKS proxies:

```bash
export KKREPO_TLS_CA_CERTIFICATES=/etc/kkrepo/internal-ca.pem
bin/start.sh
```

This also works when starting `lib/kkrepo` directly, with a JVM jar, or in a container. Native deployments do not need an installed JDK or `keytool`. Alternatively, set this application property in `conf/application.properties`:

```properties
kkrepo.tls.ca-certificates=/etc/kkrepo/internal-ca.pem
```

The file must contain one or more PEM `CERTIFICATE` blocks separated by whitespace. Combine multiple trusted CA certificates with `cat infrastructure-root.pem repository-root.pem > internal-ca.pem`. Private keys and passwords are not needed. A missing, unreadable, empty, or malformed configured file prevents startup with an error identifying `kkrepo.tls.ca-certificates`.

## Trust and replicas

Certificates are added to the active Java truststore in memory. Default public CA roots remain trusted. If `javax.net.ssl.trustStore` is explicitly configured, that store supplies the existing roots. The original truststore and global Java SSL settings are not modified. Certificate chain, validity, and hostname verification remain enabled.

Every replica loads its bundle once at startup. Deploy the same file to all replicas and restart them after certificate changes. On Kubernetes, mount the same ConfigMap or Secret at the configured path and roll out the deployment after updates. No certificate data is saved in the database or blob store.

To use Debian's system bundle explicitly, set `KKREPO_TLS_CA_CERTIFICATES=/etc/ssl/certs/ca-certificates.crt`. That file must already contain the required internal CAs. kkRepo does not automatically select an operating system certificate path.

## systemd and containers

For a systemd unit that already starts `lib/kkrepo` directly, add this to `[Service]`, then reload and restart the unit:

```ini
Environment="KKREPO_TLS_CA_CERTIFICATES=/etc/kkrepo/internal-ca.pem"
```

Give the service user read access to the bundle. In an existing container deployment, add a read-only mount and the environment variable:

```yaml
environment:
  KKREPO_TLS_CA_CERTIFICATES: /etc/kkrepo/internal-ca.pem
volumes:
  - ./internal-ca.pem:/etc/kkrepo/internal-ca.pem:ro
```

The Helm chart supports this through `extraEnv`, `extraVolumes`, and `extraVolumeMounts`.

## Existing JKS / PKCS12 truststores

GraalVM Native Image also supports Java truststore system properties at runtime:

```bash
lib/kkrepo \
  -Djavax.net.ssl.trustStore=/etc/kkrepo/truststore.p12 \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djavax.net.ssl.trustStorePassword=your-password
```

Retain the application's other startup arguments, including its external Spring configuration location. With `bin/start.sh`, put these arguments in `KKREPO_NATIVE_OPTS` for Native or `JAVA_OPTS` for JVM. These variables are interpreted by the launcher script; direct binary invocations need explicit arguments.

An explicit Java truststore replaces the default truststore, so include any required public CA roots. A configured PEM bundle is added to that explicit store for the clients described above. Native's unconfigured default truststore is embedded at build time; changing host system certificates alone does not update it.

References: [GraalVM certificate management](https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/CertificateManagement/) and [Sonatype's Java truststore guide](https://support.sonatype.com/hc/en-us/articles/213465768-SSL-Certificate-Guide).
