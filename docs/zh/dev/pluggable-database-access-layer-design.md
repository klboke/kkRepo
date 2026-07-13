# MySQL / PostgreSQL 可插拔数据库访问层设计

本文记录 [Issue #107](https://github.com/klboke/kkRepo/issues/107) 的建议实施方案。目标是在不改变仓库协议、权限、session、审计、迁移和多副本协调语义的前提下，让 kkrepo 可以选择 MySQL 或 PostgreSQL 作为共享关系数据库。

这不是一次 JDBC driver 替换。当前 MySQL 同时承载 repository、component、asset、权限、token、审计、Spring Session、cache version、后台任务 marker、upload session 和迁移 checkpoint。PostgreSQL 支持必须覆盖这些正确性边界，不能只做到应用能够连接数据库并启动。

## 当前状态

截至 2026-07-13，项目数据库层具有以下特征：

- `persistence-mysql` 包含约 6,000 行 JDBC DAO、record model 和 JSON/enum/hash 辅助逻辑。
- `server` 与 `migration-nexus` 中约 100 个生产代码文件直接引用 `com.github.klboke.kkrepo.persistence.mysql`。
- MySQL schema 由 `V1` 到 `V29` 共 29 个 Flyway migration 构成。
- 应用默认配置写死 MySQL JDBC URL、MySQL driver 和 `flyway-mysql`。
- catalog cache 广播实现和配置使用 `MysqlVersionWatermark`、`MysqlCatalogCacheBroadcaster`、`broadcast-backend=mysql` 等 MySQL 专用名称。
- 当前数据库集成测试使用 MySQL 8 Testcontainers，没有 PostgreSQL DAO contract matrix。
- `docker-compose.compat-postgres.yml` 中的 PostgreSQL 只服务 Nexus 参考实例，kkrepo 自身仍然连接 MySQL。
- 当前仓库没有 kkrepo 应用部署用 Helm chart；`protocol-helm` 是 Helm 制品协议模块，不是 Kubernetes 部署 chart。

现有 MySQL 专用 SQL 主要包括：

- `ON DUPLICATE KEY UPDATE` 和 `INSERT IGNORE`。
- `LAST_INSERT_ID()` 与生成主键返回。
- `JSON_EXTRACT`、`JSON_UNQUOTE`、`JSON_SET`。
- `MATCH ... AGAINST (... IN BOOLEAN MODE)`。
- `TIMESTAMPDIFF`、`NOW(3)`。
- MySQL generated column 和 prefix index。
- `TINYINT(1)`、`BIGINT UNSIGNED`、`BINARY(32)`、`DATETIME(3)`、`ENGINE=InnoDB`。

`FOR UPDATE`、`FOR UPDATE SKIP LOCKED`、普通 `LIMIT`、外键和大部分 CRUD SQL 可以继续使用标准或两边均支持的写法，不需要为了抽象而全部重写。

## 目标

本设计需要满足：

1. MySQL 仍然是默认且完整支持的数据库后端。
2. PostgreSQL 成为一等数据库后端，不是仅用于开发或测试的实验性 driver。
3. repository、security、session、audit、migration、cache version 和后台任务在两个数据库上保持同样的产品语义。
4. 数据库差异不能泄漏到 protocol、controller 和业务 service。
5. MySQL 已有安装升级时不出现 Flyway checksum 变化、配置失效或数据语义变化。
6. PostgreSQL 保持与 MySQL 相同的多副本正确性保证。
7. 单一发布镜像同时包含两个后端所需 driver，运行时通过显式配置选择。

## 非目标

第一阶段明确不包含：

- 不提供现有 MySQL 数据库在线切换为 PostgreSQL 的能力。
- 不实现 MySQL 到 PostgreSQL 的通用数据复制、双写或 CDC。
- 不为了支持两个关系数据库而切换到 JPA、MyBatis、jOOQ 或其它持久化框架。
- 不复制一套完整 `MySqlAssetDao` 和一套完整 `PostgreSqlAssetDao`。
- 不把 SQLite、H2、MariaDB 或其它数据库顺带声明为受支持后端。
- 不在同一个 kkrepo 集群内混用不同数据库类型的副本。

如果后续需要 MySQL 到 PostgreSQL 的切换，应单独设计离线 export/import 工具，包含 dry-run、行数和 checksum 校验、停写窗口、失败回滚和 blob 引用验证。

## 核心方案

推荐采用：

```text
persistence-jdbc 公共接口和唯一 JDBC 实现
  + 语义化 dialect SPI
  + MySQL/PostgreSQL 后端模块
```

建议模块结构：

```text
persistence-jdbc
  src/main/java/.../persistence/jdbc/api/
    model/
    RepositoryStore.java
    AssetStore.java
    ComponentStore.java
    SecurityStore.java
    CoordinationStore.java
    MigrationStore.java
  src/main/java/.../persistence/jdbc/internal/
    JdbcRepositoryStore.java
    JdbcAssetStore.java
    JdbcComponentStore.java
    JdbcSecurityStore.java
    JdbcCoordinationStore.java
    JdbcMigrationStore.java
    support/
  src/main/java/.../persistence/jdbc/spi/
    DatabaseDialect.java
    JsonPersistenceDialect.java
    SearchPersistenceDialect.java
    GeneratedKeyDialect.java

persistence-mysql
  src/main/java/.../persistence/mysql/MySqlDatabaseDialect.java
  src/main/resources/db/migration/mysql/

persistence-postgresql
  src/main/java/.../persistence/postgresql/PostgreSqlDatabaseDialect.java
  src/main/resources/db/migration/postgresql/
```

职责划分：

| 模块 | 职责 |
| --- | --- |
| `persistence-jdbc.api` | 对上暴露 Store/DAO 接口、查询参数和公共 model；不能包含 JDBC 或数据库厂商类型 |
| `persistence-jdbc.internal` | 唯一公共 JDBC 实现、RowMapper、标准 SQL、hash、enum 和 JSON 序列化 |
| `persistence-jdbc.spi` | 对下暴露 dialect、JSON binder、generated key、搜索和协调状态等数据库扩展点 |
| `persistence-mysql` | 实现 JDBC SPI 中的 MySQL 差异，提供 MySQL Flyway migration、driver 支持和 contract tests |
| `persistence-postgresql` | 实现 JDBC SPI 中的 PostgreSQL 差异，提供 PostgreSQL Flyway baseline、driver 支持和 contract tests |
| `server` | 业务代码只依赖 `persistence-jdbc.api`；启动装配层选择 database type、校验配置并加载 backend |
| `migration-nexus` | 只依赖 `persistence-jdbc.api` 的接口和 model，不依赖 JDBC internal 或数据库 backend |

`server` 同时依赖两个后端模块并打包两个 JDBC driver。只有与 `kkrepo.database.type` 匹配的 dialect bean 生效。

上层通过 `AssetStore`、`ComponentStore`、`RepositoryStore` 等 `persistence-jdbc.api` 接口访问持久化能力。公共 JDBC 实现使用 `JdbcAssetStore`、`JdbcComponentStore`、`JdbcRepositoryStore` 等内部类，并通过 SPI 调用当前数据库 backend。

这里的接口隔离不代表为每种数据库复制 DAO 实现。MySQL 和 PostgreSQL 共用同一套 `Jdbc*Store`，只有数据库差异进入 backend module。原来挂在具体 DAO 上的查询结果类型，例如 `AssetDao.HelmIndexRow`、`ComponentDao.ComponentSearchRow` 和 `RepositoryDataMigrationDao.AssetClaim`，应移动到 `persistence-jdbc.api.model` 或对应接口的公共 model 中，避免具体实现类型泄漏到上层。

依赖方向必须固定为：

```text
server business --------------> persistence-jdbc.api
migration-nexus --------------> persistence-jdbc.api

persistence-jdbc.internal ----> persistence-jdbc.api
persistence-jdbc.internal ----> persistence-jdbc.spi

persistence-mysql ------------> persistence-jdbc.spi
persistence-postgresql -------> persistence-jdbc.spi

server bootstrap -------------> persistence-jdbc.internal
server bootstrap -------------> persistence-mysql
server bootstrap -------------> persistence-postgresql
```

`persistence-jdbc` 不能依赖 `persistence-mysql`、`persistence-postgresql` 或任何未来 backend。运行时路由必须通过 SPI bean 动态分派，而不是公共 JDBC 实现直接调用某个 backend 类。

由于 `api`、`internal` 和 `spi` 位于同一个 Maven module，编译器不会自动阻止跨包引用。CI 必须增加包依赖检查，保证 protocol、controller、业务 service 和 `migration-nexus` 只能 import `persistence.jdbc.api.*`。

## 后续数据库扩展约束

本设计不应只适用于 MySQL 和 PostgreSQL。后续新增其它关系数据库时，必须继续遵守以下三条硬约束：

1. 业务代码中禁止出现 `if mysql / else postgres` 或针对具体数据库类型的分支。数据库差异只能出现在 backend module、dialect 或专用 persistence operation 中。
2. Dialect 必须按数据库能力和业务语义设计，不能退化成两组 MySQL/PostgreSQL SQL 字符串，也不能把具体数据库名称固化进公共接口。
3. 公共 CI contract 是新数据库成为 first-class backend 的准入标准。只完成连接、启动或部分 CRUD 不代表该数据库已经受支持。

新增第三种数据库时，标准接入范围应限制在：

- 新增独立 backend module，例如 `persistence-sqlserver`。
- 实现 `persistence-jdbc.spi` 中已有的 `DatabaseDialect` 和各语义化 persistence dialect。
- 提供独立 Flyway migration 或等价 schema 管理实现。
- 通过 `persistence-jdbc.api` 运行公共 Store/DAO、JSON、搜索、并发锁、session、多副本协调和 server smoke contract。
- 增加 datasource 配置、发布镜像依赖、Compose/Helm 示例和运维文档。

新增后端不应要求修改 protocol、controller、业务 service、公共 API 或公共 JDBC 实现的业务流程。如果接入新数据库时必须在这些层增加数据库类型判断，说明现有 SPI 边界不完整，应先补充通用语义接口，而不是继续扩散条件分支。

上层允许依赖：

```text
persistence.jdbc.api.*
```

上层禁止依赖：

```text
persistence.jdbc.internal.*
persistence.jdbc.spi.*
persistence.mysql.*
persistence.postgresql.*
org.springframework.jdbc.*
org.postgresql.*
com.mysql.*
```

## Dialect 边界

Dialect 位于 `persistence-jdbc.spi`，由数据库 backend 实现，由 `persistence-jdbc.internal` 使用。它不属于业务层公共 API。

Dialect 不应演变成任意字符串拼接工具，也不应提供类似 `upsert(table, columns)` 的通用 SQL builder。数据库差异应按业务语义暴露。

建议拆成少量职责明确的契约：

```java
public interface DatabaseDialect {
  DatabaseType type();

  ComponentPersistenceDialect components();

  CoordinationPersistenceDialect coordination();

  JsonPersistenceDialect json();

  SearchPersistenceDialect search();

  GeneratedKeyDialect generatedKeys();
}
```

语义化操作包括：

- component 根据唯一坐标原子 upsert 并返回 ID。
- cache version 原子创建或递增并返回新版本。
- marker queue 幂等 enqueue。
- migration checkpoint 幂等写入。
- security seed 和角色关系 insert-if-absent。
- JSON scalar 查询和 JSON boolean 更新。
- component 全文检索。
- 数据库生成主键返回。
- backlog 最老任务年龄计算。

普通查询、显式 `INSERT`、`UPDATE`、`DELETE`、`SELECT` 和两边兼容的锁语句继续保留在 `persistence-jdbc.internal` 的公共 JDBC 实现中。

禁止在公共 JDBC 实现中散落：

```java
if (databaseType == MYSQL) {
  // SQL A
} else {
  // SQL B
}
```

差异必须由 dialect 或专用 persistence operation 封装。业务 service 只能调用 `persistence-jdbc.api`，既不能知道数据库类型，也不能直接调用 dialect。

## SQL 兼容策略

### Component upsert

MySQL 当前依赖：

```sql
INSERT INTO component (...)
VALUES (...)
ON DUPLICATE KEY UPDATE
  id = LAST_INSERT_ID(id),
  last_updated_at = VALUES(last_updated_at);

SELECT LAST_INSERT_ID();
```

PostgreSQL 使用：

```sql
INSERT INTO component (...)
VALUES (...)
ON CONFLICT (repository_id, coordinate_hash)
DO UPDATE SET
  last_updated_at = EXCLUDED.last_updated_at
RETURNING id;
```

冲突目标必须显式使用业务唯一约束，不能依赖“任意唯一键冲突”。

### Cache version

MySQL 保持当前 `LAST_INSERT_ID(version + 1)` 实现。

PostgreSQL 使用单条语句返回新版本：

```sql
INSERT INTO cache_version (name, version, updated_at)
VALUES (?, 1, CURRENT_TIMESTAMP(3))
ON CONFLICT (name)
DO UPDATE SET
  version = cache_version.version + 1,
  updated_at = EXCLUDED.updated_at
RETURNING version;
```

必须增加并发 contract test，证明多个副本同时 bump 时版本严格递增且不丢更新。

### Insert-if-absent

| 语义 | MySQL | PostgreSQL |
| --- | --- | --- |
| 忽略重复 | `INSERT IGNORE` | `ON CONFLICT DO NOTHING` |
| 覆盖指定字段 | `ON DUPLICATE KEY UPDATE` | `ON CONFLICT (...) DO UPDATE` |
| 保留已有值 | 自赋值或 no-op update | `DO NOTHING` 或显式 no-op |

每个 PostgreSQL `ON CONFLICT` 应指定真实业务约束，避免新增唯一索引后悄悄改变冲突行为。

### JSON

公共 `JsonColumns` 继续负责 Java object 与 JSON 字符串之间的序列化。

数据库存储和绑定由后端处理：

- MySQL 使用 `JSON`。
- PostgreSQL 使用 `JSONB`。
- MySQL JDBC 可以直接绑定字符串。
- PostgreSQL 使用 `PGobject` 或 dialect 提供的 JSON binder，不能要求运维人员通过 JDBC URL 的 `stringtype=unspecified` 才能工作。
- PostgreSQL 读取 JSONB 时可以继续通过 `ResultSet.getString()` 交给公共 `JsonColumns`。

表达式映射：

| 语义 | MySQL | PostgreSQL |
| --- | --- | --- |
| 读取文本 | `JSON_UNQUOTE(JSON_EXTRACT(col, '$.a.b'))` | `col #>> '{a,b}'` |
| 更新 boolean | `JSON_SET(col, '$.enabled', true)` | `jsonb_set(col, '{enabled}', 'true'::jsonb, true)` |
| 判断 path 存在 | `JSON_EXTRACT(...) IS NOT NULL` | `col #> '{a,b}' IS NOT NULL` |

所有 JSON 查询都必须在 MySQL 和 PostgreSQL contract tests 中使用相同 fixture 验证。

### 全文搜索

MySQL 继续使用 `component_search` 的 FULLTEXT index 和 boolean mode。

PostgreSQL 建议增加生成的 `tsvector` 列或维护列：

```sql
search_vector tsvector GENERATED ALWAYS AS (
  to_tsvector(
    'simple',
    concat_ws(' ', namespace, name, version, keywords)
  )
) STORED
```

并创建 GIN index：

```sql
CREATE INDEX idx_component_search_vector
  ON component_search USING GIN (search_vector);
```

公共代码负责把用户关键词规范化为 token；MySQL dialect 转换为 `+token*`，PostgreSQL dialect 转换为 `token:*` 并使用 `to_tsquery('simple', ?)`.

搜索 contract test 必须验证：

- 多 token AND 语义。
- prefix 匹配。
- namespace、name、version、keywords 命中。
- format 和 repository filter。
- 相同 fixture 返回相同集合和稳定顺序。

不能只比较搜索结果数量。

### 后台任务 claim

MySQL 8 和 PostgreSQL 都支持：

```sql
SELECT ...
FROM ...
ORDER BY ...
LIMIT ?
FOR UPDATE SKIP LOCKED
```

现有 metadata rebuild、repository index rebuild、blob GC、Docker upload cleanup 和 repository migration claim 可以保留这一总体模式。

必须验证：

- 两个并发 worker 不会领取同一行。
- lease 或 claim 更新与选中行处于同一事务。
- worker 崩溃后的 retry/reenqueue 行为一致。
- `READ_COMMITTED` 下两边都满足当前并发语义。

### Backlog age

MySQL：

```sql
TIMESTAMPDIFF(SECOND, MIN(requested_at), NOW(3))
```

PostgreSQL：

```sql
EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(requested_at)))
```

这类表达式由 coordination dialect 提供，指标层只读取秒数。

### Docker connector port 唯一性

MySQL 保留 V26 的 stored generated column。

PostgreSQL 可以使用 partial unique expression index：

```sql
CREATE UNIQUE INDEX uk_repository_docker_connector_port
ON repository (
  ((attributes_json #>> '{docker,connectorPort}')::integer)
)
WHERE format = 'docker'
  AND COALESCE(
    (attributes_json #>> '{docker,connectorEnabled}')::boolean,
    true
  );
```

仓库配置 service 仍需校验 connector port 类型和范围，不能把非法 JSON 值留给数据库 cast 报错。

## 数据类型与语义

建议基础映射：

| MySQL | PostgreSQL |
| --- | --- |
| `BIGINT AUTO_INCREMENT` | `BIGINT GENERATED BY DEFAULT AS IDENTITY` |
| `BIGINT UNSIGNED` | `BIGINT`，业务需要时增加非负约束 |
| `TINYINT(1)` | `BOOLEAN` |
| `BINARY(32)` | `BYTEA` |
| `BLOB` | `BYTEA` |
| `JSON` | `JSONB` |
| `DATETIME(3)` | 根据时间语义选择 `TIMESTAMP(3)` 或 `TIMESTAMPTZ(3)` |
| `VARCHAR` prefix index | PostgreSQL 普通 index、表达式 index 或 hash 列 |

### 时间

不能把所有 `DATETIME(3)` 机械替换成同一种 PostgreSQL timestamp。

实现前应对列做分类：

- expiry、lease、claim、created/updated、audit event 等绝对时间优先映射为 Java `Instant`。
- PostgreSQL 对绝对时间优先使用 `TIMESTAMPTZ(3)`。
- 仍以 `LocalDateTime` 表示的字段必须说明时区语义。
- MySQL 已有 `DATETIME(3)` 数据不能在本 issue 中静默重写时区。
- 两个数据库必须增加 UTC、Asia/Shanghai 和 DST 边界的时间 round-trip tests。

如需统一 MySQL 历史数据为 UTC，应作为单独迁移设计，不与 PostgreSQL 首次支持混在同一改动中。

### 大小写、排序和唯一性

这是最容易被忽略的兼容风险。

当前 MySQL 多数表使用 `utf8mb4_unicode_ci`，PostgreSQL 默认 collation、大小写比较和尾部空格行为不完全相同。不能通过全局启用 `citext` 或全部 `LOWER()` 草率处理，因为 repository name、user id、package name、version 和 path 的业务语义不同。

在 PostgreSQL schema 固化前，应为以下字段建立语义矩阵：

- repository、blob store、routing rule 和 cleanup policy 名称。
- security source、user id、role id 和 privilege id。
- component namespace、name 和 version。
- asset path、Docker image/tag、Pub/Cargo/Composer package name。
- API key domain 和 owner identity。

对业务要求大小写不敏感的 identity，使用显式 normalized column、hash column 或 `LOWER(...)` 唯一索引，并让查询使用同一规范化规则。对协议要求大小写敏感的字段保留精确比较。

所有分页和生成 metadata 的查询必须有显式排序和稳定 tie-breaker，不能依赖数据库默认顺序。

## Flyway 迁移策略

### MySQL

现有 MySQL `V1` 到 `V29` 必须保持文件内容和 checksum 不变。

可以通过 `git mv` 移动到：

```text
db/migration/mysql/
```

但不能为了“统一风格”修改旧 migration。MySQL 已有安装的 `flyway_schema_history` 必须继续通过 validate。

建议增加一份只读 legacy migration test fixture：

```text
src/test/resources/legacy-mysql-v29/
```

测试流程：

1. 使用 legacy fixture 把 MySQL 升到 V29。
2. 使用新版本 Flyway locations 执行 `validate` 和 `migrate`。
3. 断言没有 checksum mismatch，且 schema version 保持正确。

### PostgreSQL

PostgreSQL 从新支持版本开始，不需要重演历史上只发生在 MySQL 的 29 次演进。

建议使用：

```text
db/migration/postgresql/V29__postgresql_baseline.sql
```

该脚本一次性创建截至 V29 的等价最终 schema、索引、约束和 seed data。这样 PostgreSQL 和 MySQL 在首个共同版本之后都从 V30 继续演进。

V30 以后要求：

```text
db/migration/mysql/V30__example.sql
db/migration/postgresql/V30__example.sql
```

两边 migration 名称可以描述各自实现，但版本号和产品语义必须一致。

CI 增加 migration parity 检查：

- MySQL 最大 migration version 等于 PostgreSQL 最大 migration version。
- 从 V30 开始，每个逻辑版本在两个目录都存在。
- 两边 fresh migration 后的逻辑 schema contract 一致。
- 重复启动不会出现 pending migration。

### Flyway 配置

建议配置：

```properties
kkrepo.database.type=${KKREPO_DATABASE_TYPE:mysql}
spring.flyway.locations=classpath:db/migration/${kkrepo.database.type}
```

合法值仅允许：

- `mysql`
- `postgresql`

删除硬编码的：

```properties
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
```

让 Spring Boot 根据 JDBC URL 选择 driver，并在应用启动后通过 `DatabaseMetaData.getDatabaseProductName()` 校验 URL 实际数据库和 `kkrepo.database.type` 一致。

MySQL 继续保留当前 baseline 兼容策略。PostgreSQL 第一阶段只支持空 schema 或已有合法 Flyway history，不能对任意非空 schema 自动 baseline 后继续建表。

Spring Session migration 也放入数据库专用目录：

- MySQL 保留当前 `BLOB` schema。
- PostgreSQL 使用 `BYTEA` schema。
- `spring.session.jdbc.initialize-schema=never` 保持不变，schema 继续由 Flyway 管理。

## 配置兼容

默认配置必须继续启动 MySQL，已有部署不需要增加新环境变量。

新增：

```properties
kkrepo.database.type=${KKREPO_DATABASE_TYPE:mysql}
```

PostgreSQL 示例：

```bash
export KKREPO_DATABASE_TYPE=postgresql
export SPRING_DATASOURCE_URL='jdbc:postgresql://postgresql:5432/kkrepo'
export SPRING_DATASOURCE_USERNAME='kkrepo'
export SPRING_DATASOURCE_PASSWORD='change-me'
```

catalog cache 配置改为数据库中性命名：

```properties
kkrepo.catalog-cache.broadcast-backend=${KKREPO_CATALOG_CACHE_BROADCAST_BACKEND:jdbc}
kkrepo.catalog-cache.jdbc.poll-delay-ms=${KKREPO_CATALOG_CACHE_JDBC_POLL_DELAY_MS:500}
kkrepo.catalog-cache.jdbc.initial-delay-ms=${KKREPO_CATALOG_CACHE_JDBC_INITIAL_DELAY_MS:500}
kkrepo.catalog-cache.jdbc.initial-jitter-ms=${KKREPO_CATALOG_CACHE_JDBC_INITIAL_JITTER_MS:100}
```

兼容规则：

- `broadcast-backend=mysql` 继续作为 `jdbc` 的 deprecated alias。
- 旧 `KKREPO_CATALOG_CACHE_MYSQL_*` 在新变量未配置时继续生效。
- 日志和指标逐步从 `mysql` 改成 `database` 或 `jdbc`，但已有 Prometheus metric name 如被外部使用，应通过兼容窗口迁移。
- 代码注释中的 “MySQL truth” 改为 “shared database truth”。

## 多副本正确性

PostgreSQL 支持完成后，以下状态仍必须以共享数据库为真相：

- Spring Session。
- authentication ticket。
- API key/token 和 last-used 状态。
- repository、security 和 blob-store catalog version。
- Maven metadata rebuild marker。
- Helm/PyPI/Yum/RubyGems repository index rebuild marker。
- blob GC claim 和 maintenance cursor。
- Docker upload session、chunk 和 cleanup lease。
- Pub upload session。
- repository migration job、asset claim 和 checkpoint。

进程内 cache 仍然只能是可丢失、可重建、有 TTL 或 version 失效条件的热缓存。

禁止出现：

- PostgreSQL 模式下退回单 JVM 内存队列。
- PostgreSQL 模式下关闭 `SKIP LOCKED` 后让多个副本串行抢全局锁。
- 依赖某个副本本地 scheduler ownership。
- session 或上传状态只保存在本机内存。

## 测试设计

### 公共 Persistence API contract

把当前 `MySqlIntegrationTestSupport` 重构为数据库中性的测试框架。Contract test 只能通过 `persistence-jdbc.api` 调用 Store/DAO 接口，不能直接实例化或调用 `Jdbc*Store` 内部实现：

```text
PersistenceStoreContract
  MySqlPersistenceContractTest
  PostgreSqlPersistenceContractTest
```

两个后端运行相同测试方法和 fixture，只允许数据库初始化、truncate 和 dialect 装配不同。

至少覆盖：

- generated key。
- component upsert 返回相同 ID。
- asset/blob 唯一约束和并发重复写。
- repository member 顺序更新。
- security user/role/privilege upsert 和关系去重。
- JSON query/update。
- component search。
- cache version 并发 bump。
- audit filter 和分页。
- Spring Session schema。
- metadata/index marker enqueue、claim、reenqueue。
- blob GC claim。
- Docker upload session row lock。
- repository migration asset claim。
- Pub upload session lock/finalize。

### 包依赖 contract

增加架构测试或等价的静态 import 检查：

- `server` 的 protocol、controller 和业务 service 只能依赖 `persistence.jdbc.api`。
- `migration-nexus` 只能依赖 `persistence.jdbc.api`。
- `persistence.jdbc.internal` 可以依赖 `api` 和 `spi`。
- 数据库 backend 只能依赖 `persistence.jdbc.spi` 和必要的 `api` model。
- 只有 server bootstrap/configuration package 可以同时看到 JDBC internal 和数据库 backend。
- 发现业务代码 import JDBC、MySQL、PostgreSQL、internal 或 SPI 类型时直接让 CI 失败。

### 并发 contract

单元测试中模拟 `DuplicateKeyException` 不足以证明数据库行为。

必须对 MySQL 和 PostgreSQL 各自运行真实并发测试：

1. 多线程同时创建同一 component，最终只有一行且所有调用得到同一 ID。
2. 多线程同时写入同一 asset path，唯一约束和 retry 行为一致。
3. 多 worker 同时 claim marker，每一行只被一个 worker 领取。
4. 多副本同时 bump cache version，结果单调递增。
5. Docker chunk append 在 row lock 下保持 offset 连续。
6. migration asset claim 不重复分配。

### Server smoke matrix

CI 对两个后端分别验证：

- Spring Boot context 启动。
- Flyway fresh migrate。
- 第二次启动无 pending migration。
- 初始化管理员。
- 登录后 session 可以由第二个实例读取。
- 创建 hosted/proxy/group repository。
- 写入和读取一个小制品。
- security、audit、cache version 和后台任务基本流程。
- actuator health 返回 `UP`。

### CI 建议

PR 必跑：

```text
unit-and-protocol-tests
persistence-package-boundary
persistence-contract (mysql)
persistence-contract (postgresql)
server-smoke (mysql)
server-smoke (postgresql)
mysql-v29-upgrade-compatibility
flyway-version-parity
```

完整 server 测试继续使用：

```bash
mvn -pl server -am -Dsurefire.failIfNoSpecifiedTests=false verify
```

PostgreSQL 第一版建议以仓库已经使用的 `postgres:16` 作为最低 CI 基线，再增加一个较新 PostgreSQL 版本的定期 workflow，发现 driver、Flyway 和 SQL 兼容回归。

Codecov 需要同时收集公共 API contract、公共 JDBC 实现和两个 dialect 的覆盖率，不能只跑 mock-based Store/DAO tests。

## Docker Compose

默认 quickstart 必须继续保持 MySQL，避免改变现有用户命令和数据卷。

建议新增：

```text
docker-compose.quickstart-postgresql.yml
```

而不是在一个 Compose 文件中用复杂 profile 条件切换 `depends_on`。

PostgreSQL quickstart 包含：

- `postgres:16`。
- 独立 `postgresql-data` volume。
- `KKREPO_DATABASE_TYPE=postgresql`。
- PostgreSQL JDBC URL。
- 与 MySQL quickstart 相同的 kkrepo、File blob store 和加密 secret 行为。

`scripts/quickstart.sh` 支持：

```bash
KKREPO_DATABASE_TYPE=postgresql bash quickstart.sh
```

默认不传时仍下载并启动 MySQL quickstart。

`docker-compose.dev.yml` 可以增加 PostgreSQL profile，开发者显式选择；不应默认同时启动 MySQL 和 PostgreSQL。

兼容性 Compose 应增加 kkrepo 数据库矩阵。当前 `docker-compose.compat-postgres.yml` 只表示源 Nexus 使用 PostgreSQL，不能把它当作 kkrepo PostgreSQL 支持证明。

## Helm / Kubernetes

当前仓库没有 kkrepo 应用 Helm chart，因此 Issue #107 中的 Helm/Kubernetes 支持是独立工作流，不能只修改不存在的 values。

建议新增：

```text
deploy/helm/kkrepo/
```

基础 values：

```yaml
database:
  type: mysql
  url: ""
  username: ""
  existingSecret: ""
  passwordKey: password

externalDatabase:
  enabled: true

embeddedDatabase:
  enabled: false
```

设计约束：

- 生产默认使用外部 MySQL 或 PostgreSQL。
- chart 不默认安装数据库 StatefulSet。
- 测试环境如需内置数据库，应通过显式 subchart/profile 启用。
- 密码必须从 Secret 引用，不能明文进入 ConfigMap。
- 多副本 Deployment 共享同一数据库和 blob store。
- readiness 必须在 Flyway 和 datasource 可用后通过。
- rolling update 需要兼容前后版本同时运行，后续 migration 采用 expand/contract。

如果暂时不准备在本仓库维护 Helm chart，应从 Issue #107 中拆出一个明确的 follow-up issue，而不是把 acceptance criteria 标记完成。

## 文档更新范围

落地时需要更新：

- `README.md`、`README.cn.md` 中的 MySQL-first 产品描述。
- `docs/en/architecture.md`、`docs/zh/architecture.md`。
- build/deployment guide。
- production hardening。
- troubleshooting。
- backup/restore。
- security model。
- FAQ。
- compatibility matrix。
- migration playbook。
- MySQL ER 文档，并新增 PostgreSQL schema 文档或统一 database schema 文档。

不能全局机械替换 `MySQL`：

- 历史事故、原有技术选择和 MySQL 专用 SQL 文档仍应保留 MySQL。
- 新产品能力描述使用 “MySQL or PostgreSQL” 或 “shared relational database”。
- MySQL/PostgreSQL 运维命令、备份、PITR、字符集/collation 和连接参数分别说明。

## 分阶段 PR

### PR 1：中性化模块和命名

- 新增 `persistence-jdbc`。
- 建立 `persistence.jdbc.api`、`persistence.jdbc.internal` 和 `persistence.jdbc.spi` 包边界。
- 把上层需要的 Store/DAO 接口、查询参数和公共 model 移入 `api`。
- 把唯一公共 JDBC 实现、RowMapper、标准 SQL、hash、enum、JSON 序列化和 support 移入 `internal`。
- 把数据库 backend 扩展契约放入 `spi`。
- 将 `server`、`migration-nexus` 和测试更新为只依赖 `persistence.jdbc.api`。
- 增加包依赖架构检查，禁止业务层 import `internal`、`spi` 和数据库 backend。
- `MysqlVersionWatermark` 改名为 `JdbcVersionWatermark`。
- `MysqlCatalogCacheBroadcaster` 改名为 `JdbcCatalogCacheBroadcaster`。
- 保持 MySQL SQL、配置和测试行为不变。

验收：

- 全仓库测试通过。
- MySQL dev/quickstart 可启动。
- 没有 PostgreSQL 行为改动。
- protocol、controller、业务 service 和 `migration-nexus` 不再依赖具体 JDBC 实现或 MySQL package。

### PR 2：抽取 MySQL dialect

- 增加 `DatabaseType` 和 dialect 契约。
- 抽取 component upsert、cache version、JSON、全文搜索、insert-ignore 和 age 表达式。
- MySQL 实现复现当前 SQL。
- `persistence-jdbc.internal` 只通过 SPI 调用 MySQL dialect，不直接依赖 MySQL 类。
- 增加 MySQL 真实数据库 contract tests。

验收：

- MySQL SQL 行为和性能基线无回归。
- 公共 JDBC 实现和业务 service 中没有 database type 条件分支。
- 上层通过公共 API contract 完成全部持久化测试。

### PR 3：PostgreSQL persistence

- 新增 PostgreSQL driver 和 Flyway database module。
- 增加 PostgreSQL dialect。
- 增加 `V29__postgresql_baseline.sql`。
- 移动并冻结 MySQL V1-V29。
- 增加 PostgreSQL contract tests。
- 增加 datasource type 启动校验。

验收：

- 两个数据库 fresh migrate 和 DAO contract 通过。
- MySQL V29 upgrade compatibility 通过。

### PR 4：双库运行时与多副本验证

- 增加 server smoke matrix。
- 增加 Spring Session 双实例测试。
- 增加 worker claim、cache version、upload session 和 migration claim 并发测试。
- 将 CI 设为 MySQL/PostgreSQL 双库 required checks。

验收：

- 两个后端都满足多副本正确性测试。
- protocol 和 service 不依赖 JDBC internal、SPI 或数据库 backend。

### PR 5：部署和文档

- PostgreSQL quickstart。
- dev Compose profile。
- compatibility database matrix。
- Helm/Kubernetes chart 或独立 follow-up issue。
- 中英文部署、备份、排障和架构文档。

验收：

- 用户可以按文档选择任一数据库完成全新部署。
- 默认 MySQL quickstart 命令保持兼容。

## 发布与回滚

发布原则：

- MySQL 始终保持默认。
- PostgreSQL 只有在 contract、server smoke 和多副本测试全部进入 required checks 后才对外声明 first-class support。
- V30 以后 migration 必须遵循 expand/contract，允许滚动升级期间新旧应用短暂共存。
- 新增列先 nullable/default-compatible，再切换读写，最后在后续版本收紧约束。
- 不在一个版本中同时重命名关键列并删除旧列。

回滚原则：

- 应用回滚不能依赖逆向执行 Flyway migration。
- migration 必须保证上一版本应用在合理滚动窗口内仍可读取 schema。
- PostgreSQL 部署失败时回滚到上一应用版本，而不是把同一数据库直接切换为 MySQL。
- 数据库类型切换必须使用新数据库和独立迁移流程。

## 主要风险

| 风险 | 缓解 |
| --- | --- |
| MySQL migration checksum 变化 | V1-V29 只移动不修改；增加 legacy V29 validate test |
| PostgreSQL JSONB 绑定失败 | 使用 dialect JSON binder，不依赖特殊 JDBC URL |
| 大小写和 collation 行为不同 | 建立 identity 语义矩阵和双库 fixture contract |
| 时间戳跨时区偏移 | 分类 Instant/LocalDateTime；增加多时区 round-trip test |
| 全文搜索结果不一致 | 固定 tokenizer、prefix 规则、集合和排序 contract |
| `ON CONFLICT` 目标错误 | 每个 upsert 显式绑定业务唯一约束 |
| worker 重复领取 | 两库真实并发 `SKIP LOCKED` 测试 |
| Spring Session schema 不兼容 | 使用数据库专用 Flyway session migration |
| 文档仍声称 MySQL-only | 发布前全仓库扫描产品描述，保留历史语境 |
| Helm scope 被遗漏 | 实际新增 chart 或拆出明确 follow-up issue |

## 完成标准

Issue #107 只有在以下条件全部满足时才能关闭：

1. 同一发布镜像可以通过配置连接 MySQL 或 PostgreSQL。
2. 两个后端都能从空 schema 完成 Flyway migration 并重复启动。
3. 已有 MySQL V29 安装升级无 checksum 和配置回归。
4. 公共 Persistence API contract 在两个数据库上全部通过。
5. protocol、controller、业务 service 和 `migration-nexus` 只能依赖 `persistence.jdbc.api`，包依赖 CI 检查通过。
6. Spring Session、security、token、audit、migration 和 cache version 行为一致。
7. 后台任务和 upload session 的多副本并发测试通过。
8. MySQL/PostgreSQL CI 都是 required checks。
9. 默认 MySQL quickstart 保持兼容，PostgreSQL quickstart 可独立运行。
10. Helm/Kubernetes acceptance 已实际落地或从本 issue 明确拆分。
11. 中英文架构、部署、备份和排障文档已更新。

## 参考资料

- [Spring Boot Flyway 数据库专用 migration 目录](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [PostgreSQL INSERT / ON CONFLICT / RETURNING](https://www.postgresql.org/docs/current/sql-insert.html)
- [PostgreSQL SELECT / FOR UPDATE SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html)
- [PostgreSQL JSON / JSONB](https://www.postgresql.org/docs/current/datatype-json.html)
- [PostgreSQL 全文检索表和索引](https://www.postgresql.org/docs/current/textsearch-tables.html)
- [PostgreSQL partial indexes](https://www.postgresql.org/docs/current/indexes-partial.html)
- [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)
