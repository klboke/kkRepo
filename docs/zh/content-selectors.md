# 内容选择器（Content Selectors）

内容选择器用于按仓库内路径授予权限。选择器定义内容范围；`repository-content-selector` 权限将选择器、仓库范围和操作关联起来；再将权限分配给角色，并将角色分配给用户。CI 凭据使用所属用户的权限，并受 token 自身限制约束。

## 在管理后台配置

1. 打开 **仓库 → 内容选择器**，点击 **创建选择器**。
2. 填写固定名称和 CSEL 表达式，例如 `format == "raw" and path =^ "/team/"`。
3. 选择仓库，点击 **预览**。预览仅查询已存储制品，不触发代理回源。
4. 保存后，在列表中点击 **创建权限**，选择仓库范围与操作，默认勾选 `browse` 和 `read`。
5. 到 **安全 → 角色** 为角色添加新权限，再将角色分配给相应用户。

列表会显示引用该选择器的权限。删除前需移除这些引用；名称和类型不能在编辑器中修改。新建或修改表达式时会先校验，不合法则返回 HTTP 400，保留原配置。

预览要求已经认证，并具有 `nexus:selectors:create` 或 `nexus:selectors:update`。仅有仓库读取权限不能执行管理预览。选择器增删改查分别要求 `nexus:selectors:read/create/update/delete`；创建权限还要求 `nexus:privileges:create`。

## 表达式范围

当前可移植 CSEL 范围对齐 Nexus Repository 3.94.0 实例验证的 `path` 和 `format` 语法。路径相对于仓库根目录，应以 `/` 开头；Maven 的 format 为 `maven2`。

| 语法 | 含义 | 示例 |
| --- | --- | --- |
| `==` / `!=` | 相等 / 不等 | `format == "npm"` |
| `=^` | 字面前缀，不是 glob | `path =^ "/@team/"` |
| `=~` | 对完整路径匹配正则 | `path =~ "^/org/example/.*"` |
| `and` / `&&` | 同时满足 | `format == "raw" and path =^ "/team/"` |
| `or` / `\|\|` | 满足任一条件 | `path == "/README" or path =^ "/team/"` |
| `( ... )` | 条件分组 | `format == "maven2" and (path =^ "/org/example/" or path == "/")` |

支持单引号和双引号字符串。字符串中的反斜杠需要转义，例如 `path =~ "^/org/example/.*\\.pom$"`。新 CSEL 不接受嵌入引号、任意函数、未知字段、数值/布尔表达式或一元 `not`/`!`。`coordinate.*` 和 `maven.groupId` 不在新 CSEL 支持范围中。表达式最多 8,192 字符、1,024 个 token、64 层嵌套。

正则使用 RE2/J，避免在仓库请求中执行回溯型正则。支持常见正则和既有的简单起始否定前瞻，例如 `(?!.*-sources.*).*`；Java 特有的反向引用、后顾以及其他不支持的环视会在保存时拒绝。这与 Nexus 使用的 Java 正则引擎存在明确差异，不支持任意 JEXL 执行或全部 Nexus 正则扩展。

已导入选择器继续使用原有的兼容子集，包括 path/format 别名、仓库别名、Maven/npm 坐标和布尔否定。仅更新描述时保留原表达式；修改标记为 `csel` 的旧表达式时，需要改为上述可移植语法；标记为 `jexl` 的选择器继续接受已有兼容子集。运行时无法解析的已有表达式拒绝授权。通用迁移 repository target 的行为保持独立。

## 授权语义

**权限是叠加的。** 选择器不是拒绝规则。如果用户通过其他角色拥有 `nx-all`、匹配的通配权限或整仓库读取权限，选择器不能收回这些访问权。

kkrepo 默认通过 `KKREPO_DEFAULT_AUTHENTICATED_ROLE_ID` 为已认证用户附加 `nx-anonymous` 角色，该角色的初始权限包含全仓库 browse/read。需要严格路径隔离的部署，应调整默认角色，或在所有副本上将该变量设置为空，再显式分配所需角色。修改认证配置后让用户重新认证。仅关闭匿名访问，并不会移除已认证用户的默认角色。

Group 范围内的权限作用于 Group URL，不会自动授予成员仓库直接 URL 的访问权。预览时选择存有制品的成员仓库。目录浏览和协议 metadata 可能需要额外放行父路径；只允许二进制文件路径可能不足以让包管理器正常工作。选择器不会将聚合协议 metadata 改写成每用户一份的文档，因此应同时验证真实客户端和直接下载流程。

操作权限沿用协议入口既有映射，包括通用 `PUT → edit`（新文件也一样）。完整映射见[安全模型](security-model.md)。对请求指向仓库根路径的上传 API，路径检查无法提前推断尚未从请求体解析出的包坐标，配置时需要验证实际入口。

## API 与预览边界

选择器管理采用 Nexus REST 路径：

- `GET/POST /service/rest/v1/security/content-selectors`
- `GET/PUT/DELETE /service/rest/v1/security/content-selectors/{name}`
- `POST /service/rest/v1/security/privileges/repository-content-selector`

UI 预览映射为 `POST /service/rest/internal/ui/content-selectors/preview`，请求示例：

```json
{"repository":"*-raw","type":"csel","expression":"path =^ '/team/'"}
```

仓库参数支持仓库名、`*` 和 `*-<format>`。响应包含 `total`、最多 10 项 `results`；结果使用 Nexus UI 的 `name`（带前导 `/`）、`repositoryName`、`containingRepositoryName`、`format`、`id` 和可用制品元数据字段。

kkrepo 额外返回 `truncated`、`totalExact` 和 `scanned`。每次最多求值 10,000 个 SQL 候选；`totalExact: false` 表示达到扫描上限，此时 `total` 是**已知匹配数的下界**。`truncated: true` 表示匹配多于 10 项或扫描尚未完整，应缩小表达式或仓库范围。预览不暴露底层存储引用和任意内部属性。

## 搜索优化与多副本

精确路径、字面前缀、固定仓库/format 条件以及可保守推导的 and/or 组合，会在组件分页前用于 SQL 候选过滤。SQL 参数正确转义 `%`、`_` 和转义字符，避免字面前缀意外成为通配模式。MySQL 和 PostgreSQL 共用条件生成逻辑，使用已有 asset 索引。

每个返回制品仍经过完整的选择器授权检查。数据库排序规则可能扩大候选范围，但不能因此放行权限。正则、否定和旧坐标条件在没有其他安全路径约束时继续进行剩余条件求值；组件搜索保留既有 1,000 个候选预算，并在响应中报告截断。此次优化避免无关路径耗尽常见精确/前缀选择器的候选预算，不承诺任意表达式下无限量扫描。

选择器和权限的权威状态仍存储于共享数据库。同名选择器并发创建使用唯一约束保护的插入，失败请求不会覆盖已经保存的表达式。写入后沿用事务提交后的安全目录刷新、授权缓存失效和数据库版本水位。每个副本通过 `LocalCacheFactory` 缓存编译后的表达式：最多 512 项，TTL 为 10 分钟，完整表达式是缓存键；不缓存用户或授权结果。表达式变化会使用不同缓存键，权限同步时延由既有目录/版本刷新周期决定，不受表达式缓存 TTL 限制。

参考：[Sonatype Content Selectors](https://help.sonatype.com/en/content-selectors.html)、[权限文档](https://help.sonatype.com/en/privileges.html)、[Nexus 3.94.0 源码](https://github.com/sonatype/nexus-public/tree/release-3.94.0-12)。
