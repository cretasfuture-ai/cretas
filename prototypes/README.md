# prototypes — Cretas 统一原型源码（Nuxt UI 4 + --cs-* 设计系统）

收录自本机 `Documents\Cretas-admin-双角色完整原型-20261028\samples-v5`（2026-11-15）。只收源码和规格；不收 `private/`、`dist/`、`node_modules/`、`public/native`（味美原版编译产物，源码即 `web-admin/`）、证据截图、`revisions/` 快照、`unified-spec/locks`。

| 目录 | 内容 |
|---|---|
| `unified-v1/src` | 统一原型页面：frame、frame-g、home、production、cost、supply、quality、collab、crew（72 个 Vue） |
| `unified-v1/public` | `shell.js` `shell.css`（工作区切换/六膳门侧栏）、`pages-d.js`（D 线对编译页面的运行时适配）、`proto-tools.js`。这是原型期补丁层，迁正式端时直接落进 `web-admin` 源码，不搬脚本 |
| `unified-v1/server.cjs` `lib` `tools` | 原型服务与构建脚本 |
| `unified-v1/spec` | `unified-spec`：DECISIONS、MIGRATION-PLAN、各批审计、G-gap-v2 逐页对照 |
| `component-library` | 系统组件库（samples-v5/src 与主题） |
| `ydwm-nuxt-system` | 味美 Nuxt UI 构建源码；与 `unified-v1/src/cost` 逐字节相同的 3 个成本页只保留在后者 |

原型目录没有 `package.json` 的部分依赖 `component-library/package.json`；本目录未验证可独立构建。

## 补收（2026-11-15）
- `component-library/legacy-src`：旧版 web-admin 快照（1164 个文件），组件库 `App.vue`、`main.ts` 引入其整个路由，`fusion/identity.ts` 与 `FusionAnalytics.vue` 引入其 store/类型/Dashboard。它与当前 `web-admin/src` 不同（旧版），迁移时以 `web-admin` 为准，此处只为让组件库保持可读、可构建。
- `ydwm-v1/public/design-system.{css,js,d.ts}`：`--cs-*` 变量与 `CretasSystem` 类型，`unified-v1/src/frame/ai-protocol.ts` 按相对路径引用，目录层级与原型一致。
