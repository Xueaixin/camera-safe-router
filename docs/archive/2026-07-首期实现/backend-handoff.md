# 后端实现交付说明

交付日期：2026-07-30

## 1. 实现内容

- 在 `code/server` 创建 Java 21、Maven 3.8+ 的 Spring Boot 服务，外部配置 PBF、GraphHopper 图缓存、摄像头 JSON、快照目录、安全半径、线程池、搜索超时和节点上限。
- 使用 GraphHopper 11.0 flexible A*/Dijkstra；CH/LM 均关闭。请求级 `BlockedEdgeSnapshot` 通过自定义 `Weighting` 在搜索热路径硬禁用有向基础边。
- 使用两个不可变 `BitSet` 分别表达正反方向；QueryGraph 虚拟边追溯 `originalEdgeKey`，不能借起终点吸附绕过基础边禁行。
- 校验并加载当前 JSON，保留同坐标不同 ID，执行 GCJ-02/WGS84 转换，构建摄像头 `STRtree` 和全量可驾车边空间索引。
- 以 30 米安全圆与道路几何相交生成双向禁行边；新摄像头、索引、禁行边和持久化元数据全部完成后才原子发布，失败保留进程内上一有效快照。
- 路线返回前由独立 JTS 组件检查最终几何；任何冲突返回 `ROUTE_CONFLICT_DETECTED`，无路线时不返回违规降级路线。
- 实现冻结契约中的 6 个接口：路线、摄像头范围查询、当前快照、本机异步刷新、health 和 readiness；统一错误体和 `X-Request-Id`。
- 提供有界路线线程池、初始化/刷新执行器、请求超时、最大访问节点保护，以及可重复的 PowerShell 基准脚本。

## 2. 修改文件

本次只在允许范围内新增或修改以下内容，没有修改 `code/web`、冻结的 `docs` 文件或原始数据：

- 构建与配置：`code/server/pom.xml`、`.env.example`、`.gitignore`、`src/main/resources/application.yml`。
- 启动与配置类：`CameraSafeRoutingApplication`、`AppProperties`、`ExecutorConfiguration`、`BackendInitializer`。
- GraphHopper 与快照：`code/server/src/main/java/cn/camera/safe/routing/` 全部类。
- 摄像头与坐标：`camera/`、`coordinate/` 全部类。
- 应用服务与 HTTP：`application/`、`api/` 及 `api/model/` 全部类。
- 独立安全校验：`validation/` 全部类。
- 自动测试：`code/server/src/test/java/cn/camera/safe/` 下 16 个测试类和 `src/test/resources/fixtures/` 下 4 个小型 JSON 夹具。
- 工具：`code/server/scripts/benchmark.ps1`。
- 后端文档：`code/docs/backend-dependencies.md`、`backend-routing-poc.md`、`backend-coordinate-control-points.md`、`backend-real-fixtures.md`、`backend-handoff.md`。

工作区根目录不是 Git 工作树，因此无法用 `git status` 提供变更集；上述清单按实际后端目录核对。

## 3. 依赖版本和选择理由

| 组件 | 版本 | 选择理由 |
|---|---:|---|
| Java | 21 | 用户环境和项目编译目标；Enforcer 限制为 `[21,22)` |
| Maven | 3.8+ | 用户环境；Enforcer 限制为 `[3.8,)` |
| GraphHopper Core | 11.0 | Java 21 可运行；源码确认可包装 `Weighting` 并追溯 QueryGraph 原始边；Apache-2.0 |
| Spring Boot | 3.5.16 | 支持 Java 21 和 Maven 3.8；保留 Jackson 2.x 生态，降低与 GraphHopper 的主版本冲突 |
| JTS Core | 1.20.0 | 与 GraphHopper 11.0 依赖线一致，用于空间索引和独立最终几何校验 |
| Maven Enforcer Plugin | 3.5.0 | 构建时明确阻止不符合约束的 Java/Maven 版本 |

详细调查、Maven 坐标、许可证和缓存格式见 `code/docs/backend-dependencies.md`。

## 4. GraphHopper POC 结论和证据

B1 已通过，硬避让不是路线生成后的过滤：

- `BlockedEdgeWeighting.calcEdgeWeight(...)` 在 A*/Dijkstra 扩展边时查询禁行快照，命中返回 `Double.POSITIVE_INFINITY`。
- 正反方向分别测试；只禁一侧时另一侧可通行，双向禁行时两侧均不可通行。
- 虚拟边用 `edgeId >= baseGraph.getEdges()` 识别，再由 `detach(false)` 得到 `VirtualEdgeIteratorState.getOriginalEdgeKey()`，继承基础边及方向限制。
- 起点吸附在已禁基础边、以及封闭所有出口的合成测试均返回无路线，没有违规路线回退。
- `SearchAudit` 记录搜索期间的边检查、虚拟边检查和禁行拒绝，证明拒绝发生在搜索热路径。

当前真实 PBF 的一次通过证据：

```text
pbfSha256=4a0f4bd4bce3a86ace3dacab54d810d3f1708cd454692ce7800e5fb973b355f3
importMillis=8010
cacheLoadMillis=179
baselineEdges=199
blockedEdgeId=182326
searchChecks=16746
blockedRejections=2
rerouteEdges=185
```

禁用基线路线中的基础 edge `182326` 后，重路由不再包含它，搜索期间有 2 次明确拒绝。耗时只记录该次环境，不是性能承诺。完整实现说明和复验命令见 `code/docs/backend-routing-poc.md`。

## 5. 测试代码

默认 `mvn test` 包含 31 项：27 项执行通过，4 项因未提供真实文件/报告属性而明确跳过。覆盖坐标转换、输入校验、JSON 解析和坏数据、同坐标不同 ID、快照失败保留旧版本、禁边方向、QueryGraph、受限起终点、无合规路线、最终几何冲突、快照版本一致性、Controller 契约、错误码、readiness、本机刷新和并发状态。

Surefire 在测试 JVM 启动时把临时目录设为模块的 `target`，JUnit 真实图测试不会依赖用户临时目录权限，且产物由 `mvn clean` 一并清理。

4 个显式测试入口及用途：

| 测试 | 属性 | 用途 |
|---|---|---|
| `RealCameraJsonTest` | `real.camera.json` | 验证 6,797 条、源文件哈希及 50 个候选 ID |
| `CoordinateControlPointReportTest` | `control.camera.json` | 只读生成 20 个坐标人工核对候选 |
| `RealPbfHardAvoidancePocTest` | `poc.pbf` | B1 首次导图、真实重路由和缓存加载 |
| `RealRoutingSnapshotIntegrationTest` | `real.pbf`、`real.camera.json` | 真实 20/30/50 米快照、原子持久化、缓存加载及零冲突路线 |

AI 已实际执行的最终默认构建结果：

```text
Tests run: 31, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

4 个显式入口也已分别运行通过；真实统计见 `code/docs/backend-real-fixtures.md`。这些结果不替代用户在 IDEA 和目标机器上的最终执行。

AI 还曾在临时端口 `18080` 以“源坐标为 GCJ-02”的条件设置启动当前服务并完成 HTTP 冒烟：health 为 `UP`，readiness 为 HTTP 200 且三个加载标志均为 `true`，当前快照为 6,797 个摄像头/30 米，GCJ-02 小范围查询返回 2 条，固定路线返回 44,946.06 米/637 点/零冲突，本机刷新返回 HTTP 202、快照版本变化且 readiness 保持就绪。临时服务已停止；这仍不是用户对坐标源或目标机器的确认。

## 6. 用户验证命令

### 6.1 IDEA 导入和启动

1. 在 IDEA 选择 **Open**，打开 `code/server/pom.xml` 或 `code/server`，按 Maven 项目导入。
2. Project SDK、Module SDK 和 Maven Runner JRE 均选择 JDK 21；Maven home 选择本机 Maven 3.8.x 或更高版本。
3. 等待 Maven 同步完成，主启动类为 `cn.camera.safe.CameraSafeRoutingApplication`。
4. Run Configuration 的 Working directory 设为模块目录 `code/server`。默认 `../../data/...` 路径只在该工作目录下语义正确。
5. VM options 建议先用 `-Xms1g -Xmx2g`，再根据真实导图和并发基准调整；环境变量按第 7 节配置。

### 6.2 PowerShell 测试和构建

从工作区根目录开始：

```powershell
Set-Location .\code\server
mvn --version
mvn test
mvn package
```

可执行 JAR 生成在 `target/camera-safe-routing-server-0.1.0-SNAPSHOT.jar`。真实文件测试使用 JUnit 临时图缓存，不修改源数据：

```powershell
mvn "-Dreal.camera.json=..\..\data\map.json" "-Dtest=RealCameraJsonTest" test
mvn "-Dcontrol.camera.json=..\..\data\map.json" "-Dtest=CoordinateControlPointReportTest" test
mvn "-Dpoc.pbf=..\..\data\Beijing.osm.pbf" "-Dtest=RealPbfHardAvoidancePocTest" test
mvn "-Dreal.pbf=..\..\data\Beijing.osm.pbf" "-Dreal.camera.json=..\..\data\map.json" "-Dtest=RealRoutingSnapshotIntegrationTest" test
```

### 6.3 启动、首次导图和缓存启动

默认安全状态不确认摄像头源坐标，只允许 health 成功；readiness 保持 `503 NOT_READY`，不会发布路线快照：

```powershell
$env:JAVA_TOOL_OPTIONS='-Xms1g -Xmx2g'
mvn spring-boot:run
```

完成第 7 节所述人工坐标核对后，在同一 PowerShell 进程设置：

```powershell
$env:CAMERA_SOURCE_COORDINATE_VERIFIED='true'
mvn spring-boot:run
```

首次启动时，若 `ROUTING_GRAPH_CACHE_PATH` 中没有 GraphHopper `properties`，服务必须能读取 PBF，先导图并写缓存和 `camera-safe-source.sha256`，再建道路索引与摄像头快照。HTTP 端口会先监听，初始化在后台进行；期间 `/health` 为 200，而 `/readiness` 为 503。导图耗时取决于磁盘、CPU 和堆内存。

后续启动发现完整缓存时直接 `load`，通常明显更快。若 PBF 仍存在，服务会校验其 SHA-256 与缓存元数据一致；PBF 不存在时可只依赖缓存及元数据加载。GraphHopper 版本、PBF、encoded values 或 profile 改变后，请指定一个新的空缓存目录并重新导图，不要混用旧 edge ID 快照。

也可启动已构建 JAR；因为相对路径按当前目录解释，仍需在 `code/server` 执行：

```powershell
java -Xms1g -Xmx2g -jar .\target\camera-safe-routing-server-0.1.0-SNAPSHOT.jar
```

### 6.4 health、readiness 和业务验证

另开 PowerShell：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
curl.exe -i http://localhost:8080/api/v1/readiness
Invoke-RestMethod http://localhost:8080/api/v1/camera-snapshots/current
```

就绪时 readiness 为 HTTP 200，且 `graphLoaded/cameraSnapshotLoaded/blockedEdgesLoaded` 全为 `true`。摄像头 GCJ-02 范围查询：

```powershell
$cameras = Invoke-RestMethod 'http://localhost:8080/api/v1/cameras?minLng=116.39&minLat=39.90&maxLng=116.41&maxLat=39.92&coordinateSystem=GCJ02'
$cameras | Select-Object coordinateSystem,snapshotVersion,@{n='count';e={$_.items.Count}}
```

固定 WGS84 路线请求：

```powershell
$body = @{
  start = @{ lng = 116.3975; lat = 39.9087; coordinateSystem = 'WGS84'; source = 'CURRENT_LOCATION' }
  end = @{ lng = 116.4700; lat = 39.9920; coordinateSystem = 'WGS84'; source = 'MAP_PICK' }
  vehicle = 'CAR'
} | ConvertTo-Json -Depth 4
$route = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/routes' `
  -ContentType 'application/json; charset=utf-8' -Body $body
if ($route.cameraConflictCount -ne 0) { throw '服务错误地返回了冲突路线' }
$route | Select-Object routeId,distanceMeters,durationSeconds,cameraConflictCount,cameraSnapshotVersion,blockedEdgeVersion
```

本机异步刷新并核对版本变化：

```powershell
$before = Invoke-RestMethod http://localhost:8080/api/v1/camera-snapshots/current
$job = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/admin/camera-snapshots/refresh
Start-Sleep -Seconds 2
$after = Invoke-RestMethod http://localhost:8080/api/v1/camera-snapshots/current
$before.snapshotVersion, $job, $after.snapshotVersion
```

刷新端点默认只接受回环地址，不包含生产认证，不能直接暴露到公网。失败刷新应保留 `$before` 对应的进程内快照。

### 6.5 基准脚本

服务 ready 后执行：

```powershell
.\scripts\benchmark.ps1 -BaseUrl 'http://localhost:8080' -Warmup 5 -Iterations 50
```

传入 `-JavaProcessId <PID>` 时脚本会在前后调用 `jcmd <PID> GC.heap_info`。记录 P50、P95、最大耗时、吞吐量、堆使用、首次导图和缓存加载耗时；不要把本交付中的单次 POC 耗时当成容量结论。

常见排查：readiness 显示坐标未确认时检查 `CAMERA_SOURCE_COORDINATE_VERIFIED`；缺 PBF 只允许在完整缓存和 `camera-safe-source.sha256` 同时存在时启动；PBF 哈希不一致时换新空缓存目录；相对路径找不到文件时检查当前工作目录或改用部署环境的绝对路径环境变量；内存不足时先提高堆上限再重新导图。

## 7. 待补配置

使用默认相对路径时，只需确保从 `code/server` 启动。部署或 IDEA 工作目录不同则补充以下环境变量，值应指向部署机文件，不要提交真实 `.env`：

| 变量 | 默认值 | 用户动作 |
|---|---|---|
| `ROUTING_PBF_PATH` | `../../data/Beijing.osm.pbf` | 指向只读 PBF；首次导图必需 |
| `ROUTING_GRAPH_CACHE_PATH` | `../../data/graph-cache` | 指向可写、持久化且独占的图缓存目录 |
| `CAMERA_JSON_PATH` | `../../data/map.json` | 指向只读摄像头 JSON |
| `CAMERA_SNAPSHOT_PATH` | `../../data/snapshots` | 指向支持同文件系统原子移动的可写目录 |
| `CAMERA_SOURCE_COORDINATE_VERIFIED` | `true` | 本地 Demo 默认按 GCJ-02 运行；正式使用前仍须完成人工控制点核对 |
| `ROUTING_SAFETY_RADIUS_METERS` | `30` | 首期保持 30，修改后必须重建并回归快照 |
| `ROUTING_CALCULATION_THREADS` | `4` | 按 CPU/基准调整 |
| `ROUTING_CALCULATION_QUEUE_CAPACITY` | `16` | 按过载策略和基准调整 |
| `ROUTING_REQUEST_TIMEOUT` | `10s` | 按真实最坏路线调整，不能取消保护 |
| `ROUTING_MAX_VISITED_NODES` | `1000000` | 按真实无路搜索基准调整 |
| `CAMERA_MAX_BBOX_RESULTS` | `10000` | 保持有限上限 |
| `CAMERA_MAX_BBOX_SPAN_DEGREES` | `1.0` | 限制恶意大范围查询 |
| `ADMIN_LOCAL_ONLY` | `true` | 当前必须保持 `true`；`false` 会禁用而非开放端点 |
| `SERVER_PORT` | `8080` | 端口冲突时修改 |

JVM 初始建议 `-Xms1g -Xmx2g`，这不是已完成容量测试后的定值。

## 8. 未验证项

- 用户尚未在高德和 OSM 手工确认摄像头原坐标确为 GCJ-02；AI 集成测试把 `CAMERA_SOURCE_COORDINATE_VERIFIED=true` 仅作为条件性测试输入，不代表确认完成。
- `real-camera-candidates-50.json` 中的 50 个真实 ID 尚未逐个完成道路级、方向、主辅路、桥上桥下和匝道人工核对。
- 用户尚未在自己的 IDEA 配置中完成 Maven 导入、启动、测试和构建，也尚未在目标机器验证首次导图与缓存重启。
- 最终 P50/P95/吞吐量、无路最坏耗时和稳定堆内存尚未由用户运行；`-Xms1g -Xmx2g` 只是起始建议。
- 尚未使用覆盖完整北京市域且边界带缓冲区的生产 PBF 回归；当前 PBF 下 30 米半径仍有 867/6,797 个摄像头未匹配。
- 未做生产反向代理、鉴权、TLS、容器和进程守护验证；本机刷新端点仅实现本地保护。

## 9. 已知风险

- 硬避让只对“正确转换并匹配到当前图中的禁行边”成立。坐标源假设、PBF 缺失/截断、道路层级或匹配误判可能造成漏禁；30 米圆命中多条邻近道路也可能过度禁行。
- 当前首期忽略摄像头拍摄方向并双向封路，符合冻结决策但比真实交通限制更保守，可能增加无路线率和绕行距离。
- GraphHopper 的 `VirtualEdgeIteratorState` 不是稳定 API；任何 GraphHopper 升级都必须重跑 B1 正反向、QueryGraph 和无路线门槛测试。
- edge ID 不是外部稳定标识。PBF、GraphHopper、encoded values 或 profile 变化后，旧禁行边快照不可复用。
- 持久化快照用于审计，当前进程启动仍从 JSON 重建内存快照；若重启后的首次构建失败，没有跨进程旧快照可继续服务。进程内刷新失败会保留上一有效版本。
- 路线线程池和超时通过 `Future.cancel(true)` 限制调用方等待；底层算法对中断的响应仍需在最坏无路场景基准中观察。
- 当前成功路线的零冲突保证以未确认的坐标系统、当前 PBF、匹配规则和同一不可变快照为边界，不能外推为完整北京覆盖保证。
