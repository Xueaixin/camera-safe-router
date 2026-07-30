# GraphHopper 硬避让 POC

验证日期：2026-07-30

## 1. 结论

GraphHopper `11.0` 能通过 flexible A*/Dijkstra 的自定义 `Weighting` 在搜索期间实现有向基础边硬禁行。本 POC 已证明：

- 被禁遍历在 `calcEdgeWeight` 搜索热路径返回正无穷，算法不会扩展它；
- 基础边正向和反向可分别禁行，首期双向禁行同时设置两个 `BitSet`；
- QueryGraph 虚拟边按基础图 `originalEdgeKey` 继承禁行，起终点吸附不能绕过；
- 封闭全部出口时 `Path.isFound()` 为 `false`，不会返回违规路线；
- 当前 PBF 可首次导入、正常寻路、动态禁用基线路线边后重路由，并可在不提供 PBF 的情况下从图缓存加载。

B1 门槛通过，可以继续 B2-B9。独立 JTS 几何校验仍按任务书作为第二道约束实现，不能替代搜索期禁边。

## 2. 扩展点和关键类

- `HardAvoidingGraphHopper`：每次路线调用使用 `ThreadLocal` 绑定一个不可变 `BlockedEdgeSnapshot` 和请求级 `SearchAudit`，在 `finally` 中清理。
- `GraphHopper.createWeightingFactory()`：包装 GraphHopper 默认 weighting，CH/LM 均不配置。
- `BlockedEdgeWeighting.calcEdgeWeight(...)`：搜索算法扩展每条边时检查有向基础 edge key；命中后返回 `Double.POSITIVE_INFINITY`。
- `BlockedEdgeSnapshot`：两个克隆后的 `BitSet` 分别保存存储方向和反方向禁行状态。
- `SearchAudit`：记录搜索期边检查、QueryGraph 虚拟边检查和禁行拒绝次数，只用于证据和诊断。

GraphHopper 11.0 的 `QueryGraph.createEdgeExplorer()` 实际把包内私有 `VirtualEdgeIterator` 传给算法，而不是直接传 `VirtualEdgeIteratorState`。POC 使用 GraphHopper 的编号约定 `edgeId >= baseGraph.getEdges()` 识别虚拟边，再仅对虚拟迭代器调用 `detach(false)`，取得公开的 `VirtualEdgeIteratorState.getOriginalEdgeKey()`。基础边热路径不会调用 `detach`，避免为每次检查复制边状态。

方向计算规则：

```text
orientedOriginalKey = virtualEdge.originalEdgeKey 或 baseEdge.edgeKey
traversalOriginalKey = reverse ? reverseEdgeKey(orientedOriginalKey) : orientedOriginalKey
```

因此双向 Dijkstra/A* 的反向搜索也按实际遍历方向检查对应 `BitSet`。

## 3. 自动测试证据

`BlockedEdgeWeightingTest` 使用 GraphHopper 11.0 的内存基础图和真实路由算法，覆盖：

1. 禁用存储方向后改走替代路径；
2. 只禁存储方向时反方向仍可通过；
3. 单独禁用反方向后反向路线改走替代路径；
4. 起点吸附到已双向禁用基础边时，QueryGraph 虚拟边被搜索期拒绝并返回无路线；
5. 所有可达出口禁用后返回无路线。

AI 实际执行结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`RealPbfHardAvoidancePocTest` 使用当前 `data/Beijing.osm.pbf`，覆盖首次导图、基线路线、动态禁边重路由、搜索审计、关闭实例及无 PBF 缓存加载。沙箱外复跑的通过证据：

```text
B1_REAL_PBF
pbfSha256=4a0f4bd4bce3a86ace3dacab54d810d3f1708cd454692ce7800e5fb973b355f3
importMillis=8010
cacheLoadMillis=179
baselineEdges=199
blockedEdgeId=182326
searchChecks=16746
blockedRejections=2
rerouteEdges=185
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

基线路线包含 199 条边。双向禁用基础 edge ID `182326` 后，新路线包含 185 条边且不再包含该基础边；搜索期发生 16,746 次检查和 2 次明确拒绝。

以上耗时只用于证明本次环境中的导入/缓存行为，不是性能承诺。

## 4. 用户复验命令

在 `code/server` 目录执行：

```powershell
mvn "-Dtest=BlockedEdgeWeightingTest" test
mvn "-Dpoc.pbf=..\..\data\Beijing.osm.pbf" "-Dtest=RealPbfHardAvoidancePocTest" test
```

真实 PBF 测试通过时会输出一行 `B1_REAL_PBF ...`，并且 Maven 以 `BUILD SUCCESS` 结束。该测试使用 JUnit 临时目录作为图缓存，不会写入或修改原始 PBF。

## 5. 限制和升级门槛

- `VirtualEdgeIteratorState` 明确不是稳定 API；GraphHopper 升级后必须重跑所有 B1 测试。
- 禁行 edge ID 只在当前 PBF、GraphHopper 版本和图构建配置对应的缓存内有效；重建图后必须重建禁行边快照。
- 本实现只允许 flexible mode；不得新增 CH/LM profile 后仍宣称请求期动态禁边有效。
- 搜索期硬禁行保证以当前禁行快照和路网匹配为边界。摄像头坐标、道路匹配和最终路线仍需 B3-B6 的独立验证。
- 当前 PBF 覆盖范围有限，只能作为首期 POC 路网，不能代表完整北京覆盖。
