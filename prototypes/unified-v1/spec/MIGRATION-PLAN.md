# MIGRATION-PLAN · 47 正式管理端换成统一原型 UI（方案，未实现）

日期：2026-11-13。依据：`DECISIONS.md` 第十六、十七节与末尾 Steve 方向（11-13）、`G-gap-v2.md`（逐页 135 行）、`BATCH7-AUDIT.md`、`BATCH8-AUDIT.md`、原型源码 `accounts/unified-v1/src/{frame-g,crew,...}` 与 19063 的 `/__unified/menu.json`（只读取一次 JSON，没点任何页面）。正式端以仓库 origin/main `93e73bc130` 只读核对。

**状态：只是方案。没有写代码、没开 PR、没部署、没写任何数据；Steve 点头之前不开实现。**

> 路径更正：正式管理端在仓库根目录 `web-admin/`，不是 `frontend/web-admin/`（`frontend/` 是 RN App）。下文路径都相对仓库根。

---

## 〇、给 Steve 的一页结论

1. **技术上是同一套栈，可以直接搬**：正式端 `web-admin` 是 Vue 3.5 + TypeScript + Vite + Element Plus + Pinia + vue-router 4；原型 `unified-v1` 也是 Vue 3 SFC。原型的设计 token 是「Cretas System v1」`--cs-*`（主色 #1b65a8，见 `accounts/ydwm-v1/public/design-system.css`），正式端目前用 Element Plus 默认蓝 #409eff（`web-admin/src/App.vue:30-33`）。所以换外观的主体工作是**外壳 + 菜单 + token**，大部分业务页**不必重写**，套新 token 就会跟着变。
2. **菜单换组不改权限**：正式端菜单是手写的 `web-admin/src/components/layout/menuConfig.ts`，每项自带 `module / roles / hideForFactoryTypes / allowedFactoryTypes / requiresFactoryContext`，路由守卫在 `router/guards.ts`，工厂级菜单开关读 `feature-config`（`factoryMenuVisibility.ts`）。新菜单只是把这些项**重新分组**（今天 / 数字班组 / 待办 / 发现的问题 / 全部功能 / 设置下沉），每一项的权限字段原样带过去，旧路由一个不删。
3. **原型里真正新增的东西不多**：G-gap-v2 第 3 节已核对，逐笔称重、入库批次、先到期先出在正式端已有同类能力，落正式端是增量。真正新的是：「今天」「待办」两页、「发现的问题」的来源分类与关闭结论、数据疑点（R30/R34）、R35/R36 两个校验、数字班组一套、对账框架、外部系统对照列。
4. **外部软件部分有一个前置缺口**：黑湖数据现在只在 Steve 本机的隔离测试库（每天 08:00 采集），正式后端 Java 里 `blacklake` 0 处、Python 的黑湖路由也没挂进主进程。所以「黑湖对照列 / 和黑湖对账」上正式端之前，要先定**黑湖只读数据怎么进 47**（见第四块选择题 Q6）。没有这条数据通道，liushanmen 也只能显示「外部数据未接入」。
5. **第 0 步已核对**：47 上的 web-admin 就是 origin/main `93e73bc130` 的构建（公网首页哈希与发布回执、main 树一致）；比 G-gap-v2 基线只多 #1679 一个提交（报废审批菜单改按权限判），影响 P2、P3、P9。
6. **分 10 批**（第二块末尾表），每批是独立 PR + CI。先做外壳/菜单/token（P1–P2），再做「今天/待办/发现的问题」（P3–P4），再做数据疑点与校验（P5），之后是业务页换皮与增量，关掉的模块只换外壳（P9），外部软件单独一批（P10），数字班组最后、先要你定口径（P8）。

---

## 第 0 步：47 当前部署的 web-admin 版本（11-13 19:08 只读核对，补 G-gap-v2 的「未在线核对」）

**结论：47 上的 web-admin 与 origin/main `93e73bc130` 的 web-admin 目录逐字节同一份构建，没有落后。**

| 读数 | 值 | 来源 |
|---|---|---|
| 公网首页 `https://admin.cretaceousfuture.com/` 的 index.html SHA-256 | `90b01d91…2346`（Last-Modified 2026-11-13 09:38:29 GMT；引用 `/assets/index-CxuvijIe.js`、`index-Bf-F27GL.css`） | 本次 `curl` 匿名 GET，不登录、不写入 |
| 本机发布记录 `~/.cache/cretas/release-reports/cretas-1794565209-57529.json` | `status: deployed`，`head_sha 93e73bc130`（11-13 18:20），web `build: reused / deploy: success`；`web_manifest.tree = 1790278058…`、`index_sha256 = 90b01d91…2346`；四方哈希（本地 / 服务器 / 网关 / 公网）全等于 `90b01d91…2346` | 发布脚本回执 |
| origin/main 的 web-admin 树 | `git rev-parse 93e73bc130:web-admin` = `1790278058…`，与上面构建所用的树相同（复用的构建来自 #1679 的 PR 头 `1f0b390961`，web 树相同所以等价） | git |

**与 G-gap-v2 基线的差异**：G-gap-v2 按 origin/main `114efd5a2d`（11-13 14:12）写。此后 web-admin 只多了 1 个提交 #1679（`93e73bc130`）：
- `menuConfig.ts`：「报废审批」`/finance/disposal-approvals` 不再按 `roles` 写死岗位，改为新字段 `requiresDisposalApproval: true`（`MenuItem` 增加该字段）；`router/guards.ts` 新增按后端 `approval-permission` 判断的守卫，读不到按没有处理；`AppSidebar.vue`、新 store `disposalApproval.ts`。
- 菜单页数不变（仍 135 页），G-gap-v2 的逐页状态仍可用。

**对各批的影响**：
- **P2**（菜单改组）：新菜单的项必须带上 `requiresDisposalApproval`，「权限字段逐项相等」闸要把这个字段算进去；AppSidebar 改造要保留 #1679 新加的过滤逻辑。
- **P3**（待办）：「待我审批」页签若汇总报废审批，计数要走同一个 `disposalApproval` store 判资格，不能按岗位名。
- **P9**（财务换外壳）：报废审批页的守卫不能动。
- 其余批不受影响。
- 实现开工时（每批从 origin/main 新开 worktree 时）重做一次这个对比：公网 index 哈希 vs 最近一次 `deployed` 回执的 `index_sha256` vs `origin/main:web-admin` 树。三者不一致就先查清再开工（说明 47 有未合并的前端或 main 有未发布的前端，后者会被我们的 web 发布一起带上线）。

---

## 一、外壳、菜单、设计系统

### 1.1 现状对照

| 项 | 正式端（origin/main） | 原型（19063） | 迁移做法 |
|---|---|---|---|
| 外壳 | `components/layout/AppLayout.vue`、`AppSidebar.vue`、`SidebarMenuNode.vue`（递归）、`AppHeader.vue`、`WorkspaceTabs.vue`、`GlobalAiControl.vue` | `public/shell.js` + `shell.css` 注入的 `#uni-nav`（深色侧栏、分组、子项、「常用」固定区）、`#uni-tools`（面包屑 `uni-crumb`、菜单搜索 Ctrl+K、工作区范围标签） | **改造** AppSidebar/AppHeader，不新建第二套外壳；原型的 shell.js 是为「编译页不能改」做的注入，不搬代码，只搬结构与样式 |
| 菜单定义 | `menuConfig.ts`（`MenuItem` 第 3 行，`menuConfig` 第 657 行，`platformMenuConfig` 第 665 行），顶层 18 组；#1679 起项上还有 `requiresDisposalApproval`（见第 0 步） | `/__unified/menu.json`：顶层 4 个独立入口 + 「全部功能」分区下的业务组 + 底部「设置」 | **改** `menuConfig.ts`：加 `section`（顶层 / 全部功能 / 设置）与 `solo` 字段，项本身沿用 |
| 面包屑 | 有闸 `__tests__/gates/breadcrumbMatchesMenuLabel.source.spec.ts`（面包屑必须等于菜单名） | 「组 › 项 › 子项」，顶层四项只显示一级 | 同步改闸的期望，不删闸 |
| 权限 | `store/modules/permission.ts`（角色 × 模块 rw/r/-，`FACTORY_TYPE_MODULE_FILTER`）、`router/guards.ts`（`meta.roles/module/modulesAny/modulesAll/hideForFactoryTypes`） | 原型只读当前账号权限做「动作权限对照」，不改权限 | **不动**；新页面按同样的 `meta` 声明 |
| 工厂开关 | `factoryMenuVisibility.ts`：`feature-config` 行的 `enabled`、`config.menuVisible`、`config.disabledMenuPaths`、`showInSidebar`；后端 `FeatureConfigController` `/api/mobile/{factoryId}/feature-config`，表 `factory_feature_config`；平台侧 `/api/platform/factories/{factoryId}/menu-config` | 原型按工作区（ydwm / lsm）切换 | **沿用**：新分组照样过 `disabledMenuPaths` / `menuVisible`；外部软件授权见第四块 |
| token | Element Plus 变量，`--el-color-primary:#409eff`，`--el-border-radius-base:4px`；全局 `src/style.css` | `--cs-*`：primary #1b65a8 / hover #15568f / soft #edf4fb，bg #f4f6f9，text #1a2332，muted #526278，border #dce3ed，success/warning/danger/info 及 soft，radius 6/10/12，space 4–32，sidebar 220 / 64，row 32/44，字体 Microsoft YaHei 起 | 新建 `web-admin/src/styles/tokens.css`（`--cs-*` 原样）+ 把 Element Plus 的 `--el-*` 映射到 `--cs-*`（primary、success、warning、danger、info、border、radius、font） |
| 格式化 | 各页自写 | T5：共用 `fmtMoney / fmtQty`（两位小数） | 放进 `web-admin/src/utils/format.ts`（先查已有同名工具，有就复用） |

### 1.2 新侧栏分组（与原型 menu.json 一致）

顶层（独立入口，`solo`）：
1. **今天** `/today`（新路由；原型 `/g/today`）
2. **数字班组**（组）：班组首页 `/crew`、工作记录与额度 `/crew/ledger`、班组入职 `/crew/onboard` —— 是否首批就上见 Q4
3. **待办** `/todo`（新路由；原型 `/g/todo`）
4. **发现的问题** `/issues`（新路由，由 `/workspaces/exceptions` 改造而来，旧路由跳过去）

「全部功能」分区（原业务组，组名与正式端现名对齐，组内顺序按原型）：概览（首页 `/dashboard`）、个人 OA、采购管理、销售管理、生产管理、仓储管理、质量管理、数据与分析，**以及原型关掉、正式端保留的**：研发管理、人事管理、设备管理、财务管理、智能调度、系统管理（见第三块）。

底部「设置」：设置 `/settings`（汇总页，新）、数字班组配置、视觉质检训练、主数据（SKU、供应商、客户、原料类型、物料分类、车辆字典、计量单位）—— 主数据下沉是只改菜单位置，路由不变。

保留：「常用」固定区（D10，按账号保存；存储用现有 `api/savedViews.ts` 或用户偏好接口，**先查**能否复用，不能复用要新表 → 强制 PR）；菜单搜索（补新组名）。

### 1.3 首页与岗位工作台
- D4 已定：岗位工作台并入首页，首页按岗位筛选。正式端 `/workdesk/*` 6 页（销售老板、财务主管、质量主管、生产经理、仓管员、采购员、质检主管）**路由保留**，跳 `/dashboard?role=...`（原型 `aliases` 已有 4 个映射；正式端多出财务、质量两个岗位，原型首页筛选目前只有销售/生产/仓库/采购，要补）。
- 首页「不打开即调用模型，点『建议』才分析」（D4、R40）沿用。

### 1.4 风险
- **面包屑闸、路由可达闸**（`breadcrumbMatchesMenuLabel`、`routerPushPathsResolve`、`moduleListsPairwiseConsistency`）会因改组变红 —— 这是预期，按新菜单改期望，不能删闸或放宽。
- **Element Plus 主色从 #409eff 换 #1b65a8** 会影响所有页的按钮、链接、图表主色；echarts 主题（`ChartThemeSelector.vue`）要一起映射，否则图表与界面两个蓝。
- 菜单项的 `roles / hideForFactoryTypes / requiresFactoryContext` 漏带一项就会让某些角色看到不该看的页或看不到该看的页 —— 用「改前改后数」（每个 path 的权限字段逐项相等）做闸，见 P2 验收。
- `WorkspaceTabs` / `WorkspaceRouteCache`（多标签 + keep-alive）原型没有，**默认保留**，否则老用户的多标签工作方式会丢（Q2）。
- 餐饮 / 物流工厂（`hideForFactoryTypes` 里的 RESTAURANT、LOGISTICS）和平台菜单 `platformMenuConfig` 不在原型范围：**只换 token 与外壳，不改分组**。

---

## 二、逐页替换

### 2.1 写法约定
- 「改造」= 正式端已有页，换外壳/token，按原型补列或补交互；「新建」= 正式端没有的页；「只挂菜单」= 页面不动，只换菜单位置。
- 数据：原型读的是 `/__unified/<线>/api/*`、`/__lsm/*`、`private/<线>/lsm/*.json`、六膳门快照（revision e4d77bafb5c9）或味美编译页。**正式端一律接 Java `/api/mobile/{factoryId}/*` 真实接口**；接口没有的写「要新增」，不造数据、不拿别的数顶。
- 「今天」在原型里 = 快照日（R28）；正式端是实时库，**一律用北京时间当天**，不搬快照日逻辑（快照日只在外部数据时点上保留，见第四块）。

### 2.2 顶层与新页

| 原型页（路由） | 正式端对应 | 方式 | 后端接口 | 数据来源说明 |
|---|---|---|---|---|
| 今天 `/g/today` | 无；新 `/today`（`views/today/`） | 新建 | 已有：`/my-todos`（OA）、`/task-badges`、`/material-batches/expiring`·`/expired`、交付风险（`/production/delivery-risks` 用的接口）、`/production/yield`；**要新增**：「今日待称重任务」聚合（按 `ProductionReport` 与计划工序算未报完的任务，见 P5）、对账差异计数（只有外部授权工厂，第四块） | 原型 `today-data.ts` 读六路演示接口；正式端换成上面真实接口。**G-1**：每路读取带 `AbortController` 超时（建议 10 s），超时显示「读取失败 · 重试」，并在超时后释放 `inflight`（不能让一个挂起的请求拖住之后所有 `loadToday()`） |
| 待办 `/g/todo` | 无；新 `/todo`（六个页签：等我确认 / 业务待办 / 指派给我 / 待我审批 / 我发起的 / 抄送与已处理） | 新建 | 已有：`/workflow/*` 用的 OA 接口、`/my-todos`、`/task-badges`；等我确认依赖数字班组（P8 前该页签不显示或写明原因） | **G-2**：页签角标只在读到数时画；读不到或本厂没有这类数据时**不画角标**，在页签内容里写一句原因（如「本厂未启用数字班组」「读取失败：……」），不画「—」 |
| 发现的问题 `/crew/issues` | 改造 `/workspaces/exceptions`（`views/workspaces/index.vue`），新路由 `/issues`，旧路由跳转 | 改造 | 已有：异常中心现接的异常/预警、`/purchase-exceptions`、`/sales-exceptions`、缺料异常 cases、成本损耗异常 cases、`admin/data-quality-queue`；**要新增**：「关闭须选结论类型 + 一句话」与「转待办」的写接口（若现有异常实体没有结论字段 → 迁移 + entity，强制 PR） | 按来源分类：数字员工汇报 / 规则预警 / 对账差异 / 数据缺口 / 数据疑点（R33、R30）。对账差异只在有外部授权的工厂出现 |
| 首页（岗位） `/dashboard` | `/dashboard` + `/workdesk/*` | 改造 | 已有 | 见 1.3 |
| AI 经营报告 `/analytics/ai-reports` | 同路由 | 改造 | 已有报告接口；R40 周期产出（按周/月规则生成事实表）**要新增**定时生成（后端任务 + 存储，可能新表 → 强制 PR） | 模型解读只在用户点击后请求 |
| 自定义报表 `/unified/reports` | `/smart-bi/query-templates`（原型 frameRoute 即指向它） | 改造 | 已有 | — |

### 2.3 生产管理

| 原型页 | 正式路由 | 方式 | 接口 | 说明 |
|---|---|---|---|---|
| 生产计划 `/production/plans` | 同 | 改造 | 已有 | 原型「精简视图」是为编译页做的包装，不搬（G-gap-v2 #11） |
| 领料 `/production/material-flow` | 同（含 `/warehouse/material-requisitions` 只读，D2） | 改造 | 已有 | — |
| 生产批次 `/production/batches` | 同 | 改造 | 已有 | 六膳门叫「工单」由 T2 决定，按工厂显示对象名 |
| 生产报工 `/production/approval` | 同 | 改造 | 已有 | — |
| 称重报工 `/production/weigh` | 无独立页；正式端在工序报工单（`ProcessSheetController` `/production-plans/{planId}/process-sheet`、`ProductionReport` 已有 input/good/defect、起止时间、byproducts） | 新建页 + 复用现有报工写接口 | **要新增**：皮重字段（`ProductionReport` 无 tare → 迁移 + entity，强制 PR）；同一任务多笔录入的列表接口（可能只是查询，先查现有） | R25 口径（成品产出 = 最后一道工序良品；整单出成率 = 末道良品 ÷ 首道投入）要先与正式端 `stepYieldRate / cumulativeYieldRate` 对齐（Q5）；R34/R36 见 P5 |
| 生产查询 `/production/analysis-query` | 同 | 改造 | 已有 | — |
| 缺料异常 `/production/shortage-cases` | 同；`/quality|procurement|sales/shortage-cases` 三个重复挂载**路由保留**，菜单里只留生产一处 + 「发现的问题」 | 改造 | 已有 | D5 |
| 状态看板 `/production/kanban` | 同 | 改造 | 已有 | — |
| 车间大屏 `/dashboard/production-progress` | 同 | 改造（大屏样式单独，token 只换色） | 已有 `ProductionProgressDashboardController` | — |
| 在制品 `/production/wip` | `/warehouse/wip-batches`（router 第 638 行附近，待核是否同义） | 只挂菜单 / 改造 | 已有 | 实现时先核对两页是否同一概念 |
| 工序配置 `/production/process-hub` | 同 | 改造 | 已有 | — |
| 出成标准 `/production/yield-standard` | 同 | 改造 | 已有 | D3：只在这里维护 |
| SKU 管理 `/system/products` | 同，菜单下沉到「设置 / 主数据」 | 只挂菜单 | 已有 | — |
| 交付风险 `/production/delivery-risks` | 同；`/sales/delivery-risks` 路由保留并落同一页（R39） | 改造 | 已有 | — |
| 工程变更通知 `/production/bom/ecns` | 同 | 改造 | 已有 | — |
| BOM 调价建议 `/production/bom/price-adjustments` | 同 | 改造 | 已有 | 只作建议、不回写 |
| 委外收发 `/lsm/outsource` | 正式端未见同名路由（grep `outsourc` 0 处） | 待定 | 待核 | 原型只给六膳门（黑湖数据）。正式端是否要这页见 Q7 |
| 成本分析 `/lsm/cost` | `/production/cost-variance`、`/production/cost-deviation-cases`、`/production-analytics/cost-summary`（财务下重复挂载的两个保留路由） | 改造（D1：入口放「数据与分析 › 成本分析」） | 已有；副产品三种分摊口径（R23-B）**要新增**口径配置 | 两单并排、按责任人导出待补清单值得一起做 |

### 2.4 仓储管理

| 原型页 | 正式路由 | 方式 | 接口 | 说明 |
|---|---|---|---|---|
| 入库任务与批次 `/warehouse/materials` | 同 | 改造 | 已有 | — |
| 入库批次 `/lsm/inbound-batches` | 并入 `/warehouse/materials` 的批次页签 + 库存查询的批次维度 | 改造 | 已有 `MaterialBatchController` `/material-batches`（含 `/expiring`、`/expired`）；**要新增**：批次号规则可改、R35 已过期确认原因字段（迁移 + entity，强制 PR） | 「关联外部入库单」列只在外部授权工厂显示（第四块） |
| 无单申请 `/warehouse/unordered-inbound-applications` | 同 | 改造 | 已有 | — |
| 出库执行 `/warehouse/shipments` | 同 | 改造 | 已有 | FEFO：正式端是自动扣减，原型是「建议」，保留正式端行为，只加显示 |
| 调拨单 `/transfer/list` | 同 | 改造 | 已有 | — |
| 库存查询 `/warehouse/inventory-query` | 同 | 改造 | 已有 | 临期/过期着色已有 |
| 进销存汇总 `/lsm/stock-summary` | `/analytics/supply-chain`（工厂原隐藏，见 Q3） | 改造 | 已有 | — |
| 库存调整单 `/warehouse/inventory-adjustments` | 正式端未见同名路由 | 待定 | 待核（是否就是报损/盘点差异） | Q7 |
| 盘点任务 `/warehouse/stocktake-query` | 同 | 改造 | 已有 | W8：现场盘点并入 |
| 仓位与平面图 `/warehouse/storage-locations` | 同 | 改造 | 已有 | — |
| 原料字典、物料分类 | `/warehouse/material-types`、`/warehouse/material-segments`，菜单下沉主数据 | 只挂菜单 | 已有 | — |
| 报损管理 `/warehouse/wastage-reports` | 同 | 改造 | 已有 | 原型审计 C-1：报损日期不能晚于今天（正式端核对是否已拦） |
| 源头个体档案 `/warehouse/source-individuals` | 同 | 改造 | 已有 | — |
| 物料均价 `/warehouse/material-price-trend` | 同，菜单放「数据与分析 › 经营分析」（D6） | 只挂菜单 | 已有 | — |

### 2.5 质量管理（正式端 7 个闭环页**都已存在**，`views/quality/{ccp,returns,disposals,recalls,param-deviation,cold-chain,vision-training}`）

| 原型页 | 正式路由 | 方式 | 接口 | 说明 |
|---|---|---|---|---|
| 质量检验 `/quality/inspections` | 同（+ `/quality/release` 放行队列保留） | 改造 | 已有 | — |
| 检验标准 / 不良分析 | `/quality/standards`、`/quality/defects` | 改造 | 已有 | — |
| CCP 监控、质检退回、废弃处理、工艺参数偏离、冷链超温、包装标签拍检 | 同名路由 | 改造（换外壳，补「时间不晚于现在」等原型校验，若正式端未拦） | 已有 | — |
| 批次召回 `/quality/recalls` | 同；`FactoryRecallController` `/quality-recalls` | 改造 | 已有 | **E-3**：正式端召回已经用 `RecallEvent.eventCode` 作单号、批次扣留用独立链接与 FROZEN 状态（`FactoryRecallService`），全仓库 `inspectionNo` 0 处。迁移时**不要**照搬原型把 RC 单号塞进 hold 的 `inspectionNo` 的写法；批次页显示扣留时读 `eventCode`，标签与批次号之间留间隔 |
| 视觉质检训练 | `/quality/vision-training`，菜单下沉「设置」 | 只挂菜单 | 已有 | — |
| 不良品处置 `/quality/disposition` | 同 | 改造 | 已有 | — |

### 2.6 采购、销售、个人 OA、数据与分析

| 原型页 | 正式路由 | 方式 | 说明 |
|---|---|---|---|
| 采购需求 / 待审批 / 全部请购 / 采购订单 / 采购建议 / 供应商 / 价格表 | `/procurement/requisitions/my`、`.../pending-approval`、`/procurement/requisitions`、`/procurement/orders`、`/procurement/ai-purchase`、`/procurement/suppliers`（下沉主数据）、`/procurement/price-lists` | 改造 / 只挂菜单 | 接口已有 |
| 订单 / 发运记录 / 退货 / 客户 / 车辆字典 / 销售付款申请 | `/sales/orders`、`/sales/shipments`、`/sales/returns`、`/sales/customers`（下沉）、`/sales/vehicles`（下沉）、`/sales/payment-requests` | 改造 / 只挂菜单 | W6 销售订单精简（8–9 列、表格｜看板、状态卡改下拉）落正式端 |
| 应收与收款 `/lsm/receivables` | `/finance/ar-ap`、`/finance/invoices`、`/finance/payments` | 只挂菜单（财务模块在正式端开着，第三块） | 原型这页只为六膳门读黑湖应收；正式端用自己的财务数据 |
| 客户价目表 `/lsm/customer-prices` | 正式端未见同名路由 | 待定 | Q7 |
| 审批中心四页 + 协同任务 `/collab/tasks` | `/workflow/pending|my-created|acted|copied`；协同任务正式端无同名路由，近似 `/my-todos` | 改造；协同任务待定 | D7：审批动作只在单据详情；Q7 |
| 经营驾驶舱 / 领域概览 / 经营主题 / 进销存 / 异常预警 / 数据工作台五页 | `/smart-bi/*`、`/analytics/*` | 改造 | **注意**：正式端这组对工厂是 `hideForFactoryTypes:['FACTORY']`（工厂看不到，`factoryMenuVisibility.hasFactoryAnalyticsSidebarOptIn` 可按厂打开）。原型给工厂都放出来了 —— 见 Q3 |
| 对账（Cretas ↔ 黑湖）`/lsm/reconcile` | 无 | 新建（只有外部授权工厂可见） | 见第四块 |
| 经营总览 `/workspaces/overview`、方案与审批、追溯与复盘 | 同名路由 | 改造；方案与审批并入「待办」后旧路由跳 `/todo?tab=approve` | — |

### 2.7 数字班组（F 线整套）

| 原型页 | 正式端 | 方式 | 接口 |
|---|---|---|---|
| `/crew` 班组首页（只留跳转，启用配置不在这里）、`/crew/ledger` 工作记录与额度、`/crew/onboard` 入职、`/crew/settings` 数字班组配置（放「设置」组：启用哪几位 + 原因 + 调整记录、能力与权限对照表、一线手机岗位 → 数字员工映射）、`/crew/issues`（即「发现的问题」，见 2.2）、`/crew/inbox`（并入待办「等我确认」）；`/crew/reports`、`/crew/worker`、`/crew/mobile`、`/crew/map` 在原型里都是别名或不进侧栏 | 无「数字员工」概念；已有 AI 页可作后台：`/dashboard/ai-value`、`/system/ai-frontline-grants`、`/system/reusable-skills`、`/system/autonomous-writes`、`/ops/agent-ops`、`/factory-readiness` | 新建 | **大量新增**：员工启用配置（按厂，带原因与调整记录）、交办/办成/没办成/结果不明四态记录与额度、等我确认（预览后确认）、主动汇报、岗位 → 数字员工映射（原型是代码常量 `crew-config.ts` 的 `DEMO_ROLES[*].mobile`，正式端要做成工厂级配置，F-crew-gaps D4）。必有新表 → 迁移 + entity + repository，强制 PR。原型 `crew-store.ts` 的读取同样要带超时（**G-1** 第二处：`crew-store.ts:52` 的 `inflight` 与 `:60-62` 读当前用户、权限的 `fetch` 无超时） |

按 F 线交接（`context-handoff/handoff/cf9e6487.md`）与 `F-crew-gaps.md` 复核后，正式端实现还要守住这几条（原型没做到或没验到）：
- **确认要在服务端校验权限**（F-crew-gaps A4）：原型 `inbox/confirm` 只看页面传来的 `perm`，服务端不校验。正式端确认接口用 `@RequirePermission` 按登录人校验对应模块权限，确认人从会话里取，不信页面传值。属于 security → 强制 PR，合并前独立审查。
- **额度口径（R29）**：交给人办记「已交办」，不扣额度；对应任务完成并回读核实后才记「已办成」、扣 1 个；取消记「没办成」；四种状态分开显示。正式端用真实日期计数（原型办成卡也已按真实日期，N-2）。
- **「结果不明」可复用正式端已有做法**：#1632（`1392d2938e`）已在 AI 写入确认里做了「确认前比对改前值 / 异常记结果不明」，数字班组的写操作沿用这条路径，不另做一套。原型没验到强制失败下的「结果不明」，正式端要用注入失败的测试证明。
- **自由对话**：正式端已有能力包匹配 `FactoryCapabilityPackController`（`/ai/capability-pack/match`）和工具网关 `ai/tool/gateway/*`（F-crew-gaps D2），接这个，按员工能力 + 登录人权限过滤意图；对话界面按仓库 `docs/ux/ai-chat-standard.md`。
- **原型没验到的**：真实触发的规则预警条目、外壳里侧栏分组生效、服务端权限校验 —— 正式端这三项都要有真实入口的验收证据。
- 查询直接返回、写操作先预览后确认（DECISIONS 第十四节硬约束）不变。

### 2.8 分批顺序（每批一个 PR、一次 CI）

| 批 | 内容 | 依赖 | 预计强制 PR 路径 | 验收要点 |
|---|---|---|---|---|
| **P1 设计系统** | `styles/tokens.css`（`--cs-*`）、`--el-*` 映射、echarts 主题映射、`fmtMoney/fmtQty` | 无 | 否 | vue-tsc、build、vitest；挑 6 页（首页、计划、库存、质检、订单、财务概览）前后截图对照 |
| **P2 外壳与菜单** | AppSidebar/AppHeader 改造、`menuConfig.ts` 改组（section/solo）、「常用」区、菜单搜索、面包屑闸改期望、旧路由跳转（`/workdesk/*`、`/workspaces/exceptions|plans-approvals`） | P1 | 「常用」若需新表则是；否则否 | 闸：改前改后每个菜单 path 的 `module/roles/hideForFactoryTypes/allowedFactoryTypes/requiresFactoryContext` 逐项相等（数量相等）；每个角色登录看侧栏截图；`disabledMenuPaths` 阴性对照（关一个路径真的消失） |
| **P3 今天 + 待办** | `/today`、`/todo` 两页，只接已有接口；G-1 超时与释放；G-2 不画「—」 | P2 | 否（只读聚合可在前端拼；若加后端聚合接口则是普通 PR） | 注入一个挂起请求，页面 10 s 内显示失败且下一次刷新能读到（G-1 的阳性对照）；本厂无数字班组时页签无角标且写明原因 |
| **P4 发现的问题** | 改造异常中心为 `/issues`：来源分类、关闭须选结论、转待办 | P2、P3 | 若加结论字段：迁移 + entity → 是 | 关闭不选结论被拒（阴性），选了能保存并回读 |
| **P5 数据疑点与录入校验** | R30/R34 数据疑点（出成率 ≥100%、起止相同、单位明显不符 → 标「数据疑点 · 找谁核」，不改原值、不进异常）；R36 时间不晚于现在 5 分钟；R35 已过期批次确认 + 原因；称重页（多笔、皮重） | P4 | 皮重、已过期原因字段：迁移 + entity → 是 | 前后端都拦（后端校验 + 前端字段旁提示）；真实入口点验 |
| **P6 生产 + 仓储换皮** | 2.3、2.4 全部「改造/只挂菜单」项；入库批次并入批次页签；成本分析入口 | P1、P2 | 副产分摊口径若落库则是 | 每页一张截图 + 一个主操作真点 |
| **P7 质量 + 采购 + 销售 + OA + 数据分析换皮** | 2.5、2.6；E-3 显示口径；W6 订单精简 | P1、P2 | 否 | 同上；召回扣留显示 `eventCode` |
| **P8 数字班组** | 2.7 | P3、P4、Steve 定口径（Q4） | 是（新表 + 确认接口服务端权限校验） | 写操作一律预览后确认；四态额度回读；无权限的人调确认接口被服务端拒绝（阴性）；注入失败记「结果不明」 |
| **P9 关掉的模块换外壳** | 第三块全部 | P1、P2 | 否 | 每组抽 2 页截图，主操作不变 |
| **P10 外部软件** | 第四块 | P4、P5、P6、Q6 数据通道 | 是（授权记录表；外部数据只读表） | liushanmen 显示对照列，另一工厂不显示（阴性对照） |

使用频率排序：P3/P4（每天打开）> P5/P6（一线每天录入）> P7 > P9（低频）。P1–P2 必须最先，否则后面每页都要改两次。

每批共同要求：从 `origin/main` 新开 worktree；`web-admin-gate.yml`（vitest + vue-tsc）、`ci.yml` 的 `vue-build-check`、`e2e-pr.yml` 全绿再合；涉及 Java 的批（P4/P5/P8/P10）另过 Java/JPA 门禁，并按 `release-cretas.sh --phase build` → 合并 → `--phase deploy` 发布；UI 完成条件有真实入口截图 + 阴性对照。web 部署会把 main 上所有人的前端一起发出去，发 web 前查外来改动调的 Java 路由 prod 是否已有（项目记忆 10-22）。

---

## 三、关掉的模块（财务、人事、设备、调度、研发、系统）

**原则**：正式端照常可用，只换新外观（P1 token 自动生效）和菜单位置（放进「全部功能」分区，系统类下沉「设置」）。不改业务逻辑、不改接口、不改权限字段。原型这些组只有骨架（`/g/finance`、`/g/people`、`/g/equipment`、`/g/scheduling`、`/g/rd`、`/g/settings`），**骨架不搬**。

| 模块 | 菜单位置（新） | 路由（菜单内，按 G-gap-v2 第 6 节） | 路由文件中的总数 |
|---|---|---|---|
| 研发管理 | 全部功能 › 研发管理 | `/rd/samples`、`/rd/converted` | `rd` 5 |
| 人事管理 | 全部功能 › 人事管理；账号/角色/邀请下沉「设置 › 组织与账号」 | `/hr/employees`、`/hr/departments`、`/hr/work-types`、`/hr/attendance`、`/hr/whitelist`、`/system/users`、`/system/roles`、`/system/permission-matrix` | `hr` 18 |
| 设备管理 | 全部功能 › 设备管理；告警同时进「发现的问题」来源 | `/equipment/list`、`/equipment/maintenance`、`/equipment/alerts` | `equipment` 3 |
| 财务管理 | 全部功能 › 财务管理；调整审批、报废审批同时进「待办 › 待我审批」 | `/finance/adjustments`、`/finance/disposal-approvals`、`/finance/costs`、`/finance/three-statements`、`/finance/reports`、`/finance/ar-ap`、`/finance/invoices`、`/finance/purchase-invoices`、`/finance/payments`、`/finance/voucher-list`、`/finance/voucher-export`、`/finance/voucher-template`（下沉设置）、`/finance/sku-margin`、`/finance/cost-variance`、`/finance/cost-deviation-cases`、`/finance/gross-margin-redline`（下沉设置）、`/finance/accounting-period` | `finance` 20 |
| 智能调度 | 全部功能 › 智能调度；告警进「发现的问题」 | `/scheduling/overview`、`/scheduling/plans`、`/scheduling/proposal-comparison`、`/scheduling/realtime`、`/scheduling/workers`、`/scheduling/alerts`、`/scheduling/settings`（下沉设置） | `scheduling` 15 |
| 系统管理 | 「设置」 | `/system/logs` 与 `/system/operation-log`（两项菜单合一，路由都留）、`/system/settings`、`/system/business-thresholds`、`/system/approval-chains`、`/system/workflow-designer`、`/system/features`、`/system/ai-frontline-grants`、`/canvas-editor`、`/system/badge-generator`、`/unit-dictionary`、`/system/process-secret`、`/system/platform-access-log`、`/system/data-backups`、`/system/reusable-skills`、`/system/autonomous-writes`、`/ops/agent-ops`、`/factory-readiness` | `system` 40 |

另：`financeManagerMenu`（`menuConfig.ts:29`，财务主管专用菜单）要一起改组，不能只改通用菜单。

风险：这些页的旧样式里可能硬写了 #409eff 或 Element 默认值（`grep '#409eff'`、`--el-color-primary` 写死处要清点）；调度、财务里有大量图表，echarts 主题要随 P1 一起换。

---

## 四、接外部软件的部分（单列）

### 4.1 什么算「外部」，什么对所有工厂开
| 对所有工厂开（计算逻辑与功能） | 只在有外部软件接口授权的工厂显示 |
|---|---|
| 称重、出成率（R25）、批次（规则、到期日、先到期先出建议）、数据疑点（R30/R34）、R35/R36 校验、发现的问题、「找谁核」、对账**框架**（组件本身）、数据时点的写法 | 外部系统对照列（如称重页「黑湖报工」列、入库批次「关联黑湖入库单」列、成本页「黑湖值 / 采用值」）、「对账（Cretas ↔ 外部）」汇总页与菜单、今天/待办里的「称重对账」「入库对账」卡、发现的问题里的「对账差异」来源、来源标签「Cretas（主来源）/ 外部」、「外部数据最近采集时点」 |

现在只有 liushanmen 有黑湖授权。没授权的工厂：上面右列**整列 / 整卡 / 整个菜单项不渲染**（不是显示空列或「—」），对账来源在「发现的问题」的分类里也不出现；来源标签一律不显示（全部是 Cretas 自己的数，无需标）。

### 4.2 用什么判定授权（查过现有）
- 现有可复用的模式：**保密配置**（`FeatureConfigController` 把平台专属表 `factory_secrecy_settings` 的值作为只读伪模块行 `moduleId="secrecy"` 下发，工厂端写不了，前端判断 `=== true`）。
- 现有**没有**外部集成授权记录：Java 后端与 web-admin 里 `blacklake / 黑湖` 0 处；黑湖连接器在 `backend/python/blacklake/`，绑定方式是环境变量里的固定租户（`BLACKLAKE_TENANT_ID`、`smartbi_api.authorize` 只认配置里的 `tenant_id` + `factory_super_admin`），而且这些路由没有挂进 Python 主进程。
- **建议**：照保密配置的模式，新增平台专属表 `factory_external_integrations`（factory_id、system 如 `blacklake`、status、来源组织 id、授权时间、授权人、备注），平台在「工厂管理」里维护；`feature-config` GET 多下发一行只读 `moduleId="integrations"`，形如 `{ blacklake: { authorized: true, lastCollectedAt: ... } }`；工厂端 PUT 一律 403。前端一个 `useExternalIntegration('blacklake')` 判定，所有右列组件只认它。**不要**用 `disabledMenuPaths` 或 `FACTORY_BRAND_OVERRIDES['LIUSHANMEN']` 这类按厂名写死的方式。
- 这一项是迁移 + entity + repository + 权限（平台写、工厂只读）→ **强制 PR**，按项目记忆要独立审查。

### 4.3 外部数据怎么到 47（前置缺口）
黑湖数据现在在 Steve 本机隔离测试库（计划任务 `Cretas-Blacklake-Daily-0800`），47 上没有。对照列和对账要有外部那一侧的数，必须先有一条只读数据通道。选项见 Q6。无论选哪个：
- 只读，不回写黑湖；不在网页或手机上登录集成账号（会让接口令牌失效）。
- 外部数据进 47 落独立表（如 `external_source_snapshot`，按 factory_id + system + 采集时点），不混进 Cretas 业务表；读接口按 `factory_id` 过滤并校验授权行。
- 页面写明「外部数据时点：YYYY-MM-DD HH:mm（北京时间）」；外部数据过期（如超过 36 小时没新采集）在对账页头标出。

### 4.4 风险
- 授权行与数据通道不同步：有授权但没数据时，对照列显示「外部数据未接入（找平台）」，不显示 0。
- 对账结果只在 liushanmen 可验；阴性对照必须用另一个工厂（如 F006）证明右列完全不出现。
- 黑湖账号凭据只在本机 DPAPI 文件，迁到服务器采集要另走凭证流程（不在本方案内）。

---

## 五、G-1 / G-2 / E-3 并入情况
| 编号 | 原型位置 | 正式端落法 | 所在批 |
|---|---|---|---|
| G-1 | `src/frame-g/today-data.ts:55-56`（`get()` 无超时）、`:186-190`（`inflight` 复用）；`src/crew/crew-store.ts:52`（`inflight`）、`:60-62`（当前用户、权限读取无超时，`Promise.all` 被一个挂起请求拖住） | 「今天」「待办」与数字班组的数据层：每个请求 `AbortController` 超时（建议 10 s），超时按「读取失败」显示可重试，`finally` 里释放 `inflight`；单测用永不返回的假请求证明超时后第二次加载能发出新请求 | P3、P8 |
| G-2 | `src/frame-g/TodoPage.vue:24`（`num()` 味美返回「—」）、`:27`（业务待办任一来源未读到就「—」）、`:45`（页签角标） | 读到数才画角标；本厂没有这类数据或读取失败时不画角标，在页签内容写原因 | P3 |
| E-3 | 原型 `src/quality/batches.cjs` 把召回单号放进 hold 的 `inspectionNo` | 正式端已有独立 `eventCode` 与 FROZEN 扣留，迁移时沿用，不引入 `inspectionNo` 混用；批次页扣留标签读 `eventCode`，与批次号留间隔 | P7 |

---

## 六、要 Steve 拍板的产品口径（选择题，一次问完）

**Q1 上线方式**
A. 一次性全站切换（P1+P2 合并后所有工厂立即看到新外观和新菜单）（推荐：目前没有真实客户、prod 就是测试环境，回滚就是回退一个 web 发布）
B. 加平台级开关，先只给 F006 / liushanmen 开新外观，验收后全开
C. 新旧菜单并存一段时间，用户自己切

**Q2 多标签页（WorkspaceTabs）**
A. 保留正式端多标签（推荐：老用户在用，原型没有不等于不要）
B. 去掉，跟原型一致单页

**Q3 「数据与分析」组对工厂的可见性**（正式端现在对 FACTORY 隐藏，原型全放出来）
A. 跟原型：对工厂默认显示，按厂可关（推荐）
B. 维持正式端：默认隐藏，按厂打开

**Q4 数字班组首批是否上**
A. 菜单先不出现数字班组组，等 P8 口径定了再上（推荐：后端是新数据模型，赶不及 P2）
B. P2 就挂入口，页内写「本厂未启用」
C. 把正式端现有 AI 页（AI 工作台、一线角色 AI 权限、可复用 Skill、定时任务写入）先收进「数字班组」组

**Q5 称重出成率口径**（R25 与正式端 stepYield / cumulativeYield 的关系）
A. 正式端照 R25：成品产出 = 末道工序良品，整单出成率 = 末道良品 ÷ 首道投入；正式端现有累计出成率改名或并入（推荐）
B. 两个口径并列显示，各写清楚
C. 保留正式端口径，R25 只用于外部对账

**Q6 黑湖只读数据怎么进 47**
A. 本机每日 08:00 采集后，推一份只读快照到 47 的独立表（推荐：采集仍在本机，凭据不出本机）
B. 采集挪到服务器上跑（要另做凭证迁移与运维）
C. 先不接，P10 只做授权判定与「外部数据未接入」提示

**Q7 原型里正式端没有同名路由的 4 页**（委外收发、客户价目表、库存调整单、协同任务）
A. 正式端新建，作为通用功能对所有工厂开（数据用 Cretas 自己的）
B. 只在外部授权工厂作为外部数据页显示
C. 先不做，菜单不出现（推荐：原型里这 4 页主要服务六膳门黑湖数据，正式端先核对有没有近似功能）

**Q8 「发现的问题」关闭结论类型**
A. 沿用原型的结论类型清单（推荐）
B. 正式端异常中心现有处理状态不变，只加来源分类

---

## 七、强制 PR 的高风险路径（汇总）
| 批 | 路径类型 | 内容 |
|---|---|---|
| P2 | 可能 | 「常用」固定区若要新表（迁移 + entity + repository） |
| P4 | 迁移 + entity | 异常关闭结论、转待办关联 |
| P5 | 迁移 + entity | `ProductionReport` 皮重；批次「已过期仍建」原因；后端时间校验 |
| P6 | 可能 | 副产品分摊口径若落库 |
| P8 | 迁移 + entity + repository + security | 数字员工启用、额度四态、等我确认 |
| P10 | 迁移 + entity + repository + security | `factory_external_integrations`（平台写、工厂只读）、外部快照表、读接口授权校验 |
| P2 全部 | security 相关前端 | `menuConfig.ts` 改组、`router/guards.ts` 不改；权限字段逐项相等闸 |
动库存 / 账务 / 状态机的（P5 批次、P8 写操作）合并前派独立审查。

---

## 八、仍未核对
- 47 线上版本已按第 0 步核对（公网 index 哈希 = main 构建）；但没有登录 47（没读测试账号），各角色实际看到的菜单仍以源码为准。
- 正式端 `views/` 逐页没打开读；「在制品」「委外」「客户价目表」「库存调整单」「协同任务」是否有近似页只按路由 grep 判断。
- `store/modules/config.ts`、`app.ts`、`navigationVisibility.ts` 没读；「常用」能否复用 `savedViews` 未核。
- 异常中心现有实体是否已有结论字段、`ProductionReport` 多笔查询接口是否已有，未读代码。
- liushanmen 在 47 上是否有 Cretas 侧的真实称重 / 批次数据，未查。
- 原型 19063 只读了 `/__unified/menu.json`，没有重新点页面（页面状态以 BATCH8-AUDIT 为准）。
