# Nexus 迁移实战手册

本文是从现有 Nexus 迁移到 kkrepo 的实战 checklist。它补充 [Nexus 迁移说明](nexus-migration-guide.md)，后者更详细说明产品内迁移流程。

## 迁移策略

推荐策略：

1. 并行准备 kkrepo 目标环境。
2. 执行元数据 preflight。
3. 迁移元数据。
4. 同步仓库 metadata。
5. 同步 package blob。
6. 在切换窗口内重复增量同步。
7. 切换 DNS、负载均衡或客户端流量。
8. 验证，并保留明确回滚窗口。

最好的迁移应该很平淡：preflight 提前暴露问题，增量同步缩短切换时间，域名切过去后客户端仍可继续使用相同 `/repository/<repo>/...` 路径。

## 阶段 0：范围和盘点

收集：

- 源 Nexus 版本。
- 仓库列表、format、recipe 和名称。
- Blob store 列表和大致大小。
- 必须迁移的 hosted 仓库。
- 需要作为历史缓存或备份迁移的 proxy 仓库。
- 用户/角色/权限模型。
- LDAP/OIDC/anonymous 设置。
- API key 和 token 使用情况。
- 关键 CI/CD 客户端及其配置。
- 当前域名和反向代理规则。
- 预期切换窗口和回滚窗口。

决策：

- 哪些仓库在迁移范围内。
- 哪些不支持格式明确不迁移。
- proxy 仓库是迁移历史缓存，还是让它们从上游重新回源。
- 哪些用户可能需要迁移后重置密码。
- 哪些验证客户端代表迁移成功。

## 阶段 1：准备目标 kkrepo

按接近生产的方式部署 kkrepo：

- 初始化安装前选定的外部 MySQL 或 PostgreSQL。
- OSS/S3 兼容 blob 存储。
- 稳定 `KKREPO_CREDENTIAL_SECRET`。
- 稳定 `KKREPO_API_KEY_PAYLOAD_SECRET`。
- HTTPS 反向代理。
- 管理端口监控。
- 所选数据库和 blob storage 备份。

迁移前验证：

```bash
curl -i http://<target-management-host>:8081/actuator/health
```

登录 `/admin/`，确认目标实例健康。

## 阶段 2：准备源 Nexus

在源 Nexus 上：

- 确认可用于迁移的管理员凭据。
- 尽可能启用 Script REST API 脚本创建能力。
- 确认源仓库 online。
- 如果维护任务会影响读取，在迁移窗口内暂停或避开。
- 回滚窗口关闭前保持源 Nexus 可用。

Script REST API 很重要，因为部分 Nexus 数据无法通过普通 REST API 获取。如果脚本能力关闭，迁移仍可报告无法补偿的部分，但本地密码 hash 补偿和仓库 asset 发现会受限。

## 阶段 3：元数据 Preflight

在 kkrepo `/admin/`：

1. 打开 `Nexus Metadata`。
2. 填写 source URL、username 和 password。
3. 执行 `Run preflight`。
4. 查看 blocking issue 和 warning。

处理：

- Source URL 或凭据错误。
- 源权限不足。
- Script API disabled 错误。
- 不支持的仓库格式。
- 无法映射的 blob store 或 repository 配置。
- 需要重置密码的用户。

在理解 preflight 输出前，不要进入切换。

## 阶段 4：元数据迁移

在 `Nexus Metadata` 页面执行 `Run migration`。

预期迁移类别：

- 用户。
- 角色。
- 权限。
- Blob store。
- Repository 定义。
- 能从源 Nexus 读取的安全兼容数据。

元数据迁移后：

- 在 Admin UI 检查 repository 定义。
- 检查用户和角色。
- 检查 anonymous access 设置。API 迁移会保留源端启用或关闭状态。
- 检查 LDAP/OIDC realm 设置，必要时更新 secret。
- 创建或验证目标 blob store。

部分 secret 可能需要重新录入，因为源 Nexus 不一定暴露明文凭据。

## 阶段 5：仓库数据 Metadata Sync

打开 `Nexus Repository Data`。

首次扫描：

- `Metadata since` 保持为空。
- Page size 和 concurrency 保守设置。
- 只有明确要迁移 proxy 缓存数据时，才指定 proxy 仓库。
- 执行 `Sync metadata`。

`Sync metadata` 只记录需要迁移的对象，不下载全部 blob。

检查：

- 发现的 asset 总数。
- 包含的仓库。
- 不支持或失败的条目。
- 估算迁移规模。

## 阶段 6：Package Blob Sync

执行 `Sync packages`。

建议：

- 保持 checksum validation 开启。
- 从适中并发开始，例如 `8`。
- 在确认源 Nexus、网络、数据库和对象存储压力后，再提高并发。
- 监控失败 asset，修复根因后 retry。

常见失败原因：

- 源 Nexus 读取超时。
- 源权限不足。
- 对象存储限流。
- 目标 blob store 配置错误。
- 迁移期间制品被删除或变更。

修复原因后使用 `Retry failed`。

## 阶段 7：增量同步

切换窗口内重复：

1. 将 `Metadata since` 设置为上一次同步开始前的时间。
2. 执行 `Sync metadata`。
3. 执行 `Sync packages`。
4. 检查失败项。

`Metadata since` 建议保留重叠窗口，避免时钟偏差或长事务写入导致漏同步。

如果源 Nexus 仍在接收写入，最终增量同步应尽量贴近 DNS/负载均衡切换。

## 阶段 8：切换

切换前：

- 如果可行，宣布短暂冻结窗口。
- 停止向源 Nexus 写入。
- 执行最终增量 `Sync metadata`。
- 执行最终 `Sync packages`。
- 确认关键仓库无失败 asset。
- 直接面向 kkrepo 验证代表性客户端。

切换方式：

- 将原 Nexus 域名指向 kkrepo。
- 切换负载均衡 upstream。
- 如果无法保留域名，则更新客户端配置。

切换后：

- 观察仓库请求指标。
- 观察应用日志。
- 运行代表性 CI pipeline。
- 对关键格式执行测试 pull 和 push。
- 回滚窗口内将源 Nexus 保持只读或 standby。

## 验证 Checklist

验证：

- Maven dependency resolve 和 deploy。
- npm install 和 publish。
- PyPI pip install 和 twine upload。
- Helm repo update 和 chart upload。
- Dart / Pub `dart pub get`、`flutter pub get` 和 `dart pub publish`。
- Composer 2 从 group 执行 `composer install --prefer-dist`，验证 hosted 包、proxy 传递依赖、autoload 和 lock replay。
- NuGet restore 和 package push。
- RubyGems install 和 gem push。
- Yum install 和 RPM upload。
- Raw upload/download。
- datastore H2/PostgreSQL Cargo 迁移后的 sparse registry config、index entry、`.crate` 下载和 checksum。
- Nexus 3.92.0 Pub 迁移后的 package metadata、`version.json`、archive 下载、`archive_sha256` 和 publish token 行为。
- 显式选择 Nexus 原生 Composer proxy 后，验证 `packages.json`、p2 metadata、语义化 dist path、checksum，以及断开 Packagist 后的缓存下载；未选择时确认只迁移配置。
- 管理员登录。
- Browse/search。
- 用户角色和仓库权限。
- CI token/API key 行为。
- 审计日志可见。

命令示例见 [客户端配置示例](client-recipes.md)。

## 回滚计划

切换前定义回滚：

- DNS/负载均衡回滚目标。
- 回滚窗口内源 Nexus 写入策略。
- 切换后写入 kkrepo 的内容是否需要回放到 Nexus。
- 源 Nexus 保留多久。
- 谁批准回滚。

当切换窗口短，并且源 Nexus 保持不变或只读时，回滚最简单。

如果用户切换后已经向 kkrepo 发布新包，回滚会变成数据对账问题。开放写入前应决定这是否可接受。

## 迁移后清理

回滚窗口结束后：

- 备份 kkrepo 数据库和 blob storage。
- 确认源 Nexus 不再接收流量。
- 归档源 Nexus 配置和迁移报告。
- 业务确认后再下线源 Nexus。
- 审查用户、角色和 token。
- 记录已知兼容差异或后续 issue。

目标数据库引擎是安装决策，不是迁移开关。不要只修改 JDBC URL 就把已初始化目标从 MySQL 切换到 PostgreSQL（或反过来）；应创建并验证新的目标库。详见[数据库后端](database-backends.md)。

## 什么时候停止并调查

出现以下情况时，停止迁移并调查：

- Preflight 报告关键仓库不支持。
- 源凭据无法读取必要 metadata。
- Blob-store health check 失败。
- 同一仓库反复 checksum validation 失败。
- 安全角色或 anonymous 设置不符合预期。
- 关键 CI 客户端在 staging 无法认证或解析包。

不要带着无法解释的权限或 checksum 失败强行切换。
