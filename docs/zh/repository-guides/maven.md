# Maven 仓库使用指南

kkRepo 通过兼容 Nexus 的 `/repository/<repo>/...` 路径支持 Maven `hosted`、`proxy` 和
`group` 仓库。Hosted 用于发布内部 release 与 snapshot，proxy 用于缓存 Maven Central
等上游，group 作为统一的依赖解析地址。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 内部 release | `maven2-hosted` | Release version policy 和合适的 write policy |
| 内部 snapshot | `maven2-hosted` | Snapshot version policy 和合适的 write policy |
| Maven Central 缓存 | `maven2-proxy` | Remote URL `https://repo1.maven.org/maven2/` |
| 统一依赖读取 | `maven2-group` | Hosted 仓库排在 proxy 前面 |

仓库名可按部署需要选择。下文使用 `maven-releases`、`maven-snapshots`、
`maven-central` 和 `maven-public`。

## 配置依赖解析

在 `~/.m2/settings.xml` 中把 group 配置为 mirror：

```xml
<mirrors>
  <mirror>
    <id>kkrepo</id>
    <mirrorOf>*</mirrorOf>
    <url>https://nexus.example.com/repository/maven-public/</url>
  </mirror>
</mirrors>
```

Group 为私有仓库时，增加 ID 匹配的 `<server>`，不要把密码提交到项目中。Maven 通过
server ID 选择凭据。

## 发布 Release 与 Snapshot

在项目 `distributionManagement` 中声明 hosted 地址，并在用户 `settings.xml` 的 server
配置中使用相同 ID：

```xml
<distributionManagement>
  <repository>
    <id>maven-releases</id>
    <url>https://nexus.example.com/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>maven-snapshots</id>
    <url>https://nexus.example.com/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

使用 `mvn deploy` 发布。底层 CI 流程也可以向标准 Maven 路径直接 PUT，但必须一致地上传
POM、制品及所需 checksum。

## 仓库行为

- Hosted 写入会校验 Maven 路径，并在事务中更新 release 或 snapshot metadata。
- Proxy 会缓存上游制品、metadata、checksum、validator 和 negative lookup。
- Group 只读并按成员顺序解析；内部坐标需要优先时，把私有 hosted 放在公共 proxy 前面。
- Browse 与 Search 展示坐标和资产，生成 metadata 不作为唯一事实来源。

## 运维与安全

消费者只授予 read/browse 权限，发布身份才授予 add/edit 权限。Release 仓库应使用防止意外
覆盖的 write policy。Cleanup 和安全扫描面向已存储 component 及其 asset，清理 release
数据前应先执行 preview。

## 相关文档

- [Maven 客户端配置示例](../client-recipes.md#maven)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Apache Maven 仓库说明](https://maven.apache.org/guides/introduction/introduction-to-repositories.html)
- [Apache Maven 发布安全配置](https://maven.apache.org/guides/mini/guide-deployment-security-settings.html)
