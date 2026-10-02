# 出站 HTTPS 自定义 CA 证书

通过 `KKREPO_TLS_CA_CERTIFICATES` 为 HTTPS S3/MinIO Blob Store、`oss-native` Blob Store 和上游仓库请求配置受信任的内部 CA，也适用于经过 HTTP CONNECT 和 SOCKS 代理的上游请求：

```bash
export KKREPO_TLS_CA_CERTIFICATES=/etc/kkrepo/internal-ca.pem
bin/start.sh
```

直接启动 `lib/kkrepo`、使用 JVM jar 或容器部署时同样有效。Native 部署无需安装 JDK 或使用 `keytool`。也可以在 `conf/application.properties` 中配置：

```properties
kkrepo.tls.ca-certificates=/etc/kkrepo/internal-ca.pem
```

文件必须包含一个或多个以空白分隔的 PEM `CERTIFICATE` 块。多个受信任的 CA 可以通过 `cat infrastructure-root.pem repository-root.pem > internal-ca.pem` 合并。无需私钥或密码。配置的文件不存在、不可读、为空或格式错误时，应用会拒绝启动，错误中会指出 `kkrepo.tls.ca-certificates`。

## 信任范围与多副本

证书会在内存中追加到当前 Java 信任库，默认公共 CA 保持受信任。如果显式配置了 `javax.net.ssl.trustStore`，则以该信任库作为已有证书来源。原信任库文件与 Java 全局 SSL 设置不会被修改，证书链、有效期和主机名校验保持启用。

每个副本在启动时加载一次证书包。请向所有副本部署相同文件，证书变更后重启所有副本。Kubernetes 可将同一个 ConfigMap 或 Secret 挂载到指定路径，更新后滚动部署。证书内容不会写入数据库或 Blob Store。

如需使用 Debian 系统证书包，可显式设置 `KKREPO_TLS_CA_CERTIFICATES=/etc/ssl/certs/ca-certificates.crt`，该文件需要已包含所需的内部 CA。kkRepo 不会自动选择操作系统证书路径。

## systemd 与容器

对于已直接启动 `lib/kkrepo` 的 systemd unit，在 `[Service]` 中加入以下设置，然后重新加载并重启该 unit：

```ini
Environment="KKREPO_TLS_CA_CERTIFICATES=/etc/kkrepo/internal-ca.pem"
```

确保服务用户可以读取证书包。容器可在现有部署中增加只读挂载和环境变量：

```yaml
environment:
  KKREPO_TLS_CA_CERTIFICATES: /etc/kkrepo/internal-ca.pem
volumes:
  - ./internal-ca.pem:/etc/kkrepo/internal-ca.pem:ro
```

Helm chart 可通过 `extraEnv`、`extraVolumes`、`extraVolumeMounts` 配置。

## 已有 JKS / PKCS12 信任库

GraalVM Native Image 也支持运行时 Java truststore 系统属性：

```bash
lib/kkrepo \
  -Djavax.net.ssl.trustStore=/etc/kkrepo/truststore.p12 \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djavax.net.ssl.trustStorePassword=your-password
```

同时保留原有启动参数，包括 Spring 外部配置目录。通过 `bin/start.sh` 启动时，Native 将这些参数放入 `KKREPO_NATIVE_OPTS`，JVM 则使用 `JAVA_OPTS`。这两个变量由启动脚本解析，直接启动二进制时需要显式传参。

显式 Java truststore 会替换默认信任库，因此其中应包含需要的公共 CA。配置的 PEM 证书会为上述客户端追加到该信任库。Native 未配置时使用构建阶段嵌入的默认信任库，仅修改宿主机系统证书不会更新它。

参考：[GraalVM 证书管理](https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/CertificateManagement/) 与 [Sonatype Java truststore 指南](https://support.sonatype.com/hc/en-us/articles/213465768-SSL-Certificate-Guide)。
