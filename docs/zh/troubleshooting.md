# 排障指南

本文覆盖常见安装、运行、迁移和兼容性问题。公开分享日志前，请先脱敏 secret、token、密码、私有仓库名和私有包内容。

## Quickstart 启动前失败

建议在单独本地目录执行 quickstart：

```bash
curl -fsSLO https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh
bash quickstart.sh
```

先检查基础条件：

- Docker 已安装并运行：`docker info`。
- Docker Compose 可用：`docker compose version`。
- 已安装 `curl`。
- `19090` 和 `19091` 端口未被占用。

如果端口被占用，可以覆盖端口：

```bash
KKREPO_HTTP_PORT=19190 KKREPO_MANAGEMENT_PORT=19191 bash quickstart.sh
```

查看 quickstart 状态：

```bash
cd kkrepo-quickstart
docker compose -f docker-compose.quickstart.yml ps
docker compose -f docker-compose.quickstart.yml logs -f kkrepo
```

停止试用环境但保留数据：

```bash
docker compose -f docker-compose.quickstart.yml down
```

删除所有试用数据：

```bash
docker compose -f docker-compose.quickstart.yml down -v
```

## 服务健康检查不变为 UP

检查管理端口：

```bash
curl -i http://127.0.0.1:19091/actuator/health
```

常见原因：

- MySQL 未健康，或账号密码不正确。
- Flyway migration 执行失败。
- 应用无法写入配置的 File blob 目录。
- 生产类配置中缺少加密密钥，或密钥过短。
- 容器或 VM 中复制了普通 jar，而不是 Spring Boot 可执行 jar。部署前需要执行 `spring-boot:repackage`。

有用日志：

```bash
docker compose -f docker-compose.quickstart.yml logs --tail 200 mysql
docker compose -f docker-compose.quickstart.yml logs --tail 200 kkrepo
```

源码方式启动：

```bash
mvn -pl server -am -DskipTests package spring-boot:repackage
java -jar server/target/kkrepo-server-*.jar
```

## 无法打开 `/admin/` 或 `/browse/`

确认访问的是应用端口，不是管理端口：

- 应用：`http://127.0.0.1:19090/admin/`
- Browse UI：`http://127.0.0.1:19090/browse/`
- 健康检查：`http://127.0.0.1:19091/actuator/health`

如果使用反向代理，请确认：

- 代理保留完整 path。
- 允许足够大的上传 body。
- 上传和下载 timeout 足够长。
- 不会移除 Maven/npm/pip/Helm/Cargo/NuGet/gem/yum 客户端需要的认证 header。

## 初始管理员设置问题

首次访问时，在 UI 中创建初始 `Local/admin` 管理员密码。

如果没有出现初始化页面：

- 确认连接的是预期数据库。
- 检查该环境中是否已经存在 admin 用户。
- 查看服务日志中的安全初始化错误。

不要在生产环境复用 quickstart secret 或试用密码。

## Blob Store 问题

首次登录后，请创建名为 `default` 的 blob store，除非你的 repository 定义使用了其它 blob store 名称。

常见现象：

- 仓库引用了不存在的 blob store，导致上传失败。
- File blob store 因目录 owner 或权限错误无法写入。
- S3/OSS blob store 健康检查失败，原因可能是 endpoint、region、bucket、access key、secret key 或 path-style access 配置错误。

生产环境建议：

- 优先使用 OSS/S3 兼容存储。
- 按恢复要求配置 bucket lifecycle、versioning、备份和保留策略。
- 写入凭据或 API key payload 后，不要随意更改加密密钥。

## MySQL 问题

服务运行时必须依赖 MySQL。核心元数据、身份、权限、session、审计日志、迁移状态和跨副本协调状态都在 MySQL 中。

检查连通性：

```bash
mysql -h127.0.0.1 -P13306 -ukkrepo -pkkrepo kkrepo
```

源码本地启动时，可以覆盖 datasource：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/kkrepo?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME=kkrepo
export SPRING_DATASOURCE_PASSWORD=kkrepo
```

常见原因：

- MySQL 端口和本地默认值不同。
- 用户没有 `kkrepo` 数据库权限。
- 数据库字符集不是 `utf8mb4`。
- 当前环境需要调整 MySQL timezone 或 SSL 参数。

## 客户端收到 401 或 403

检查：

- 仓库处于 online 状态。
- anonymous access 配置符合预期。
- 用户具备所需仓库 browse/read/add/edit/delete 权限。
- 客户端使用了该协议正确的凭据类型。
- 反向代理保留了 `Authorization` header。

对于 npm、Cargo、NuGet、RubyGems 等 token 型客户端，修改用户或 realm 设置后，建议重新生成相关 token 或 API key。

如果问题是 Nexus 兼容差异，提交 issue 时请同时提供 Nexus 和 kkrepo 对同一请求的响应。

## 上传失败

检查：

- 目标仓库是 `hosted`，不是 `proxy` 或 `group`。
- 仓库 write policy 允许该操作。
- blob store 存在且可写。
- 反向代理 body size 和 timeout 足够大。
- 客户端发布地址是 `/repository/<repo>/...`。
- 用户具备所需仓库 add/edit 权限。

如果重复上传失败，请确认 hosted 仓库 write policy。部分仓库会按设计拒绝重复发布。

## 迁移问题

推荐顺序：

1. 在 `Nexus Metadata` 页面执行 `Run preflight`。
2. 解决阻塞问题。
3. 执行元数据迁移。
4. 在 `Nexus Repository Data` 页面执行 `Sync metadata`。
5. 执行 `Sync packages`。

常见原因：

- 源 Nexus 未启用 Script REST API。
- 源 Nexus 凭据权限不足。
- 源 Nexus 无法暴露本地用户密码 hash；这些用户需要重置密码。
- 期望迁移 proxy 仓库，但没有在 `Optional proxy repositories` 中列出。
- Cargo / Rust 仓库迁移当前不属于现有迁移流程支持范围。
- blob 迁移慢，可能是并发过低、源 Nexus 压力过大，或对象存储限流。

详见 [Nexus 迁移说明](nexus-migration-guide.md)。

## 兼容性测试问题

默认测试不需要 live Nexus 实例：

```bash
mvn -pl compat-test -am test
```

Live 测试需要同时启动 Nexus 参考实例和 kkrepo：

```bash
scripts/build-docker-image.sh kkrepo:compat
docker compose -f docker-compose.compat.yml up -d mysql nexus kkrepo
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh smoke
docker compose -f docker-compose.compat.yml down -v
```

Cargo 兼容性测试使用 Nexus 3.77.x+ 参考 compose 文件和 `cargo` suite。运行 Cargo 读写检查前请先查看 [compat-test README](../../compat-test/README.md)。

如果 live 检查失败：

- 确认选择的 suite。
- 检查 Docker Compose 服务健康状态。
- 查看 `nexus` 和 `kkrepo` 日志。
- 确认 base URL 和凭据配置正确。
- 运行写入类 suite 前，确认确实需要启用写测试。

## Issue 中适合附带的日志

有用信息：

- kkrepo 版本或 commit。
- 部署模式和副本数。
- 仓库格式和仓库类型。
- 客户端命令或 HTTP 请求。
- 脱敏后的响应状态码、header 和 body 片段。
- 失败时间点附近的脱敏应用日志。

不要公开：

- 密码、token、API key 或 cookie。
- 私有包内容。
- 会暴露敏感拓扑的私有 hostname。
- 包含用户或凭据数据的完整迁移 dump。

## 什么时候私下报告

如果问题可能导致以下影响，请按 [SECURITY.md](../../SECURITY.md) 私下报告：

- 认证绕过。
- 授权绕过。
- 凭据、token 或 cookie 暴露。
- 仓库内容泄露。
- 权限提升。
- 远程代码执行。
- 从源 Nexus 泄露迁移数据。
