# VS Code / Open VSX 仓库指南

kkRepo 实现 Eclipse Open VSX Registry HTTP API 以及 VS Code Gallery 兼容接口，支持
hosted、proxy、group 三种仓库类型。

## 仓库类型

| 场景 | Recipe | 说明 |
| --- | --- | --- |
| 私有扩展发布 | `openvsx-hosted` | 使用 `.vsix` 发布，extension/version/targetPlatform 坐标不可变 |
| 镜像 `open-vsx.org` | `openvsx-proxy` | 元数据与 VSIX 按 TTL 缓存 |
| 聚合多个 Registry | `openvsx-group` | 按成员顺序读取，第一个在线成员命中版本 |

Proxy 默认远端为 `https://open-vsx.org/`。可通过管理界面或 `/internal/repositories` API 创建。

## 客户端配置

元数据地址：

```text
https://nexus.example.com/repository/openvsx-hosted/api/<namespace>/<extension>
```

VS Code 兼容编辑器可配置自定义 Gallery：

```json
{
  "extensionsGallery": {
    "serviceUrl": "https://nexus.example.com/repository/openvsx-group/vscode/gallery",
    "itemUrl": "https://nexus.example.com/repository/openvsx-group/vscode/item"
  }
}
```

## 发布

创建域为 `OpenVsxToken` 的 API Key，然后：

```bash
ovsx publish \
  --registryUrl https://nexus.example.com/repository/openvsx-hosted \
  --pat "$OPENVSX_TOKEN" \
  acme-demo-1.0.0.vsix
```

对应接口：

```text
POST /repository/openvsx-hosted/api/-/publish?token=<OpenVsxToken>
Content-Type: application/octet-stream
```

也支持 `Authorization: Bearer`、`X-API-Key` 以及 Components API/UI 的 `.vsix` 上传。

## 行为说明

- `.vsix` 必须包含 `extension/package.json`，并声明 `publisher`、`name`、`version`。
- hosted 的扩展版本坐标不可变；重复发布会被拒绝。
- proxy 会缓存上游元数据和 VSIX，并把元数据中的 `files`、`allVersions`、`namespaceUrl`
  改写到当前仓库。
- group 按成员顺序合并版本，第一个成员优先；支持嵌套 group 和循环检测。
- VSIX 会被纳入制品扫描；Browse/Search 展示扩展、版本、校验和与大小。

## 已知限制

- 不校验 VSIX 加密签名，不实现 publisher 所有权、trusted publishing、评分/评论等 Marketplace 功能。
- targetPlatform 变体按独立 component version 存储；无精确匹配时回退到 universal。
- proxy/group 的搜索和 namespace 列表基于已缓存的扩展；冷启动不会全量爬取上游。
- Nexus 没有原生 Open VSX 格式，因此不涉及该格式的 Nexus 内容迁移。

## 相关文档

- [Open VSX API](https://open-vsx.org/v3/api-docs)
- [设计说明](../../en/dev/openvsx-repository-design.md)
