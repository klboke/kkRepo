# 构建部署指南

本文档说明 kkrepo 的本地启动、发行构建、压缩包部署、生产部署架构和资源规格建议。开发热重载和协议测试见 [开发指南](development-guide.md)。

## 前置要求

- JDK 25
- Maven 3.9 或兼容版本
- MySQL 8.0 或 PostgreSQL 12+（生产使用仍在维护期的 PostgreSQL 版本）
- 可选：Docker，用于构建镜像或运行容器化依赖

服务运行时依赖一个共享关系数据库：默认使用 MySQL，也支持 PostgreSQL。blob store 本地试用可以使用 File，生产环境建议使用 OSS/S3。详见[数据库后端](database-backends.md)。

## Docker Compose 快速试用

如果只是想快速体验，可以用公开发行镜像一条命令拉起 kkrepo 和 MySQL：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | bash
```

如需改用 PostgreSQL：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh \
  | KKREPO_DATABASE_TYPE=postgresql bash
```

该脚本会逐步打印日志并执行：

1. 检查 Docker、Docker Compose 和 curl。
2. 创建 `kkrepo-quickstart/` 工作目录。
3. 生成 `.env`，保存镜像 tag、端口和本地试用密钥。
4. 下载 `docker-compose.quickstart.yml`。
5. 检查宿主机端口占用。
6. 拉取镜像并执行 `docker compose up -d`。
7. 等待 `/actuator/health` 返回 `UP`，然后打印访问地址和常用命令。

如果希望先检查脚本内容，可以改用两步执行：

```bash
curl -fsSLO https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh
bash quickstart.sh
```

默认会启动：

- `ghcr.io/klboke/kkrepo:0.5.0`
- MySQL 8.0
- 用于本地试用的持久化 MySQL volume 和 File blob storage volume

启动后访问：

- 管理控制台：`http://127.0.0.1:19090/admin/`
- 用户侧浏览器：`http://127.0.0.1:19090/browse/`
- 健康检查：`http://127.0.0.1:19091/actuator/health`
- Prometheus 指标：`http://127.0.0.1:19091/actuator/prometheus`

首次进入页面时，在 UI 中创建初始 `Local/admin` 管理员密码。进入管理控制台后，先创建名为 `default` 的 blob store；本地试用可以选择 File，生产环境建议使用 OSS/S3。

停止试用环境：

```bash
docker compose -f docker-compose.quickstart.yml down
```

清空试用数据：

```bash
docker compose -f docker-compose.quickstart.yml down -v
```

脚本生成的 `.env` 加密密钥仅用于本地试用。对外部署时必须替换 `KKREPO_CREDENTIAL_SECRET` 和 `KKREPO_API_KEY_PAYLOAD_SECRET`，并使用独立 MySQL/PostgreSQL 与 OSS/S3 blob store。

如果默认端口被占用，可以覆盖端口后启动：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | KKREPO_HTTP_PORT=19190 KKREPO_MANAGEMENT_PORT=19191 bash
```

## 源码本地快速启动

创建本地数据库和账号：

```sql
CREATE DATABASE IF NOT EXISTS kkrepo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'kkrepo'@'%' IDENTIFIED BY 'kkrepo';
GRANT ALL PRIVILEGES ON kkrepo.* TO 'kkrepo'@'%';
FLUSH PRIVILEGES;
```

默认配置连接 `127.0.0.1:13306/kkrepo` 的 MySQL。如果本地数据库使用其他端口或账号，可以通过 Spring Boot 环境变量覆盖，并声明类型：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/kkrepo?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME=kkrepo
export SPRING_DATASOURCE_PASSWORD=kkrepo
export KKREPO_DATABASE_TYPE=mysql
```

PostgreSQL 使用 `KKREPO_DATABASE_TYPE=postgresql` 和 `jdbc:postgresql://...` URL。

构建并启动：

```bash
mvn -pl server -am -DskipTests package spring-boot:repackage
java -jar server/target/kkrepo-server-*.jar
```

启动后访问：

- 管理控制台：`http://127.0.0.1:8080/admin/`
- 用户侧浏览器：`http://127.0.0.1:8080/browse/`
- 健康检查：`http://127.0.0.1:8081/actuator/health`
- Prometheus 指标：`http://127.0.0.1:8081/actuator/prometheus`

首次进入管理控制台后，先创建名为 `default` 的 blob store；本地试用可以选择 File，生产环境建议使用 OSS/S3。

## Spring Boot 可执行 Jar

打包可执行 jar：

```bash
mvn -pl server -am -DskipTests package spring-boot:repackage
```

产物路径：

```text
server/target/kkrepo-server-<version>.jar
```

注意：普通 `server` 模块 jar 没有 Spring Boot 可执行入口。需要复制或部署 `server/target/kkrepo-server-*.jar` 时，必须先执行 `spring-boot:repackage`。

## Docker 镜像

拉取最新公开发行镜像：

```bash
docker pull ghcr.io/klboke/kkrepo:0.5.0
```

也可以使用 `latest` 跟随最新公开发行版本：

```bash
docker pull ghcr.io/klboke/kkrepo:latest
```

构建默认镜像：

```bash
./scripts/build-docker-image.sh
```

指定镜像 tag：

```bash
./scripts/build-docker-image.sh kkrepo:local
```

容器化部署仍建议使用独立 MySQL 和 OSS/S3 blob store，不建议把容器本地文件系统作为长期生产 blob 存储。

## 压缩包部署

压缩包中包含可执行 jar、启动脚本、停止脚本、重启脚本、状态检查脚本和外部配置文件，适合快速试用、传统 VM 部署或需要接入 systemd 的环境。

在仓库根目录执行：

```bash
./scripts/build-dist.sh
```

构建完成后会生成：

```text
server/target/kkrepo-<release-version>.tar.gz
server/target/kkrepo-<release-version>.zip
```

如果后续开发版本使用 Maven `-SNAPSHOT` 后缀，构建脚本会自动在压缩包文件名和解压目录中去掉该后缀。

压缩包结构：

```text
kkrepo-<release-version>/
  bin/
    start.sh
    stop.sh
    restart.sh
    status.sh
  conf/
    application.properties
  lib/
    kkrepo.jar
  logs/
  data/
```

`logs/` 和 `data/` 目录由启动脚本自动创建。

解压：

```bash
tar -xzf kkrepo-<release-version>.tar.gz
cd kkrepo-<release-version>
```

编辑外部配置：

```bash
vi conf/application.properties
```

至少需要确认以下配置：

```properties
server.port=8080
management.server.port=8081

spring.datasource.url=jdbc:mysql://127.0.0.1:3306/kkrepo?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
spring.datasource.username=kkrepo
spring.datasource.password=kkrepo

kkrepo.security.encryption.credential-secret=CHANGE_ME_credential_secret_at_least_32_chars
kkrepo.security.encryption.api-key-payload-secret=CHANGE_ME_api_key_payload_secret_at_least_32_chars
```

`credential-secret` 和 `api-key-payload-secret` 必须替换成稳定的强随机字符串。它们用于加密 blob store 凭据、认证 realm 密钥和 API key payload；已经写入数据后不要随意更换。

常用脚本：

```bash
bin/start.sh
bin/status.sh
bin/restart.sh
bin/stop.sh
```

启动脚本默认以解压目录作为 `KKREPO_HOME`。可以通过环境变量覆盖常用路径：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `KKREPO_HOME` | 解压目录 | 应用主目录 |
| `KKREPO_CONF_DIR` | `$KKREPO_HOME/conf` | 外部配置目录 |
| `KKREPO_LOG_DIR` | `$KKREPO_HOME/logs` | 日志目录 |
| `KKREPO_PID_FILE` | `$KKREPO_LOG_DIR/kkrepo.pid` | PID 文件 |
| `KKREPO_JAR_FILE` | `$KKREPO_HOME/lib/kkrepo.jar` | 可执行 jar |
| `JAVA_OPTS` | 空 | JVM 参数 |

示例：

```bash
export JAVA_OPTS='-Xms2g -Xmx4g -XX:+UseG1GC'
bin/start.sh
```

## systemd 示例

可以用 systemd 托管压缩包部署：

```ini
[Unit]
Description=kkrepo
After=network.target

[Service]
Type=forking
User=kkrepo
Group=kkrepo
Environment="JAVA_OPTS=-Xms2g -Xmx4g -XX:+UseG1GC"
WorkingDirectory=/opt/kkrepo
ExecStart=/opt/kkrepo/bin/start.sh
ExecStop=/opt/kkrepo/bin/stop.sh
PIDFile=/opt/kkrepo/logs/kkrepo.pid
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## 生产部署架构

生产部署建议使用多副本 kkrepo 服务、独立 MySQL 或 PostgreSQL 和 OSS/S3 blob store：

```text
Maven/npm/PyPI/Go/Helm/Cargo/Pub/Composer/Terraform/Docker/OCI/NuGet/RubyGems/Yum clients
        |
DNS / Load Balancer / Reverse Proxy
        |
kkrepo replicas
        |---------------- MySQL 8 / PostgreSQL 12+
        |---------------- OSS / S3 blob store
```

kkrepo 运行时不依赖 OrientDB、内嵌 Elasticsearch 或本地持久化 blob 文件系统。仓库、组件、asset、用户、权限、token、审计、迁移状态和跨副本协调状态存储在所选关系数据库；大 blob 存储在 OSS/S3；进程内 TTL 缓存只作为可重建热缓存。

## 生产资源建议

- kkrepo 服务实例：生产环境建议单实例至少 2C4G；如果需要高可用，建议至少部署 2 个副本，并根据请求量继续横向扩容。
- 数据库：生产环境建议至少 2C4G，使用独立 MySQL/PostgreSQL 实例，并按仓库数量、包数量、索引规模和迁移任务量扩容 CPU、内存、磁盘和 IOPS。
- blob 存储：生产环境建议使用 OSS/S3；File blob store 更适合本地开发、测试或有强一致共享文件系统的特定部署。
- 生产环境建议使用独立关系数据库和独立 OSS/S3 bucket，不建议把 File blob store 作为长期生产存储。
- 多副本部署时，session、认证 ticket、catalog 水位、锁和迁移进度通过数据库协调；节点本地缓存丢失不影响正确性。
- 迁移大仓库时，`Nexus Repository Data` 页面建议把 `Concurrency` 从默认 `8` 调整到 `32`，并根据源 Nexus、网络和对象存储压力继续调优。

## 升级建议

如果使用压缩包部署，建议升级流程：

1. 解压新版本到新的目录。
2. 复制旧版本的 `conf/application.properties`。
3. 停止旧版本。
4. 启动新版本。
5. 通过 `/actuator/health`、`/admin/`、`/browse/` 和关键仓库拉包验证。

继续使用同一个数据库和 OSS/S3 blob store 时，升级前请备份两者，并确认新版本对应数据库的 Flyway migration 符合预期。
