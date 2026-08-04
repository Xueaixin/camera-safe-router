# 路线搜索基础配置 POC 报告

状态：合规距离配置、独立缓存和真实路线回归通过，已升为默认
验证日期：2026-08-04

## 1. 结论

默认 `COMPLIANT_DISTANCE_V1` 已满足六环算法的 GraphHopper 基础门槛：启用机动车 turn costs，导入 OSM 转向限制，显式处理 `road_access`，并使用距离主导权重。动态摄像头禁边仍由 `BlockedEdgeWeighting` 包装，成功路线继续执行 edge key 检查和 JTS 零冲突终检。

候选配置先在独立 `graph-cache/jingjinji-compliant-distance-v1` 构图，没有覆盖旧 `graph-cache/jingjinji`。最小 OSM 和真实京津冀回归通过后才将其升为直接 Debug 默认；`ROUTING_PROFILE_MODE=CURRENT` 保留旧 profile 回退能力。

## 2. 当前真实图证据

- 图指纹：`sha256:23c0009a6943af69973808f5d446698c6ebd3cc8a222b831025a59195a8ddafc`
- `Profile.hasTurnCosts()`：`true`
- turn encoded values：存在
- 实际导入可用转向限制：2,359 条
- `distance_influence`：`1000000.0` 秒/公里
- 自定义模型引用 `road_access`：`true`
- 节点：2,360,314
- 基础边：3,234,241
- 可驾车索引边：2,906,087

距离影响设置为每公里 1,000,000 秒，使搜索在京津冀业务范围内以距离为第一目标、道路速度为次级决胜因素；`calcEdgeMillis` 仍按道路速度计算，返回时长没有被距离权重放大。六环多目标搜索不会把该秒权重误当作米，而是复用其合法性和转向代价后按真实边长累计 `Dmin`。

## 3. road_access 分布与规则

以下是候选图中允许汽车访问的有向遍历计数：

| road_access | 遍历数 |
|---|---:|
| yes | 5,152,709 |
| destination | 1,634 |
| customers | 360 |
| delivery | 0 |
| private | 22,319 |
| agricultural | 0 |
| forestry | 0 |
| no | 51 |

`NO` 为硬不可达。进入 `private`、`destination`、`customers`、`delivery`、`agricultural` 或 `forestry` 时增加高额入口代价：起终点位于受限道路内部时仍可规划，但普通路线不会把它作为穿行捷径。最小 OSM 测试已实际覆盖 private、destination 和 customers 的绕行与端点可达行为；其余枚举由同一条件表达式和配置契约测试覆盖。

## 4. 已通过门槛

1. OSM `no_straight_on` 实际导入，最短到达状态被禁转后，同节点另一条到达 edge key 仍能找到合法绕行。
2. 距离更短但速度更慢的道路优先，返回预计时长仍保持真实速度口径。
3. 摄像头基础边和 QueryGraph 虚拟边继续在搜索阶段返回无穷权重。
4. 九条固定京津冀路线在冷导图和缓存重载阶段均为零冲突，两条真实无合规路线仍保持无路。
5. 最大访问节点映射为 503 `ROUTING_NOT_READY`，不再误报为 409 `NO_COMPLIANT_ROUTE`；请求超时由外层 Future 独立处理。
6. 候选缓存同时校验 PBF SHA-256 与路由配置 SHA-256，缺失或不匹配均拒绝加载。

## 5. 仍未完成

GraphHopper 基础配置已不再阻断生产算法。剩余工作是完善有向通行口拓扑、人工抽查边界规则、扩充东南西北多方向真实路线，然后修改 OpenAPI 并迁移多目标搜索到生产服务。

## 6. 复现

- 配置与真实图报告：`server/src/test/java/cn/camera/safe/routing/RoutingFoundationPocTest.java`
- 最小 OSM 合法性测试：`server/src/test/java/cn/camera/safe/routing/CompliantRoutingProfileIntegrationTest.java`
- 配置与缓存契约：`server/src/test/java/cn/camera/safe/routing/RoutingGraphConfigurationTest.java`
- 外部完整报告：`E:\camera-safe-routing-data\work\sixth-ring-poc\20260804-compliant-distance-v1\routing-foundation-poc-report.md`

普通 `mvn test` 执行最小图和配置测试；真实图报告只有显式传入 PBF、候选缓存和输出路径时才运行。
