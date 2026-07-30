# 前端依赖基线

## 1. 运行环境

- 目标 Node.js：`24.16.0`。
- 包管理器：npm，当前 Node 安装附带版本为 `11.13.0`。
- 依赖锁：提交 `code/web/package-lock.json`，用户应优先使用 `npm install` 按锁文件安装。
- `package.json` 将 Node 范围限制为 `>=24.16.0 <25`，避免 IDE 静默切换到未经验证的主版本。

本会话在 npm registry 实时核对了主要包的 `engines` 和 peer 约束。Vite 8 要求
`^20.19.0 || >=22.12.0`，Vitest 4 支持 `>=24.0.0`，Playwright 1.62 要求
`>=20`，均覆盖 Node 24.16.0。

## 2. 生产依赖

| 依赖          |   版本 | 用途与选择理由                                                                                |
| ------------- | -----: | --------------------------------------------------------------------------------------------- |
| `vue`         | 3.5.40 | 冻结技术栈要求的 Vue 3 页面与组合式 API。                                                     |
| `pinia`       |  4.0.2 | 管理路线、地图和定位的跨组件业务状态；地图 SDK 实例不进入 Store。                             |
| `@lucide/vue` | 1.27.0 | 工具按钮和状态图标。原 `lucide-vue-next` 已被 registry 标记弃用，因此使用官方维护中的替代包。 |

页面没有引入 Element Plus。当前控件规模较小，轻量自定义组件可以降低地图首屏包体和样式覆盖成本。

## 3. 构建与类型

| 依赖                 |    版本 | 用途与兼容结论                                           |
| -------------------- | ------: | -------------------------------------------------------- |
| `vite`               |   8.1.5 | 开发服务器和生产构建，Node 24.16.0 满足 engines。        |
| `@vitejs/plugin-vue` |   6.0.8 | Vue 单文件组件编译；peer 支持 Vite 8 和 Vue 3。          |
| `typescript`         |   6.0.3 | 当前与 `typescript-eslint` 兼容的最新 6.0 小版本。       |
| `vue-tsc`            |   3.3.8 | Vue 模板和 TypeScript 严格检查，peer 接受 TypeScript 6。 |
| `@types/node`        | 24.13.3 | Vite、Playwright 配置的 Node 类型。                      |

没有采用 TypeScript 7.0.2。实际安装时 npm 检测到 `typescript-eslint 8.65.0` 的 peer 上限为
`<6.1.0`；使用 `--force` 会掩盖不兼容，因此锁定 TypeScript 6.0.3。

## 4. 测试与质量

| 依赖                |    版本 | 用途                                                 |
| ------------------- | ------: | ---------------------------------------------------- |
| `vitest`            |  4.1.10 | 单元和状态机测试。                                   |
| `jsdom`             |  30.0.1 | Vue 组件 DOM 环境；Node 要求覆盖 24.16.0。           |
| `@vue/test-utils`   |  2.4.11 | Vue 组件挂载与交互断言。                             |
| `@playwright/test`  |  1.62.0 | Chromium mock 端到端、定位权限和响应式检查。         |
| `eslint`            |  10.8.0 | JavaScript/TypeScript/Vue 静态规则入口。             |
| `@eslint/js`        |  10.0.1 | 当前 registry 已发布的 ESLint flat config 基础规则。 |
| `eslint-plugin-vue` | 10.10.0 | Vue 模板语义规则。                                   |
| `typescript-eslint` |  8.65.0 | TypeScript ESLint flat config。                      |
| `prettier`          |   3.9.6 | 统一代码与文档格式。                                 |

Vitest 仅收集 `tests/unit/**/*.spec.ts`；Playwright 仅收集 `tests/e2e`，两套运行器不会互相加载测试文件。

## 5. 高德加载方式

未安装第三方高德 Vue 封装。`src/maps/amapLoader.ts` 通过高德 JS API 2.0 官方脚本地址加载 SDK，原因如下：

1. 在脚本注入前设置 `window._AMapSecurityConfig.securityJsCode`。
2. 用模块级 Promise 保证脚本只加载一次。
3. 显式加载 `AMap.AutoComplete` 和 `AMap.Geocoder` 插件。
4. 由地图适配层统一管理地图、覆盖物、事件监听和销毁。
5. 单元和 Playwright 测试使用独立 mock 适配层，不依赖真实 Key 或网络。

高德类型只在 `src/types/amap.ts` 中声明本项目实际使用的最小 SDK 表面。真实 SDK 联调时若高德调整回调结构，只修改适配层，不扩散到业务 Store 或 API DTO。

2026-07-30 已只读核对高德官方 [JS API 2.0 文档](https://lbs.amap.com/api/javascript-api-v2/documentation) 和 [JS API 2.0 静态参考](https://a.amap.com/jsapi/static/doc/20230922/index.html?v=2)：文档中存在 `convertFrom`、`AutoComplete`、`Geocoder`、`MassMarks`、`LabelsLayer`、`LabelMarker` 和 `MarkerCluster`。本实现选择 `MassMarks`，但回调数据和真实渲染仍需使用用户自己的 Key 联调。
