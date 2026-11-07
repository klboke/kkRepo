# OHPM Proxy

文件冷缓存通过 V63 的 `ohpm_fetch_lease` 在多副本间协调，键包含仓库、路径、源身份、下载完整性值及持久化 content generation。租约有效期 30 秒并自动续约，获取最多等待 60 秒；过期可接管，发布事务内校验 owner 和 fencing token，拒绝旧持有者写入。等待者获得租约后重新检查共享缓存，失败会释放租约。content TTL 为零时仍按每次请求重新验证。

上游传输、摘要校验和对象上传均在 metadata 事务外完成；仅发布 metadata 和引用时持有包锁。文件热读通过数据库 JSON 查询仅取目标版本 manifest，避免在 JVM 中序列化、解析完整历史；metadata 过期时仍走原有刷新流程。不引入进程内包缓存。

`ohpm-proxy` 提供上游 OHPM metadata 代理和经过完整性校验的 HAR/HSP 下载。仓库只读：通过仓库鉴权后，发布、流式发布、tag 修改和下架均返回 `405`。

## 配置

通过现有仓库管理 API 创建仓库。以下公共 OpenHarmony 仓库配置包含 OHPM 6.0.1 验收时实际使用的 CDN：

```json
{
  "name": "ohpm-public",
  "recipe": "ohpm-proxy",
  "online": true,
  "blobStoreName": "default",
  "proxy": {
    "remoteUrl": "https://ohpm.openharmony.cn/ohpm",
    "contentMaxAgeMinutes": 60,
    "metadataMaxAgeMinutes": 5,
    "autoBlock": true,
    "allowedRedirectHosts": ["contentcenter-drcn.dbankcdn.cn"]
  }
}
```

公共仓库的文件 GET 会跳转到 CDN，需要在 `allowedRedirectHosts` 中配置实际下载域名。共享出站策略继续校验目标地址和重定向；跨源请求不携带上游凭据。

私有 OHPM 上游通过 `proxy.remoteBearerToken` 配置 Token，沿用现有远端凭据加密存储。对于 OHPM，该字段生成协议规定的原始 `Authorization` 值，不添加 Bearer 前缀。客户端访问 kkRepo 的凭据单独配置，并校验仓库 READ 权限。

```bash
ohpm config set registry https://repo.example.com/repository/ohpm-public/
ohpm config set //repo.example.com/repository/ohpm-public/:_auth <token>
ohpm install @ohos/axios@2.2.15
```

## 缓存与故障行为

- metadata 保存在无 blob 的 package root asset 属性中。文件字节保存在配置的 blob store；HAR/HSP asset 共享同一包版本的 component。读取直接查询共享数据库，不依赖节点本地包状态。
- metadata/content 分别使用 TTL 和持久化失效水位。TTL 为零时每次重新验证；负值沿用现有缓存约定，不按时间过期。仅对来源匹配的缓存发送 ETag、Last-Modified validator。
- 来源标识包含上游 URL、认证和重定向策略；文件额外绑定完整远端下载 URL 与预期 SHA-512。上游配置或完整性值变化后，不复用错误来源的内容。
- `404`、`410`、`451` 保留对应状态，并使用共享 negative cache 抽象。沿用 `kkrepo.proxy.negative-cache.enabled` 和 `kkrepo.proxy.negative-cache.ttl-minutes`。持久化删除标记保证 negative cache 过期后，旧 metadata 或文件不会在后续故障中重新作为 stale 数据返回。
- 传输失败和上游 `5xx` 可返回已存在且来源匹配的缓存，否则返回 `502`。auto-block 使用现有共享代理状态和有上限的退避。上游认证失败、metadata 无效、URL 不被允许及校验和错误返回 `502`，该请求不使用 stale 回退。
- metadata 刷新在行锁内比较旧快照后才替换；文件入库前同时检查当前 metadata、来源及缓存水位。较慢的请求不能覆盖更新的缓存或删除标记。

metadata 最多 16 MiB，并限制 JSON 深度、字符串和 token 数。下载使用固定缓冲区和请求临时文件，沿用 `kkrepo.ohpm.max-file-bytes` 的单文件上限，默认 1 GiB。SHA-512 校验成功后才能成为可见的持久化缓存；成功和失败均清理暂存文件。

`dist.tarball`、`dist.resolved_hsp` 均重写为 kkRepo 下载地址。远端文件名不必与本地 scoped package 路径一致。公开 metadata 不暴露上游签名下载 URL 的查询参数，component metadata 保存本地路径。

## 验证

定向测试：

```bash
mvn -pl server -am \
  -Dtest='OhpmPackageMetadataTest,OhpmProxyServiceTest,OhpmProtocolHandlerTest,HttpRemoteFetcherTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

运行时测试分别启动 MySQL、PostgreSQL，每种数据库由两个 kkRepo 实例共享数据库和文件测试存储。受控认证 HTTP 上游覆盖 metadata 复用、条件刷新、并发下载、HAR/HSP component 关联、失效、损坏拒绝与只读行为。启用真实 OHPM 对受控上游进行安装：

```bash
mvn -pl server -am -Dtest=OhpmProxyRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

追加 `-Dkkrepo.ohpm.publicProxy=true` 可通过公共上游安装 `@ohos/axios@2.2.15`，并断言 kkRepo 已持久化下载的 HAR。该检查需要公网和真实客户端，不属于常规离线 CI。客户端不在 PATH 时设置 `-Dkkrepo.ohpm.command=/absolute/path/to/ohpm`。

上述运行时测试使用文件测试存储。历史客户端及 S3 兼容存储的覆盖与限制见[独立验收说明](ohpm-acceptance.md)。HSP 设备运行尚未验证。已提供 [Group 仓库](ohpm-group.md) 和 [独立导入](ohpm-import.md)。
