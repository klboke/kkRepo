# OHPM 独立验收与 CI

`OhpmRepositoryBlackBoxCompatibilityTest` 通过 HTTP 访问独立启动的 kkRepo，不引用 server 服务或直接访问数据库。协议参考为华为官方接口及真实 OHPM 客户端，测试不需要 Nexus。

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

不安装 OHPM 时运行 7 项 HTTP 黑盒验收：

```bash
export OHPM_COMPAT_LOGIN_ENABLED=true
bash scripts/ci/run-ohpm-compat.sh blackbox
```

`OHPM_COMPAT_LOGIN_ENABLED` 与服务端配置一致，显式设置可防止本来要验证签名登录却只执行了关闭分支。需要服务端 `kkrepo.ohpm.login.enabled=true` 才能启用。

运行含真实客户端的 8 项验收：

```bash
export OHPM_COMMAND=/absolute/path/to/ohpm
export OHPM_EXPECTED_VERSION=6.0.1
bash scripts/ci/run-ohpm-compat.sh client-e2e
```

两个命令均校验 Surefire XML 的用例数量、失败、错误和跳过数；要求运行的用例缺失或跳过会使命令失败。结果位于 `compat-test/target/surefire-reports/*OhpmRepositoryBlackBoxCompatibilityTest*`。也可使用统一入口 `bash scripts/ci/run-live-compat.sh ohpm` 或 `ohpm-client-e2e`。

当服务端不能直接访问 `KKREPO_COMPAT_BASE_URL`，用 `OHPM_COMPAT_UPSTREAM_BASE_URL` 指定从服务端访问候选实例的地址。例如客户端通过宿主机端口访问容器，而容器内上游应使用 `http://kkrepo:8080`。下载给客户端的链接仍须使用公开候选地址。

## 自动化与版本

`.github/workflows/ohpm-compat.yml` 在相关 PR 变更和手动触发时运行 MySQL/PostgreSQL × Login 关闭/开启矩阵。每个任务构建并启动独立可执行 jar，初始化临时实例，运行上述完整客户端验收并保存报告。

CI 使用华为云官方镜像的 OHPM 5.0.2 归档，固定 SHA-256：

```text
a91888b3c1623d29fd0e387eee4f5e9523cc8f6242de9cb4be3ef8c46eab7e4a
```

`setup-ohpm-legacy.sh` 校验摘要后仅提取 OHPM 子目录，拒绝覆盖已有安装目录：

```bash
OHPM_INSTALL_DIR=/absolute/path/to/new/toolchain bash scripts/ci/setup-ohpm-legacy.sh
```

最终本地独立服务已验证 OHPM 6.0.1（PostgreSQL、Login 开启）以及 OHPM 5.0.2 的 MySQL/PostgreSQL × Login 关闭/开启四组合，共五组、每组 8 项且无跳过。Login 开启时的客户端验收包含真实加密私钥签名发布及安装，6.0.1 的双实例场景另由 `OhpmHostedRuntimeTest` 覆盖。每项结论只适用于实际运行的版本；HSP 夹具验证仓库协议及文件传输，不代表已在 HarmonyOS 设备执行 HSP。上游 Actions 的运行需要维护者批准，本地验收通过不表示远端 CI 已执行。
