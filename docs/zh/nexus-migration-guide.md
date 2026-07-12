# Nexus 迁移说明

本文说明从 Nexus Repository 迁移到 kkrepo 的准备条件、执行顺序、增量迁移和域名切换方式。

kkrepo 兼容 Nexus 的 `/repository/<repo>/...` URL 布局、客户端协议行为和权限认证模型。迁移完成后，只需要把原 Nexus 域名指向 kkrepo，Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、NuGet、RubyGems、Yum 等已被迁移流程覆盖的非 Docker 客户端配置不需要修改。Docker / OCI 使用 Registry HTTP API V2 的 `/v2/...` 路由；切换 Docker 客户端时需要保持仓库名和 connector/path-based routing 入口一致。

Cargo / Rust 在 kkrepo 中同样使用 `/repository/<repo>/...` sparse registry URL。对于 preflight 已确认 Cargo content model 指纹的 datastore 时代 Nexus H2/PostgreSQL 源端，hosted Cargo 仓库数据也走同一套迁移流程。

Dart / Pub 在 kkrepo 中同样使用 `/repository/<repo>/...` hosted URL。对于 preflight 已确认 Pub content model 的 Nexus 3.92.0 源端，hosted Pub 仓库数据走同一套迁移流程；显式选择的 Pub proxy cache 只有在 plan 为 `FULL` 时才迁移。

Composer / PHP 同样保留 `/repository/<repo>/...` Composer 2 URL。Nexus 原生 Composer 是 3.75.0+ Pro proxy-only 能力，因此迁移只接受 Nexus 原生 Composer proxy，并保持 proxy 语义；未在 `Optional proxy repositories` 中显式选择时只迁移配置，不迁移 cache。kkrepo 自有的 Composer hosted/group 不是 Nexus 源端迁移推断目标。

## 迁移流程概览

迁移按以下顺序执行：

| 顺序 | 后台入口 | 操作 | 结果 |
| --- | --- | --- | --- |
| 1 | 源 Nexus | 开启 Script REST API 脚本创建能力 | kkrepo 可以读取普通 REST API 无法直接提供的源端数据 |
| 2 | Nexus Metadata | 先执行 `Run preflight`，确认无阻塞问题后执行 `Run migration` | 迁移用户、角色、权限、blob store、repository 定义等系统元数据 |
| 3 | Nexus Repository Data | 执行 `Sync metadata` | 扫描源 Nexus hosted 仓库资产，生成仓库数据迁移任务 |
| 4 | Nexus Repository Data | 执行 `Sync packages` | 迁移真实 blob/package 数据到 kkrepo |
| 5 | Nexus Repository Data | 上线前按需重复增量 `Sync metadata` 和 `Sync packages` | 迁移切换窗口内新增或更新的数据 |
| 6 | DNS 或反向代理 | 把原 Nexus 域名指向 kkrepo | 客户端无感切换到 kkrepo |

## 迁移前准备

- 确认 kkrepo 已经部署并连接生产 MySQL。
- 在 kkrepo 中先创建名为 `default` 的 blob store；生产环境建议使用 OSS/S3 blob store。
- 确认源 Nexus 账号具备迁移权限，建议直接使用源 Nexus admin 账号。
- 在源 Nexus 开启脚本能力，详见下方“源端脚本能力配置”。
- 建议迁移窗口内保留源 Nexus 可访问，方便多轮增量同步和回查。

## 元数据迁移

先进入 kkrepo 后台 `/admin/` 的 `Nexus Metadata` 页面，填写源 Nexus 地址、用户名和密码。

1. 点击 `Run preflight`，检查脚本能力、账号权限、repository/blob store/security 配置和不支持项。
2. 根据 preflight 结果处理阻塞问题，例如脚本能力未开启、账号权限不足、部分用户密码 hash 无法补偿等。
3. 点击 `Run migration`，迁移用户、角色、权限、realm、anonymous、blob store、repository 定义等系统元数据。

仓库数据迁移依赖目标 repository 和 blob store 已经存在，因此必须先完成 `Nexus Metadata` 迁移，再执行 `Nexus Repository Data` 迁移。

## 仓库数据迁移

仓库数据迁移在 kkrepo 后台 `/admin/` 的 `Nexus Repository Data` 页面执行，分两步：

1. 先迁移仓库元数据：扫描源 Nexus hosted 仓库中的 component、asset、路径、大小、content-type、时间戳和 blob 引用等信息，在 kkrepo MySQL 中生成迁移任务。
2. 再迁移 blob 真实数据：按迁移任务下载源 Nexus asset 内容，并写入 kkrepo 的目标 blob store。

对于 source profile 已确认支持 Cargo content model 的 Nexus 3.77.x+ datastore 时代 H2/PostgreSQL 源端，Cargo / Rust hosted 仓库数据通过此流程迁移。对于 source profile 已确认 Pub content model 的 Nexus 3.92.0+ datastore 源端，Dart / Pub hosted 仓库数据也通过此流程迁移；Pub proxy cache 仅在显式选择且计划为 `FULL` 时迁移。Nexus 原生 Composer proxy cache 同样要求管理员显式选择，并由 source profile 证明 Composer datastore content model；没有 Composer Pro 能力、未知 schema、community plugin 或非 proxy Composer 源都会 fail closed。旧 OrientDB 源端不会启用 datastore-only 内容导出。

### 第一次迁移

第一次迁移仓库数据时，`Metadata since` 不用选择，保持为空即可。这样会扫描源 Nexus 中所有可迁移的 hosted 仓库数据。

执行步骤：

1. 填写 `Source URL`、`Source username` 和 `Source password`。
2. 根据源 Nexus 规模调整 `Page size` 和 `Concurrency`；如果仓库数据量较大，建议把 `Concurrency` 从默认 `8` 调整到 `32`。
3. 保持 `Metadata since` 为空。
4. 点击 `Sync metadata`，扫描全部仓库元数据。
5. 元数据扫描完成后，点击 `Sync packages`，迁移 blob 真实数据。
6. 如有失败项，处理源端权限、网络或存储问题后点击 `Retry failed`，再继续 `Sync packages`。

`Sync metadata` 只发现和记录待迁移资产，不会下载真实 blob。真实包文件由 `Sync packages` 迁移。

### Docker 仓库端到端验证

Docker / OCI 仓库迁移也走 `Nexus Repository Data` 两步流程。为了避免本地验证误扫所有 hosted 仓库，可以在请求中指定 `repositories` 或 `repositoryNames`，只迁移目标 Docker hosted 仓库。

本地参考 Nexus 默认使用 `http://localhost:28090/`：

```bash
scripts/docker-compat/migration-e2e.sh
```

该脚本会向源端 `docker-hosted` 推送一个测试镜像，只迁移这个 Docker hosted 仓库的 metadata 和 blob 数据，然后从 kkrepo Docker connector 拉取同一镜像并校验 digest。

### 增量迁移

第二次及后续迁移时，可以指定 `Metadata since` 做增量迁移。`Metadata since` 会按源 Nexus asset/blob 的更新时间过滤，只扫描该时间之后新增或更新的资产。

建议在切换前至少再执行一轮增量：

1. 在 `Metadata since` 选择上一次迁移开始时间，或选择略早于上一次同步完成时间的时间点，保留少量重叠窗口。
2. 点击 `Sync metadata`，扫描增量仓库元数据。
3. 点击 `Sync packages`，迁移增量 blob 真实数据。

已经迁移完成的路径会被识别并跳过，不会重复迁移；目标端已经存在的资产也会标记为已迁移。

### 中断和续跑

迁移任务、仓库扫描游标、asset 状态和失败信息都存储在 MySQL 中。迁移过程中如果 kkrepo 重启、网络中断、源 Nexus 短暂不可用或页面关闭，可以继续执行：

- 仓库元数据扫描中断后，点击 `Continue metadata` 继续扫描。
- blob 真实数据迁移中断后，重新点击 `Sync packages` 继续迁移未完成部分。
- 失败资产修复原因后，点击 `Retry failed` 重新入队，再执行 `Sync packages`。

迁移设计为可中断、可继续。已经迁移完成的数据会保持已完成状态，后续迁移会跳过这些部分。

## 域名切换

全量迁移和最终增量迁移完成后，检查关键仓库的 browse/search、包下载、checksum 和常用客户端拉取行为。

确认无问题后，把原 Nexus 域名通过 DNS 或反向代理指向 kkrepo。由于 kkrepo 兼容 Nexus 的 `/repository/<repo>/...` URL 布局、客户端协议和权限认证模型，对于已迁移的仓库格式，客户端不需要修改 Maven settings、npm registry、PyPI index-url、Go GOPROXY、Helm repo、Cargo sparse registry URL、Pub hosted URL、Composer repository URL 等配置。

切换完成后，建议保留源 Nexus 一段观察期，并在确认不再需要补偿迁移后关闭源端脚本能力。

## 源端脚本能力配置

从 Nexus 迁移到 kkrepo 前，源端 Nexus 需要允许通过 Script REST API 创建并执行临时 Groovy 脚本。

kkrepo 迁移流程会在源 Nexus 端临时创建脚本，用于补偿 Nexus 普通 REST API 无法直接读取的数据，例如本地用户 password hash、API key 兼容信息，以及 hosted 仓库资产分页发现。脚本执行完成后，kkrepo 会尝试删除临时脚本。

### 适用范围

- Nexus Repository 3.21.2 及以上：Groovy scripting 默认关闭，需要显式开启。
- Nexus Repository 3.21.1 及以下：脚本功能默认开启，但仍需要执行迁移的账号具备对应权限。

即使只做迁移 preflight，也建议先开启脚本创建能力。否则本地用户密码 hash 无法自动补偿，迁移结果会输出需要重置密码的用户清单；仓库数据迁移也无法创建用于读取源端资产元数据的临时脚本。

### 开启方式

在源 Nexus 的数据目录配置文件中增加以下配置：

```properties
nexus.scripts.allowCreation=true
```

配置文件位置是：

```text
$data-dir/etc/nexus.properties
```

常见部署路径示例：

| 部署方式 | 常见配置文件路径 |
| --- | --- |
| Docker | `/nexus-data/etc/nexus.properties` |
| tar/zip 或服务化部署 | `<nexus-data>/etc/nexus.properties` |

如果文件不存在，可以创建该文件；如果文件已存在，直接追加配置。修改后必须重启源 Nexus，配置才会生效。

### Docker 示例

进入源 Nexus 容器：

```bash
docker exec -it <nexus-container> sh
```

追加配置：

```bash
printf '\nnexus.scripts.allowCreation=true\n' >> /nexus-data/etc/nexus.properties
```

退出容器后重启 Nexus 容器：

```bash
docker restart <nexus-container>
```

### Kubernetes 环境变量示例

如果 Nexus 通过 Kubernetes 部署，也可以通过 `INSTALL4J_ADD_VM_PARAMS` 增加 JVM system property：

```yaml
- name: INSTALL4J_ADD_VM_PARAMS
  value: '-Xms8G -Xmx8G -XX:MaxDirectMemorySize=8192M -Djava.util.prefs.userRoot=/nexus-data/javaprefs -Dnexus.scripts.allowCreation=true'
```

修改 Deployment/StatefulSet 后，需要重启或滚动更新源 Nexus Pod，使新的 JVM 参数生效。

### 非容器部署示例

找到 Nexus 数据目录后编辑：

```bash
vi <nexus-data>/etc/nexus.properties
```

加入：

```properties
nexus.scripts.allowCreation=true
```

然后按你的部署方式重启 Nexus 服务，例如：

```bash
systemctl restart nexus
```

### 验证

使用具备管理员权限的账号访问源 Nexus 的 Script REST API：

```bash
curl -u '<admin-user>:<admin-password>' \
  -H 'Content-Type: application/json' \
  -X POST 'http://<nexus-host>:8081/service/rest/v1/script' \
  -d '{
    "name": "kkrepo-script-check",
    "type": "groovy",
    "content": "return \"ok\""
  }'
```

如果返回 204、200 或 201 一类成功响应，说明脚本创建能力可用。随后清理测试脚本：

```bash
curl -u '<admin-user>:<admin-password>' \
  -X DELETE 'http://<nexus-host>:8081/service/rest/v1/script/kkrepo-script-check'
```

也可以直接在 kkrepo 后台管理页面执行 Nexus Metadata preflight。若脚本能力未开启，源端通常会在创建脚本时返回 `410 Gone`，并提示创建或更新脚本已被禁用。

## 迁移账号权限

执行迁移的源 Nexus 账号建议使用 admin 账号，至少需要具备：

- 读取 blob store、repository、security、content selector、role、privilege、user 等配置的权限。
- 创建、运行、读取和删除 Script REST API 脚本的权限。
- 读取需要迁移的 hosted 仓库资产内容的权限。

权限不足时，迁移可能表现为 `401 Unauthorized`、`403 Forbidden`，或者 preflight 中出现无法读取某类源端对象的错误。

## 迁移完成后关闭脚本能力

脚本能力风险较高，只建议在迁移窗口内开启。迁移完成并确认无需继续补偿迁移后，建议关闭：

1. 删除或注释 `$data-dir/etc/nexus.properties` 中的配置：

   ```properties
   # nexus.scripts.allowCreation=true
   ```

2. 重启源 Nexus。
3. 确认没有残留 `kkrepo-*` 临时脚本；如有残留，可通过 Script REST API 删除。

## 常见问题

### `410 Gone`

源 Nexus 禁止创建或更新脚本。确认 `$data-dir/etc/nexus.properties` 中已设置：

```properties
nexus.scripts.allowCreation=true
```

并且已经重启源 Nexus。

### `401 Unauthorized` 或 `403 Forbidden`

迁移账号认证失败或权限不足。建议使用源 Nexus admin 账号执行迁移，或补齐 Script REST API 和相关管理对象读取权限。

### `404 Not Found`

确认访问的是 Nexus Repository 3 的地址和端口，并且路径是：

```text
/service/rest/v1/script
```

如果 Nexus 前面有反向代理，确认代理没有拦截 `/service/rest/v1/script`。

### 迁移后仍提示部分用户需要重置密码

源端脚本 API 可用不代表所有本地用户 password hash 都一定能读取或写入目标端。preflight 和迁移结果会列出需要人工重置密码的用户，按结果处理即可。

## 官方参考

- Sonatype Script API: https://help.sonatype.com/en/script-api.html
- Sonatype Scripting Nexus Repository 3: https://support.sonatype.com/hc/en-us/articles/360045220393-Scripting-Nexus-Repository-3
