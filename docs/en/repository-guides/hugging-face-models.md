# Hugging Face Models Repository Guide

kkRepo supports Models-only Hugging Face Hub proxy repositories at the Nexus-compatible entrypoint:

```text
https://nexus.example.com/repository/<repo>/
```

The `huggingface-proxy` recipe caches model metadata and complete model files. It resolves mutable
branches and tags to immutable Git commits, bridges Git LFS/Xet downloads on the server, validates
the full file, and serves the published blob from the repository's File or OSS/S3 blob store.

## Create A Proxy Repository

Create a `huggingface-proxy` repository in Admin UI and configure:

- `Remote URL`: normally `https://huggingface.co`;
- `Blob store`: File for local trials, OSS/S3 for production;
- metadata/content TTL, negative cache, timeouts, auto-block, and outbound policy;
- an optional repository-scoped remote bearer token for private or gated models.

The remote bearer token is the upstream service identity and is encrypted at rest. It is separate
from the local user's kkRepo credential and is never returned to clients or written to logs.

## Configure Hugging Face Clients

Point `HF_ENDPOINT` at the repository root. Large cold downloads are fully cached before they are
served, so use explicit download and metadata timeouts:

```bash
export HF_ENDPOINT='https://nexus.example.com/repository/huggingface-models'
export HF_HUB_DOWNLOAD_TIMEOUT=120
export HF_HUB_ETAG_TIMEOUT=1800

hf download sshleifer/tiny-gpt2 config.json
hf download sshleifer/tiny-gpt2 --include '*.json' '*.safetensors'
```

For a protected local repository, create a read-scoped `GenericToken` in **My Token** and expose
the complete generated token through `HF_TOKEN`. Do not reuse the remote Hub token as the local
client token.

Python clients use the same endpoint:

```python
from huggingface_hub import hf_hub_download, snapshot_download

config = hf_hub_download("sshleifer/tiny-gpt2", "config.json")
snapshot = snapshot_download(
    "sshleifer/tiny-gpt2",
    allow_patterns=["*.json", "*.safetensors"],
)
```

Transformers and Diffusers inherit `HF_ENDPOINT` through `huggingface_hub`:

```python
from transformers import AutoModel, AutoTokenizer
from diffusers import DiffusionPipeline

model = AutoModel.from_pretrained("hf-internal-testing/tiny-random-bert")
tokenizer = AutoTokenizer.from_pretrained("hf-internal-testing/tiny-random-bert")
pipeline = DiffusionPipeline.from_pretrained(
    "hf-internal-testing/tiny-stable-diffusion-pipe"
)
```

## Cache, Identity, And Xet Behavior

- Model info, revision info, tree, nested tree, paths-info, refs, and resolve routes remain below
  `/repository/<repo>/...`.
- Branches, tags, and PR refs are short-lived aliases. Metadata, files, Browse, and Search are
  projected against the resolved 40-character commit so a snapshot cannot mix revisions.
- Regular Git files are checked against their Git blob OID. LFS/Xet-backed files are checked
  against `X-Linked-Etag` SHA-256 and `X-Linked-Size`; kkRepo also records its own SHA-256.
- CDN redirects, signed URLs, Xet tokens, CAS URLs, `X-Xet-Hash`, and `xetHash` are consumed or
  removed server-side. Even with `hf_xet` installed, the client remains connected to kkRepo.
- A cold HEAD or Range request performs one complete, verified fill. Warm GET/HEAD, conditional
  requests, and single-byte-range reads are served from the local blob.

## Browse, Search, Cleanup, Scanning, And Migration

Browse and Search expose model namespace/name, resolved commit, requested ref, relative path,
file kind, size, Git/LFS/internal checksums, and available model metadata. Internal raw API cache
objects, leases, and route projections stay hidden.

Cleanup treats an immutable model commit as the subject. Shared blobs are reclaimed only after no
asset still references them. When artifact scanning is enabled, SafeTensors, pickle/PyTorch,
GGUF, ONNX, Keras, and shard-index candidates are inspected asynchronously with bounded static
parsers; kkRepo never imports model code or deserializes a model during scanning.

Nexus migration recognizes `huggingface-proxy` definitions from Nexus 3.77+. Proxy content is
eligible only when explicitly selected and the Nexus 3.94 source datastore shape proves the
repo/commit/path identity. Unknown shapes, masked remote tokens, invalid commits, and corrupt or
missing blobs fail closed and produce a manual action. Generated API metadata and leases are
rebuilt on the target.

## Limits And Troubleshooting

| Symptom | Check |
| --- | --- |
| `401`/`403` from a gated model | Verify the repository remote bearer token, gated access grant, local read permission, and token rotation |
| Client contacts `huggingface.co` or an Xet host | Confirm `HF_ENDPOINT` is the exact repository root and no client-specific endpoint override is active |
| Cold request times out | Increase both Hub timeout variables and verify upstream, outbound policy, blob-store health, and the configured maximum file size |
| Snapshot mixes or misses files | Use a current `huggingface_hub`; inspect the resolved commit and tree response rather than treating `main` as immutable |
| Cached file is rejected | Check upstream Git/LFS identity, linked size, truncation, redirect policy, and checksum diagnostics |

This recipe supports Models proxy reads only. Hosted publication, group repositories, Datasets,
Spaces, Kernels, Buckets, inference APIs, Git push, LFS/Xet upload, and Hub social/web APIs are not
part of this capability.

## References

- [Hugging Face Hub download guide](https://huggingface.co/docs/huggingface_hub/en/guides/download)
- [Hugging Face Xet storage](https://huggingface.co/docs/hub/en/xet/index)
- [Hugging Face implementation design](../../zh/dev/hugging-face-models-repository-design.md)
- [Hugging Face performance baseline](../dev/hugging-face-models-performance-baseline.md)
