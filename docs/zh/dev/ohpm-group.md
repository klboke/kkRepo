# OHPM Group

文件 GET/HEAD 选择只读取当前成员的目标版本 manifest，校验同一 HAR/HSP 对及来源指纹，不再重建完整包历史。Proxy 成员仍检查持久化 TTL 和失效状态，必要时刷新过期 metadata。每次重新解析仓库成员和在线状态，不使用进程内选择缓存。

`ohpm-group` 按配置顺序组合 OHPM hosted 和 proxy 成员，只提供读取能力。客户端使用与 hosted/proxy 相同的原始 Token 配置，并校验 group 仓库的 READ 权限。

通过现有仓库管理 API 创建：

```json
{
  "name": "ohpm-all",
  "recipe": "ohpm-group",
  "online": true,
  "group": {
    "memberNames": ["ohpm-private", "ohpm-public"]
  }
}
```

成员必须是 OHPM hosted 或 proxy，拒绝嵌套 group、其他格式和自身。每次 group 请求直接读取共享数据库中的成员及顺序。

```bash
ohpm config set registry https://repo.example.com/repository/ohpm-all/
ohpm config set //repo.example.com/repository/ohpm-all/:_auth <token>
ohpm install
```

## 合并与下载行为

- versions、dist-tags 分别以第一个成员优先。版本时间来自提供该版本的成员；有效的 `time.created` 取成员最早创建时间，`time.modified` 取最晚修改时间。
- HSP 类型归入各自的版本，不让某个成员的根级 HSP 声明影响其他成员的 HAR 版本。
- 每个版本只选定一个来源，HAR/HSP 两个文件使用同一成员。前面的 HAR-only 版本不会从后面的成员补入 HSP。选定成员缺少文件时直接失败，不回退到其他成员的不同内容。
- 下载 URL 包含 `kkrepo_source`，绑定 group、成员、包版本和两个文件的完整性值。同一 HSP 的两个文件共享指纹；使用完整 URL，并保留查询参数。
- 缺少或格式错误的指纹返回 `400`；来源或完整性值变化返回 `409`。需要刷新包 metadata，并按客户端工作流更新锁文件中的下载地址后重试。该指纹不是凭据：每次请求仍进行仓库鉴权并重新计算成员选择。
- 打开响应体前，还会将实际 blob 校验和与选定 metadata 比较，避免选择完成后发生并发替换而返回不同发布的内容。

Group metadata 使用内容 ETag 和 `Cache-Control: private, no-cache`，不发送 Last-Modified。成员发布时间不能反映 group 成员或顺序变化，因此不能据此返回 `304`。ETag 未变时支持 `304`；仅提供时间条件的请求返回当前 metadata。

## 成员故障与一致性

成员对该包返回 `404`、`410` 时继续合并其他成员；成员离线返回 `503`，包括 `451` 在内的其他成员错误使 group 请求失败，不因故障静默改变优先级。Proxy 成员仍可按其自身策略返回有效 stale 快照。

Group 直接读取最新仓库及成员记录，文件由选定的 hosted/proxy 服务读取，不增加节点本地选择缓存或新表。继续执行来源文件的下载策略，并将 group 作为请求入口仓库。

## 验证

纯合并及服务测试覆盖冲突、HSP 归一化、时间、指纹、成员失败、缺失文件、选定后内容变化和条件请求：

```bash
mvn -pl server -am -Dtest='OhpmGroup*Test,OhpmProtocolHandlerTest,RepositoryRecipesTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`OhpmProxyRuntimeTest` 同时验证由 hosted 和认证 proxy 组成的 group。MySQL、PostgreSQL 分别启动两个 kkRepo 实例，覆盖成员类型拒绝、共享配置改序、HAR/HSP 选择、缺失文件不回退、离线成员、只读操作、查询参数保留及真实 OHPM 安装：

```bash
mvn -pl server -am -Dtest=OhpmProxyRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

测试使用生成的协议夹具和文件测试存储。历史客户端及 S3 兼容存储的覆盖与限制见[独立验收说明](ohpm-acceptance.md)。HSP 设备执行尚未验证。
