# Git LFS 仓库使用指南

在 Admin 创建 `gitlfs-hosted`，选择 OSS/S3 blob store，向读取者授予 `read`，向发布者授予 `add`。
Git 提交和 ref 继续保存在原 Git 服务；kkRepo 只保存 LFS 大文件。客户端入口为：

```text
https://repo.example.com/repository/project-lfs/info/lfs
```

## 配置与上传

安装 Git LFS（验收固定为 3.8.0），在工作仓库执行：

```sh
git lfs install --local
git config -f .lfsconfig lfs.url https://repo.example.com/repository/project-lfs/info/lfs
git config credential.https://repo.example.com.useHttpPath true
git lfs track '*.psd'
git add .lfsconfig .gitattributes design.psd
git commit -m "Store design assets in Git LFS"
git push origin HEAD
```

Basic 用户名/密码通过 Git credential helper 提供，不写入 `.lfsconfig` 或 URL。
Git remote 使用 SSH 不代表自动获得 kkRepo 权限。通过 `git lfs env` 核对实际 URL，避免本地或 remote 配置覆盖。

CI 可以将密钥库中的 `GenericToken` 注入限定仓库 URL 的 Git HTTP header；关闭 shell tracing：

```sh
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=http.https://repo.example.com/repository/project-lfs/.extraHeader
export GIT_CONFIG_VALUE_0="Authorization: Bearer $KKREPO_LFS_TOKEN"
git lfs push --all origin
unset GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0
```

token 所属用户仍需仓库权限。这里使用既有 bearer 认证，不支持把任意 API key 当成 Basic password。
每个 action 重新检查凭据和授权，撤销 token 后不能用旧 Batch action 完成发布。

## 下载与历史提交

```sh
git clone <your-git-remote>
cd <checkout>
git lfs pull
git lfs fetch --all
git checkout <historical-commit>
git lfs fsck
```

对象以 SHA-256 为不可变标识；重复 push 跳过当前仓库已有内容。完整长度和摘要通过后才发布 asset。
下载支持 HEAD、单 Range 和条件请求，并执行仓库权限与制品下载策略。
Browse/Search 展示 OID、字节数和 checksum；原文件名来自 Git 历史。

## 运维边界

- 仅 hosted，不提供 proxy/group、Git 服务、文件锁、tus、客户端分片续传或 S3 直传。
- 使用原生 Git LFS 上传，普通组件上传 API/UI 对该格式关闭。
- 自动 Cleanup 禁用：服务器不知道 Git 历史的对象可达性。人工删除可能破坏旧提交 checkout；备份需同时覆盖数据库和 blob。
- 未覆盖的 LFS 二进制扫描结果为 `NOT_APPLICABLE`，不表示没有漏洞。
- `KKREPO_GITLFS_MAX_OBJECT_BYTES` 默认 32 GiB；`KKREPO_GITLFS_CONCURRENT_UPLOADS` 默认每 pod 4 路。
  Batch 上限为 1,000 对象和 1 MiB JSON。429 按 Retry-After 重试；旧上传 context 失效时重新 Batch。
- 反向代理配置足够的 body 限额和 idle timeout，关闭会导致整文件落盘的请求缓冲。
  存储凭据除读写删除外需要 multipart list/abort 权限；失败上传由数据库协同的 worker 回收。

## Nexus 迁移

预检仅为 Nexus 3.94.x datastore 中已证明 OID/size/checksum 形态的 LFS 内容开启自动迁移。
未知版本/shape 和 OrientDB 内容需人工处理。先创建同名目标 hosted 仓库，再使用 Nexus Repository Data 页面，
开启 checksum 校验。dry-run、持久进度、resume 和报告沿用通用流程，每个导入对象使用相同的完整性校验与发布事务。

最终同步时停止源写入，然后更改有效 LFS URL，或在原仓库 URL 切换后端；Git remote 和 pointer 不变。
从独立 clone 执行 fetch --all、fsck 和历史 checkout 后再退役只读源。回退前必须补齐切换后新增的对象。

参见[兼容矩阵](../compatibility-matrix.md)、[实现设计](../dev/git-lfs-repository-design.md)和[验收记录](../dev/git-lfs-acceptance.md)。
