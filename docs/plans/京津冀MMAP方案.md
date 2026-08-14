# 京津冀 MMAP 方案

更新时间：2026-08-14
分支：`jingjinji-MMAP`

## 1. 方案定位

本方案属于“路线规划三方案”（见 dev 分支《路线规划三方案总览.md》）中的方案三：**京津冀路网 + 后端 MMAP 存储 + 后端自寻全路线**。图数据以内存映射文件方式加载，进程常驻内存显著低于 RAM 方案，适合 2G 低配服务器。

## 2. 数据与配置

| 项 | 内容 |
|---|---|
| PBF | `osm/jingjinji-latest.osm.pbf`（约 177MB） |
| 图缓存 | `graph-cache/jingjinji-compliant-time-v2-mmap`（MMAP 存储） |
| 边界 | `boundaries/sixth-ring-boundary-jingjinji.geojson`（schema v4 京津冀版，`sourcePbfSha256=2a1bcebc…`） |
| 存储开关 | 构图与启动均需 `-Drouting.graph.storage=MMAP` |

注意：RAM 与 MMAP 构建的缓存不兼容，切换存储类型必须重新构图，缓存目录必须区分（`...-v2` / `...-v2-mmap`）。本分支 `application.yml` 默认 `candidate-graph-cache-path` 已指向 `-v2-mmap`。

## 3. 路由链路

- 场景 2（界内↔界内）：受控区（六环+通州）合规路线，摄像头硬避让、收费站/互转走廊、省界禁行规则。
- 场景 1（界外↔界外）：后端在京津冀路网内自寻全路线（界外 A*）。
- 场景 3（界内↔界外）：受控内段合规路线 + 界外段由后端 A* 完成，完整行程全部由后端返回。
- 前端不调用高德路线规划，无 JS API 配额消耗；高德仅用于地图底图、POI 搜索与 URI 导航跳转。

## 4. 内存与性能（实测）

- 后端加载图后实测 java 进程约 1.5GB（`-Xmx2048m` 与 `-Xmx1280m` 均约 1.5GB），相比 RAM 方案（约 2.3~2.7GB）降幅约 1/3。
- 启动约 1.5~2 分钟，与 RAM 基本持平。
- 寻路（预热后）与 RAM 同水平：毫秒级，朱辛庄↔武清跨界约 0.4~0.6s，低配约 0.6~1.5s；路线结果与 RAM 完全一致。
- 真实 2G 物理内存下跨界长查询的换页行为需在真机上验证。

## 5. 数据准备（正式目录）

MMAP 缓存已构图并验证：

```text
osm\jingjinji-latest.osm.pbf
boundaries\sixth-ring-boundary-jingjinji.geojson   # approvedForProduction=true
graph-cache\jingjinji-compliant-time-v2-mmap       # MMAP_STORE，sourceSha256=2a1bcebc…
```

缓存更新（PBF 变更后）：

```powershell
java -Xmx4g -Drouting.graph.storage=MMAP -jar server.jar graph-build `
  --pbf <jingjinji pbf> --cache-out <空目录> --threads 4
java -Drouting.graph.storage=MMAP -jar server.jar graph-check --pbf <jingjinji pbf> --cache <缓存目录>
```

## 6. 部署

- 启动必须带 `-Drouting.graph.storage=MMAP`（systemd 模板已含）。
- 部署步骤见本分支《部署上线实施清单.md》。
- 数据包由 `build-deployment-package.ps1 -Pbf <jingjinji pbf>` 产出，脚本已默认以 MMAP 构图。

## 7. 与其它方案差异

| 对比项 | 本方案（MMAP） | 方案二（RAM） | 方案一（北京+高德） |
|---|---|---|---|
| 路网 | 京津冀 | 京津冀 | 北京 |
| 存储 | MMAP | RAM | RAM |
| 内存 | 约 1.5GB | 约 2.7GB | 约 1GB |
| 界外路线 | 后端 A* | 后端 A* | 高德 JS API |
| 配额 | 无 | 无 | 有（场景 3） |
| 2G 服务器 | 可（+swap） | 不推荐 | 可 |
