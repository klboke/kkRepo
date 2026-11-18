# OHPM 独立验收与 CI

`OhpmRepositoryBlackBoxCompatibilityTest` 通过 HTTP 访问独立启动的 kkRepo，不引用 server 服务或直接访问数据库。协议参考为华为官方接口及真实 OHPM 客户端，测试不需要 Nexus。

## 事务与下载回归

`OhpmHostedRuntimeTest` 在真实文件存储适配器的 `putFile` 内暂停上传，断言此时没有数据库事务，并通过另一连接获取已有包锁；导入场景同时验证任务和逐项锁可获取。MySQL、PostgreSQL 均保留 HAR/HSP 原子回滚、事务重试和共享 blob 保护验证。

`OhpmProxyRuntimeTest` 向延迟响应的上游发起跨两个副本的 8 个并发冷缓存请求，要求仅 1 次上游文件 GET，8 次返回相同字节。首个持有者收到损坏内容时必须失败并释放租约，由一个后继下载满足其余 7 个请求。双数据库 DAO 契约覆盖租约竞争、过期接管和旧持有者拒绝；服务测试覆盖租约失效、源配置变化时禁止发布。

目标版本 DAO 契约写入 1,000 个版本，验证仅返回选定 manifest 和根级类型/cache 字段，包含带点及预发布版本键的安全参数绑定。Group 回归禁止反复下载热文件时调用完整成员 metadata，同时保留来源指纹和 HAR/HSP 选择校验。这些测试证明协调及数据访问行为，不代表生产吞吐、S3 延迟或分配量基准。

## 验收范围

| 用例 | 检查 |
| --- | --- |
| HAR/HSP × 普通/流式上传 | 未授权拒绝、重复版本拒绝、metadata、SHA-512、两个附件的实际字节、ETag、tag 和下架 |
| Proxy 与 Group | 上游认证、下载地址改写、双文件来源一致、成员顺序调整、禁止写入、上游下架后缓存读取 |
| 管理上传与导入 | HAR/HSP 管理上传、浏览/校验和信息、dry-run 不发布、执行与重复恢复、Group 下载 |
| 可选 Login | 校验配置状态；关闭时 404；开启时签名登录、防重放、管理 API 隔离及密钥禁用；客户端模式另使用加密私钥实际签名发布及安装 |
| 真实客户端 | 小/大 HAR/HSP 发布，经 hosted/proxy/group 安装，tag 变更及下架 |

每次客户端安装删除上次的模块目录和 lock 文件，不把已有安装目录当作下载成功的证据。下载测试另行比对 HAR/HSP 字节和 SHA-512。

实际 S3 兼容存储的第二附件故障注入、回滚及重试使用独立脚本，见 [S3 故障验收](ohpm-s3-acceptance.md)。HTTP/客户端黑盒使用 file 测试存储，不能代替该存储验收。

## 准备独立候选服务

需要 JDK 25、Maven 3.9+、Python 3，以及候选服务使用的 MySQL/PostgreSQL。真实客户端模式还需要已安装的 OHPM 与对应 Node.js。

```bash
mvn -pl server -am -DskipTests package spring-boot:repackage
```

使用生成的可执行 jar 启动临时服务，并配置数据库、存储及凭据加密密钥。`test` profile 仅用于临时验收环境。服务端须允许访问测试上游地址，例如设置 `KKREPO_OUTBOUND_ALLOWED_HOSTS=127.0.0.1`；批量创建测试身份时使用 `KKREPO_LOGIN_RATE_LIMIT_PER_MINUTE=200`，避免验收请求触发默认登录限流。

全新实例可用辅助脚本初始化管理员及 file blob store：

```bash
export KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090
export KKREPO_COMPAT_USERNAME=admin
export KKREPO_COMPAT_PASSWORD=<临时实例管理员口令>
export OHPM_COMPAT_BLOB_PATH=/absolute/path/to/disposable/blobs
python3 scripts/ci/setup-ohpm-candidate.py
```

脚本要求未初始化的临时实例，拒绝覆盖已有管理员设置。测试为每个用例创建独立用户和随机名称仓库；结束时下架 hosted 包并删除可删除的仓库和用户。当前通用仓库删除接口拒绝删除仍有缓存组件的 proxy，测试会明确记录保留的 proxy 名称；销毁临时数据库和 blob 存储完成清理。因此这些写测试只能指向可销毁的验收实例。

## 运行

不安装 OHPM 时运行 8 项 HTTP 黑盒验收：

```bash
export OHPM_COMPAT_LOGIN_ENABLED=true
bash scripts/ci/run-ohpm-compat.sh blackbox
```

`OHPM_COMPAT_LOGIN_ENABLED` 与服务端配置一致，显式设置可防止本来要验证签名登录却只执行了关闭分支。需要服务端 `kkrepo.ohpm.login.enabled=true` 才能启用。

运行含真实客户端的 9 项验收：

```bash
export OHPM_COMMAND=/absolute/path/to/ohpm
export OHPM_EXPECTED_VERSION=6.0.1
bash scripts/ci/run-ohpm-compat.sh client-e2e
```

两个命令均校验 Surefire XML 的用例数量、失败、错误和跳过数；要求运行的用例缺失或跳过会使命令失败。结果位于 `compat-test/target/surefire-reports/*OhpmRepositoryBlackBoxCompatibilityTest*`。也可使用统一入口 `bash scripts/ci/run-live-compat.sh ohpm` 或 `ohpm-client-e2e`。

当服务端不能直接访问 `KKREPO_COMPAT_BASE_URL`，用 `OHPM_COMPAT_UPSTREAM_BASE_URL` 指定从服务端访问候选实例的地址。例如客户端通过宿主机端口访问容器，而容器内上游应使用 `http://kkrepo:8080`。下载给客户端的链接仍须使用公开候选地址。

## 自动化与版本

Full E2E 已把 `ohpm` 纳入现有 `core` 客户端分片，覆盖 JVM/Native × MySQL/PostgreSQL。`live-compat.yml` 复用统一候选镜像产物、SDK 镜像、环境初始化和日志上传；通过 Compose override 将现有第二副本配置为 OHPM Login 开启，主副本保持关闭，两个副本使用同一候选镜像。仅 OHPM runner 遍历这两种设置，不把整个 Full E2E 矩阵翻倍。

`run-client-e2e.sh` 将 OHPM 分派到 `ohpm-client-e2e.sh`，后者要求提供两个候选地址，并先验证 Login 配置。每种设置的 Surefire XML、运行日志、清理断言保存在 `artifacts/client-e2e/ohpm/login-{false,true}/`。汇总的 `cleanup-ohpm.json` 要求两种设置都明确拒绝自动清理，并通过 OHPM 协议删除验证 HAR/HSP 与 hosted/group 元数据消失。完整性门禁会拒绝缺少 OHPM 耗时记录、缺少任一 Login 设置或清理断言不完整的报告。

定向执行可手动触发 `.github/workflows/ohpm-compat.yml`，它以 `client_protocol: ohpm` 调用同一个可复用工作流，覆盖两种 runtime；不再额外监听 PR，也不再按 Login 开关重复构建服务。本地可对使用同一产物、共享数据库与 blob store、已初始化的临时双实例运行：

```bash
export KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090
export OHPM_LOGIN_BASE_URL=http://127.0.0.1:18094
CLIENT_E2E_TESTS=ohpm bash scripts/ci/run-client-e2e.sh
```

`OhpmProxyRuntimeTest` 还会先通过 Docker 存储有效 HAR，再分别由 OHPM hosted 和 proxy 复用，验证两种数据库上的跨副本 Group 下载。共享 writer 在 DAO 事务锁保护的复用/插入路径内合并已验证的 SHA-512，保留 `dockerDigest` 和其他属性；未读取内容的 blob 链接操作不会生成虚假的校验和。

CI 使用华为云官方镜像的 OHPM 5.0.2 归档，固定 SHA-256：

```text
a91888b3c1623d29fd0e387eee4f5e9523cc8f6242de9cb4be3ef8c46eab7e4a
```

`setup-ohpm-legacy.sh` 校验摘要后仅提取 OHPM 子目录，拒绝覆盖已有安装目录：

```bash
OHPM_INSTALL_DIR=/absolute/path/to/new/toolchain bash scripts/ci/setup-ohpm-legacy.sh
```

Login 开启时的客户端验收包含真实加密私钥签名发布及安装，`OhpmHostedRuntimeTest` 另支持双实例客户端验收。每项结论只适用于实际执行的版本与 runtime；HSP 夹具验证仓库协议及文件传输，不代表已在 HarmonyOS 设备执行 HSP。Native 纳入 Full E2E 不等于 Native 已运行成功，应以对应流水线报告为准。这里描述的是构建复用结构，没有声称实测 CI 资源节省比例。
