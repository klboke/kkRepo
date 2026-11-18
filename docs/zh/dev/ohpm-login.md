# OHPM 可选签名登录

OHPM 默认继续使用现有 Access Token。可选签名登录允许发布客户端用 RSA 私钥证明身份，由 kkRepo 签发短期 Token。协议依据为[华为官方接口说明](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-interface-protocol)及 OHPM 6.0.1 实际请求。

## 启用与公钥管理

所有服务副本使用相同配置，变更后重启相应副本：

```yaml
kkrepo:
  ohpm:
    login:
      enabled: true
      timestamp-tolerance-seconds: 300
      token-ttl-seconds: 300
```

`enabled` 默认 `false`。时间容差允许 1–600 秒，Token 期限允许 1–3600 秒，两者默认 300 秒；非法配置使启动失败。服务器时钟需要同步。

管理员在 **Admin → Security → Keys & Tokens → OHPM Public Keys**（`#admin/security/ohpm-login`）管理公钥。点击 **Register public key（登记公钥）** 打开弹窗，通过可搜索单选框选择已有用户的来源和 ID，粘贴 X.509 PEM 格式 RSA 公钥（2048–8192 位）。新登记公钥立即启用，每个用户只能绑定一个公钥。列表展示 publish ID、DER 编码公钥的 SHA-256 指纹及启停状态，并提供统一分页和每页条数选择。私钥保留在发布机器。

服务端签名登录关闭时，UI 显示未启用说明，禁用登记、轮换和启用按钮，仍可查看、禁用和删除已有公钥。开启后隐藏该说明。公钥自身的启用状态不能覆盖服务端配置。

公钥绑定用户而非仓库。认证后仍按该用户当前角色和权限判断仓库访问。如需将自动化限定到单个仓库，应使用仅具有所需权限的专用用户。该公钥不用于仓库或制品签名。普通发布与安装可使用 GenericToken，无需启用签名登录，参见[使用指南](../repository-guides/ohpm.md)。

选择 **Rotate** 替换已有公钥。轮换弹窗锁定所属用户，提交 **Rotate public key（轮换公钥）** 后，在数据库锁内保留 publish ID 和当前启停状态，包括打开表单后其它副本修改的状态。登记已绑定公钥的用户同样需要确认轮换并保留原状态；自动启用仅适用于新绑定。Enable/Disable 作为独立列表操作保留。轮换、禁用和删除前均需确认所属用户、指纹及旧签名登录 Token 失效的影响。删除后重新登记会生成新的 publish ID。

管理接口要求实时验证的 `nexus:*` 权限：

| 操作 | 接口 |
| --- | --- |
| 查看当前副本配置 | `GET /internal/ohpm/login` |
| 分页查看绑定，每页最多 100 项 | `GET /internal/ohpm/login/keys?offset=0` |
| 创建或轮换公钥 | `PUT /internal/ohpm/login/keys`，字段 `source`、`userId`、`publicKey`、`enabled` |
| 轮换并保留当前启停状态 | `PUT /internal/ohpm/login/keys/{publishId}/public-key`，字段 `publicKey` |
| 启停密钥 | `PUT /internal/ohpm/login/keys/{publishId}/enabled`，字段 `enabled` |
| 删除绑定 | `DELETE /internal/ohpm/login/keys/{publishId}` |

同一用户轮换公钥保留 publish ID；删除后重新创建会获得新的 publish ID。保存、启停和删除均撤销该用户已签发的 Login Token，不撤销其他域的 GenericToken。重新启用密钥不会恢复旧 Token。

## 发布客户端

在发布机器生成带口令的私钥并导出公钥：

```bash
openssl genpkey -algorithm RSA -aes-256-cbc \
  -pkeyopt rsa_keygen_bits:3072 -out ohpm-private.pem
openssl pkey -in ohpm-private.pem -pubout -out ohpm-public.pem
```

把公钥交给管理员绑定，在项目 `.ohpmrc` 配置：

```ini
registry=https://repo.example.com/repository/ohpm-hosted/
publish_registry=https://repo.example.com/repository/ohpm-hosted/
publish_id=<管理页面返回的 publish ID>
key_path=/absolute/path/to/ohpm-private.pem
```

执行 `ohpm publish library.har`，按客户端提示输入私钥口令。用于验证签名模式的配置中不要同时设置 registry `_auth`，否则客户端可能直接使用已有 Token。

OHPM 6.0.1 要求私钥加密；`.ohpmrc` 中的 `key_passphrase` 也必须经过客户端的加密组件处理，不能直接填入明文。交互输入无需配置该字段。自动化测试使用 Python 3 的标准 PTY 驱动真实客户端提示，私钥、口令和客户端配置均在临时目录中生成。

安装和下载仍遵循仓库读取权限。普通 CI 推荐使用现有 GenericToken；签名登录不会绕过包路径选择器或提供管理员权限。

## 协议与多副本语义

- 仅 online 的 hosted 仓库提供 `POST /repository/<repo>/login`。默认关闭时返回 `404`，proxy/group 不提供签名登录。
- 接收 `version: "v1"`、publishId、毫秒时间戳、32 位十六进制 nonce 和标准 Base64 签名。签名原文是 `v1-<publishId>-<timestamp>-<nonce>`，算法为 RSA-SHA256。
- 成功返回 `{"success":true,"token":"..."}`；失败返回 `success:false` 与 `error`，响应禁止缓存。
- `login_pss` 当前返回 `404`；OHPM 6.0.1 已验证会回退经典 Login。其他客户端版本须单独验证，不能据此宣称支持 PSS。
- V62 迁移在 MySQL/PostgreSQL 中创建 `ohpm_login_key` 与 `ohpm_login_nonce`。公钥、用户状态和 nonce 通过数据库锁及唯一约束协调，同一 nonce 在不同副本只能成功一次。nonce 按小写形式比较，保留至请求时间戳加容差再加一秒，后台按批清理到期记录。
- 密钥更新时间、nonce 期限与清理时间均以 UTC 写入和读取无时区字段。双库契约覆盖 UTC、上海和洛杉矶 JVM 时区切换，防止不同副本提前清理防重放记录。
- Token 使用现有 `api_key` 存储的 `OhpmLogin` 域，绑定 publish ID 与公钥指纹。期限按 UTC 判断；数据库日期时间读取保留字段值，避免连接时区与 JVM 时区不同造成期限偏移。
- 同一用户并发使用不同 nonce 登录时复用仍有效的 Token，过期后换发。防重放记录与签发在同一事务内提交。
- Login Token 仅可用于 OHPM 协议，不能认证管理 API 或其他协议。OHPM 请求实时读取期限、密钥和用户状态，不使用通用 API key 认证缓存；包操作另外实时检查原有权限。
- 关闭配置后，该副本拒绝 Login 和已签发的 Login Token；所有副本必须一起更新配置。GenericToken 行为不变。

## 验证入口

真实客户端契约及双数据库/双实例运行时：

```bash
mvn -pl compat-test -am -Dtest=OhpmClientContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
mvn -pl server -am -Dtest=OhpmHostedRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

需要 Docker、真实 OHPM 6.0.1、对应 Node.js 和 Python 3。OHPM 不在 PATH 时用 `-Dkkrepo.ohpm.command=/absolute/path/to/ohpm` 指定。签名用例覆盖客户端回退、真实发布后跨实例安装、nonce 竞争、期限、密钥禁用/轮换及用户禁用；独立黑盒及 Full E2E 覆盖见[验收说明](ohpm-acceptance.md)：既有 core 分片运行 JVM/Native × MySQL/PostgreSQL、OHPM 5.0.2 和 Login 关闭/开启两种状态，不为每种 Login 状态单独构建镜像。具体结果以每次运行报告为准。
