# 后端依赖调查与版本决策

调查日期：2026-07-30

## 1. 本机和数据基线

| 项目 | 调查结果 |
|---|---|
| Java | Oracle JDK `21.0.10` |
| Maven | Apache Maven `3.8.1` |
| `data/Beijing.osm.pbf` | 22,108,810 bytes；SHA-256 `4A0F4BD4BCE3A86ACE3DACAB54D810D3F1708CD454692CE7800E5FB973B355F3` |
| `data/map.json` | 2,332,799 bytes；SHA-256 `F2E1CE2392A2E1F029F42E0FA7728044C72475725DD40944794AFB42C44CA65A` |
| `map.json` 结构 | UTF-8 顶层数组，6,797 条；字段为 `Id/District/Lng/Lat/IsSixRingOut/CameraType/Address/PicCount/IsNew` |
| 既有后端文件 | 调查时 `code/` 为空 |

仓库根目录不是可识别的 Git 工作树，因此本次不能用 `git status` 判断其他会话改动；后续按文件清单和时间戳保护既有内容。

用户可复算哈希：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath .\data\Beijing.osm.pbf, .\data\map.json
```

## 2. 锁定版本

| 依赖 | 版本 | Maven 坐标或来源 |
|---|---:|---|
| GraphHopper Core | 11.0 | `com.graphhopper:graphhopper-core:11.0` |
| Spring Boot | 3.5.16 | `org.springframework.boot:spring-boot-starter-parent:3.5.16` |
| JTS | 1.20.0 | `org.locationtech.jts:jts-core:1.20.0` |
| Java 编译目标 | 21 | Maven Compiler `release=21` |

选择理由：

- Maven Central 元数据中 GraphHopper 11.0 是当前正式版；发布 POM 和源码均为 Apache License 2.0。
- GraphHopper 11.0 父 POM以 Java 17 编译，在当前 JDK 21 上运行；项目自身仍使用 Java 21。
- Spring Boot 3.5.16 是当前维护中的 3.x 正式版。官方系统要求为 Java 17 至 25、Maven 3.6.3+，覆盖 JDK 21 和 Maven 3.8.1。
- 不选择 Spring Boot 4.x：当前后端契约不需要其新能力，而 GraphHopper 11.0 仍使用 Jackson 2.x；Boot 3.5 可减少 Jackson 主版本混用风险。
- JTS 1.20.0 与 GraphHopper 11.0 父 POM一致，避免空间类库出现二进制版本分叉。

核对来源：Maven Central 发布元数据和 POM、Spring Boot 3.5 官方 System Requirements、GraphHopper 11.0 source JAR。

## 3. GraphHopper 11.0 扩展点结论

实际源码确认：

- `Weighting.calcEdgeWeight(EdgeIteratorState, boolean)` 是 A*/Dijkstra 搜索扩展边时调用的热路径，接口注释明确其单次路线可能调用数百万次。
- `RoutingAlgorithmFactorySimple.createAlgo(...)` 在创建 flexible A*/Dijkstra 时调用 `graph.wrapWeighting(weighting)`。
- `QueryGraph.wrapWeighting(...)` 使用 `QueryGraphWeighting` 处理虚拟节点的转向成本，但边权仍委托给传入 weighting。
- `VirtualEdgeIteratorState.getOriginalEdgeKey()` 返回带方向的基础图原始 edge key；因此虚拟边可继承基础边禁行，而不能仅检查虚拟 edge ID。
- `GraphHopper.createWeightingFactory()` 是 `protected` 扩展点。POC 通过请求线程内的不可变快照包装默认 weighting，不修改共享基础图。
- 动态禁边必须使用 flexible mode；本项目不配置 CH/LM profile，防止预计算结果绕过请求期禁边。

本项目的 hard-block weighting 对被禁遍历返回 `Double.POSITIVE_INFINITY`，而不是使用 `AvoidEdgesWeighting`。后者只乘惩罚因子，不能证明硬禁行。

## 4. 图缓存格式

GraphHopper 图缓存是版本化内部二进制格式，不是稳定的外部契约。11.0 源码对 node、edge、geometry、location index、KV storage 等分别保存并校验格式版本，同时校验 encoded values 和 profile 内容。

因此：

- GraphHopper 版本、PBF、encoded values 或 profile 改变后必须重建缓存。
- 禁行 edge ID 只对同一次图构建有效；重建图后必须重新生成禁行边快照。
- 首次启动使用 PBF 导入并写缓存；后续可以不读取 PBF而直接 `load` 已有缓存。
- 不提交图缓存，部署时把缓存目录作为可写外部目录。

## 5. 已知限制

- `VirtualEdgeIteratorState` 源码注明不是稳定公共 API，可能在 GraphHopper 小版本中变化；升级必须重新执行 B1 测试。
- 当前 PBF 仅作为城市范围 POC 输入，不能据此声称覆盖全部北京点位。
- Maven 首次构建需要从配置的仓库下载依赖；用户机器若使用镜像，应确认镜像已同步 GraphHopper 11.0 与 Spring Boot 3.5.16。
- B0 只确认扩展点可实现；是否真正满足正反方向、QueryGraph 和无路门槛，以 B1 可执行测试结果为准。
