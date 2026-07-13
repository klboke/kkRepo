# Set Up a Maven Private Repository with kkRepo

## Introduction

In team development, a Maven private repository is almost a standard piece of Java infrastructure. It can cache dependencies from central repositories, host internal company components, provide a unified CI/CD publishing endpoint, and reduce the impact of public network instability on builds.

kkRepo is a self-hosted artifact repository that follows Nexus-style client access patterns. It supports Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Docker/OCI, and other artifact formats. For Maven usage, it keeps the familiar `/repository/<repo>/...` URL layout, which lowers the client-side configuration cost when migrating from or replacing Nexus.

- https://github.com/klboke/kkrepo
- https://gitee.com/kailing/kkRepo

## 1. Start kkRepo Quickly

For a local trial, you can use the official quickstart script. It starts kkRepo and MySQL:

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | bash
```

After startup, open:

```text
Admin console: http://127.0.0.1:19090/admin/
User browser: http://127.0.0.1:19090/browse/
Health check: http://127.0.0.1:19091/actuator/health
```

On the first visit to the admin console, create the initial `Local/admin` administrator password.

The local quickstart uses File blob storage by default, which is suitable for trials and development verification. For production, use a dedicated MySQL instance and switch blob storage to OSS/S3.

## 2. Create Maven Repositories

After opening `/admin/`, create the common Maven repository layout:

```text
maven-releases    hosted, for publishing release versions
maven-snapshots   hosted, for publishing SNAPSHOT versions
maven-central     proxy, for proxying Maven Central
maven-public      group, as the dependency resolution entrypoint
```

When creating the `maven-central` proxy repository, use the official Maven Central repository URL as the upstream:

```text
https://repo.maven.apache.org/maven2/
```

If your company already has an internal Maven mirror, you can use that internal mirror URL as the proxy upstream instead.

When creating the `maven-public` group repository, add these member repositories:

```text
maven-releases
maven-snapshots
maven-central
```

With this setup, clients only need to resolve dependencies from the single `maven-public` URL. Internal packages published to the private repository are served from `maven-releases` or `maven-snapshots`, while third-party open source dependencies are fetched and cached through the `maven-central` proxy. Publishing still goes directly to `maven-releases` or `maven-snapshots`.

## 3. Configure Maven Dependency Resolution

Edit `~/.m2/settings.xml` on your local machine or CI environment:

```xml
<settings>
  <mirrors>
    <mirror>
      <id>kkrepo</id>
      <mirrorOf>*</mirrorOf>
      <url>http://127.0.0.1:19090/repository/maven-public/</url>
    </mirror>
  </mirrors>
</settings>
```

For production, use HTTPS, for example:

```text
https://repo.example.com/repository/maven-public/
```

After this configuration, normal Maven builds automatically go through kkRepo:

```bash
mvn clean package
```

## 4. Configure Maven Publishing

If your project needs to publish internal jars, add this to the project `pom.xml`:

```xml
<distributionManagement>
  <repository>
    <id>maven-releases</id>
    <url>http://127.0.0.1:19090/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>maven-snapshots</id>
    <url>http://127.0.0.1:19090/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

Then configure the publishing account in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>maven-releases</id>
      <username>alice</username>
      <password>${env.KKREPO_PASSWORD}</password>
    </server>
    <server>
      <id>maven-snapshots</id>
      <username>alice</username>
      <password>${env.KKREPO_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Note: each `server.id` must match the corresponding `id` in `distributionManagement`.

Set the environment variable and publish:

```bash
export KKREPO_PASSWORD='your-password-or-token'
mvn deploy
```

## 5. Upload Artifacts Manually

Besides `mvn deploy`, kkRepo also supports PUT uploads to Maven hosted repositories. For example:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file app-1.0.0.jar \
  http://127.0.0.1:19090/repository/maven-releases/com/acme/app/1.0.0/app-1.0.0.jar
```

The path must follow the Maven repository layout:

```text
groupId/artifactId/version/artifactId-version.jar
```

For example:

```text
com/acme/app/1.0.0/app-1.0.0.jar
```

## 6. Production Recommendations

The local quickstart helps you validate the flow quickly, but production deployments should pay attention to these points:

1. Use a dedicated MySQL 8.0 instance to store metadata, users, permissions, tokens, and runtime state.
2. Use OSS/S3 as the blob store. Avoid storing large files on a single local disk for long-term production use.
3. Enable HTTPS to avoid transmitting Maven passwords or tokens in plaintext.
4. Use user tokens or CI tokens instead of writing administrator passwords into pipelines.
5. Replace `KKREPO_CREDENTIAL_SECRET` and `KKREPO_API_KEY_PAYLOAD_SECRET` with stable strong random strings.
6. Expose a stable domain, such as `https://repo.example.com`, to make future migration and scaling easier.

One important kkRepo design point is multi-replica deployment: sessions, permissions, tokens, migration state, and other shared state are stored in MySQL or PostgreSQL, while in-process cache is only a rebuildable local hot cache. This is helpful for rolling upgrades and horizontal scaling in production.

## 7. Summary

The core flow for setting up a Maven private repository with kkRepo is straightforward:

```text
Start kkRepo
Create a blob store
Create maven-releases / maven-snapshots / maven-central / maven-public
Add hosted and proxy repositories to the maven-public group
Configure Maven settings.xml
Configure project distributionManagement
Run mvn deploy
```

If your team is already familiar with Nexus, kkRepo's `/repository/<repo>/...` URL layout also helps reduce migration cost. For artifact repository deployments that want MySQL, OSS/S3, and a design that is better suited to multi-replica operation, kkRepo is worth trying.
