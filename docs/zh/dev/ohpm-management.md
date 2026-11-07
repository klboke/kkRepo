# OHPM 管理与浏览

Admin 使用标准仓库编辑器管理 `ohpm-hosted`、`ohpm-proxy` 和 `ohpm-group`。Hosted 版本不可覆盖；Proxy 支持上游 URL、原始 Authorization 值、缓存有效期与重定向白名单；Group 仅接受 OHPM hosted/proxy 成员，成员顺序决定版本和 tag 的优先级。参见 [Hosted](ohpm-hosted.md)、[Proxy](ohpm-proxy.md) 和 [Group](ohpm-group.md)。

## 上传

Browse 上传页接受 HAR 与可选的配对 HSP，或一个完整的 HSP tgz/tar.gz 包，另可指定分发 tag（默认 `latest`）。包名和版本来自 HAR 内的 `package/oh-package.json5`。`packageType: "InterfaceHar"` 必须提供 HSP，普通 HAR 则不能附带 HSP。完整 HSP 包复用官方 stream 发布的有界双附件解包器，不允许再附带额外的 HSP 字段。

相同操作可通过 API 完成：

```bash
curl --fail-with-body \
  -H "Authorization: Bearer ${KKREPO_TOKEN}" \
  -F 'ohpm.asset=@example.har' \
  -F 'ohpm.tag=beta' \
  'https://repo.example.com/service/rest/v1/components?repository=ohpm-hosted'
```

Interface HAR 另加 `-F 'ohpm.hsp=@example.hsp'`；完整 HSP 包只需 `-F 'ohpm.asset=@example.tgz'`。管理 API 使用标准 kkRepo 认证（Bearer Token 或已登录的 UI session）；OHPM 协议接口使用原始 Authorization。

服务端支持 JSON5 注释、单引号、尾随逗号、十六进制数和字符串续行，拒绝重复字段、尾随文档和非有限数。manifest 上限为 2 MiB、64 层嵌套及 100,000 个值。单个归档受配置的上传大小限制，HAR 解压总量不得超过该限制的四倍加 1 MiB；tar header 最多 100,000 个，扩展 header 最多 16 KiB。拒绝危险路径、链接、稀疏文件、重复 manifest、损坏归档及尾随非填充数据。

归档暂存到临时文件，对原始字节计算 SHA-512，然后进入与 OHPM 协议相同的包事务。HAR/HSP 双附件一起提交或回滚，成功和失败均回收暂存文件。生成的 metadata 将 `_ohpmVersion` 设为已验证的协议基线 `6.0.1`，使该客户端接受自定义仓库；这个字段不表示实际使用了该版本 CLI 上传。

归档处理前先认证。解析出坐标后，在发布事务内按真实包路径检查 ADD；已有包还需 EDIT。内容选择器可以使用 `format == 'ohpm'` 与规范化包路径。拒绝上传不会留下包、component 或 blob 引用。管理上传写入含仓库及包标识的审计记录。

## 浏览、搜索和删除

Browse 展示已存储的包、版本、文件、校验和、来源仓库、tag 和 manifest 属性，搜索结果链接到版本目录。Proxy/Group 浏览展示持久化内容，不枚举上游所有包。仓库列表和搜索沿用已有 catalog 刷新及广播机制，协议读写直接读取共享持久化状态。

Group 下载链接包含协议要求的来源指纹。Browse 根据成员已持久化的 metadata 生成链接，不为浏览操作访问上游。如果 metadata、优先成员或文件变化，协议以 409 拒绝旧链接，避免返回另一个文件。通过 Group 刷新包 metadata 可获取当前选择结果。

管理员可通过 Browse 或 `DELETE /internal/browse/<repository>?path=<browse-path>` 删除 hosted 包、版本或归档。删除任一 HSP 附件会下架整个版本，删除 HAR/HSP 并清理指向该版本的 tag；删除包会下架全部版本。拒绝删除任意 scope/目录，确认框说明实际删除范围。Group 管理操作可指定 hosted 来源成员。Proxy 内容不能在本地下架，应使用缓存失效操作。

管理删除沿用 `nexus:*` 权限要求；协议下架仍使用仓库 DELETE 权限。通用 Nexus asset-ID DELETE 对 OHPM 保持 405，不能绕过 OHPM 事务。

## 公共能力与边界

- 路由规则在 proxy 读取和 group 成员选择前匹配已解码的规范化路径，编码后的 scope 分隔符不能绕过规则。
- 下载复用统一下载策略和使用量跟踪。OHPM 安全扫描状态为 `NOT_APPLICABLE`，不表示已扫描 HAR/HSP 漏洞。
- OHPM 自动清理与通用目录删除保持禁用；支持显式、协议感知的下架。
- [独立导入](ohpm-import.md) 支持目录与已有仓库来源及断点恢复；OHPM 不是 Nexus 迁移格式。[可选签名 Login](ohpm-login.md) 默认关闭，提供独立公钥管理页面。
- 协议外部链接沿用可信转发头策略，Browse 链接使用仓库相对路径。

## 验证

```bash
mvn -pl server -am \
  -Dtest='Ohpm*Test,ComponentUploadServiceTest,ComponentUploadErrorAdviceTest,ManagementAuditFilterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
node --test browse-ui/src/test/js/browse-ohpm-management.test.js
```

运行时测试分别在 MySQL、PostgreSQL 上启动两个实例，覆盖管理 HAR/HSP 上传、协议 metadata/下载、Browse 链接与属性、搜索、Group 创建和编辑、OHPM 6.0.1 安装、版本/整包删除、选择器限定发布、跨实例权限变更及审计。归档与 UI 测试覆盖异常输入、大小限制、暂存回收、双文件表单和删除确认。
