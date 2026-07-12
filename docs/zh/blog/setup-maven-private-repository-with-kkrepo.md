# 使用 kkRepo 搭建 Maven 私服

## 前言

在团队开发中，Maven 私服几乎是 Java 工程的基础设施标配。它可以缓存中央仓库依赖、托管公司内部组件、统一 CI/CD 发布入口，也能减少公网网络抖动对构建的影响。

kkRepo 是一个兼容 Nexus 客户端访问习惯的自托管制品仓库，支持 Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Docker/OCI 等多种制品格式。对于 Maven 场景，它保留了常见的 `/repository/<repo>/...` URL 结构，因此从 Nexus 迁移或替换时，客户端配置成本比较低。

- https://github.com/klboke/kkrepo
- https://gitee.com/kailing/kkRepo

## 一、快速启动 kkRepo

本地体验可以直接使用官方 quickstart 脚本，它会拉起 kkRepo 和 MySQL：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | bash
```

启动完成后访问：

```text
管理控制台：http://127.0.0.1:19090/admin/
用户侧浏览器：http://127.0.0.1:19090/browse/
健康检查：http://127.0.0.1:19091/actuator/health
```

首次进入管理控制台时，需要创建初始 `Local/admin` 管理员密码。

本地 quickstart 默认使用 File blob storage，适合试用和开发验证。生产环境建议使用独立 MySQL，并将 blob 存储切换为 OSS/S3。

## 二、创建 Maven 仓库

进入 `/admin/` 后，建议按 Maven 常见用法创建三类仓库：

```text
maven-releases    hosted，用于发布正式版本
maven-snapshots   hosted，用于发布 SNAPSHOT 版本
maven-central     proxy，用于代理 Maven Central
maven-public      group，用于依赖拉取入口
```

创建 `maven-central` proxy 仓库时，上游地址可以填写 Maven Central 的官方仓库地址：

```text
https://repo.maven.apache.org/maven2/
```

如果公司已有内网 Maven 镜像，也可以把 proxy 的上游地址替换成内部镜像地址。

创建 `maven-public` group 仓库时，把下面几个成员仓库加入 group：

```text
maven-releases
maven-snapshots
maven-central
```

这样客户端只需要从 `maven-public` 一个地址拉依赖：公司内部发布到私服的包会从 `maven-releases` 或 `maven-snapshots` 命中，第三方开源依赖会通过 `maven-central` proxy 回源并缓存。发布时仍然分别写入 `maven-releases` 或 `maven-snapshots`。

## 三、配置 Maven 拉取依赖

编辑本机或 CI 环境中的 `~/.m2/settings.xml`：

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

生产环境中建议使用 HTTPS，例如：

```text
https://repo.example.com/repository/maven-public/
```

配置完成后，普通 Maven 构建会自动走 kkRepo：

```bash
mvn clean package
```

## 四、配置 Maven 发布

如果项目需要发布内部 jar，在项目 `pom.xml` 中添加：

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

然后在 `~/.m2/settings.xml` 中配置发布账号：

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

注意：`server.id` 必须和 `distributionManagement` 里的 `id` 保持一致。

设置环境变量后发布：

```bash
export KKREPO_PASSWORD='your-password-or-token'
mvn deploy
```

## 五、手工上传制品

除了 `mvn deploy`，kkRepo 也支持 Maven hosted 仓库的 PUT 上传。例如：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file app-1.0.0.jar \
  http://127.0.0.1:19090/repository/maven-releases/com/acme/app/1.0.0/app-1.0.0.jar
```

路径需要符合 Maven 仓库布局：

```text
groupId/artifactId/version/artifactId-version.jar
```

例如：

```text
com/acme/app/1.0.0/app-1.0.0.jar
```

## 六、生产环境建议

本地 quickstart 可以帮助我们快速跑通流程，但生产环境还需要注意这些点：

1. 使用独立 MySQL 8.0 存储元数据、用户、权限、token 和运行状态。
2. 使用 OSS/S3 作为 blob store，不建议把大文件长期放在单机本地磁盘。
3. 开启 HTTPS，避免 Maven 密码或 token 明文传输。
4. 使用用户 token 或 CI token，避免把管理员密码写入流水线。
5. 将 `KKREPO_CREDENTIAL_SECRET` 和 `KKREPO_API_KEY_PAYLOAD_SECRET` 换成稳定的强随机字符串。
6. 对外提供统一域名，例如 `https://repo.example.com`，方便后续迁移和扩容。

kkRepo 的一个重要设计点是面向多副本部署：session、权限、token、迁移状态等共享状态存储在 MySQL 中，进程内缓存只作为可重建的本地热缓存。这对生产环境的滚动升级和横向扩容比较友好。

## 七、总结

使用 kkRepo 搭建 Maven 私服的核心流程并不复杂：

```text
启动 kkRepo
创建 blob store
创建 maven-releases / maven-snapshots / maven-central / maven-public
把 hosted 和 proxy 仓库加入 maven-public group
配置 Maven settings.xml
配置项目 distributionManagement
执行 mvn deploy
```

如果团队已经熟悉 Nexus，kkRepo 的 `/repository/<repo>/...` URL 结构也能降低迁移成本。对于希望使用 MySQL、OSS/S3，并且需要更适合多副本部署的制品仓库场景，kkRepo 是一个值得尝试的选择。
