# 统一原型 · 实现阶段共同规则（A–E 各线都要读）

根目录 `C:\Users\Steve\Documents\Cretas-admin-双角色完整原型-20261028\samples-v5\`。统一入口 `http://127.0.0.1:19063/open`（依赖 19060 味美、19062 六膳门在跑）。融合交付记录 `accounts\unified-v1\docs\unified-fusion-20261111.md`，裁定 `unified-spec\DECISIONS.md`，你自己的规格在 `unified-spec\` 下。

## 1 写入范围（scope 锁，只改自己的，越界先问 organizer）
| 线 | 独占文件 |
|---|---|
| A 生产链 | `accounts\unified-v1\src\production\*`；`accounts\ydwm-v1\public\chain-workspace.js/.css`、`bom-actions.js`、`purpose-flow.js` |
| B 成本 | `accounts\unified-v1\src\cost\*`、`accounts\unified-v1\lib\cost-lsm.cjs`；`accounts\ydwm-v1\src\nuxt-system\ProductCostWorkspace.vue`、`ProductCostReview.vue`、`CostBatchDetail.vue`、`cost-review.ts` |
| C 采购销售库存 | `accounts\unified-v1\src\supply\*`；`accounts\ydwm-v1\src\nuxt-system\SupplyRegions.vue`、`accounts\ydwm-v1\public\supply-system.css`、`warehouse-background.js`、`upload.js` |
| D 首页/分析/外壳/AI 协议 | `accounts\unified-v1\server.cjs`、`public\shell.js/.css`、`src\frame\*`（含 `AiFloat.vue`）、`src\home\*`；`accounts\ydwm-v1\public\design-system.*`、`menu-composition.js`、`home-workspace.js`、`list-analysis.js`、`history.js`、`enhance.js`、`nuxt-bridge.js`（驾驶舱/经营总览页签）、`src\nuxt-system\App.vue`、`main.ts`、`style.css`、`types.ts` |
| E 质量与协同任务 | 新建 `accounts\unified-v1\src\quality\*`、`src\collab\*`（只新建，不改别人的） |

- 新增文件放在自己目录里；需要改别人的文件（例如加菜单项、加路由、用共享格式函数）时，用 SendMessage 发给 organizer 会话（`local_f2ec35f8-e3d5-4e4d-8e11-72ce0cdbc97e`）说清改什么，由 organizer 转给 Owner 线或批准，不要自己改。
- 菜单和路由注册统一由 D 加；A/B/C/E 把要加的项发给 organizer。
- 上表之外的 `ydwm-v1\public\*.js`（如 `account-flows.js`、`action-contracts.js`、`business.js`、`practice.js`、`simulation.js`、`startup-*.js`、`nuxt-bridge.js`、`nuxt-loader.js`、`modern.css`、`design-polish.js`、`zones-layout.js`）暂不分配，要改先问。

## 2 绝对不能动
- 原版：`accounts\ydwm-v1\public\native\**`、19061 服务。
- 账号源数据与身份：`accounts\ydwm-v1\data\**`（只允许新增 `data\v3\test-state\` 下自己的会话文件）、`accounts\ydwm-v1\private\**`、`accounts\liushanmen-v1\private\**`（来源快照与保存区）。
- Steve 有意关闭的模块、账号权限。生产写入 0，不回写黑湖，不动任何 git 仓库，不发布远程域名。
- 动手前、交付前各跑一次 `node accounts\unified-v1\tools\protect-check.cjs <线>-before` / `<线>-after`。注意：旧的「3896」集合包含味美 `public\*.js` 和 `src\nuxt-system\*`，那是组件库阶段的冻结口径；本阶段这些 UI 文件里**属于你 scope 的**允许变化，回执里逐个列出变化的文件。`public\native`、`data`、`private` 下的任何文件变化都算违规，必须还原。

## 3 构建与重启要排队
多条线共用一个构建产物和 19063 服务。**11-11 起只能用锁脚本，不许再手写建锁/删锁命令**（手写的已误删别人的锁两次）：
- 推荐：`node unified-spec\tools\build-lock.cjs run <线> -- <构建或重启命令>`，脚本自动排队等锁（最多 20 分钟），跑完无论成败只释放自己的锁，退出码就是命令的退出码。
- 需要锁内跑多条命令时：`acquire <线>`（0=拿到，75=超时）→ 分开执行各条命令 → `release <线>`（锁不是你的会返回 3 且不删）。
- 查看谁占着：`node unified-spec\tools\build-lock.cjs status`。
1. （旧写法作废，保留编号）
2. 锁内要做的：`node accounts\unified-v1\tools\build.cjs`，需要时 `powershell -File accounts\unified-v1\tools\restart.ps1`；味美 `src\nuxt-system` 的构建同样在锁内做：`node accounts\ydwm-v1\tools\build-nuxt-system.mjs`，任何改了 nuxt-system 源码的线都可以在锁内跑。**它写出的 `ydwm-v1\public\nuxt-system\*`，以及构建产物选择文件 `ydwm-v1\private\nuxt-build.json`、`ydwm-v1\private\nuxt-ui-active.json`，organizer 已裁定列为允许变化**（private 下仅这两个文件例外；`private\fast-ui-*.json` 仍不动），回执里注明。构建前先确认其他线在 nuxt-system 里的改动能编译通过；编译失败是别人的文件时，不要改它，发消息给 organizer。
3. 重启 19063 也必须在锁内（用 `build-lock.cjs run`）；`restart.ps1` 的输出重定向到文件，**不要接管道**（接管道会挂住，锁被占约 10 分钟，11-11 B 线踩过）。
4. 不许用 `rm`/`del` 直接删 `build.lock`。锁超过 20 分钟没动视为遗留，先 SendMessage 告诉 organizer，不要擅自删。
4. **验收一律在统一入口 19063 上做**：19063 请求味美页面时用 classic 组装，各线对 `ydwm-v1\public\*.js` 的修改在 19063 上直接生效；19060 单独打开仍加载冻结的 fast-ui 打包，看不到你的改动，不能拿它判断。

## 4 实现要求
- 按自己的规格逐页实现，使用逻辑和信息层级以规格第 3、4 项为准，AI 以规格第 5 项和 DECISIONS 第三节为准。
- 统一设计系统（`--cs-*` 变量、24 类组件、32–33px 紧凑行高、两位小数）；不新造样式体系。
- 缺的数据不猜不补，保持真实空态；演示写入只用 `?acceptanceSession=test-<线>-20261111`。
- 每完成一个页面就自测一次，不要攒到最后。

## 5 验收与回执
- 在真实浏览器里按规格第 7 项逐条点验，含保存→刷新→回读和阴性对照；截图放 `accounts\unified-v1\evidence\impl-<线>\`。
- 交付记录写 `unified-spec\impl-<线>.md`：做了哪些页、改了哪些文件、点验结果（逐条，通过/未通过/未验证）、保护校验前后读数、遗留问题。没验证的不能写成通过。
- 结束时用一段话回报：完成页数/总页数、未完成项、需要 organizer 协调的跨线事项。

## 6 点验前先看顶栏工作区（organizer 11-11 21:30）
内置浏览器的 localStorage 被几条线共用，顶栏工作区（味美/六膳门）和当前页可能被别的会话改掉。每次点验、截图前先确认顶栏工作区和地址栏 hash；截图里要能看到顶栏工作区。结论受工作区影响的，回执里写明当时的工作区。
- 六膳门侧（`/__unified/lsm/`）由 `accounts\unified-v1\tools\build.cjs` 构建，构建时会先清空 dist，构建期间访问 404/白屏属正常窗口期；先看 `build-lock.cjs status` 有没有人在构建，等锁释放后再测，不要据此判定产物丢失。
