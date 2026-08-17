# Hugging Face Models 仓库使用指南

kkRepo 在 Nexus 兼容入口提供仅面向 Models 的 Hugging Face Hub proxy 仓库：

```text
https://nexus.example.com/repository/<repo>/
```

`huggingface-proxy` 会缓存模型元数据和完整模型文件，把可变 branch/tag 解析为不可变 Git
commit，在服务端桥接 Git LFS/Xet 下载、校验完整文件，再从仓库的 File 或 OSS/S3 blob
store 对外提供已发布内容。

## 创建 Proxy 仓库

在 Admin UI 创建 `huggingface-proxy`，并配置：

- `Remote URL`：通常为 `https://huggingface.co`；
- `Blob store`：本地试用可用 File，生产环境使用 OSS/S3；
- metadata/content TTL、negative cache、timeout、auto-block 与出站策略；
- 私有或 gated model 所需的可选仓库级 remote bearer token。

Remote bearer token 是访问上游的服务身份，会加密保存；它与本地用户访问 kkRepo 的凭据
相互独立，不会返回客户端，也不会写入日志。

## 配置 Hugging Face 客户端

把 `HF_ENDPOINT` 指向仓库根路径。大文件冷请求会先完成全量缓存再响应，因此显式配置下载
和 metadata timeout：

```bash
export HF_ENDPOINT='https://nexus.example.com/repository/huggingface-models'
export HF_HUB_DOWNLOAD_TIMEOUT=120
export HF_HUB_ETAG_TIMEOUT=1800

hf download sshleifer/tiny-gpt2 config.json
hf download sshleifer/tiny-gpt2 --include '*.json' '*.safetensors'
```

本地仓库受保护时，在 **My Token** 创建只有读取权限的 `GenericToken`，并把完整 token 通过
`HF_TOKEN` 提供给客户端。不要把访问远端 Hub 的 token 当成本地客户端 token 复用。

Python 客户端使用同一个 endpoint：

```python
from huggingface_hub import hf_hub_download, snapshot_download

config = hf_hub_download("sshleifer/tiny-gpt2", "config.json")
snapshot = snapshot_download(
    "sshleifer/tiny-gpt2",
    allow_patterns=["*.json", "*.safetensors"],
)
```

Transformers 与 Diffusers 会通过 `huggingface_hub` 继承 `HF_ENDPOINT`：

```python
from transformers import AutoModel, AutoTokenizer
from diffusers import DiffusionPipeline

model = AutoModel.from_pretrained("hf-internal-testing/tiny-random-bert")
tokenizer = AutoTokenizer.from_pretrained("hf-internal-testing/tiny-random-bert")
pipeline = DiffusionPipeline.from_pretrained(
    "hf-internal-testing/tiny-stable-diffusion-pipe"
)
```

## 缓存、身份与 Xet 语义

- Model info、revision info、tree、nested tree、paths-info、refs 和 resolve 路径始终位于
  `/repository/<repo>/...` 下。
- Branch、tag 与 PR ref 只是短 TTL alias；metadata、file、Browse 和 Search 都绑定解析后的
  40 位 commit，避免一次 snapshot 混入多个 revision。
- 普通 Git 文件按 Git blob OID 校验；LFS/Xet 文件按 `X-Linked-Etag` SHA-256 与
  `X-Linked-Size` 校验，同时记录 kkRepo 自己计算的 SHA-256。
- CDN redirect、signed URL、Xet token、CAS URL、`X-Xet-Hash` 与 `xetHash` 都在服务端消费
  或剥离。即使客户端安装了 `hf_xet`，网络仍只连接 kkRepo。
- 冷 HEAD 或 Range 同样先执行一次完整校验填充；热 GET/HEAD、条件请求和单区间 Range 从
  本地 blob 提供。

## Browse、Search、Cleanup、安全扫描与迁移

Browse/Search 展示模型 namespace/name、resolved commit、requested ref、相对路径、文件类型、
size、Git/LFS/internal checksum 及可用模型元数据；内部 raw API cache、lease 与 route
projection 不对用户展示。

Cleanup 以不可变 model commit 为主体；只有没有任何 asset 引用后才回收共享 blob。开启制品
扫描后，SafeTensors、pickle/PyTorch、GGUF、ONNX、Keras 与 shard index 候选会进入异步、
有界的静态检查；扫描过程不会 import 仓库代码，也不会反序列化或运行模型。

Nexus 迁移可识别 3.77+ 的 `huggingface-proxy` definition。Proxy content 只有在管理员显式
选择，且 Nexus 3.94 source datastore shape 能证明 repo/commit/path 身份时才可迁移；未知
shape、masked remote token、非法 commit 与损坏/缺失 blob 都会失败关闭并生成 manual action。
生成 API metadata 和 lease 在目标端重建。

## 限制与排障

| 现象 | 检查项 |
| --- | --- |
| Gated model 返回 `401`/`403` | 检查仓库 remote bearer token、gated access 授权、本地 read 权限和 token 轮换 |
| 客户端访问 `huggingface.co` 或 Xet host | 确认 `HF_ENDPOINT` 是精确仓库根路径，且没有其它客户端 endpoint override |
| 冷请求超时 | 提高两个 Hub timeout，检查上游、出站策略、blob store 健康与最大文件大小配置 |
| Snapshot 缺文件或混入不同版本 | 使用当前版 `huggingface_hub`，检查 resolved commit 与 tree，不要把 `main` 当不可变版本 |
| 缓存文件被拒绝 | 检查上游 Git/LFS identity、linked size、截断、redirect policy 与 checksum 诊断 |

该 recipe 只支持 Models proxy 读取。Hosted 发布、group、Datasets、Spaces、Kernels、Buckets、
推理 API、Git push、LFS/Xet upload 与 Hub 社区/Web API 不在本能力范围内。

## 参考资料

- [Hugging Face Hub 下载指南](https://huggingface.co/docs/huggingface_hub/en/guides/download)
- [Hugging Face Xet storage](https://huggingface.co/docs/hub/en/xet/index)
- [Hugging Face 实现设计](../dev/hugging-face-models-repository-design.md)
- [Hugging Face 性能基线](../dev/hugging-face-models-performance-baseline.md)
