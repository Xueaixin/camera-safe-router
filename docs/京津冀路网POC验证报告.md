# 京津冀路网 POC 验证报告

验证时间：2026-08-02

## 1. 结论

本地 POC 通过。中国 PBF 已切分为独立京津冀候选文件，候选道路的 way 节点引用完整，GraphHopper 关注的转向限制关系引用完整。候选文件在独立缓存完成构图，当前生产过滤口径下的摄像头快照成功发布，四条京津冀跨城路线均返回零摄像头冲突。

本报告证明当前输入和代表性请求可用。2026-08-03 已将验证产物发布为默认 `jingjinji-latest.osm.pbf` 和 `graph-cache/jingjinji`，但这不代替生产自动发布、全量路线质量或摄像头坐标控制点验收。

## 2. 环境与工具

| 项目 | 值 |
|---|---|
| 操作系统 | Windows 11 64 位，系统版本 10.0.26100 |
| CPU | Intel Core Ultra 5 135H，14 核 18 线程 |
| 内存 | 15.5 GB |
| Java | Oracle JDK 21.0.12 |
| osmium | osmium-tool 1.19.1、libosmium 2.23.1 |
| GraphHopper | 项目内 GraphHopper 11 |
| JVM 参数 | `-Xms1g -Xmx5g` |

Windows 的 osmium 使用便携 `micromamba 2.8.1` 和独立 conda-forge 环境，不修改全局 PATH、Python 或 Java。运行 Spring Boot 不需要 osmium；它只参与离线 PBF 处理。

## 3. 输入与切分

中国源文件：

```text
E:\camera-safe-routing-data\osm\china-latest.osm.pbf
```

| 项目 | 值 |
|---|---|
| 数据时间 | `2026-07-29T00:58:13Z` |
| 文件大小 | 1,715,052,026 字节 |
| SHA-256 | `25564ac2ef03f74f187c7bf98d24885cd0b271b7ef8beda450ed9d9ceb49d573` |
| 节点 | 237,947,698 |
| Way | 18,349,300 |
| Relation | 306,240 |

源文件本身有 58,153 个 way 节点引用缺失，推测是中国行政边界提取造成。因为京津冀范围处于中国内部，本次继续切分，但把候选道路的引用完整性作为阻断检查。

切分参数：

```text
边界：113.0,35.5,120.5,43.0
策略：smart
```

可重复执行命令：

```powershell
.\scripts\prepare-jingjinji-osm.ps1 `
  -DataRoot 'E:\camera-safe-routing-data'
```

选择 `smart` 而不是 `complete_ways`，是因为它还会补充相交 multipolygon 的依赖对象，而候选文件只增加约 0.6 MB。依赖补全会让文件内少量对象坐标超出请求边界，这是该策略的预期行为。

最终候选文件：

```text
E:\camera-safe-routing-data\work\osm\jingjinji-smart-20260729-25564ac2ef03.osm.pbf
```

| 项目 | 值 |
|---|---|
| 文件大小 | 185,525,553 字节 |
| SHA-256 | `2a1bcebc16586858bc116a67dd10b9e5191241bbbb096866d0d0a2538ca16131` |
| CRC32 | `9149f58d` |
| Header bbox | `113.0,35.5,120.5,43.0` |
| 节点 | 22,988,898 |
| Way | 2,451,162 |
| Relation | 61,567 |

## 4. 引用完整性

| 检查 | 结果 |
|---|---|
| Way 中缺失节点 | 0 |
| 全部 relation 中缺失节点 | 1,795 |
| 全部 relation 中缺失 way | 166,773 |
| 全部 relation 中缺失 relation | 1,339 |
| `type=restriction` 数量 | 1,825 |
| 转向限制相关节点/way/relation 缺失 | 全部为 0 |

区域切分后的普通 relation 缺失主要来自跨区域行政边界、公交、铁路等大型关系。GraphHopper 当前使用的转向限制已单独抽取并通过完整引用检查：

```text
节点：19,967
Way：2,921
Relation：1,825
```

## 5. GraphHopper 构图与摄像头快照

POC 阶段使用以下独立目录，避免在验证完成前影响当时的北京缓存：

```text
PBF：E:\camera-safe-routing-data\work\osm\jingjinji-smart-20260729-25564ac2ef03.osm.pbf
图缓存：E:\camera-safe-routing-data\graph-cache\jingjinji-2a1bcebc1658
快照：E:\camera-safe-routing-data\work\snapshots\jingjinji-2a1bcebc1658
端口：http://localhost:18081
```

| 项目 | 结果 |
|---|---|
| PBF 读取和图构建 | 成功 |
| GraphHopper 节点 | 2,360,314 |
| GraphHopper 基础边 | 3,234,067 |
| 可驾车道路索引边 | 2,905,913 |
| 图缓存大小 | 约 0.245 GB |
| 首次 PBF 读取和建图 | 约 28 秒 |
| 从开始导图到快照发布 | 约 31 秒 |
| 构图后进程工作集观测值 | 约 2.52 GB |
| readiness | `READY`，三个加载标志均为 `true` |

当前摄像头快照统计：

| 项目 | 数量 |
|---|---:|
| 源记录 | 6,803 |
| `IsSixRingOut!=1` 保留 | 5,707 |
| `IsSixRingOut=1` 排除 | 1,096 |
| 缺失或非法 `IsSixRingOut` | 7 |
| 匹配摄像头 | 5,688 |
| 未匹配摄像头 | 19 |
| 30 米禁行边 | 19,731 |

摄像头统计与北京图一致，说明扩大路网没有改变当前 19 个未匹配点。禁行边版本会因图指纹和 edge ID 变化而重新生成，不能把北京缓存的 edge ID 快照直接复用到京津冀图。

## 6. 跨城路线

请求使用 WGS84 坐标、`MAP_PICK` 和 `CAR`。API 耗时为本机 HTTP 调用墙钟时间；路线时长是 GraphHopper 估算的行驶时间。

| 路线 | 距离 | 估算时长 | 几何点 | API 耗时 | 摄像头冲突 |
|---|---:|---:|---:|---:|---:|
| 北京 -> 天津 | 140,966.86 米 | 7,738 秒 | 1,424 | 538 ms | 0 |
| 北京 -> 石家庄 | 302,318.41 米 | 13,568 秒 | 1,745 | 235 ms | 0 |
| 北京 -> 张家口 | 213,423.77 米 | 10,737 秒 | 1,735 | 206 ms | 0 |
| 天津 -> 石家庄 | 302,602.80 米 | 12,399 秒 | 1,542 | 185 ms | 0 |

测试坐标：北京 `(116.3970,39.9080)`、天津 `(117.200983,39.084158)`、石家庄 `(114.5149,38.0428)`、张家口 `(114.8859,40.7689)`。

## 7. 已知问题与下一步

- GraphHopper 对 OSM way `1368805799` 给出“804 km 渡轮缺少 duration 标签”警告。该对象是天津到韩国仁川的渡轮关系，当前代表性路线未使用，但后续发布前应明确跨境渡轮的过滤或时长策略。
- 尚未运行京津冀候选图的 20/30/50 米分档匹配报告、北京边缘路线、无路路线和全部错误场景回归。
- 当前 POC 未实现中国源自动下载、候选构图调度、原子切换和旧图缓存回滚。
- “跨区域路线优先进入六环路”仍是后续算法议题，本次只验证统一路网下的基础可达性和硬避让。
- Linux 部署可以直接上传本次切分得到的 PBF，由服务器重新构建 GraphHopper 缓存；这种方式不要求服务器安装 osmium。
