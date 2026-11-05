# OHPM Hosted HAR/HSP

`ohpm-hosted` 当前支持普通和流式 HAR/HSP 发布、metadata、下载、dist-tags 和下架。协议行为依据华为官方文档与 OHPM 6.0.1，参见 [协议基线](ohpm-protocol-baseline.md)。

## 使用

通过仓库管理 API 创建 `recipe=ohpm-hosted` 的 hosted 仓库并分配 blob store。使用现有 Token 管理生成 GenericToken，按仓库权限授予 READ、ADD、EDIT、DELETE。首次发布要求 ADD；为已有包增加版本还要求 EDIT，保护共享 metadata 和 tag。Ping 公开；是否允许匿名读取由现有匿名用户权限决定。

```bash
ohpm config set registry https://repo.example.com/repository/ohpm-hosted/
ohpm config set publish_registry https://repo.example.com/repository/ohpm-hosted/
ohpm config set //repo.example.com/repository/ohpm-hosted/:_auth <token>
ohpm publish ./example.har
ohpm install
ohpm dist-tags add @scope/example@1.0.0 beta
ohpm unpublish @scope/example@1.0.0
```

版本不可覆盖。下架会移除指向被删除版本的 tag；需要新的默认版本时，应显式设置 `latest`。删除后可以重新发布同一版本。

普通发布的 base64 附件直接解码到请求临时文件，并在进入持久化事务前验证名称、版本、附件数量、长度和 SHA-512。默认每个文件最多 1 GiB，可通过 `kkrepo.ohpm.max-file-bytes` 调整；还受全局上传限制约束。metadata 有独立的累计字符数、token 数和嵌套深度限制。请求失败和正常结束均清理临时文件。

流式发布要求 multipart 恰好包含 `metadata` 和 `pkg_stream` 两个字段。HAR stream 是 HAR 原始字节；HSP stream 是包含 interface HAR 和 HSP 的 gzip/tar。两个附件都校验成功后才进入统一发布事务，HSP 下载分别使用 `dist.tarball`、`dist.resolved_hsp` 及各自的完整性值。客户端提交的文件名和本地路径不参与生成暂存文件路径。

multipart metadata 在 JSON 限制之外还受 8 MiB 原始字节上限约束。HSP 归档最多包含 32 个 header，扩展 header 最多 16 KiB，只允许两个普通文件，每个文件受配置的大小上限约束。压缩及展开总量分别不得超过文件上限的两倍加 1 MiB。支持安全目录和 PAX 长路径；拒绝路径穿越、链接、稀疏文件、重复/多余文件、校验损坏及尾随归档。Servlet multipart 的内存阈值为零，业务暂存使用固定大小缓冲区，并在成功或失败后清理临时文件；全局 multipart 大小限制仍然生效。

仅发布时数据库临时错误重试耗尽返回 `598`；非预期存储故障返回 `500`，输入错误返回 `400`，大小超限返回 `413`。实测 OHPM 6.0.1 普通上传不会自动重试 `598`；回滚后可显式重试，已经提交的版本仍禁止覆盖。客户端请求契约测试继续通过录制服务验证 stream `404` 回退。

另已提供 [Proxy](ohpm-proxy.md) 和 [Group 仓库](ohpm-group.md)。Login、管理端文件上传和独立导入尚未提供。安全扫描目前将 OHPM 归为不适用；自动清理暂不开放，避免通用删除绕过 package metadata 更新。协议写操作已经接入审计与请求指标。

## 一致性与存储

- package root 是无 blob 的 metadata asset，attributes 保存物化 metadata。每个版本有一个 component 和 HAR asset，HSP 发布另外包含一个 HSP asset；制品字节存储在所配置的 blob store。
- 发布、tag 和下架以 package root 的数据库行锁串行化；通过自然路径唯一约束处理首次创建竞争。锁定查询直接按路径读取当前行，避免 MySQL 可重复读事务中的旧快照遗漏并发发布。
- 版本 component、文件 asset、blob 引用和 package metadata 在同一事务内提交。整包下架保留空 metadata 行作为锁身份；读取空包返回 `404`，再次发布复用该锁身份。
- metadata 与下载直接读取共享数据库，不依赖节点本地 metadata 缓存。客户端下载地址按当前请求的外部地址生成，沿用项目的可信转发头策略。
- 上传后外层事务失败时，通用文件写入器检查持久化引用再补偿清理；复用的 blob 不会被当成新上传删除。事务重试重新打开已验证的请求临时文件。

MySQL 与 PostgreSQL 均有数据库锁契约测试以及两个应用实例共享数据库、文件测试存储的集成测试。生产部署继续使用 OSS/S3；本地测试存储不代表已完成云存储故障注入验收。

## 验证

基础测试：

```bash
mvn -pl server -am \
  -Dtest='OhpmPublishParserTest,OhpmStreamParserTest,OhpmProtocolHandlerTest,OhpmAccessTest,RawAssetWriterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

实际服务测试要求 Docker。附加客户端开关后，对两种数据库的运行中 kkrepo 实例执行真实 OHPM 命令：

```bash
mvn -pl server -am -Dtest=OhpmHostedRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

客户端不在 PATH 时加 `-Dkkrepo.ohpm.command=/absolute/path/to/ohpm`。显式启用客户端后，客户端缺失或版本不符会使测试失败。测试使用隔离的 registry、Token、客户端配置和缓存。

HTTP 集成测试覆盖跨实例多版本并发、重复版本竞争、tag/下架竞争、下载与条件请求、整包下架后重建、HAR/HSP 类型切换，以及数据库回滚对新 blob 和复用 blob 的不同处理，包括第二个 HSP 附件写入后失败。真实客户端覆盖普通和流式 HAR/HSP 发布、HSP 双文件逐字节下载验证、HAR 安装、tag 操作及版本/整包下架；通过审计断言大 HAR/HSP 确实成功使用 stream 接口。解析器测试覆盖请求中断、暂存失败、归档边界与清理，并用动态生成的 64 MiB HAR 验证固定大小的读取缓冲区。历史客户端和 HSP 设备运行尚未验证。
