# 前端交接说明

## 1. 已实现内容

- Vue 3 + TypeScript + Vite 的移动端优先地图工具，桌面为 390px 左侧控制栏加地图。
- 高德 JS API 2.0 单次加载、安全密钥预配置、失败重试和卸载清理。
- 独立 mock 地图适配层；真实 SDK 加载失败时只显示可恢复错误，不显示模拟地图或假路线。
- 高德地址联想、搜索结果键盘选择、地图选点、起终点标记、清空和交换。
- 冻结 OpenAPI 对应的路线、摄像头、快照、健康和就绪客户端类型。
- 路线成功响应运行时校验：未知坐标系、少于两个几何点或 `cameraConflictCount !== 0` 均拒绝绘制。
- `AbortController`、15 秒 HTTP 超时和请求序号共同防止重复请求、迟到响应覆盖和自动重试。
- 路线绘制、视野调整、距离/时间、快照版本、道路步骤和完整错误状态。
- 摄像头 bbox 查询、防抖、移动阈值、过期取消、图层开关和高德 `MassMarks` 批量渲染。
- 浏览器 `watchPosition` 状态机、原始 WGS84 位置、GCJ-02 展示位置、精度圆和 750ms 绘制节流。
- “回到当前位置”和“从当前位置重新规划”；连续定位更新不会调用路线 API。
- 手动重新规划在成功前保留旧路线和旧起点，失败后继续显示旧路线并提示未更新。
- `100dvh`、安全区、横屏、小屏和长错误文案适配；工具按钮有 `aria-label` 和 tooltip。
- OpenStreetMap 署名及版权链接。

## 2. 修改文件范围

本次只创建或修改：

```text
code/web/
code/docs/frontend-dependencies.md
code/docs/frontend-handoff.md
```

没有修改 `code/server/`、`docs/api-contract.yaml`、`docs/coordinate-contract.md`、`docs/test-fixtures.md` 或其他冻结共享契约。

## 3. 需要补充的环境变量

在 `code/web/.env.local` 填写以下四项，不要把真实值提交到版本控制：

```dotenv
VITE_AMAP_KEY=你的高德 Web JS Key
VITE_AMAP_SECURITY_CODE=你的高德安全密钥
VITE_API_BASE_URL=
VITE_USE_MOCK_API=true
```

说明：

- `.env.example` 只包含空 Key 和非敏感默认值。
- `.env`、`.env.local`、其他 `.env.*` 和 `certs/` 已加入 `.gitignore`。
- 修改环境变量后需要重启 Vite。
- mock 模式可以留空两个高德变量；真实模式必须填写。
- 本地开发建议将 `VITE_API_BASE_URL` 留空，由 Vite 把同源 `/api` 请求代理到电脑上的 `http://127.0.0.1:8080`。这样手机 HTTPS 调试不会请求手机自身的 `localhost`，也不会产生 HTTPS 页面访问 HTTP API 的混合内容错误。
- 只有不使用 Vite 代理、直接跨域访问 Java 服务时，才填写完整 API 地址；此时 Java 服务必须允许实际前端 Origin，并且 HTTPS 页面不能直连 HTTP API。

高德控制台入口：[高德开放平台应用管理](https://console.amap.com/dev/key/app)。为 Web JS Key 配置实际使用的允许域名。手机通过局域网 IP 或本地域名访问时，也要按控制台规则加入该调试地址；不要把 Key 或安全密钥发到聊天中。

## 4. WebStorm 配置

1. 在 WebStorm 选择 **Open**，打开 `F:\CodexProjects\routing-plan\code\web`，不要打开 `dist`。
2. 打开 **Settings > Languages & Frameworks > JavaScript Runtime**，选择 Node.js 24.16.0。
3. 若 nvm-desktop 的默认 shim 未生效，直接选择 `D:\nvmd\24.16.0\node.exe`。
4. 在 **Settings > Languages & Frameworks > Node.js > Package manager** 选择该 Node 目录下的 npm。
5. 从 `.env.example` 创建 `.env.local`，只在本机填写真实值。
6. 在 npm 工具窗口运行 `dev`、`typecheck`、`lint`、`test`、`test:e2e`、`build` 或 `preview`。
7. 创建 npm Run Configuration 时，`package.json` 指向 `code/web/package.json`，Node interpreter 固定为 24.16.0。

本会话没有打开 WebStorm，因此上述 IDE 配置和 WebStorm 内构建仍需用户验证。

## 5. Mock 模式

设置：

```dotenv
VITE_USE_MOCK_API=true
```

mock API 和 mock 地图分别位于独立模块，真实 `HttpApiClient` 中没有 mock 分支。默认地址为：

```text
http://localhost:5173/
```

通过查询参数演示主要状态：

| 地址参数                           | 场景                                 |
| ---------------------------------- | ------------------------------------ |
| `?mockScenario=success`            | 首次规划和手动重规划成功             |
| `?mockScenario=no-route`           | 无合规路线                           |
| `?mockScenario=start-restricted`   | 起点位于限制范围                     |
| `?mockScenario=end-restricted`     | 终点位于限制范围                     |
| `?mockScenario=outside-bounds`     | 超出路网范围                         |
| `?mockScenario=not-ready`          | 路由服务未就绪                       |
| `?mockScenario=snapshot-not-ready` | 摄像头快照未就绪                     |
| `?mockScenario=network-error`      | 网络失败                             |
| `?mockScenario=protocol-conflict`  | HTTP 成功但冲突数非零，前端拒绝路线  |
| `?mockScenario=invalid-geometry`   | HTTP 成功但几何不足，前端拒绝路线    |
| `?mockScenario=reroute-failure`    | 首次成功、手动重规划失败并保留旧路线 |

可加 `mockDelay` 验证规划中状态，例如：

```text
http://localhost:5173/?mockScenario=reroute-failure&mockDelay=800
```

mock 地图是自动化测试适配层，不代表高德底图、真实路线或真实点位已经验证。

## 6. 真实 API 模式

设置并重启开发服务器：

```dotenv
VITE_AMAP_KEY=本机真实值
VITE_AMAP_SECURITY_CODE=本机真实值
VITE_API_BASE_URL=
VITE_USE_MOCK_API=false
```

真实模式行为：

- 高德负责底图、联想搜索、逆地理编码、WGS84 GPS 定位到 GCJ-02 的展示转换和覆盖物绘制。
- 产品路线只调用 `POST /api/v1/routes`；不会调用高德驾车路线服务。
- 摄像头只调用 `GET /api/v1/cameras`，查询参数与冻结 OpenAPI 一致。
- 后端返回 WGS84 路线、非零冲突或非法几何时，页面进入协议错误且不绘制。
- 定位更新仅保存在浏览器状态并移动位置图层；只有用户点击规划命令时才发送单个位置。
- 开发服务器将同源 `/api` 代理到 `http://127.0.0.1:8080`；如后端端口改变，需要同步调整两个 Vite 配置文件中的代理目标，或者使用满足 CORS 和 HTTPS 要求的完整 `VITE_API_BASE_URL`。

## 7. PowerShell 命令

```powershell
Set-Location F:\CodexProjects\routing-plan\code\web

node --version
npm --version
npm install

# 首次运行 Playwright 时安装测试浏览器
npx playwright install chromium

npm run dev
npm run typecheck
npm run lint
npm run test
npm run test:e2e
npm run build
npm run preview
```

`npm run dev` 绑定 `0.0.0.0`，默认端口是 `5173`；端口被占用时以 Vite 终端输出为准。`npm run preview` 用于检查已构建的 `dist`，不能替代开发服务。

## 8. 手机 HTTPS 定位验证

浏览器 Geolocation 需要安全上下文。桌面 `http://localhost` 通常被浏览器视为安全例外，但手机访问 `http://电脑局域网IP:5173` 通常不满足要求。

仓库提供 `npm run dev:https`，固定读取：

```text
code/web/certs/dev-cert.pem
code/web/certs/dev-key.pem
```

可使用 mkcert 生成包含电脑局域网 IP 的开发证书。下面将 `192.168.1.23` 替换为电脑实际 IPv4：

```powershell
winget install FiloSottile.mkcert
mkcert -install

Set-Location F:\CodexProjects\routing-plan\code\web
New-Item -ItemType Directory -Force .\certs
mkcert -cert-file .\certs\dev-cert.pem `
  -key-file .\certs\dev-key.pem `
  localhost 127.0.0.1 ::1 192.168.1.23

npm run dev:https
```

验证步骤：

1. 电脑与手机连接同一可信局域网；Windows 防火墙只允许 Node.js 的专用网络访问。
2. 运行 `mkcert -CAROOT` 找到本地 CA。将 `rootCA.pem` 安装到专用测试手机并显式信任。
3. Android 在系统的安全/凭据设置中安装 CA；iOS 安装描述文件后，还需在“关于本机 > 证书信任设置”启用完全信任。菜单名称随系统版本变化。
4. 只在自己控制的测试设备上信任该 CA；验证结束后可移除手机上的测试 CA。
5. 高德控制台为 Web JS Key 配置实际局域网 IP 或本地调试域名。
6. 手机打开 `https://192.168.1.23:5173`，确认地址栏无证书警告，再允许位置权限。
7. 检查当前位置标记、精度圆和“回到当前位置”；移动位置不应自行改变路线。
8. 先规划一条路线，再点击“从当前位置重新规划”，确认终点保留；拒绝定位和低精度状态也要人工检查。

若不希望在手机安装本地 CA，可以使用受信任证书的 HTTPS 开发域名或 HTTPS 隧道；仍需把该域名配置到高德允许域名，并确保 API 地址和 CORS 与该 Origin 匹配。

## 9. 提供的测试代码

Vitest 覆盖：

- 距离、时间和快照版本格式化。
- 错误码到 UI 状态映射。
- 成功响应协议校验、非零冲突、未知坐标系和非法几何拒绝。
- 高德脚本单次注入和安全配置先置。
- 起终点交换、无终点时规划按钮禁用。
- 定位状态、WGS84/GCJ-02 分离、位置更新不调用路线 API。
- 手动重新规划使用最新 WGS84、保留终点、失败保留旧路线。
- 迟到路线响应不覆盖新请求。
- 摄像头 bbox 防抖、过期请求取消和迟到结果拒绝。

Playwright mock 覆盖：

- 首次规划成功和无合规路线。
- 定位允许、定位拒绝、回到当前位置和当前位置作为起点。
- 手动重规划成功和失败保留旧路线。
- 摄像头图层开关和地图选点。
- 360×800、390×844、430×932、844×390、1280×720、1440×900 无横向溢出和主要浮层重叠。

## 10. AI 实际执行过的检查

在 Node.js 24.16.0 下实际完成：

```text
npm install                         通过，生成 package-lock.json
npm run format:check                通过
npm run typecheck                   通过
npm run lint                        通过
npm run test                        通过，9 个文件、23 个测试
npm run test:e2e -- --workers=4     通过，18 个 Chromium 场景
npm run build                       通过，Vite 8.1.5 生成 dist
```

Playwright 使用 mock API、mock 地图和浏览器模拟 Geolocation。测试 Web Server 在用例结束后自动退出；没有留下长期运行的开发服务器。

## 11. 未验证项和风险

- 未使用真实 `VITE_AMAP_KEY` 和安全密钥，真实高德脚本、允许域名、搜索、逆地理编码、`MassMarks` 和 `AMap.convertFrom(..., 'gps')` 尚未联调。
- 未连接真实 Java API；CORS、超时、错误码和 DTO 只能确认前端与冻结 OpenAPI 一致。
- 未在 WebStorm 中启动或构建。
- 未在手机上验证 HTTPS 证书信任、系统定位权限、真实 GPS 精度、软键盘和移动浏览器安全区。
- mock 摄像头数量较少。生产实现使用 `MassMarks` 且按 bbox 加载，但 6797 点位下的真实帧率、点击命中和内存仍需真数据验证。
- 已在高德官方 JS API 2.0 文档中核对 `convertFrom`、`AutoComplete`、`Geocoder` 和 `MassMarks` 的接口名称；真实 Key 下的回调对象、渲染效果及账户权限仍只能在本地联调时确认。
- 后端成功路线必须始终返回 `GCJ02` 和 `cameraConflictCount = 0`；前端不会转换或展示其他路线，也不会提供违规备用路线。
- Android/iOS 对用户安装 CA 的限制不同；企业策略或新版系统可能禁止安装用户 CA，此时应改用正式受信任 HTTPS 域名。

## 12. 对其他会话的接口影响

没有变更共享接口。前端严格依赖：

- `POST /api/v1/routes`
- `GET /api/v1/cameras`
- `GET /api/v1/camera-snapshots/current`
- `GET /api/v1/health`
- `GET /api/v1/readiness`

联调会话需要重点确认：真实路线顶层坐标系为 `GCJ02`、成功冲突数严格为 0、摄像头 bbox 响应也是 `GCJ02`、Java 服务 CORS 包含前端实际 Origin。
