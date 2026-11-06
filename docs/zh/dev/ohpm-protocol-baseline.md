# OHPM 协议兼容性基线

本文记录 `protocol-ohpm` 的协议依据与客户端请求测试。这些测试中的 HTTP 服务只负责录制请求和返回测试响应。实际 `ohpm-hosted` HAR/HSP 服务、流式上传及独立运行时验证见 [OHPM Hosted HAR/HSP](ohpm-hosted.md)，不要将请求录制测试作为服务端验证结果。

## 协议来源

- [官方当前接口协议](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-interface-protocol)，页面更新时间为 2026-10-10。
- [官方 V5 接口协议](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-interface-protocol-V5)，原设计引用的版本，页面更新时间为 2025-01-17。
- DevEco Studio 随附的 OHPM 6.0.1，通过真实命令运行确认请求行为。

协议模型独立于 npm。名称校验采用 OHPM 6.0.1 的限制：名称总长不超过 128 个字符，使用小写字母；包名允许内部数字、点、下划线和连字符，scope 不允许点；首字符为字母，末字符为字母或数字。

## 已确认的差异

| 项目 | 实现依据 |
| --- | --- |
| Token 配置 | 客户端使用 `//host/repository/name/:_auth`，只读 Token 使用 `:_read_auth`；传输时为裸 `Authorization` 值，不使用 npm 的 `:_authToken` 配置键。 |
| Ping | 官方协议要求无需认证；私有包 metadata 和下载仍必须遵守仓库 READ 权限。 |
| Scoped package | 接受 `@scope/name`、`@scope%2fname` 和客户端 dist-tags 使用的 `%40scope%2Fname`。只解码一次，拒绝歧义路径。 |
| 下载路径 | 文件后缀可以是 `@scope/name-1.0.0.har` 或 `.hsp`，必须保留 `/-/` 后面的完整路径。 |
| 发布 tag | `ohpm publish --tag beta` 的请求中可以只有 `beta`，不能强制要求请求携带 `latest`。 |
| DistTags | POST 新增、PUT 更新、DELETE 删除。新增/更新 body 为 `{"version":"1.2.3"}`；查询通过 metadata 完成。 |
| 下架版本 | 当前协议和 6.0.1 在 DELETE body 中发送 `{"version":"1.2.3"}`。旧 V5 文档使用 URL 的 `@version` 后缀，两种格式均需支持。 |
| 整包下架 | 6.0.1 要求 CLI 使用 `--force`，发送 `{"version":""}`。服务端仍须检查 DELETE 权限；无效 body 或版本冲突必须拒绝，不能转换成整包删除。 |
| HSP metadata | `packageType=InterfaceHar`、`hspType=bundle_app`；`hspType` 可出现在请求根部，发布时 `resolved_hsp` 尚未生成，不能要求客户端提交。 |
| 流式上传 | 默认超过 5 MB 时 POST `stream/:package`；multipart 字段为 `metadata` 和 `pkg_stream`，不是 npm 附件 JSON。 |
| 404 回退 | 6.0.1 对流式接口返回的 404 回退为普通 PUT，HAR/HSP 均已纳入客户端测试。 |
| 598 重试 | 官方文档描述 5.0.1+ 自动重试，但本次实测 6.0.1 普通上传收到 598 后直接失败，并展示错误。测试固定这一观察及显式重试；不能把自动重试当成服务端正确性的前提。 |
| 可选 Login | 6.0.1 先尝试 `login_pss`，遇到 `404` 后回退经典 `login`。后者以 RSA-SHA256 签名 UTF-8 的 `v1-<publishId>-<timestamp>-<nonce>`，返回的 Token 用于发布请求。详见[签名登录](ohpm-login.md)。 |

新版本文档还描述精简 metadata、`login_pss`、Versions 和 CheckUpdate。这些不属于原设计列出的基础接口，不能仅凭已有协议模型宣称全部新版本 API 已支持；后续客户端矩阵扩展时应分别核对。

## 客户端测试矩阵

`OhpmClientContractTest` 固定客户端版本，连接临时 loopback HTTP 服务，不向公共仓库发布。

| 客户端 | 夹具/操作 | 检查内容 |
| --- | --- | --- |
| 6.0.1 | 小 HAR / 小 HSP | PUT body、scope 编码、Token、tag、附件字节数和 SHA-512 |
| 6.0.1 | 大 HAR / 大 HSP | POST multipart metadata 和文件原始字节；完整性模型与客户端一致 |
| 6.0.1 | 大 HAR / 大 HSP + stream 404 | 回退到 PUT 后的附件内容 |
| 6.0.1 | Ping、tag 新增/更新/删除、版本/整包下架 | HTTP 方法、路径、JSON body |
| 6.0.1 | 发布并安装 HAR | metadata 查询、下载携带 Token、完整性校验及实际解包 |
| 6.0.1 | 普通发布返回 598 | 命令失败且展示 598；显式重试发送相同版本和内容 |
| V5 文档格式 | 旧版 `@version` 下架路径 | Java 协议测试覆盖；尚未声明旧客户端执行结果 |

当前实际执行版本为 6.0.1。客户端升级和历史版本回归需使用各自的版本参数重新运行，不把一个版本的结果外推到所有 OHPM 版本。

## 夹具与可重复性

`OhpmFixture` 在测试临时目录生成包，不提交第三方制品或客户工程：

- HAR 为 gzip/tar，包含 `package/oh-package.json5`、`Index.ets`，HSP 的 interface HAR 另有 `Index.d.ets`。
- HSP 为包含最小 `module.json` 的 ZIP；外层 tgz 包含 interface HAR 和 HSP。用于客户端协议验证，不用于设备运行验证。
- 大包增加固定随机种子生成的 6 MiB 内容，以避免 gzip 将包压缩到流式阈值以下。
- metadata 和 SHA-512 由真实 OHPM publish 产生，Java 模型解析后与生成文件的字节再次比较。
- 每次运行使用独立 `.ohpmrc`、缓存及临时端口，不修改用户的 registry 配置。

## 运行方式

JDK 25、Maven 3.9+；真实客户端测试还要求已按华为工具要求安装 OHPM 和对应 Node.js。签名发布测试需要 Python 3，以驱动客户端的交互口令提示。

协议单元测试：

```bash
mvn -pl protocol-ohpm -am test
```

真实客户端请求测试：

```bash
mvn -pl compat-test -am \
  -Dtest=OhpmClientContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true \
  -Dkkrepo.ohpm.expectedVersion=6.0.1 \
  test
```

客户端不在 PATH 时，加 `-Dkkrepo.ohpm.command=/absolute/path/to/ohpm`。未显式启用的普通 CI 会跳过客户端测试；显式启用后，客户端缺失、版本不匹配或命令失败都导致失败。

## 后续服务端验收

请求录制测试证明客户端与协议模型的契合，不证明真实仓库的持久化、权限、并发、缓存、导入或多副本行为。这些必须在后续 hosted/proxy/group 实现时，针对真实 kkrepo 实例补齐独立服务测试和黑盒测试。
