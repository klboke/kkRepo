# OHPM 独立导入

OHPM 导入从文件目录或已有 ohpm-repo 读取包数据，写入 OHPM hosted 仓库。它使用独立任务与逐版本报告，不进入 Nexus 迁移模型。

管理入口为 **Admin → Migration → OHPM Import**。API 位于 `/internal/ohpm/imports`，使用管理端 session 或 Bearer token，并要求当前用户拥有 `nexus:*`。执行前及发布事务内重新鉴权；各实例直接读取共享任务状态。

## 两种来源

### 文件目录

在浏览器中选择目录。每个版本提供以下任一种文件组合：

```text
packages/
  ordinary.har
  ordinary.metadata.json
  shared.har
  shared.hsp
  shared.metadata.json
  complete.tgz
  complete.metadata.json
```

- `.har` 包含 `package/oh-package.json5`，从中读取名称、版本及 HAR/HSP 类型。
- HSP 双文件使用相同文件名主体，例如 `shared.har` 和 `shared.hsp`；也可以提供官方格式的完整 `.tgz` 或 `.tar.gz`。
- 可选的同名 `.metadata.json` 是官方 Fetch Metadata 返回的**完整包 metadata**，包括 `name`、`versions`、`dist-tags` 和每个文件的 SHA-512。导入只选择与该归档身份对应的版本及指向它的 tags，并核对 metadata 与实际文件的校验和。
- 没有 metadata 文件时保留归档 manifest，计算校验和，但**不创建默认 tag**。这避免导入多个历史版本时猜测 `latest`；可用确切版本安装，或导入后通过 OHPM dist-tags 管理命令配置 tag。
- 不配对的 HSP、重复路径、bundle 外额外 HSP 和没有对应归档的 `.metadata.json` 会在客户端提交前报错。

上传与解包复用 [管理上传](ohpm-management.md) 的有界 JSON5/归档校验。每次请求结束后删除本机暂存文件；dry-run 不写入目标 package、component、asset 或 blob。任务计划和报告保存在数据库中。

### 已有 ohpm-repo

先配置一个 OHPM proxy，填写源仓库 URL、远端凭据、下载重定向 allowlist 和出站策略，再在导入页面选择它作为来源。此配置仅用作远端连接设置；导入器直接读取源 metadata 和文件，不填充该 proxy 的缓存。

输入明确的包名列表，例如：

```text
@scope/library
@scope/shared
```

导入器通过官方 Fetch Metadata 获取这些包的版本，下载并验证每个版本的 HAR/HSP。它不假设源仓库提供全库枚举 API，也不读取 ohpm-repo 的内部数据库或磁盘布局。下载仍受现有出站地址和重定向规则约束；跨来源请求遵守既有凭据隔离规则。

远端认证沿用 OHPM 的原始 `Authorization` token 语义。源凭据保留在现有 proxy 配置中，不放入导入任务；报告中的归档下载地址被移除。源存储插件提供的外部下载 URL 可以作为输入，但最终文件通过统一 hosted 写入流程存入目标 blob store。

## Dry-run、执行和恢复

1. **New dry run** 创建 `PLANNING` 任务，逐版本验证真实文件。每项计划固定名称、版本、manifest、tags、文件大小和 SHA-512；每个任务最多 1,000 项，计划 metadata 合计不超过 16 MiB，单项不超过 2 MiB。
2. 完整规划后进入 `READY`。此时计划不可修改，报告中可能包含需处理的 `MANUAL` 项。
3. **Import / resume** 将任务转为 `APPLYING`，重新读取来源并与计划比较，再逐版本写入。仅当全部版本为 `IMPORTED` 或 `SKIPPED` 时进入 `COMPLETED`。
4. **Stop after current item** 在当前请求完成后停止调度。刷新或关闭浏览器不会丢失已提交的逐项结果；再次选择保存的任务即可恢复。
5. 目录来源恢复时须重新选择相同目录及 metadata 文件。远端来源从当前 proxy 配置重新连接。服务端不依赖先前实例的本机文件。

dry-run 的目标检查是当时的观察；正式导入在 package 行锁内重新检查。计划封存后源文件或 metadata/tag 发生变化会记录 `MANUAL`，不能把变更静默作为原计划导入。已成功的任务项直接返回既有结果，不再写入；如需再次核验后来被删除或修改的目标，创建新 dry-run 任务。

逐项结果：

| 状态 | 含义 |
| --- | --- |
| `PLANNED` | 来源校验通过，等待执行 |
| `IMPORTED` | 新版本与逐项检查点在同一事务提交 |
| `SKIPPED` | 目标版本的 metadata、HAR/HSP SHA-512 及实际文件均匹配；仅补充不冲突的缺失 tags |
| `MANUAL` | 来源无效/缺失/变化、目标版本不同或 tag 冲突，需要人工处理 |
| `FAILED` | 内容读取或执行失败；可在同一任务中重试，成功项不受影响 |

规划期间无法确定有效版本的来源会作为 `MANUAL` 输入项保留。修正这类来源后创建新计划；它不能被当作一个可执行版本。用户、token、权限和存储插件设置不自动迁移，报告始终单独列出这些 `MANUAL` 配置事项。

版本内容永不覆盖。相同文件校验和但发布 manifest 不同也会阻止导入；比较时忽略已独立验证的 `dist`、派生 `_id` 和客户端版本来源字段 `_nodeVersion`、`_ohpmVersion`。目标已有 tag 指向其他版本时，整个版本导入被阻止，不先写入文件再移动 tag。导入器不会删除源端未列出的目标 tags。

## 多副本事务

`ohpm_import_job`、`ohpm_import_item` 在 MySQL 和 PostgreSQL 中保存计划和结果。执行请求先锁定任务行，再进入统一 hosted package 事务；版本、双附件、metadata、tags、blob 引用及成功检查点一起提交。检查点写入失败会回滚制品写入，继续使用已有 blob 回滚补偿。

网络下载、解包、源校验、已有目标字节验证及预备对象上传均在获取任务、逐项和包锁前完成。发布事务重新检查仓库、版本及已验证的 blob 绑定，再更新 tags 或提交内容。两个实例同时恢复同一任务时，数据库行锁和终态检查保证同一版本只提交一次。此实现按请求执行，不使用后台 worker 或 JVM 任务队列，不需要持久化本地暂存文件或以租约猜测上次请求是否完成。

## API 流程

| 方法与路径 | 用途 |
| --- | --- |
| `POST /internal/ohpm/imports` | JSON `{repository, source: "DIRECTORY" \| "REGISTRY", sourceRepository?}`，创建计划 |
| `GET /internal/ohpm/imports?repository=<name>` | 最近 100 个任务 |
| `GET /internal/ohpm/imports/<id>` | 任务、逐项计划/结果及人工配置事项；可保存为 JSON 报告 |
| `POST /internal/ohpm/imports/<id>/directory` | multipart：`sourceKey`、`asset`、可选 `hsp`、`metadata`、`packageName` |
| `GET /internal/ohpm/imports/<id>/source-versions?name=<package>` | 列出明确指定的源包版本 |
| `POST /internal/ohpm/imports/<id>/registry` | JSON `{name, version}`，校验并规划一个远端版本 |
| `POST /internal/ohpm/imports/<id>/state` | JSON `{state: "READY" \| "APPLYING" \| "COMPLETED" \| "CANCELLED"}`，按状态机转换 |
| `POST /internal/ohpm/imports/<id>/items/<key>/directory` | 重新提交对应归档与可选 metadata，执行/恢复 |
| `POST /internal/ohpm/imports/<id>/items/<key>/registry` | 重新读取远端来源，执行/恢复 |

`metadata` 上传需要 `packageName` 来校验完整 metadata 的身份。任务 item 的 `key` 来自服务端报告，客户端不自行推导。每次执行读取返回的 item `state`；HTTP 成功表示请求被处理，不表示该版本一定导入成功。

验证入口：

```bash
mvn -pl persistence-mysql,persistence-postgresql -am \
  -Dtest='OhpmImport*Test' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl server -am -Dtest='Ohpm*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
node --test admin-ui/src/test/js/ohpm-import.test.js
```
