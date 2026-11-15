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
