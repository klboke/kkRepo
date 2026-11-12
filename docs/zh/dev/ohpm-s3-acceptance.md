# OHPM 实际 S3 存储故障验收

`scripts/ci/verify-ohpm-s3.py` 使用独立 RustFS 容器，对已运行的临时 kkRepo 候选服务执行验收。仅依赖 Python 3 标准库和 Docker，不执行 Maven 构建，不使用 file 存储替代 S3。另见[独立验收说明](ohpm-acceptance.md)。

## 运行

候选服务应已初始化，并允许访问故障网关地址。必须使用可销毁的数据库，因为正常 API 清理可能保留等待垃圾回收的 blob 记录。默认地址假设候选服务和 Python 位于同一宿主机。

```bash
docker pull docker.m.daocloud.io/rustfs/rustfs:1.0.0
export KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18094
export KKREPO_COMPAT_USERNAME=admin
read -rs -p "Disposable candidate administrator password: " KKREPO_COMPAT_PASSWORD
echo
export KKREPO_COMPAT_PASSWORD
python3 scripts/ci/verify-ohpm-s3.py
unset KKREPO_COMPAT_PASSWORD
```

口令输入示例使用 Bash。无需安装 OHPM 客户端或 Python 第三方包；脚本直接访问已有候选，不执行初始化、构建或重启。

每次运行生成随机 `ohpm-s3-<id>` 名称，创建专属容器、bucket、S3 blobstore、hosted 仓库、临时用户和 GenericToken。临时用户使用管理员角色，避免把权限失败误判为存储失败；凭据只用于本次包的操作。不修改既有用户/token 或其他 fixture。RustFS 凭据每次随机生成，通过环境变量传给 Docker。

RustFS 与故障网关分别动态分配端口；RustFS 只绑定宿主机 loopback。若候选运行在容器中，可使用 `--gateway-bind 0.0.0.0 --gateway-host host.docker.internal`，并限制这个临时监听地址的网络访问。需要固定网关端口时使用 `--gateway-port`。`--image` 可选择本地已有 RustFS 镜像，Docker 使用 `--pull=never`。`--report /path/to/new-report.json` 指定证据文件，已有文件不会被覆盖。

## 验证内容

1. 生成唯一、有效的 HAR/HSP 归档和 SHA-512 metadata，配置真实 `s3` / `aws-s3` blobstore。
2. 发送包含双附件的普通 OHPM JSON 发布请求，先观察第一个附件实际 S3 PUT 成功，再阻断第二个附件。
3. 故障启用期间，网关按不可变对象路径中的 HSP SHA-256 匹配，**持续对每一次 HSP PUT 返回 S3 XML `503 SlowDown`**。SDK 重试和重新生成对象 ID 都不能绕过故障。HAR 写入、读取及回滚删除正常转发给 RustFS。
4. 断言发布返回 `500`、包 metadata 及两个公开附件路径均为 `404`，回滚后专属 bucket 为空。S3 失败不映射为 OHPM `598`。
5. 解除故障，在同一 `ALLOW_ONCE` 仓库重试完全相同的发布请求，要求返回 `200`；版本、tag、checksum 正确，HAR/HSP 实际下载字节与原始归档完全一致。
6. 确认两个候选下载都触发成功的 S3 GET；另用 SigV4 直接列出并下载 RustFS 对象，与原始归档逐字节比对。

网关仅改变 TCP 连接目的地址，保留原请求路径、header 和请求体，尤其保留 SigV4 签名使用的原始 `Host`，并显式关闭 Python 自动生成 Host 的行为。证据不保存 Authorization 内容或私密凭据。

## 证据与清理边界

默认报告位于 `artifacts/ohpm-s3/ohpm-s3-<id>.json`，包含镜像标识、fixture 摘要和大小、发布结果、HSP 重试次数、失败/恢复/API 清理后的对象清单、网关 method/path/Host/status 事件以及清理结果。退出码零表示存储与发布断言通过；API 清理是否完整须另外检查 `residuals`。

断言失败时也会执行清理：解除故障，下架专属包，删除专属仓库，尝试删除 blobstore，删除临时用户及其 token；最后关闭网关，删除本次 RustFS 容器及匿名卷。不修改共享数据库，不触发全局 GC，不删除其他 fixture。

仍有注册 blob 或待删除记录时，blobstore 删除可能返回 `409`，报告保留对应 ID。删除专属容器会销毁剩余测试对象，但**不代表应用的垃圾回收已完成**；数据库残留配置将指向已移除的测试端点。最终通过销毁候选数据库完成清理。若 Python 被强制终止，可能需要按本次 run ID 手工删除容器。

## 实际运行证据

2026-11-06（Asia/Shanghai），完成全量回归和 AOT 后重建、重启的最终候选服务分别通过 PostgreSQL 和 MySQL 验收：

| 数据库 | 运行标识 | 本地原始报告 |
| --- | --- | --- |
| PostgreSQL | `ohpm-s3-803cd11f256a` | `artifacts/ohpm-s3/stage8-final-postgres.json` |
| MySQL | `ohpm-s3-ee0d83e3c523` | `artifacts/ohpm-s3/stage8-final-mysql.json` |

两次运行均观察到：

| 观察项 | 结果 |
| --- | --- |
| RustFS 镜像 | `1.0.0`，image ID `sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff` |
| 首个 HAR PUT | RustFS 返回 `200` |
| HSP PUT 尝试 | 共 4 次，全部注入 `503` |
| 失败发布 / metadata | `500` / `404`，两个公开附件路径同样为 `404` |
| 失败回滚 | HAR DELETE 返回 `204`，随后 bucket 清单为空 |
| 恢复后发布 | `200`，候选下载和直接 S3 下载的 HAR/HSP 字节均一致 |
| API 清理 | 下架 `200`、删除仓库 `204`、删除用户/token `200` |
| 残留边界 | blobstore 删除返回 `409`；销毁专属 RustFS 前仍有两个对象，具体 ID 见各自报告 |

专属容器和卷已删除，报告为本地生成的验收产物，不提交到源码仓库。后续修改并重建服务时应重新运行，不能沿用旧 jar 的验证结论。

本验收覆盖小附件、普通 OHPM 双附件发布及真实 S3 单对象 PUT/GET 的故障恢复，不覆盖 S3 multipart upload 中断、进程崩溃恢复、生产 OSS/S3 行为、最终 GC、多副本行为或 HarmonyOS 设备上的 HSP 执行。
