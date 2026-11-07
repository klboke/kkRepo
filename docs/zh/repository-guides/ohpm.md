# OHPM / HarmonyOS 仓库使用指南

kkRepo 支持用于 HAR/HSP 包的 OHPM hosted、proxy 和 group 仓库。OHPM 对齐华为仓库协议，是独立于 npm 的仓库格式；客户端地址沿用 `/repository/<name>/`。

## 创建仓库

在 **Admin → Repositories** 创建以下仓库，为 hosted/proxy 选择合适的 blob store：

| 示例名称 | Recipe | 用途 |
| --- | --- | --- |
| `ohpm-hosted` | `ohpm-hosted` | 发布私有 HAR/HSP 包；已有版本不可覆盖 |
| `ohpm-proxy` | `ohpm-proxy` | 缓存上游仓库，例如 `https://ohpm.openharmony.cn/ohpm` |
| `ohpm-all` | `ohpm-group` | 按成员顺序读取，例如先 hosted、再 proxy |

Proxy 和 group 拒绝发布、tag 修改及下架。Group 仅接受 OHPM hosted/proxy 成员，不支持嵌套 group。发布直接指向 hosted，安装依赖可统一使用 group。

公开上游下载需要将实际使用的 CDN 主机加入重定向白名单；基线验收中观察到的是 `contentcenter-drcn.dbankcdn.cn`。私有上游的凭据与客户端访问 kkRepo 的凭据分别配置。详见 [Proxy 配置与缓存行为](../dev/ohpm-proxy.md) 和 [Group 优先级与下载一致性](../dev/ohpm-group.md)。

## 认证与客户端使用

1. 创建或选择已有 Local、LDAP 或 OIDC 用户；CI 可以使用专用账号。
2. 通过角色为该用户分配仓库权限。下载需要 READ；首次发布需要 ADD；向已有包添加版本还需要 EDIT；下架需要 DELETE。为客户端安装所使用的仓库入口分配 READ；使用 group 时包含该 group。
3. 打开 **Admin → Security → Keys & Tokens → API Tokens → Create API key**，选择 `GenericToken`，选择来源和用户，并妥善保存返回的 Token。可搜索用户选择器需要读取用户的权限。API Key 使用所属用户的权限；已存储的 `scopes` 元数据不会单独限制 Token。
4. 按 [OHPM 客户端示例](../client-recipes.md#ohpm--harmonyos) 配置项目。OHPM 直接发送原始 Token 作为 `Authorization`，不使用 `Bearer <token>`。

```ini
registry=https://repo.example.com/repository/ohpm-all/
publish_registry=https://repo.example.com/repository/ohpm-hosted/
//repo.example.com/repository/ohpm-all/:_auth=<GenericToken>
//repo.example.com/repository/ohpm-hosted/:_auth=<GenericToken>
```

```bash
ohpm publish ./example.har
ohpm install @scope/example@1.0.0
ohpm dist-tags add @scope/example@1.0.0 beta
ohpm unpublish @scope/example@1.0.0
```

替换为实际归档和包坐标，生产环境使用 HTTPS，不要将 Token 提交到代码仓库。CLI 根据配置和包大小选择普通或流式发布；服务端在发布前校验 HAR/HSP 内容及 SHA-512。已有版本不可覆盖，显式下架后允许重新发布同一版本。HSP 双附件、大小限制和重试行为见 [Hosted HAR/HSP](../dev/ohpm-hosted.md)。

## 可选公钥登录

使用 GenericToken 即可完成发布与安装，无需登记公钥。需要 RSA 签名登录时，在所有副本设置 `kkrepo.ohpm.login.enabled=true` 并重启对应进程；默认关闭。

打开 **Admin → Security → Keys & Tokens → OHPM Public Keys → Register public key**（OHPM 公钥 → 登记公钥）。在弹窗中选择已有用户的来源和 ID，粘贴 X.509 PEM 格式 RSA 公钥（2048–8192 位）。新登记公钥立即启用；私钥始终保留在发布机器。替换公钥使用 **Rotate**，停止使用则选择 **Disable**。列表采用统一的分页和每页条数控件。

签名登录关闭时，UI 禁用登记、轮换和启用操作，仍可查看、禁用和删除已有公钥；开启后不再显示未启用提示。

公钥绑定的是**用户**，不绑定仓库。登录成功后签发短期 Token，仍按该用户当前的仓库权限授权。如需将自动化发布限定到单个仓库，使用仅拥有所需仓库权限的专用用户。这里的公钥不用于仓库元数据签名或包签名。每个用户只有一个公钥绑定；轮换保留 publish ID 和当前启停状态，并撤销旧签名登录 Token。

`publish_id`、`key_path` 配置见[签名登录指南](../dev/ohpm-login.md)。仅 hosted 提供签名登录；不支持 `login_pss`，已验证的 6.0.1 客户端会回退到经典 Login。

## 浏览、上传与导入

- Browse/Search 展示已存储包、版本、tag、校验和、来源及下载链接。
- UI/Components API 上传支持 HAR、interface HAR 加 HSP 双文件或完整 HSP 包；包/版本删除走 OHPM 协议感知的下架流程。详见[管理与浏览](../dev/ohpm-management.md)。
- **Admin → OHPM Import** 支持从目录或已有 OHPM 仓库导入，包含 dry-run、校验和验证、持久化进度和断点恢复。这是独立于 Nexus 迁移的功能，详见[导入说明](../dev/ohpm-import.md)。

## 能力边界与验证

- OHPM 不支持自动清理，使用显式包/版本下架。
- 安全扫描状态为 `NOT_APPLICABLE`，不代表已覆盖 HAR/HSP 漏洞扫描。
- 客户端契约基线是 OHPM 6.0.1。Full E2E 安装固定的 OHPM 5.0.2，在既有 core 分片的 JVM/Native × MySQL/PostgreSQL 矩阵中覆盖 hosted/proxy/group 发布与安装，以及签名登录关闭/开启两种状态。
- HSP 测试证明协议和文件完整性，不证明 HarmonyOS 设备上的执行结果，也不将结果外推到所有客户端版本。
- 执行命令、每次运行报告及独立的真实 S3 故障覆盖见[验收与 CI](../dev/ohpm-acceptance.md)和[兼容性矩阵](../compatibility-matrix.md)。
