# 数据库 Schema

kkrepo 维护一套同时由 MySQL 和 PostgreSQL 实现的逻辑关系 schema。大制品内容仍只存储在 OSS/S3；数据库保存元数据、索引、blob 引用、安全状态、session、审计数据、迁移进度和跨副本协同状态。

## 迁移目录

```text
persistence-mysql/src/main/resources/db/migration/mysql/
  V1 ... V29       不可修改的 MySQL 历史

persistence-postgresql/src/main/resources/db/migration/postgresql/
  V29              与逻辑 schema 等价的 PostgreSQL baseline
```

从 V30 开始，每次变更都要在两个目录增加版本和描述相同的迁移。可以使用数据库特有语法，但表/列语义、约束、索引、默认值和应用可见行为必须等价。CI 会拒绝版本漂移，并用真实数据库容器校验两套历史。

## 逻辑分组

| 分组 | 代表表 | 用途 |
| --- | --- | --- |
| 仓库目录 | `blob_store`、`repository`、`repository_member`、`routing_rule`、`cleanup_policy` | 仓库配置和有序 group 成员 |
| 内容 | `component`、`asset`、`asset_blob`、`browse_node`、`component_search` | 包元数据、blob 引用、浏览和 SQL 搜索 |
| 安全 | `security_user`、`security_role`、`security_privilege`、映射表、`api_key` | 身份与 Nexus 兼容授权 |
| 审计 | `security_audit_log` | 可搜索的安全与管理操作历史 |
| Session | `SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES`、`auth_ticket` | 跨副本登录和短生命周期认证状态 |
| 协同 | `cache_version`、重建 marker、`maintenance_cursor`、代理、Docker 与 Terraform lease/binding 状态 | 缓存失效、claim/retry 工作和共享运行时状态 |
| 迁移 | `migration_job`、checkpoint、校验、仓库/资产迁移表 | 可恢复的 Nexus 迁移与报告 |
| 协议侧表 | Docker/OCI、Pub、Terraform 等协议专有关系 | 无法只通过公共 asset attributes 表达的状态；Terraform 在 V30 增加 signing key、Provider revision/platform、group source binding 和 publish lease |

自然键和显式唯一约束保护仓库名、路径、package/version identity、blob hash、token identity 和 claim marker，确保并发副本下不会重复。后台 worker claim 持久化行，不依赖 JVM 本地队列。

## 类型映射

JDBC 持久层负责语义映射，不向协议代码泄露数据库类型：

| 语义 | MySQL | PostgreSQL |
| --- | --- | --- |
| 结构化 attributes | `JSON` | `jsonb` |
| 布尔值 | `BOOLEAN`/驱动数字映射 | `boolean` |
| 绝对时间 | 时区规范化 timestamp | `timestamptz` |
| 墙上时间（`LocalDateTime`） | `datetime` | `timestamp without time zone` |
| 生成 identity | auto increment | identity/sequence |
| upsert/claim 语法 | MySQL dialect | `ON CONFLICT`、`RETURNING`、PostgreSQL 锁语法 |

应用服务统一使用 `DatabaseDialect`；数据库特有 SQL 只能放在 `persistence-mysql` 或 `persistence-postgresql`。公共 DAO 不能依赖数据库特有 JDBC 对象推断语义。

## Schema 变更清单

1. 定义逻辑变更和多副本语义。
2. 为两个数据库增加相同 Flyway 版本和描述。
3. 原样保留 MySQL V1-V29，绝不能修改已经执行的迁移。
4. 更新公共持久层契约和迁移兼容测试。
5. 在真实 MySQL/PostgreSQL 上比较约束、索引、默认值、JSON、生成主键、claim 和时间行为。
6. 执行 Flyway parity、package boundary、双实例 server smoke 和干净 reactor 验证。
7. 数据归属发生变化时同步更新本文和 ER 参考。

详细实体关系仍见[MySQL ER 设计](mysql-er.md)；虽然文件名是历史命名，其中的逻辑关系适用于两种后端。数据库运维见[数据库后端](database-backends.md)。
