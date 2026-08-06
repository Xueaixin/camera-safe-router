# 摄像头安全路线规划

一个面向京津冀区域的移动端优先 Web 路线规划 MVP。用户输入起点和终点后，后端基于本地 OSM 路网规划驾车路线，并在搜索过程中硬避开摄像头点位对应的道路；网页负责高德地图展示、当前位置显示和手动从当前位置重新规划。

> 当前项目属于技术验证版本。摄像头来源、坐标精度和 OSM 道路属性仍需持续核验，路线结果不能替代交通法规、道路标志或正式导航产品。

## 当前状态

- 前端 Vue 页面、地图展示、浏览器定位、摄像头图层和手动重新规划已实现。
- 后端 Spring Boot API、GraphHopper 搜索期硬禁边、JTS 独立安全校验和快照持久化已实现。
- 默认安全半径为 30 米；任何成功路线都必须满足 `cameraConflictCount = 0`，不存在违规降级路线。
- 当前摄像头源为 6,803 条；生产加载器只排除 `IsSixRingOut` 语义为 `1` 的记录，缺失或非法值保留并计数。
- 当前保留 5,707 个点，其中 30 米匹配 5,688 个、未匹配 19 个。
- 摄像头远程更新已支持后端内部定时执行和本机手动触发；默认关闭，确认数据源使用许可后通过环境变量开启。
- 后端业务日志使用中文，同时输出到控制台和数据根目录的 `logs`，按日期和大小滚动归档。
- 京津冀路网和 `COMPLIANT_DISTANCE_V1` 已成为后端默认：启用 OSM 转向限制、距离主导权重和受限道路端点例外。六环生产拓扑、多目标搜索、跨界单调硬约束和 OpenAPI 1.3 已接入正式路线接口；当前规划器的 10 条跨界与 4 条环内路线已通过真实图零冲突回归，候选边界、地图人工验收和 4 个保留位置仍未获生产批准。
- 当前仅承诺京津冀候选范围内的驾车和手动重新规划，不包含自动偏航重算和逐向导航。

详细进度和未完成项见 [开发计划与当前进度](docs/plans/开发计划与当前进度.md)。

## 核心设计

```mermaid
flowchart LR
    U["移动端或桌面浏览器"] --> W["Vue 3 + 高德 Web JS API"]
    W -->|"WGS84 / GCJ-02 显式坐标"| A["Spring Boot API"]
    P["jingjinji-latest.osm.pbf"] --> G["GraphHopper 路网与缓存"]
    B["六环内外边界 GeoJSON"] --> T["有向通行口拓扑"]
    G --> T
    C["摄像头 camera.json"] --> S["摄像头与禁行边快照"]
    T --> R["四模式路线规划与多目标搜索"]
    S --> R
    R --> V["JTS 最终路线独立校验"]
    V --> A
```

安全约束分成两层：GraphHopper 在 A*/Dijkstra 搜索期间把禁行边权重设为无穷，JTS 在返回前再次检查路线几何。仅在两层均通过时返回路线。

## 技术栈

| 模块 | 技术 |
|---|---|
| 前端 | Vue 3、TypeScript、Vite、Pinia、高德地图 Web JS API 2.0 |
| 前端测试 | Vitest、Vue Test Utils、Playwright |
| 后端 | Java 21、Spring Boot 3.5、Maven 3.8+ |
| 路由与空间 | GraphHopper 11、JTS 1.20、OSM PBF |
| 数据存储 | 外部文件目录、GraphHopper 图缓存、版本化 JSON 快照 |

选择单体 Java 后端是为了直接嵌入 GraphHopper，降低首期部署和跨服务调用复杂度。前端只消费与客户端无关的 HTTP API，后续 App 可以复用同一后端。

## 仓库结构

```text
code/
├─ README.md                 项目入口
├─ .gitignore               统一忽略规则
├─ docs/                    按 plans、guides、reference、reports 分类的当前文档
├─ scripts/                 跨模块开发辅助脚本
├─ server/                  Java 后端
└─ web/                     Vue 前端
```

PBF、摄像头 JSON、图缓存、运行快照、证书和真实密钥不属于 Git 仓库。

后端包职责、前端目录、启动/路线/更新调用链以及常见改动位置见 [代码结构与核心流程](docs/reference/代码结构与核心流程.md)。

## 运行数据与离线工作区

Windows 默认使用后端启动所在盘根目录的 `camera-safe-routing-data`。该目录只保存服务启动输入和运行产物；例如从 `F:` 盘的 IDEA 工程启动时：

```text
F:\camera-safe-routing-data\
├─ osm\jingjinji-latest.osm.pbf
├─ cameras\camera.json
├─ boundaries\sixth-ring-boundary.geojson
├─ graph-cache\jingjinji-compliant-distance-v1\
├─ graph-cache\jingjinji\             旧 profile 回退缓存，可选保留
├─ snapshots\
├─ downloads\cameras\
├─ failed\cameras\
├─ backups\cameras\
└─ logs\
```

离线下载、PBF 切分、候选构图、六环分析、工具和审查材料统一放在仓库同级的工作区，不是服务启动依赖：

```text
F:\CodexProjects\routing-plan\workspace\
├─ downloads\osm\
├─ tools\
├─ work\osm\
├─ work\graph-cache-candidates\
├─ work\sixth-ring\
├─ reports\
├─ reviews\
└─ archive\
```

在 `code` 目录执行以下脚本可分别创建两棵目录结构：

```powershell
.\scripts\init-data-directory.ps1
.\scripts\init-workspace-directory.ps1
```

如果希望使用其他位置，只设置一个系统或 IDEA 环境变量即可：

```text
ROUTING_DATA_ROOT=D:\camera-safe-routing-data
```

单项路径仍可覆盖，但普通开发不需要。完整规则见 [运行配置与数据目录](docs/guides/运行配置与数据目录.md)。

## 数据来源

| 数据 | 当前用途 | 来源 |
|---|---|---|
| 京津冀 OSM PBF | 当前路网，由中国源离线切分 | `osm/jingjinji-latest.osm.pbf` |
| 六环边界 | G4501 关系生成的内外双环候选边界 | `boundaries/sixth-ring-boundary.geojson`；当前仍为未生产批准状态 |
| 中国 OSM PBF | 京津冀切分和后续月更源 | [china-latest.osm.pbf](http://download.openstreetmap.fr/extracts/asia/china-latest.osm.pbf) |
| 摄像头点位 | 禁行点快照 | `POST https://www.jjz365.cn/CameraData/GetAllRing`，正式文件名为 `camera.json` |

OSM 数据遵循 Open Database License，地图或衍生数据发布时必须保留 OpenStreetMap 署名。摄像头接口的可用性、使用许可、频率限制和字段稳定性需要在自动同步上线前确认。

## 环境要求

- JDK 21
- Maven 3.8 或更高版本
- Node.js 24.16.x
- npm 11 或与 Node 24 配套版本
- IntelliJ IDEA、WebStorm 可选
- 高德 Web JS Key 和安全密钥，仅保存在 `web/.env.local`

`osmium-tool` 只在从中国 PBF 离线切分京津冀数据时需要，运行 Spring Boot 服务不需要它。Windows 可以使用便携 `micromamba` 在离线工作区创建隔离环境，无需修改全局 PATH 或现有 Python：

```powershell
$workspaceRoot = 'F:\CodexProjects\routing-plan\workspace'
$micromambaHome = Join-Path $workspaceRoot 'tools\micromamba'
$micromambaArchive = Join-Path $micromambaHome 'micromamba-win-64.tar.bz2'
New-Item -ItemType Directory -Path $micromambaHome -Force | Out-Null
Invoke-WebRequest `
  -Uri 'https://micro.mamba.pm/api/micromamba/win-64/latest' `
  -OutFile $micromambaArchive
tar.exe -xjf $micromambaArchive -C $micromambaHome

$micromamba = Join-Path $workspaceRoot 'tools\micromamba\Library\bin\micromamba.exe'
$env:MAMBA_ROOT_PREFIX = Join-Path $workspaceRoot 'tools\micromamba-root'
& $micromamba create -y `
  -p (Join-Path $workspaceRoot 'tools\osmium-env') `
  -c conda-forge `
  'osmium-tool=1.19.1'
```

上述压缩包来自 [micromamba 官方 Windows 下载地址](https://micro.mamba.pm/api/micromamba/win-64/latest)。默认工具路径是 `F:\CodexProjects\routing-plan\workspace\tools\osmium-env\Library\bin\osmium.exe`。Linux 服务器可以安装发行版的 `osmium-tool`，但若直接上传本地切分好的京津冀 PBF，服务端不需要再安装。

切分和引用完整性检查：

```powershell
.\scripts\prepare-jingjinji-osm.ps1 `
  -WorkspaceRoot 'F:\CodexProjects\routing-plan\workspace' `
  -DataRoot 'F:\camera-safe-routing-data'
```

脚本默认读取 `workspace/downloads/osm/china-latest.osm.pbf`，把候选产物写到 `workspace/work/osm`，不会自动覆盖运行目录中的正式 PBF。它使用 `smart` 策略和 `113.0,35.5,120.5,43.0` 边界，检查所有 way 节点引用，并单独检查 GraphHopper 使用的 `type=restriction` 关系。当前 POC 明细见 [京津冀路网 POC 验证报告](docs/reports/京津冀路网POC验证报告.md)。

## 后端启动

1. 将已验证的京津冀 PBF 放到数据根目录的 `osm/jingjinji-latest.osm.pbf`。
2. 将摄像头 JSON 放到 `cameras/camera.json`。
3. 将当前六环边界放到 `boundaries/sixth-ring-boundary.geojson`；服务会校验其中的来源 PBF SHA-256。
4. IDEA 打开 `server/pom.xml`，Project SDK、Maven Runner JRE 均选择 JDK 21。
5. 启动类为 `cn.camera.safe.CameraSafeRoutingApplication`。已有 `graph-cache/jingjinji-compliant-distance-v1` 时直接 Debug 即可，不需要设置路网环境变量；建议 VM options 使用 `-Xms1g -Xmx2g`。

也可以从 PowerShell 启动：

```powershell
Set-Location .\server
mvn test
mvn spring-boot:run
```

首次启动会解析 PBF 并生成 `jingjinji-compliant-distance-v1` 图缓存；后续启动直接加载缓存。PBF 内容或 profile/import 配置变化时不得继续复用旧缓存，服务会通过源文件与路由配置两份 SHA-256 元数据拒绝不一致组合。临时回退旧 profile 时可显式设置 `ROUTING_PROFILE_MODE=CURRENT`，它只会读取 `graph-cache/jingjinji`。

业务日志默认写入数据根目录的 `logs/camera-safe-routing-server.log`，归档文件位于 `logs/archive`。完整滚动和保留配置见 [运行配置与数据目录](docs/guides/运行配置与数据目录.md)。

就绪检查：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
curl.exe -i http://localhost:8080/api/v1/readiness
```

## 前端启动

在 `web/.env.local` 填写本机配置：

```dotenv
VITE_AMAP_KEY=你的高德WebJSKey
VITE_AMAP_SECURITY_CODE=你的高德安全密钥
VITE_API_BASE_URL=
VITE_USE_MOCK_API=false
```

本地开发推荐让 `VITE_API_BASE_URL` 保持为空，Vite 会把同源 `/api` 代理到 `http://127.0.0.1:8080`。

```powershell
Set-Location .\web
npm ci
npm run dev
```

桌面浏览器通常可通过 `http://localhost:5173` 访问。

### 手机 HTTPS 开发

手机通过局域网访问时，浏览器 Geolocation 通常要求可信 HTTPS。`npm run dev:https` 固定读取：

```text
web/certs/dev-cert.pem
web/certs/dev-key.pem
```

这两个文件不会提交到 Git，每台开发电脑都需要单独生成。Windows 推荐通过 winget 安装：

```powershell
winget install FiloSottile.mkcert
```

winget 不可用时，可从 [mkcert 官方发行版](https://github.com/FiloSottile/mkcert/releases) 安装。安装后重新打开 PowerShell，并确认可以执行 `mkcert`。

先通过 `ipconfig` 找到电脑当前局域网 IPv4，例如 `192.168.1.20`，然后在 `web` 目录执行：

```powershell
Set-Location .\web
New-Item -ItemType Directory -Path .\certs -Force | Out-Null
mkcert -install
mkcert `
  -cert-file .\certs\dev-cert.pem `
  -key-file .\certs\dev-key.pem `
  localhost 127.0.0.1 ::1 192.168.1.20
npm run dev:https
```

把示例 IP 替换为本机实际局域网 IP。证书必须包含手机访问时使用的 IP；电脑 IP 变化后需要重新生成证书。

电脑执行 `mkcert -install` 后会信任本地开发 CA。手机还需要信任同一个开发 CA：

```powershell
mkcert -CAROOT
```

该命令会显示 CA 目录。只把其中的 `rootCA.pem` 安装到自己的测试手机，绝不能复制或分享 `rootCA-key.pem`。

- Android：在系统安全设置中选择“安装 CA 证书”，菜单名称因系统版本而异。
- iOS：安装 `rootCA.pem` 描述文件后，还要在“设置 -> 通用 -> 关于本机 -> 证书信任设置”中启用完全信任。

随后在手机访问：

```text
https://192.168.1.20:5173
```

手机与电脑必须处于同一局域网，Windows 防火墙需要允许 Node/Vite，且高德控制台应允许当前开发来源。地址栏仍有证书警告时，定位权限通常不会正常工作；证书可信后还要在浏览器站点设置中允许位置访问。

完整真机检查项见 [验证与测试指南](docs/guides/验证与测试指南.md)。

## 常用验证

后端：

```powershell
Set-Location .\server
mvn test
mvn package
```

前端：

```powershell
Set-Location .\web
npm run typecheck
npm run lint
npm run test
npm run build
```

真实 PBF、摄像头匹配报告、零冲突路线、API 冒烟和手机验证的完整命令见 [验证与测试指南](docs/guides/验证与测试指南.md)。

## API

唯一机器可读契约为 [api-contract.yaml](docs/reference/api-contract.yaml)。当前接口：

路线响应已经实现 OpenAPI 1.3：服务端自动判定四种规划模式。跨界路线分别返回用于技术审计的 `boundaryCrossing`、可证明位于普通道路上时才返回的 `navigationHandoff`、兼容保留的严格环外审计点 `externalHandoff`、分段路线和完整 `geometry`。前端默认保留完整路线，仅在 `navigationHandoff` 非空时标点并提供“设为起点/终点”；弹窗围绕该导航交接点解析 200 米内的交通地标，并提供基于 GCJ-02 精确坐标的高德导航。当前默认边界仍为开发候选状态，真实高德和地图人工验收尚未完成，不能据此声明已经满足生产发布条件。

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/routes` | 规划零摄像头冲突路线 |
| `GET` | `/api/v1/cameras` | 按地图范围查询点位；按实际命中数量保护响应规模 |
| `GET` | `/api/v1/camera-snapshots/current` | 查询当前快照状态 |
| `POST` | `/api/v1/admin/camera-snapshots/refresh` | 本机触发摄像头快照刷新 |
| `POST` | `/api/v1/admin/camera-data/update` | 本机立即下载、校验并发布摄像头数据 |
| `GET` | `/api/v1/health` | 进程存活状态 |
| `GET` | `/api/v1/readiness` | 路网、摄像头和禁行边就绪状态 |

坐标系和错误码约定见 [坐标系与接口约定](docs/reference/坐标系与接口约定.md)。

## 文档入口

- [文档索引](docs/README.md)
- [方案与进度](docs/plans/开发计划与当前进度.md)
- [运行与验证指南](docs/guides/运行配置与数据目录.md)
- [规范与接口契约](docs/reference/坐标系与接口约定.md)
- [验证与回归报告](docs/reports/六环东南西北真实路线回归报告.md)

## Git 仓库状态

`code` 已建立统一 Git 仓库，`server` 和 `web` 都由根仓库直接跟踪，两个子目录中不再包含嵌套 `.git` 元数据。不要在子目录中再次执行 `git init` 或引入独立仓库，否则会破坏统一版本管理并可能形成 gitlink。

根级 `.gitignore` 已排除构建产物、密钥、证书和运行数据。提交前仍应检查暂存区，确认 `.env.local`、HTTPS 私钥、PBF、图缓存、快照和日志没有进入版本控制。
