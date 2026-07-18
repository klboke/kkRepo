(function () {
  const STORAGE_KEY = "kkrepo.uiSettings";
  const SUPPORTED_DEFAULT_LANGUAGES = new Set(["browser", "zh-CN", "en"]);
  const DEFAULT_LANGUAGE = "en";
  const ATTRIBUTE_NAMES = ["aria-label", "placeholder", "title"];
  const SKIP_TEXT_TAGS = new Set(["SCRIPT", "STYLE", "TEXTAREA", "CODE", "PRE", "KBD", "SAMP"]);
  const textOriginals = new WeakMap();
  const attributeOriginals = new WeakMap();
  let settings = readCachedSettings();
  let currentLanguage = resolveLanguage(settings.defaultLanguage);
  let applying = false;
  let observing = false;
  let observer = null;
  let readyResolve;
  const readyPromise = new Promise((resolve) => {
    readyResolve = resolve;
  });
  const originalTitle = document.title;

  const zh = {
    "kkrepo administration": "kkrepo 管理后台",
    "kkrepo browse": "kkrepo 浏览",
    "kkrepo Repository Manager": "kkrepo 仓库管理器",
    "Workspace": "工作区",
    "Administration navigation": "管理导航",
    "Browse navigation": "浏览导航",
    "Browse": "浏览",
    "Admin": "管理",
    "Repository": "仓库",
    "Repositories": "仓库",
    "Manage repositories": "管理仓库",
    "Create repository": "创建仓库",
    "Save repository": "保存仓库",
    "Edit repository": "编辑仓库",
    "Delete repository": "删除仓库",
    "Recipe": "配方",
    "Name": "名称",
    "Blob store": "Blob 存储",
    "Blob Stores": "Blob 存储",
    "Manage blob stores": "管理 Blob 存储",
    "Create blob store": "创建 Blob 存储",
    "Save blob store": "保存 Blob 存储",
    "Save this Blob Store configuration.": "保存此 Blob 存储配置。",
    "Engine": "引擎",
    "Endpoint": "端点",
    "Region": "区域",
    "Bucket": "存储桶",
    "Prefix": "前缀",
    "Path": "路径",
    "Access key": "Access key",
    "Secret key": "Secret key",
    "Path style access": "Path-style 访问",
    "Type": "类型",
    "Health": "健康状态",
    "Bucket / Path": "存储桶 / 路径",
    "Prefix / Resolved path": "前缀 / 解析路径",
    "Path style": "Path style",
    "Docker Registry": "Docker Registry",
    "Manage Docker connector runtime and repository cache": "管理 Docker 连接器运行时和仓库缓存",
    "Refresh connectors": "刷新连接器",
    "Clear Docker cache": "清理 Docker 缓存",
    "Connector runtime": "连接器运行时",
    "Transfer limits": "传输限制",
    "Repository entrypoints": "仓库入口",
    "Use the Browse, Search, and Upload areas with Nexus-compatible repository URLs.": "通过 Nexus 兼容的仓库 URL 使用浏览、搜索和上传功能。",
    "Repository Manager": "仓库管理器",
    "Hosted settings": "Hosted 设置",
    "Proxy settings": "Proxy 设置",
    "Docker connector": "Docker 连接器",
    "Optional compatibility entrypoint for repository-specific Docker traffic. Leave it disabled to use path-based Docker routing on the main service port; enable it only when clients need a dedicated /v2/ port or isolated upload/download traffic.": "用于仓库级 Docker 流量的可选兼容入口。禁用时将使用主服务端口上的路径式 Docker 路由；仅当客户端需要独立的 /v2/ 端口或隔离上传下载流量时才需启用。",
    "Enable HTTP connector": "启用 HTTP 连接器",
    "Connector port": "连接器端口",
    "Connector port (optional)": "连接器端口（可选）",
    "Public URL": "公网 URL",
    "Remote bearer token": "远端 Bearer token",
    "Clear saved bearer token": "清除已保存的 Bearer token",
    "Cargo settings": "Cargo 设置",
    "Controls the Cargo registry `auth-required` client hint. Repository access is still decided by anonymous settings and security privileges.": "控制 Cargo registry `auth-required` 客户端提示。仓库访问仍由匿名访问设置和安全权限决定。",
    "Authentication requirements": "认证要求",
    "Group members": "组成员",
    "Available": "可用",
    "Selected": "已选",
    "Granted": "已授予",
    "Given": "已赋予",
    "Contained": "已包含",
    "drag to reorder · priority top → bottom": "拖拽排序 · 优先级从上到下",
    "Move members": "移动成员",
    "Move user roles": "移动用户角色",
    "Move privileges": "移动权限",
    "Move roles": "移动角色",
    "Add all": "全部添加",
    "Add selected": "添加所选",
    "Remove selected": "移除所选",
    "Remove all": "全部移除",
    "Filter by name…": "按名称过滤…",
    "Filter roles...": "过滤角色...",
    "Filter privileges...": "过滤权限...",
    "Filter": "过滤",
    "Source": "来源",
    "All sources": "全部来源",
    "Online": "在线",
    "Offline": "离线",
    "Status": "状态",
    "URL": "URL",
    "Actions": "操作",
    "Recipe": "配方",
    "Format": "格式",
    "Write policy": "写入策略",
    "Allow redeploy": "允许重复部署",
    "Disable redeploy": "禁止重复部署",
    "Read-only": "只读",
    "Version policy": "版本策略",
    "Release": "Release",
    "Snapshot": "Snapshot",
    "Mixed": "Mixed",
    "Layout policy": "布局策略",
    "Strict": "严格",
    "Permissive": "宽松",
    "Remote URL": "远端 URL",
    "Remote username": "远端用户名",
    "Remote password": "远端密码",
    "Content max age (min)": "内容最大缓存时间（分钟）",
    "Metadata max age (min)": "元数据最大缓存时间（分钟）",
    "Auto-block remote": "自动阻断远端",
    "Clear saved password": "清除已保存密码",
    "Outbound network proxy": "出站网络代理",
    "Route upstream fetches for this proxy repository through an HTTP or SOCKS proxy (e.g. a corporate Clash on the LAN). Useful when the remote registry (hub.docker.com, pypi.org, ...) is unreachable from the server.": "通过 HTTP 或 SOCKS 代理转发此 proxy 仓库的上游拉取（例如局域网内的企业 Clash）。当远端仓库（hub.docker.com、pypi.org 等）在服务器上无法直接访问时非常有用。",
    "Proxy type": "代理类型",
    "Direct (no proxy)": "直连（不使用代理）",
    "Proxy host": "代理主机",
    "Proxy port": "代理端口",
    "Proxy username": "代理用户名",
    "Proxy password": "代理密码",
    "Clear saved proxy password": "清除已保存的代理密码",
    "Saved proxy password unchanged": "代理密码保持不变",
    "Strict content type validation": "严格校验内容类型",
    "Cancel": "取消",
    "Close": "关闭",
    "Check": "检查",
    "edit": "编辑",
    "delete": "删除",
    "view": "查看",
    "Security": "安全",
    "Users": "用户",
    "Manage local and external security users": "管理本地和外部安全用户",
    "Create user": "创建用户",
    "Save user": "保存用户",
    "User ID": "用户 ID",
    "First name": "名",
    "Last name": "姓",
    "Email": "邮箱",
    "Password": "密码",
    "Roles": "角色",
    "Role ID": "角色 ID",
    "Description": "描述",
    "Manage role privileges and inherited roles": "管理角色权限和继承角色",
    "Create role": "创建角色",
    "Save role": "保存角色",
    "Privileges": "权限",
    "Manage Nexus-compatible wildcard and repository privileges": "管理 Nexus 兼容通配符和仓库权限",
    "Create privilege": "创建权限",
    "Save privilege": "保存权限",
    "Privilege ID": "权限 ID",
    "Permission": "权限",
    "Properties JSON": "属性 JSON",
    "Realms": "Realm",
    "Manage local, LDAP and OIDC authentication priority": "管理本地、LDAP 和 OIDC 认证优先级",
    "Save realm order": "保存 Realm 顺序",
    "Local realm is required.": "Local Realm 必须启用。",
    "Enabled": "启用",
    "Priority": "优先级",
    "Realm": "Realm",
    "LDAP": "LDAP",
    "Manage LDAP connection, user mapping, and group roles": "管理 LDAP 连接、用户映射和组角色",
    "Protocol": "协议",
    "Host": "主机",
    "Port": "端口",
    "Use trust store": "使用 trust store",
    "Search base": "搜索基准",
    "Auth scheme": "认证方案",
    "Auth realm": "认证 Realm",
    "Auth username": "认证用户名",
    "Auth password": "认证密码",
    "Connection timeout": "连接超时",
    "Retry delay": "重试延迟",
    "Max incidents": "最大异常次数",
    "User base DN": "用户 Base DN",
    "User subtree": "用户子树",
    "User object class": "用户对象类",
    "User filter": "用户过滤器",
    "User ID attribute": "用户 ID 属性",
    "Real name attribute": "真实姓名属性",
    "MemberOf attribute": "MemberOf 属性",
    "Email attribute": "邮箱属性",
    "Password attribute": "密码属性",
    "Groups as roles": "组作为角色",
    "Group type": "组类型",
    "Group base DN": "组 Base DN",
    "Group subtree": "组子树",
    "Group ID attribute": "组 ID 属性",
    "Group member attribute": "组成员属性",
    "Group member format": "组成员格式",
    "Group object class": "组对象类",
    "Attributes JSON": "属性 JSON",
    "Save LDAP settings": "保存 LDAP 设置",
    "OIDC": "OIDC",
    "Manage OIDC issuer, token validation, and claim mapping": "管理 OIDC Issuer、Token 校验和声明映射",
    "Issuer": "Issuer",
    "JWKS URI": "JWKS URI",
    "Audience": "Audience",
    "Client ID": "Client ID",
    "Client secret": "Client Secret",
    "Scopes": "Scopes",
    "Authorization endpoint": "授权端点",
    "Token endpoint": "Token 端点",
    "Redirect URI": "回调 URI",
    "User ID claim": "用户 ID Claim",
    "First name claim": "名 Claim",
    "Last name claim": "姓 Claim",
    "Email claim": "邮箱 Claim",
    "Groups claim": "组 Claim",
    "Roles claim": "角色 Claim",
    "Clock skew seconds": "时钟偏移秒数",
    "JWKS cache seconds": "JWKS 缓存秒数",
    "Save OIDC settings": "保存 OIDC 设置",
    "Anonymous": "匿名",
    "Anonymous Access": "匿名访问",
    "Configure the anonymous user used for unauthenticated repository reads": "配置用于未认证仓库读取的匿名用户",
    "User source": "用户来源",
    "Anonymous users always use the built-in Local source.": "匿名用户始终使用内置 Local 来源。",
    "Realm name": "Realm 名称",
    "Save anonymous access": "保存匿名访问",
    "API Keys": "API Key",
    "Manage API key ownership and domains": "管理 API Key 所有者和域",
    "Create API key": "创建 API Key",
    "Domain": "域",
    "Owner source": "所有者来源",
    "Owner user ID": "所有者用户 ID",
    "Display name": "显示名称",
    "Generate": "生成",
    "Current API keys": "当前 API Key",
    "Generated token": "已生成 Token",
    "Shown once after generate or reset.": "仅在生成或重置后显示一次。",
    "Copy": "复制",
    "Copied": "已复制",
    "Reset": "重置",
    "Updated": "更新时间",
    "Owner": "所有者",
    "Scopes": "Scopes",
    "Audit Log": "审计日志",
    "Search management mutations recorded in MySQL": "搜索 MySQL 中记录的管理变更操作",
    "Search actor, path, permission, details": "搜索操作者、路径、权限、详情",
    "Actor": "操作者",
    "Outcome": "结果",
    "All": "全部",
    "From": "开始",
    "To": "结束",
    "Search": "搜索",
    "Time": "时间",
    "Remote": "远端",
    "Method": "方法",
    "Details": "详情",
    "Rows": "行数",
    "Migration": "迁移",
    "Nexus Metadata": "Nexus 元数据",
    "Migrate Nexus users, roles, permissions, blob stores, and repository definitions": "迁移 Nexus 用户、角色、权限、Blob 存储和仓库定义",
    "Source URL": "源 URL",
    "Source username": "源用户名",
    "Source password": "源密码",
    "Run preflight": "运行预检",
    "Run migration": "运行迁移",
    "Nexus Repository Data": "Nexus 仓库数据",
    "Discover and migrate hosted repository assets from Nexus": "发现并迁移 Nexus hosted 仓库资产",
    "Page size": "分页大小",
    "Concurrency": "并发数",
    "Metadata since": "元数据起始时间",
    "Validate size": "校验大小",
    "Optional proxy repositories": "可选 Proxy 仓库",
    "Sync metadata": "同步元数据",
    "Continue metadata": "继续元数据",
    "Sync packages": "同步包",
    "Retry failed": "重试失败项",
    "Refresh jobs": "刷新任务",
    "System": "系统",
    "UI Settings": "界面设置",
    "Configure the default UI language shared by all replicas": "配置所有副本共享的默认界面语言",
    "Default language": "默认语言",
    "Follow browser": "跟随浏览器",
    "Chinese": "中文",
    "English": "英文",
    "Save UI settings": "保存界面设置",
    "This preference is stored in MySQL and applies to the administration, browse, and sign-in UI on every replica.": "此设置存储在 MySQL 中，对所有副本的管理、浏览和登录界面均生效。",
    "Welcome": "欢迎",
    "Nexus-compatible repository access for internal packages": "面向内部包的 Nexus 兼容仓库访问",
    "Initial administrator setup": "初始管理员设置",
    "Administrator": "管理员",
    "Confirm password": "确认密码",
    "Allow anonymous repository reads": "允许匿名读取仓库",
    "Users can browse and download readable repository content without signing in. You can change this later in Administration > Security > Anonymous Access.": "用户无需登录即可浏览和下载有读取权限的仓库内容。之后可在“管理后台 > 安全 > 匿名访问”中调整。",
    "Create administrator": "创建管理员",
    "Browse assets and components": "浏览资产和组件",
    "Copy": "复制",
    "Search for components by attribute": "按属性搜索组件",
    "Keyword": "关键字",
    "Any": "任意",
    "Only showing the first 20 of": "仅显示前 20 条，共",
    "results": "条结果",
    "Group": "组",
    "Version": "版本",
    "Upload": "上传",
    "Upload content to the repository": "上传内容到仓库",
    "My Token": "我的 Token",
    "API keys for package clients": "包客户端使用的 API Key",
    "NpmToken": "NpmToken",
    "RubyGemsApiKey": "RubyGemsApiKey",
    "GenericToken": "GenericToken",
    "npm token": "npm token",
    "Sign in": "登录",
    "Sign out": "退出登录",
    "Current user": "当前用户",
    "My Token": "我的 Token",
    "Sign-in method": "登录方式",
    "Local / LDAP": "本地 / LDAP",
    "Local": "本地",
    "Sign in with SSO": "使用 SSO 登录",
    "Username": "用户名",
    "Disabled": "禁用",
    "Yes": "是",
    "No": "否",
    "Loading": "加载中",
    "Loading...": "加载中...",
    "Loading…": "加载中…",
    "Searching...": "搜索中...",
    "Failed": "失败",
    "Healthy": "健康",
    "Checking": "检查中",
    "Not saved": "未保存",
    "Read/write check passed.": "读写检查通过。",
    "Persist configuration before checking.": "请先保存配置后再检查。",
    "No repositories yet. Create your first one.": "还没有仓库，请创建第一个仓库。",
    "No blob stores": "没有 Blob 存储",
    "No Docker connector ports configured.": "未配置 Docker 连接器端口。",
    "No users.": "没有用户。",
    "No roles.": "没有角色。",
    "No privileges.": "没有权限。",
    "No API keys.": "没有 API Key。",
    "No audit records.": "没有审计记录。",
    "No matches": "没有匹配项",
    "No available roles": "没有可用角色",
    "No roles granted": "没有已授予角色",
    "No available privileges": "没有可用权限",
    "No privileges given": "没有已赋予权限",
    "No contained roles": "没有已包含角色",
    "No eligible members": "没有符合条件的成员",
    "No source blob stores were reported; the default target blob store will be used.": "源端未报告 Blob 存储；将使用默认目标 Blob 存储。",
    "No supported repositories were found in the source inventory.": "源清单中没有支持的仓库。",
    "No repository jobs.": "没有仓库任务。",
    "No repository data migration jobs.": "没有仓库数据迁移任务。",
    "No repository data migration job selected.": "未选择仓库数据迁移任务。",
    "No supported hosted repository selected.": "未选择支持的 hosted 仓库。",
    "Sign in required.": "需要登录。",
    "No components found.": "未找到组件。",
    "Select a component or file in the tree to view details.": "选择树中的组件或文件查看详情。",
    "No component coordinates for this path.": "此路径没有组件坐标。",
    "Loading repositories...": "正在加载仓库...",
    "Loading upload repositories...": "正在加载可上传仓库...",
    "No supported hosted repository selected.": "未选择支持的 hosted 仓库。",
    "Active ports": "活跃端口",
    "Active uploads": "活跃上传",
    "Max uploads": "最大上传数",
    "Active downloads": "活跃下载",
    "Max downloads": "最大下载数",
    "Runtime errors": "运行时错误",
    "Last refreshed": "上次刷新",
    "Sequence": "序列",
    "Unlimited": "无限制",
    "Repository metadata sync started.": "仓库元数据同步已启动。",
    "Metadata worker triggered": "元数据 worker 已触发",
    "Package sync triggered": "包同步已触发",
    "Failed package retry triggered": "失败包重试已触发",
    "Running preflight...": "正在运行预检...",
    "Preflight finished.": "预检完成。",
    "Running migration...": "正在运行迁移...",
    "Migration finished.": "迁移完成。",
    "Blob store check completed.": "Blob 存储检查完成。",
    "Blob store created.": "Blob 存储已创建。",
    "Blob store updated.": "Blob 存储已更新。",
    "Repository created.": "仓库已创建。",
    "Repository updated.": "仓库已更新。",
    "Repository deleted.": "仓库已删除。",
    "User saved.": "用户已保存。",
    "User deleted.": "用户已删除。",
    "Role saved.": "角色已保存。",
    "Role deleted.": "角色已删除。",
    "Privilege saved.": "权限已保存。",
    "Privilege deleted.": "权限已删除。",
    "Realms saved.": "Realm 已保存。",
    "LDAP settings saved.": "LDAP 设置已保存。",
    "OIDC settings saved.": "OIDC 设置已保存。",
    "Anonymous access saved.": "匿名访问已保存。",
    "API key deleted.": "API Key 已删除。",
    "API key imported.": "API Key 已导入。",
    "Docker connectors refreshed.": "Docker 连接器已刷新。",
    "Docker cache clear failed.": "Docker 缓存清理失败。",
    "Docker connector refresh failed.": "Docker 连接器刷新失败。",
    "Docker operations request failed.": "Docker 操作请求失败。",
    "Docker operations request failed": "Docker 操作请求失败",
    "UI language settings saved.": "界面语言设置已保存。",
    "Session check failed": "Session 检查失败",
    "Save failed": "保存失败",
    "Create failed": "创建失败",
    "Delete failed": "删除失败",
    "Check failed": "检查失败",
    "Preflight failed": "预检失败",
    "Migration failed": "迁移失败",
    "Metadata sync failed": "元数据同步失败",
    "Worker trigger failed": "Worker 触发失败",
    "Load jobs failed": "任务加载失败",
    "Load status failed": "状态加载失败",
    "Failed to load recipes": "加载配方失败",
    "Failed to load repositories": "加载仓库失败",
    "Failed to load blob stores": "加载 Blob 存储失败",
    "Failed to load users": "加载用户失败",
    "Failed to load roles": "加载角色失败",
    "Failed to load privileges": "加载权限失败",
    "Failed to load realms": "加载 Realm 失败",
    "Failed to load LDAP settings": "加载 LDAP 设置失败",
    "Failed to load OIDC settings": "加载 OIDC 设置失败",
    "Failed to load anonymous settings": "加载匿名设置失败",
    "Failed to load API keys": "加载 API Key 失败",
    "Failed to load audit log": "加载审计日志失败",
    "Failed to refresh realms": "刷新 Realm 失败",
    "Failed to load session": "加载 Session 失败",
    "Failed to load administrator bootstrap status": "加载管理员初始化状态失败",
    "Failed to load permissions": "加载权限失败",
    "Failed to load upload repositories": "加载可上传仓库失败",
    "Failed to load upload specs": "加载上传规格失败",
    "Failed to generate API key": "生成 API Key 失败",
    "Failed to reset API key": "重置 API Key 失败",
    "Failed to delete API key": "删除 API Key 失败",
    "Search failed": "搜索失败",
    "Upload failed": "上传失败",
    "Delete this API key?": "删除这个 API Key？",
    "Invalid username or password.": "用户名或密码无效。",
    "Username and password are required.": "用户名和密码为必填项。",
    "Sign in failed": "登录失败",
    "Authentication required.": "需要认证。",
    "Pick a recipe before saving.": "保存前请选择配方。",
    "Name already exists.": "名称已存在。",
    "Blob store name already exists.": "Blob 存储名称已存在。",
    "Blob store no longer exists. Refresh and try again.": "Blob 存储已不存在，请刷新后重试。",
    "Blob store is fixed after repository creation.": "仓库创建后 Blob 存储不可修改。",
    "Invalid JSON": "JSON 无效",
    "JSON must be an object": "JSON 必须是对象",
    "User ID is required.": "用户 ID 为必填项。",
    "Role ID is required.": "角色 ID 为必填项。",
    "Privilege ID is required.": "权限 ID 为必填项。",
    "Owner user ID is required.": "所有者用户 ID 为必填项。",
    "LDAP is enabled but URL or Host is required.": "启用 LDAP 时必须填写 URL 或 Host。",
    "LDAP URL or host is required when LDAP is enabled.": "启用 LDAP 时必须填写 LDAP URL 或主机。",
    "OIDC is enabled but required fields are missing": "OIDC 已启用，但缺少必填字段",
    "Password reset required": "需要重置密码",
    "Password hash": "密码哈希",
    "Password resets": "密码重置",
    "Content selectors": "内容选择器",
    "Proxy remotes": "Proxy 远端",
    "Manual actions": "手动操作",
    "Package progress": "包进度",
    "Migration result": "迁移结果",
    "Preflight result": "预检结果",
    "Latest repository data migration": "最近一次仓库数据迁移",
    "Ready for package sync": "可同步包",
    "Completed": "已完成",
    "Completed with failures": "已完成但有失败项",
    "Pending": "待处理",
    "Migrated": "已迁移",
    "Discovered": "已发现",
    "Missing": "缺失",
    "Error": "错误",
    "Message": "消息",
    "Phase": "阶段",
    "Progress": "进度",
    "Cursor": "游标",
    "Total": "总数",
    "Failed": "失败",
    "Idle": "空闲",
    "ACTIVE": "ACTIVE",
    "DISABLED": "DISABLED",
    "SUCCESS": "SUCCESS",
    "FAILURE": "FAILURE",
    "File": "File",
    "AWS S3 SDK": "AWS S3 SDK",
    "OSS Native SDK": "OSS Native SDK"
  };

  const prefixTranslations = [
    "Session check failed",
    "Save failed",
    "Create failed",
    "Delete failed",
    "Check failed",
    "Preflight failed",
    "Migration failed",
    "Metadata sync failed",
    "Worker trigger failed",
    "Load jobs failed",
    "Load status failed",
    "Failed to load recipes",
    "Failed to load repositories",
    "Failed to load blob stores",
    "Failed to load users",
    "Failed to load roles",
    "Failed to load privileges",
    "Failed to load realms",
    "Failed to load LDAP settings",
    "Failed to load OIDC settings",
    "Failed to load anonymous settings",
    "Failed to load API keys",
    "Failed to load audit log",
    "Failed to refresh realms",
    "Failed to load session",
    "Failed to load administrator bootstrap status",
    "Failed to load permissions",
    "Failed to load upload repositories",
    "Failed to load upload specs",
    "Failed to generate API key",
    "Failed to reset API key",
    "Failed to delete API key",
    "Search failed",
    "Upload failed",
    "Invalid JSON",
    "Sign in failed",
    "OIDC is enabled but required fields are missing",
    "Last runtime error"
  ];

  function readCachedSettings() {
    try {
      const cached = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
      return normalizeSettings(cached);
    } catch {
      return normalizeSettings({});
    }
  }

  function normalizeSettings(value) {
    const defaultLanguage = normalizeDefaultLanguage(value?.defaultLanguage);
    return {
      defaultLanguage,
      supportedDefaultLanguages: Array.isArray(value?.supportedDefaultLanguages)
        ? value.supportedDefaultLanguages
        : ["browser", "zh-CN", "en"],
      updatedAt: value?.updatedAt || null
    };
  }

  function normalizeDefaultLanguage(value) {
    if (value === "zh" || value === "zh-cn" || value === "zh_CN" || value === "zh-CN") return "zh-CN";
    if (value === "en" || value === "en-US") return "en";
    if (value === "browser") return "browser";
    return SUPPORTED_DEFAULT_LANGUAGES.has(value) ? value : DEFAULT_LANGUAGE;
  }

  function resolveLanguage(defaultLanguage) {
    const mode = normalizeDefaultLanguage(defaultLanguage);
    if (mode === "zh-CN" || mode === "en") return mode;
    const languages = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language];
    return languages.some((language) => String(language || "").toLowerCase().startsWith("zh"))
      ? "zh-CN"
      : "en";
  }

  function updateSettings(nextSettings, options = {}) {
    settings = normalizeSettings(nextSettings);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    const nextLanguage = resolveLanguage(settings.defaultLanguage);
    const changed = nextLanguage !== currentLanguage || options.force;
    currentLanguage = nextLanguage;
    if (changed) apply();
    dispatchChange();
    return settings;
  }

  async function loadSettings() {
    try {
      const response = await fetch("/internal/ui-settings", {
        headers: { Accept: "application/json" },
        cache: "no-store"
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return updateSettings(await response.json());
    } catch {
      dispatchChange();
      return settings;
    }
  }

  async function saveDefaultLanguage(defaultLanguage) {
    const normalized = normalizeDefaultLanguage(defaultLanguage);
    const response = await fetch("/internal/ui-settings", {
      method: "PUT",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ defaultLanguage: normalized }),
      cache: "no-store"
    });
    if (!response.ok) {
      throw new Error(await responseMessage(response, `HTTP ${response.status}`));
    }
    return updateSettings(await response.json(), { force: true });
  }

  async function responseMessage(response, fallback) {
    try {
      const text = await response.text();
      if (!text) return fallback;
      const json = JSON.parse(text);
      return json.message || json.error || text;
    } catch {
      return fallback;
    }
  }

  function setDefaultLanguage(defaultLanguage) {
    return updateSettings({ ...settings, defaultLanguage }, { force: true });
  }

  function translate(value) {
    if (currentLanguage !== "zh-CN") return String(value ?? "");
    return translateBody(normalizeBody(value));
  }

  function translateBody(body) {
    if (!body) return body;
    if (zh[body]) return zh[body];
    let match = body.match(/^([^A-Za-z0-9\u4e00-\u9fff]+)\s+(.+)$/);
    if (match) {
      const translated = translateBody(match[2]);
      if (translated !== match[2]) return `${match[1]} ${translated}`;
    }
    for (const prefix of prefixTranslations) {
      if (body === prefix) return zh[prefix] || prefix;
      if (body.startsWith(`${prefix}: `)) {
        return `${zh[prefix] || prefix}：${body.slice(prefix.length + 2)}`;
      }
    }
    match = body.match(/^Page (\d+) \/ (\d+)$/);
    if (match) return `第 ${match[1]} 页 / 共 ${match[2]} 页`;
    match = body.match(/^(\d+)-(\d+) of (\d+)$/);
    if (match) return `${match[1]}-${match[2]} / 共 ${match[3]}`;
    match = body.match(/^(.+) sort (ascending|descending|none)$/);
    if (match) {
      const order = { ascending: "升序", descending: "降序", none: "无排序" }[match[2]] || match[2];
      return `${translateBody(match[1])}排序：${order}`;
    }
    match = body.match(/^Copy (.+)$/);
    if (match) return `复制 ${match[1]}`;
    match = body.match(/^Open (.+) in Browse$/);
    if (match) return `在浏览中打开 ${match[1]}`;
    match = body.match(/^Delete repository "(.+)"\?$/);
    if (match) return `删除仓库“${match[1]}”？`;
    match = body.match(/^Delete role "(.+)"\?$/);
    if (match) return `删除角色“${match[1]}”？`;
    match = body.match(/^Delete privilege "(.+)"\?$/);
    if (match) return `删除权限“${match[1]}”？`;
    match = body.match(/^Delete user "(.+)"\?$/);
    if (match) return `删除用户“${match[1]}”？`;
    match = body.match(/^Delete (.+)\?$/);
    if (match) return `删除 ${match[1]}？`;
    match = body.match(/^Edit repository: (.+)$/);
    if (match) return `编辑仓库：${match[1]}`;
    match = body.match(/^Edit blob store: (.+)$/);
    if (match) return `编辑 Blob 存储：${match[1]}`;
    match = body.match(/^Edit user: (.+)$/);
    if (match) return `编辑用户：${match[1]}`;
    match = body.match(/^Edit role: (.+)$/);
    if (match) return `编辑角色：${match[1]}`;
    match = body.match(/^Edit privilege: (.+)$/);
    if (match) return `编辑权限：${match[1]}`;
    match = body.match(/^API key created: (.+)$/);
    if (match) return `API Key 已创建：${match[1]}`;
    match = body.match(/^Docker cache cleared for (.+)$/);
    if (match) return `已清理 ${match[1]} 的 Docker 缓存`;
    match = body.match(/^Default language: (.+)\. Active language: (.+)\.$/);
    if (match) return `默认语言：${translateBody(match[1])}。当前语言：${translateBody(match[2])}。`;
    match = body.match(/^Current language: (.+)$/);
    if (match) return `当前语言：${translateBody(match[1])}`;
    return body;
  }

  function normalizeBody(value) {
    return String(value ?? "").trim().replace(/\s+/g, " ");
  }

  function translateWithWhitespace(original) {
    const body = normalizeBody(original);
    if (!body) return original;
    const translated = translateBody(body);
    if (translated === body) return original;
    const leading = String(original).match(/^\s*/)?.[0] || "";
    const trailing = String(original).match(/\s*$/)?.[0] || "";
    return `${leading}${translated}${trailing}`;
  }

  function apply(root = document.body || document.documentElement) {
    if (!root) return;
    applying = true;
    try {
      document.documentElement.lang = currentLanguage === "zh-CN" ? "zh-CN" : "en";
      document.title = currentLanguage === "zh-CN"
        ? translateBody(normalizeBody(originalTitle))
        : originalTitle;
      applyNode(root);
    } finally {
      applying = false;
    }
  }

  function applyNode(node) {
    if (!node) return;
    if (node.nodeType === Node.TEXT_NODE) {
      if (isI18nSkipped(node.parentElement)) return;
      applyTextNode(node);
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    if (isI18nSkipped(node)) return;
    applyAttributes(node);
    if (SKIP_TEXT_TAGS.has(node.tagName)) return;
    node.childNodes.forEach(applyNode);
  }

  function isI18nSkipped(element) {
    return Boolean(element?.closest?.("[data-i18n-skip]"));
  }

  function applyTextNode(node) {
    const original = textOriginals.get(node) || node.nodeValue;
    if (!textOriginals.has(node)) textOriginals.set(node, original);
    const nextValue = currentLanguage === "zh-CN" ? translateWithWhitespace(original) : original;
    if (node.nodeValue !== nextValue) {
      node.nodeValue = nextValue;
    }
  }

  function applyAttributes(element) {
    for (const name of ATTRIBUTE_NAMES) {
      if (!element.hasAttribute(name)) continue;
      let originals = attributeOriginals.get(element);
      if (!originals) {
        originals = {};
        attributeOriginals.set(element, originals);
      }
      if (!(name in originals)) originals[name] = element.getAttribute(name);
      const original = originals[name];
      const nextValue = currentLanguage === "zh-CN" ? translateWithWhitespace(original) : original;
      if (element.getAttribute(name) !== nextValue) {
        element.setAttribute(name, nextValue);
      }
    }
  }

  function isExpectedTranslatedText(node) {
    if (!textOriginals.has(node)) return false;
    const original = textOriginals.get(node);
    const expected = currentLanguage === "zh-CN" ? translateWithWhitespace(original) : original;
    return node.nodeValue === expected;
  }

  function isExpectedTranslatedAttribute(element, name) {
    const originals = attributeOriginals.get(element);
    if (!originals || !(name in originals)) return false;
    const original = originals[name];
    const expected = currentLanguage === "zh-CN" ? translateWithWhitespace(original) : original;
    return element.getAttribute(name) === expected;
  }

  function rememberChangedAttribute(element, name) {
    let originals = attributeOriginals.get(element);
    if (!originals) {
      originals = {};
      attributeOriginals.set(element, originals);
    }
    originals[name] = element.getAttribute(name);
  }

  function observe() {
    if (observing || !document.body) return;
    observer = new MutationObserver((records) => {
      if (applying) return;
      applying = true;
      try {
        for (const record of records) {
          if (record.type === "characterData") {
            if (isI18nSkipped(record.target.parentElement)) continue;
            if (isExpectedTranslatedText(record.target)) continue;
            textOriginals.set(record.target, record.target.nodeValue);
            applyTextNode(record.target);
          } else if (record.type === "attributes") {
            if (isI18nSkipped(record.target)) continue;
            if (isExpectedTranslatedAttribute(record.target, record.attributeName)) continue;
            rememberChangedAttribute(record.target, record.attributeName);
            applyAttributes(record.target);
          } else {
            record.addedNodes.forEach(applyNode);
          }
        }
      } finally {
        applying = false;
      }
    });
    observer.observe(document.body, {
      attributes: true,
      attributeFilter: ATTRIBUTE_NAMES,
      characterData: true,
      childList: true,
      subtree: true
    });
    observing = true;
  }

  function dispatchChange() {
    window.dispatchEvent(new CustomEvent("kkrepo:i18n-change", {
      detail: {
        defaultLanguage: settings.defaultLanguage,
        currentLanguage,
        settings
      }
    }));
  }

  function installDialogWrappers() {
    const nativeAlert = window.alert.bind(window);
    const nativeConfirm = window.confirm.bind(window);
    window.alert = (message) => nativeAlert(translate(message));
    window.confirm = (message) => nativeConfirm(translate(message));
  }

  function onReady() {
    apply();
    observe();
    readyResolve(settings);
    loadSettings();
  }

  window.kkrepoI18n = {
    apply,
    currentLanguage: () => currentLanguage,
    defaultLanguage: () => settings.defaultLanguage,
    loadSettings,
    ready: () => readyPromise,
    saveDefaultLanguage,
    setDefaultLanguage,
    settings: () => settings,
    text: translate
  };

  installDialogWrappers();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", onReady, { once: true });
  } else {
    onReady();
  }
})();
