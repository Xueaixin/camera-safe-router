# 路线搜索基础配置 POC 报告

状态：当前配置缺口已确认，正式修正尚未实施  
验证日期：2026-08-04

## 1. 结论

当前 GraphHopper 图和 `car` Profile 不能直接作为六环算法的正式基础：OSM 转向限制没有导入，`road_access` 已编码但没有参与权重，内置 `car.json` 也不是距离最短权重。

此外，GraphHopper 11 的 `DijkstraOneToMany` 不能直接承担本项目的多目标搜索。节点模式会拒绝带 turn costs 的权重；其实现按节点保存单一状态，无法表达“同一节点、不同到达 edge key、后续转向合法性不同”的情况。正式 POC 必须使用有向 edge key 作为搜索状态。

## 2. 当前真实图证据

- 图指纹：`sha256:94e12bc1b598706e2bcd10e563a5ce91187d5e15632a8605a3e652d83a0e8a20`
- `Profile.hasTurnCosts()`：`false`
- turn encoded values：空
- 内置 `car.json` 的 `distance_influence`：`90.0`
- 内置 `car.json` 是否引用 `road_access`：`false`

`distance_influence = 90` 表示时间权重叠加每公里 90 秒的距离影响，不等于按道路距离求最短路线。

## 3. road_access 分布

以下是当前图中允许汽车访问的有向遍历计数：

| road_access | 遍历数 |
|---|---:|
| yes | 5,152,473 |
| destination | 1,634 |
| customers | 360 |
| delivery | 0 |
| private | 22,319 |
| agricultural | 0 |
| forestry | 0 |
| no | 51 |

真实图中存在大量 `private`、`destination` 和 `customers` 道路。当前模型不读取 `road_access`，因此不能把该缺口视为理论问题。

## 4. 多目标算法结论

合成图验证包含一个禁止转向：最短到达中间节点的状态无法继续，但稍远的另一条到达边可以合法抵达终点。标准 edge-based Dijkstra 能找到合法绕行；`DijkstraOneToMany` 的 node-based 模式在构造时直接拒绝 turn-cost weighting。

实现方向确定为自建单次 edge-based 多目标 Dijkstra：

1. 搜索状态使用有向 edge key，不使用单一节点状态。
2. 每次扩展调用基础距离权重、`BlockedEdgeWeighting` 和 `calcTurnWeight`。
3. 首个通行口确定 `Dmin` 后，继续搜索到队列最小距离大于 `Dmin + 1000 米`，收集完整候选集。
4. 没找到通行口时只有完全耗尽可达状态才能证明无路；超时和访问状态上限使用独立结果。
5. 入环场景使用反向 edge-based 搜索，转向代价参数必须按正向驾驶顺序反转。

## 5. 修正影响

- 启用 turn costs 会改变图结构和缓存指纹，必须重建 GraphHopper 缓存，不能在现有缓存上热切换。
- 距离优先模型和访问权限规则会改变已有路线回归结果，需要建立新基线。
- 动态摄像头禁边继续由 `BlockedEdgeWeighting` 包装，不因基础权重变化而放松。
- 在新缓存和新权重完成前，真实图多目标测试只能验证性能形态，不能证明生产路线正确。

## 6. 复现

- 当前配置报告测试：`server/src/test/java/cn/camera/safe/routing/RoutingFoundationPocTest.java`
- 一对多适配性测试：`server/src/test/java/cn/camera/safe/routing/MultiTargetAlgorithmSuitabilityTest.java`
- 外部完整报告：`F:\camera-safe-routing-data\work\sixth-ring-poc\20260804-bb572f9\routing-foundation-poc-report.md`

普通 `mvn test` 会执行合成图适配性测试；真实图配置报告只有显式传入 PBF、图缓存和输出路径时才运行。
