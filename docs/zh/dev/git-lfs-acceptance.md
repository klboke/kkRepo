# Git LFS hosted 验收记录

日期：2026-10-03。本文区分本地实测、可重放测试入口和仍需扩展的覆盖，
不把默认大小上限或 CI 工作流定义当成性能/运行结果。

## 参考环境

| 项目 | 版本/配置 |
| --- | --- |
| Nexus | `sonatype/nexus3:3.94.0`，3.94.0-12，H2/file |
| Nexus image digest | `sha256:6e8905c14210c595a8177910179ce080cdc22fa3f6b660a13e6dfa1f8378a866` |
| Git / Git LFS | 2.51.0 / 3.8.0（darwin amd64） |
| Java | 25.0.3 |
| 数据库 | MySQL 8.0.46；PostgreSQL 17（应用），PostgreSQL 12.22（contract） |
| Blob | MinIO `RELEASE.2025-04-22T22-12-26Z`，AWS S3 adapter |
| 副本 | 两个 JVM，共享 PostgreSQL 和同一 S3 bucket/prefix |

生产不依赖 File 引擎；这里 Nexus 的 file 与候选的 S3 不同，不能据此比较吞吐。

## 已完成的本地检查

- `GitLfsRepositoryBlackBoxCompatibilityTest` 对 Nexus 和候选运行：创建 hosted、Batch/basic/verify、
  下载、Range、重复 push 的无 actions 响应、混合缺失对象、仅 tus 协商失败，以及候选错误 SHA-256 拒绝。
- 116 项针对性 Java 测试通过，无失败/跳过，覆盖 parser、长度/摘要 verifier、S3/OSS Native/File provider、
  双库真实事务/并发/租约/删除/恢复、身份缓存撤销、selector、发布失败、管理删除、HTTP 流式响应、Cleanup 和迁移 shape。
  另执行 RepositorySecurityFilter 和 cache/persistence 边界回归。
- 原生 Git LFS 3.8.0：两个历史大对象（跨 8 MiB part）、三个并发小对象，Basic push、重复 push、
  独立空缓存 clone/pull、fetch --all、历史 checkout 字节比较、fsck；URL-scoped GenericToken push/fetch。
  MySQL 和 PostgreSQL 应用均完成客户端闭环。
- 双副本：A Batch/B PUT/B verify/A GET；错误摘要、短 chunked body 和缺失 context 不发布；Range/suffix/416/HEAD/304；
  跨仓库 context 拒绝；token 和角色权限撤销；逐 OID selector；人工删除阻止旧 context 复活；同 OID 并发只保留完整对象。
- Nexus hosted 迁移到 PostgreSQL/S3 和 MySQL/S3：两个多 part 对象和一个空对象，预检、持久任务、
  checksum/字节比较、verify、metadata/package 重跑（resume），3/3 成功且无重复/失败。
- Admin/Browse JavaScript 语法、shell/Python 语法和 `git diff --check`。

Nexus 边界差异（错误 OID 接受、verify 错误、HEAD、锁、Authorization 回显）见[实现设计](git-lfs-repository-design.md#协议与-nexus-基线)。
这些差异不被 compatibility fixture 的 host/时间归一化掩盖。

## 重放入口

在已初始化的候选与参考实例上设置测试凭据；不要把生产凭据写入脚本。两个副本必须共享数据库和 blob store。

```sh
export KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090
export KKREPO_SECONDARY_BASE_URL=http://127.0.0.1:18092
export KKREPO_COMPAT_USERNAME=admin
export KKREPO_COMPAT_PASSWORD='<test-password>'
export NEXUS_COMPAT_BASE_URL=http://127.0.0.1:28090
export NEXUS_COMPAT_USERNAME=admin
export NEXUS_COMPAT_PASSWORD='<reference-test-password>'
export CLIENT_E2E_ARTIFACT_DIR=artifacts/git-lfs
bash scripts/ci/git-lfs-client-e2e.sh
python3 scripts/ci/git-lfs-resilience-e2e.py
python3 scripts/ci/git-lfs-migration-e2e.py
```

脚本创建独立命名的测试仓库。客户端脚本清理自己的临时 Git checkout；服务端测试数据保留用于诊断。
迁移脚本要求源开启 Script API，并为候选配置可用的 `default` blob store。

Java 回归可运行：

```sh
mvn -pl server,persistence-mysql,persistence-postgresql -am \
  -Dtest=GitLfsDaoMySqlIntegrationTest,GitLfsDaoPostgreSqlIntegrationTest,GitLfsAccessTest,GitLfsHostedServiceTest,GitLfsProtocolHandlerTest,NexusAssetManagementServiceTest,NexusSourceProfileTest,GitLfsProtocolTest,VerifiedBlobReaderTest,S3BlobStorageTest,OssNativeBlobStorageTest,FileBlobStorageTest,SecurityAuthenticationServiceTest,CleanupPolicyCapabilitiesTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实客户端接入 `client-e2e-plan.py` 的 system shard，覆盖 Full E2E 的 JVM/Native × MySQL/PostgreSQL。
覆盖门禁对 LFS 检查自动 Cleanup 被拒绝，保留其它格式的 Cleanup try-run/execute 检查。
Migration E2E 在 Nexus 3.94.0 lanes 额外执行 LFS 迁移/恢复。Git LFS Linux amd64 3.8.0 安装包固定 SHA-256。
Native 和完整 CI 的执行结论以实现 PR 的具体 head checks 为准，本地 JVM 结果不替代 Native 验收。

## 未扩大承诺的范围

OSS Native 经过 SDK adapter contract/mock 测试，尚无真实阿里云 OSS 端点验收。
本次没有运行 1 GiB/4 GiB+、百万 OID、8/32 并发性能矩阵或所有崩溃注入点。
32 GiB 是默认配置限额；不声称已测得该上限的吞吐、内存峰值或优于 Nexus。
历史 Git LFS 客户端、OrientDB LFS、未知 Nexus 版本/shape、文件锁和自动 Git reachability GC 均不在支持承诺中。
