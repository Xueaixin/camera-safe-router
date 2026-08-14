# 京津冀 RAM 方案

更新时间：2026-08-14
分支：`jingjinji-RAM`

## 1. 方案定位

本方案属于“路线规划三方案”（见 dev 分支《路线规划三方案总览.md》）中的方案二：**京津冀路网 + 后端 RAM 存储 + 后端自寻全路线**。适合 4G 内存服务器、希望后端自给自足且不接受高德路线配额的场景。

## 2. 数据与配置

| 项 | 内容 |
|---|---|
| PBF | `osm/jingjinji-latest.osm.pbf`（约 177MB） |
| 图缓存 | `graph-cache/jingjinji-compliant-time-v2`（RAM 存储） |
| 边界 | `boundaries/sixth-ring-boundary-jingjinji.geojson`（schema v4 京津冀版，`sourcePbfSha256=2a1bcebc…`） |
| 启动方式 | `ROUTING_DATA_ROOT=<数据根>`，其余走 `application.yml` 默认值 |

注意：正式数据根中同时保留北京方案与京津冀方案的 PBF/缓存，边界通过文件名区分（北京 `sixth-ring-boundary.geojson`、京津冀 `sixth-ring-boundary-jingjinji.geojson`），本分支全部默认指向京津冀文件。

## 3. 路由链路

- 场景 2（界内↔界内）：受控区（六环+通州）合规路线，摄像头硬避让、收费站/互转走廊、省界禁行规则。
- 场景 1（界外↔界外）：后端在京津冀路网内自寻全路线（界外 A*）。
- 场景 3（界内↔界外）：受控内段合规路线 + 界外段由后端 A* 完成，完整行程全部由后端返回。
- 前端不调用高德路线规划，无 JS API 配额消耗；高德仅用于地图底图、POI 搜索与 URI 导航跳转。

## 4. 内存与性能

- 后端加载图后实测 java 进程约 2.7GB 私有内存（RAM 存储整图驻留堆内）。
- 4G 服务器推荐 `-Xmx2048m`；2G 服务器需 4G swap 兜底且不推荐。
- 启动约 2~3 分钟；跨界/界内查询正常。

## 5. 数据准备（正式目录）

```powershell
# 初始化数据根（自动创建 beijing/jingjinji 两套目录结构）
.\scripts\init-data-directory.ps1

# 京津冀 PBF 与边界（已就位）
osm\jingjinji-latest.osm.pbf
boundaries\sixth-ring-boundary-jingjinji.geojson   # approvedForProduction=true

# 图缓存（RAM，已就位）
graph-cache\jingjinji-compliant-time-v2
```

缓存与 PBF/边界哈希一致性由 `verify-data-consistency.ps1` 校验；缓存缺失时用 `java -jar server.jar graph-build --pbf <jingjinji pbf> --cache-out <空目录>` 冷构图后发布到正式目录。

## 6. 部署

部署步骤见本分支《部署上线实施清单.md》。核心差异：数据包使用 `build-deployment-package.ps1 -Pbf <jingjinji pbf>`，JVM 按 4G 主方案 `-Xmx2048m`。

## 7. 与其它方案差异

| 对比项 | 本方案（RAM） | 方案三（MMAP） | 方案一（北京+高德） |
|---|---|---|---|
| 路网 | 京津冀 | 京津冀 | 北京 |
| 存储 | RAM | MMAP | RAM |
| 内存 | 约 2.7GB | 约 1.5GB | 约 1GB |
| 界外路线 | 后端 A* | 后端 A* | 高德 JS API |
| 配额 | 无 | 无 | 有（场景 3） |
| 2G 服务器 | 不推荐 | 可（+swap） | 可 |
