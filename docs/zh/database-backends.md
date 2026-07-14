# 数据库后端

kkrepo 支持使用 MySQL 8 或 PostgreSQL 12+ 作为可互换的共享状态后端。为兼容已有安装，MySQL 仍是默认值。同一个可执行 jar 和容器镜像同时包含两种驱动，首次启动前需要明确选择一种后端。PostgreSQL 12 是 SQL 兼容性下限；生产部署应选择仍在 PostgreSQL 社区或托管服务商维护期内的版本。

## 选择数据库

数据库类型、JDBC URL 和凭据必须成组配置：

```bash
# MySQL（默认）
export KKREPO_DATABASE_TYPE=mysql
export SPRING_DATASOURCE_URL='jdbc:mysql://db:3306/kkrepo?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=kkrepo
export SPRING_DATASOURCE_PASSWORD='<password>'
```

```bash
# PostgreSQL
export KKREPO_DATABASE_TYPE=postgresql
export SPRING_DATASOURCE_URL='jdbc:postgresql://db:5432/kkrepo'
export SPRING_DATASOURCE_USERNAME=kkrepo
export SPRING_DATASOURCE_PASSWORD='<password>'
```

`KKREPO_DATABASE_TYPE` 对应 Spring 属性 `kkrepo.database.type`。Flyway 执行前，启动流程会用 JDBC metadata 校验声明类型和真实数据库是否一致，因此误把 MySQL 配成 PostgreSQL（或反过来）会在修改 schema 前失败。

不要仅通过修改 URL 把一个已有安装从一种数据库切换到另一种数据库。应创建新库，用经过验证的数据库迁移流程复制数据，校验行数、checksum 和应用行为后再切流。

## Quickstart

默认启动 MySQL：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | bash
```

使用同一个脚本选择 PostgreSQL：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh \
  | KKREPO_DATABASE_TYPE=postgresql bash
```

脚本会为 MySQL 选择 `docker-compose.quickstart.yml`，为 PostgreSQL 选择 `docker-compose.quickstart-postgresql.yml`。PostgreSQL quickstart 当前默认使用 `postgres:16` 镜像，但这不是运行时最低支持版本。两种方案暴露完全相同的应用端口、管理端口、Admin UI、Browse UI 和仓库 URL。

仓库开发环境可单独启动 PostgreSQL profile：

```bash
docker compose -f docker-compose.dev.yml --profile postgresql up -d postgresql
```

## Schema 迁移

Flyway 按数据库使用独立目录：

- MySQL：`classpath:db/migration/mysql`
- PostgreSQL：`classpath:db/migration/postgresql`

MySQL V1-V29 历史不可修改，并保留原 checksum。PostgreSQL 从逻辑等价的 V29 baseline 开始。从 V30 起，每次 schema 变更都必须在两个目录使用相同版本和描述，并产生相同的逻辑结果。

CI 会校验 MySQL V1-V29 文件哈希、重复启动和 validate、PostgreSQL 12 最低版本的全新/重复启动、PostgreSQL 16 端到端兼容性，以及两套迁移的版本对齐。多个应用副本可能同时连接同一个数据库，因此迁移必须具备多副本启动安全性。

## 多副本语义

所选关系数据库是以下状态的共享正确性边界：

- 仓库、组件、资产、身份、权限、token 和审计元数据；
- Spring Session 和短生命周期认证 ticket；
- 迁移任务、checkpoint、claim、重试和维护 cursor；
- 缓存版本水位和后台任务 marker。

进程内 TTL cache 只作为可重建热缓存。副本重启或本地缓存丢失最多增加数据库读取，不能影响正确性。同一部署中的所有副本必须使用相同的数据库引擎、schema、blob store 和加密 secret。

## 部署

打包后的 jar 和容器镜像不需要针对后端重新构建。在 VM、Compose、Kubernetes 或 Helm 中设置上述三个数据库变量即可。

`deploy/helm/kkrepo` 下的 Helm chart 要求使用外部数据库 secret，values 会校验数据库类型，并支持直接凭据或已有 Kubernetes Secret。生产环境应至少部署两个应用副本，使用滚动更新和 OSS/S3 blob 存储。

## 备份与恢复

关系数据库和 blob store 必须作为同一恢复集备份。优先使用数据库一致性快照边界后备份对象存储版本，或者在采集两者期间暂停写入。两个应用加密 secret 也必须纳入恢复材料。

- MySQL：使用一致性 `mysqldump` 或托管快照，并恢复到 MySQL 8。
- PostgreSQL：使用 `pg_dump`/`pg_restore` 或托管快照，并恢复到兼容的 PostgreSQL 版本。

恢复后先让一个 kkrepo 副本连接恢复库，等待 Flyway validate，通过 `/actuator/health` 并抽查资产和权限后再扩容。

## 故障排查

- **声明类型与 JDBC 数据库不一致：** 修正 `KKREPO_DATABASE_TYPE` 或 URL，不要绕过校验。
- **Flyway validate 失败：** 恢复原迁移文件；不要修改已执行迁移，应新增版本。
- **JSON 查询或绑定报错：** 确认配置的数据库类型、驱动和服务一致。PostgreSQL 使用 `jsonb`，MySQL 使用 `JSON`。
- **时区差异：** 应用节点使用 UTC，并显式配置 JDBC/服务器时区。基于 instant 的对外时间是绝对时间点；契约测试覆盖 UTC、Asia/Shanghai 和夏令时切换。为保持 MySQL 行为，历史 API key 与安全审计的 `LocalDateTime` 字段有意使用不带时区的墙上时间。
- **只有一个副本看到变更：** 确认所有副本共享同一个数据库和缓存版本表，再检查数据库连接与轮询指标。

另见[数据库 Schema](database-schema.md)、[生产加固](production-hardening.md)和[备份恢复](backup-restore.md)。
