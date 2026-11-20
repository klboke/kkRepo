# Docker / OCI 仓库使用指南

kkRepo 支持 Docker Registry HTTP API V2 和 OCI Distribution 的 `hosted`、`proxy`、`group`
仓库。Docker 客户端使用 registry `/v2/...` route，不使用普通制品的
`/repository/<repo>/...` URL。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 image/artifact | `docker-hosted` | Blob store、write policy、connector/routing 配置 |
| 上游 pull-through cache | `docker-proxy` | Remote registry、凭据、缓存 TTL |
| 统一 pull 入口 | `docker-group` | Hosted 排在 proxy 前面 |

Docker Hub 使用 `https://registry-1.docker.io/` 这类 registry endpoint；kkRepo 会处理官方
image 的客户端可见 `library` namespace 行为。

## 选择访问布局

共享入口部署把 kkRepo 仓库名放在 image path 第一个 segment：

```text
<host>:<shared-port>/<repo>/<image>:<tag>
```

仓库级 connector port 可以提供标准路径：

```text
<host>:<repo-port>/<image>:<tag>
```

选择一种布局，为 `/v2/` 配置 TLS 与反向代理转发，并把准确 host:port 提供给用户。Docker
image reference 中不要加入 `/repository/<repo>/`。

## HTTPS 连接器

Docker 仓库可以在不同端口分别启用 HTTP 和 HTTPS。在仓库表单中勾选 **启用 HTTPS 连接器**，
填写端口并选择 **TLS 配置**；留空使用部署默认配置。证书由部署统一管理，不在仓库表单中上传。
在所有副本配置相同的服务端证书和私钥：

```properties
spring.ssl.bundle.pem.registry.keystore.certificate=file:/run/secrets/registry-fullchain.pem
spring.ssl.bundle.pem.registry.keystore.private-key=file:/run/secrets/registry-key.pem
kkrepo.tls.default-bundle=registry
```

证书文件应包含服务端证书及中间证书链。也支持通过 Spring Boot
`spring.ssl.bundle.jks.<名称>.*` 配置 JKS/PKCS12；存在多个私钥时必须指定 `key.alias`。
主站可以独立设置 `server.ssl.bundle=registry` 复用证书，Docker HTTPS 不要求主站同时启用 HTTPS。
`KKREPO_TLS_DEFAULT_BUNDLE` 指定 Docker 默认配置，未设置时回退到 `server.ssl.bundle`。
入站证书与仅用于出站 CA 信任的 `KKREPO_TLS_CA_CERTIFICATES` 相互独立。

仓库 API 的 `docker` 配置示例：

```json
{
  "connectorEnabled": true,
  "connectorPort": 5000,
  "httpsConnectorEnabled": true,
  "httpsConnectorPort": 5443,
  "tlsBundle": "registry"
}
```

旧字段 `connectorEnabled` / `connectorPort` 仍然控制 HTTP。更新时省略字段会保留已有配置，
设置 `httpsConnectorEnabled=false` 关闭 HTTPS，设置 `tlsBundle` 为空字符串恢复部署默认配置。
数据库约束保证 HTTP/HTTPS 端口跨仓库唯一。容器端口映射、Service、防火墙和负载均衡需另外配置。

首次升级到此版本时，应暂停仓库配置写入，待所有副本升级完成后再恢复；旧版本不会维护新增的跨协议端口占用记录。

各副本默认每 5 秒从数据库读取已提交的配置，可通过 `KKREPO_DOCKER_CONNECTOR_REFRESH_INTERVAL_MS`
调整。因此在一个副本修改配置后，其他副本也会自动生效。`/internal/docker/connectors` 显示
**当前响应节点**的 ID、真实监听状态及证书指纹，`sequence` 是本节点刷新次数，不是集群配置版本。
保存成功不代表所有副本已经绑定端口；HTTPS 启动失败不会降级为 HTTP，其他端口仍可使用，失败的端口会重试。
更换协议或 TLS 配置引用会替换旧监听器，失败时该端口保持不可用，直至修正配置。
向新端口导入流量前应检查各副本状态。

`/internal/docker/tls-bundles` 向 Docker 管理员提供证书元信息，不返回私钥或密码。
所有副本必须部署相同名称和内容的证书配置。替换证书文件或 Secret 后，通过滚动重启生效；
附加连接器暂不支持证书自动热重载。由反向代理终止 HTTPS 时，仍可使用后端 HTTP 连接器，
并正确配置受信任转发头。`connectorPublicUrl` 是两个连接器共用的可选外部访问地址，
填写 HTTPS URL 不会开启 TLS。参见[反向代理配置](../nginx-reverse-proxy.md)。

## 登录、Push 与 Pull

共享入口示例：

```bash
docker login nexus.example.com
docker pull nexus.example.com/docker-proxy/library/alpine:3.20
docker tag alpine:3.20 nexus.example.com/docker-hosted/team/alpine:3.20
docker push nexus.example.com/docker-hosted/team/alpine:3.20
docker pull nexus.example.com/docker-group/team/alpine:3.20
```

没有本地或 LDAP 密码的用户（例如仅使用 OIDC 登录的用户）可以把自己的 API key 作为密码：`docker login -u <user-id> nexus.example.com`，然后输入完整 API key。用户名必须是 key 的所有者（也可以写成 `<source>/<user-id>`）。所有发送 HTTP Basic 凭据的客户端都适用。API key 继承 OIDC/LDAP realm 在该用户最近一次登录时映射的角色，且不能用于打开 Admin UI session。由于 kkRepo 无法感知 IdP 中账号被禁用，OIDC/LDAP 用户超过 `kkrepo.security.external-identity.max-age`（默认 `30d`，`0` 表示关闭）未通过 IdP 登录时，其 API key 会失效，重新登录即可恢复。升级后，每个 OIDC/LDAP 用户需先登录一次，其 API key 才能使用。本地服务账号不受影响，更适合用于 CI。

只向 hosted push；即使调用者已认证，proxy 和 group 仍是读取入口。


Browse 中复制的镜像引用只包含 registry 主机、可选端口、仓库路由路径、镜像名以及 tag 或 digest。
`docker pull`、`docker tag` 和 `docker push` 参数不应包含 `http://`、`https://` 或 Registry API
前缀 `/v2/`。全局搜索从共享数据库读取当前 Docker tag，已有镜像无需重新推送；仓库权限和
内容选择器权限仍然生效。支持按镜像名、tag 或 manifest digest 搜索，删除 tag 后搜索结果同步更新。

## 仓库行为

- Hosted 支持 blob upload session、manifest、tag、cross-repository blob mount 和 OCI referrer。
- Blob 按 content address 安全共享，repository-level reference 仍是鉴权与生命周期状态的事实
  来源。
- Proxy 缓存上游 manifest/blob，并保持 digest 校验。
- Group 按成员顺序解析 manifest，并让后续 blob read 绑定选中的同一来源。
- Browse 与 Search 展示 manifest、tag、media type、platform、blob 和 referrer metadata。

## 清理与安全

管理员可以在 Browse 中选中 tag 或 manifest digest 并删除，每次只移除选中的引用。
删除 digest 条目会保留已有 tag 及其可下载的 manifest 内容，与 Nexus 管理界面的资产删除行为一致。
没有 tag 或 digest 条目引用内容后才释放内容，共享 blob 仍受保护；重新推送会恢复 digest 条目。
Registry V2 的 digest 删除仍会移除 manifest 及其全部标签。Docker 镜像和命名空间目录
不是删除目标，API 会拒绝目录路径。从 group 发起删除时只影响选中的成员；删除 proxy
内容只清理本地缓存，后续仍可从上游重新获取。Browse 管理删除遵循门户的管理员权限规则，
独立于 Registry V2 写入策略。

删除和 cleanup 会区分 tag、manifest 与共享 blob reference，不能直接从 blob storage 删除
对象。先执行 cleanup preview，并由引用计数判断 blob 何时不再被引用。安全扫描应面向已提交
manifest 与 layer 集合，而不是未完成的 upload session。

## 排障

先运行 `curl -I https://<host>/v2/` 检查认证 challenge。私有 registry 登录前返回 `401`
是正常行为；登录后持续 `401` 通常表示广告的 realm/service 与客户端可见 host 不一致。
反向代理后的 push 失败常见于 request body 限制、timeout 或 upload-session location 转发错误。

Docker proxy 会在获取或刷新 bearer token 前释放上游 401 challenge 占用的连接，token realm
与 registry 同主机时也适用。每个副本独立维护可重建的出站连接池。等待连接池分配连接的默认
超时为 10 秒，可通过 `KKREPO_OUTBOUND_PROXY_CONNECTION_REQUEST_TIMEOUT_MS`（毫秒）调整。
该配置适用于所有直连和经过出站代理的 HTTP 请求，与 TCP 建连超时及响应/下载超时相互独立。

### 慢对象存储与上传事务

Docker 分块接收、对象存储读写和摘要校验在数据库事务外执行。短事务读取共享的上传偏移量和分块序号，发布前再次锁住会话行核对两者；blob 元数据、可选的最后一个分块和会话完成状态原子提交。慢 S3/OSS 传输因此不会在整个传输期间占用数据库连接。

各副本通过共享会话和不可变分块对象协调。复用 blob 时，已校验数据会暂存到本地，并在发布事务内重新查询、锁住有效 blob 行；若原记录已被删除或 GC 回收，则回滚这次发布，在事务外写入新对象后重新提交。本地暂存可丢弃，节点故障后仍可从共享上传分块重试。并发追加会使旧操作读取的偏移量或序号失效，取消或过期会阻止迟到请求发布。追加竞争失败的请求返回 `BLOB_UPLOAD_INVALID`（416），客户端可查询最新上传状态后续传。数据库事务重试复用已校验的数据，不重复传输。失败操作会尝试清理自己尚未发布的对象；提交确认丢失时先核对持久化引用，避免误删。此机制不修改 Hikari 泄漏检测阈值，也不取代进程或存储故障后的孤立对象核对清理。

## 相关文档

- [Docker / OCI 客户端配置示例](../client-recipes.md#docker--oci)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [OCI Distribution Specification](https://github.com/opencontainers/distribution-spec/blob/main/spec.md)
