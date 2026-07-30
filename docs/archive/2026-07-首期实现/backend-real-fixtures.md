# 后端真实数据夹具与验证基线

记录日期：2026-07-30

本文记录 B9 使用当前只读数据得到的可重复基线。它证明代码能够处理当前文件，但不替代高德/OSM 人工坐标和道路层级核对，也不是生产覆盖率或性能承诺。

## 1. 输入文件指纹

| 文件 | 条目/大小 | SHA-256 |
|---|---:|---|
| `data/Beijing.osm.pbf` | 22,108,810 bytes | `4A0F4BD4BCE3A86ACE3DACAB54D810D3F1708CD454692CE7800E5FB973B355F3` |
| `data/map.json` | 6,797 条，2,332,799 bytes | `F2E1CE2392A2E1F029F42E0FA7728044C72475725DD40944794AFB42C44CA65A` |
| `code/server/src/test/resources/fixtures/real-camera-candidates-50.json` | 50 条小型候选元数据 | `43FE6E536D8568AC9BC569EFA0BC056CE07F7757B336758A6BC74F9E8E123BDC` |

复算命令（在工作区根目录执行）：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath `
  .\data\Beijing.osm.pbf, `
  .\data\map.json, `
  .\code\server\src\test\resources\fixtures\real-camera-candidates-50.json
```

## 2. 20/30/50 米快照统计

`RealRoutingSnapshotIntegrationTest` 对同一份 PBF 和 6,797 个摄像头分别构建三个半径的禁行边集合。道路索引包含 222,367 条可驾车基础边。

| 安全半径 | 匹配摄像头 | 未匹配摄像头 | 双向禁行基础边 |
|---:|---:|---:|---:|
| 20 米 | 5,912 | 885 | 15,287 |
| 30 米 | 5,930 | 867 | 20,557 |
| 50 米 | 5,939 | 858 | 28,591 |

首期运行配置固定为 30 米。命中规则是摄像头安全圆与可驾车基础边几何相交；首期不解析拍摄方向，命中的基础边正反方向同时禁行。edge ID 只对当前 PBF、GraphHopper 版本和图构建配置有效，重建图后必须重建快照。

## 3. 50 个道路级人工核对候选

小型夹具只保存真实 `map.json` 中的 ID 和地址关键词分类，不复制原始数据：

| 分类 | 数量 |
|---|---:|
| 交叉口 | 15 |
| 桥梁高架 | 10 |
| 主路 | 8 |
| 辅路 | 8 |
| 匝道 | 5 |
| 高速 | 4 |

`RealCameraJsonTest` 已自动确认这 50 个 ID 均存在于当前 6,797 条源记录中。它们尚未逐个完成高德点位、GCJ-02 转 WGS84 结果、OSM 道路、主辅路、桥上桥下和匝道层级的人工核对，因此不能把此夹具称为 50 个已验证匹配。

人工核对时应记录每个候选的高德原点、转换后的 OSM 点、预期道路/方向、实际命中边及判定；完成多数代表性样本并确认坐标源后，才允许把 `CAMERA_SOURCE_COORDINATE_VERIFIED` 设为 `true`。

## 4. 固定零冲突路线

在“假定源数据为 GCJ-02”的条件性真实集成测试中，30 米快照找到并独立校验了以下 WGS84 路线：

```text
start=116.3975,39.9087
end=116.4700,39.9920
distanceMeters=44946.1
geometryPoints=637
searchChecks=52186
blockedRejections=2166
cameraConflictCount=0
```

成功响应必须满足 `cameraConflictCount=0`；GraphHopper 无合规路线或 JTS 最终几何发现任何冲突时，服务返回业务错误，不返回违规候选作为降级路线。

该结果的保证边界是：上述文件哈希、GraphHopper 11.0 图、当前 30 米匹配规则以及“源坐标为 GCJ-02”的未确认假设。它不是任意起终点或完整北京市域的覆盖证明。

## 5. 复验命令

在 `code/server` 目录执行：

```powershell
mvn "-Dreal.camera.json=..\..\data\map.json" "-Dtest=RealCameraJsonTest" test
mvn "-Dreal.pbf=..\..\data\Beijing.osm.pbf" "-Dreal.camera.json=..\..\data\map.json" "-Dtest=RealRoutingSnapshotIntegrationTest" test
```

第二条命令在 JUnit 临时目录中导图、构建三个半径的快照、原子持久化 30 米快照、验证固定路线，并证明缓存可以在不提供 PBF 时再次加载。它不会修改原始 PBF 或 `map.json`，但首次导图会消耗明显时间和内存。

## 6. 待人工完成

- 按 `docs/coordinate-contract.md` 完成摄像头源坐标控制点确认；当前没有用户确认结论。
- 逐个核对 50 个候选的高德/OSM 道路层级和匹配结果；当前只有候选 ID 分类。
- 为 867 个 30 米未匹配点区分 PBF 范围外、道路数据缺失、坐标误差和真实离路点。
- 使用完整且带缓冲区的北京 PBF 重新计算覆盖率、禁行边和路线回归结果。

