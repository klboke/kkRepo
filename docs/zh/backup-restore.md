# 备份恢复指南

kkrepo 将元数据和协调状态存储在 MySQL，将制品字节内容存储在 blob storage。可恢复的生产部署必须同时备份两者。

## 必须备份什么

备份 MySQL：

- 仓库定义和 group membership。
- Component、asset、browse node、search index 和 blob 引用。
- 用户、角色、权限、realm、API key、session 和审计日志。
- 迁移 job、迁移 checkpoint 和重试状态。
- Cache 水位、marker 表和后台 worker cursor。

备份 blob storage：

- Hosted 包内容。
- 需要保留的 proxy 缓存内容。
- 迁移后的 blob object。
- 作为 blob 存储的生成 metadata asset。

MySQL 保存引用和 checksum，blob storage 保存真实字节。只恢复其中一边不是完整恢复。

## 备份策略

推荐策略：

- 使用自动 MySQL 备份，条件允许时启用 point-in-time recovery。
- 如果恢复要求需要，启用 OSS/S3 versioning 或云厂商 snapshot。
- 尽可能把备份放在独立账号、bucket、region 或故障域中。
- 备份静态加密。
- 定期演练恢复。
- 明确记录 RPO 和 RTO。

小规模部署可能每天 MySQL 备份加 blob-storage versioning 就足够。承担核心 CI/CD 基础设施时，应缩短 MySQL 备份间隔，并做明确恢复演练。

## 一致性模型

干净的备份点应包含：

- 时间点 `T` 的 MySQL snapshot。
- 包含 MySQL 在时间点 `T` 引用的所有对象的 blob storage 状态。

Blob storage 中存在额外未引用 object 通常可以接受，因为 MySQL 控制可见仓库内容。MySQL 引用的 blob object 缺失不可接受，会导致下载失败。

如果对象存储支持 versioning，把 MySQL 恢复到时间点 `T`，同时保留 `T` 或之后的 blob 版本，通常比把 blob storage 恢复到更早时间点更安全。

## MySQL 备份示例

逻辑备份：

```bash
mysqldump \
  --single-transaction \
  --routines \
  --triggers \
  --databases kkrepo \
  > kkrepo-$(date +%Y%m%d%H%M%S).sql
```

托管 MySQL 通常提供 snapshot 和 point-in-time recovery。生产环境优先使用托管 PITR。

## Blob Storage 备份选项

可选择一种或多种：

- 云厂商 bucket versioning。
- 云厂商备份或复制。
- 跨地域复制。
- 定时复制对象到另一个 bucket/account。
- Lifecycle policy 保留删除版本足够长时间。

不要把 lifecycle 删除配置得过于激进，以免 MySQL 备份保留期内仍被引用的对象提前消失。

## 恢复顺序

推荐恢复流程：

1. 停止 kkrepo 副本，或阻断仓库写流量。
2. 将 MySQL 恢复到选定恢复点。
3. 恢复或验证 blob storage，确保所有被引用对象存在。
4. 启动一个 kkrepo 副本。
5. 验证 `/actuator/health`。
6. 验证 admin UI 和 browse UI。
7. 从关键仓库拉取代表性制品。
8. 执行协议级 smoke 检查。
9. 启动剩余副本。

如果对象存储已经包含所需对象的超集，可能只需要恢复 MySQL 并验证 blob 可用性。

## 恢复后验证

检查：

- `/actuator/health` 为 `UP`。
- 管理员登录可用。
- 仓库列表和 blob store 列表可加载。
- 关键 Maven/npm/PyPI/Go/Helm/Cargo/Pub/Docker/OCI/NuGet/RubyGems/Yum/Raw 包或镜像可下载。
- 测试仓库 hosted 上传可用。
- Browse/search 返回预期 asset。
- 迁移页面没有遗留非预期 running job；如有，应确认是否需要恢复运行。
- 审计日志和安全配置可见。

如果 browse 或 search 看起来过期，但直接制品下载可用，优先使用已有 rebuild 或 maintenance 流程，不要手动改 MySQL。

## 灾备演练

建议定期：

1. 创建一个全新环境。
2. 恢复接近生产规模的 MySQL 备份。
3. 指向恢复或复制后的 blob store。
4. 使用相同加密密钥启动 kkrepo。
5. 验证代表性客户端操作。
6. 记录耗时和发现的问题。

加密密钥必须和源环境一致。缺少这些密钥时，已加密的 blob-store 凭据、realm secret 和 API key payload 无法解密。

## 迁移期间备份

Nexus 迁移前：

- 备份目标 kkrepo MySQL。
- 如果目标 blob store 已有重要数据，先备份或 snapshot。
- 导出或记录源 Nexus 版本和仓库列表。
- 最终切换被接受前，保持源 Nexus 可用且尽量不改动。

迁移期间：

- 元数据迁移前执行 `Run preflight`。
- 先执行 `Sync metadata`，再执行 `Sync packages`。
- 除非有明确原因，否则保持 checksum validation 开启。
- 监控失败 asset，修复根因后 retry。

迁移后：

- 切换前做最终增量同步。
- 验收后备份 kkrepo。
- 可行时保留源 Nexus 一段回滚窗口。

## 常见恢复问题

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| 制品元数据存在但下载失败 | 恢复后的存储缺少 blob object | 恢复缺失对象版本，或恢复到更晚的 blob 备份 |
| 管理员登录失败 | 数据库不对、realm 状态不对，或加密密钥缺失 | 检查 datasource 和密钥 |
| Blob store 凭据无法读取 | `KKREPO_CREDENTIAL_SECRET` 变化 | 恢复原密钥或重新配置 blob store |
| API key 不再可用 | `KKREPO_API_KEY_PAYLOAD_SECRET` 变化，或数据库恢复到旧状态 | 恢复原密钥或重新签发 API key |
| 新上传失败 | Blob store 权限或 bucket policy 改变 | 执行 blob store health probe 并检查 IAM policy |
| 迁移 job 看起来卡住 | 恢复数据库时 job 正在运行 | 检查迁移状态，在 UI 中 retry failed 或继续未完成任务 |

## 不要这样做

- 不要只恢复 blob storage 就认为仓库元数据也恢复了。
- 如果被 MySQL 引用的 blob object 已删除，不要只恢复 MySQL。
- 不要把轮换加密密钥作为应急恢复的一部分。
- 不要在不了解外键和 rebuild 行为的情况下手动删除 repository、asset 或 blob 表数据。
- 不要在公开 issue 中发布完整备份 dump。

安全敏感恢复事件请按 [SECURITY.md](../../SECURITY.md) 处理。
