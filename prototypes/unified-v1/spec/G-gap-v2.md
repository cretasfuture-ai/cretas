# G-gap-v2 · 47 正式管理端 vs 统一原型：二次差距分析（2026-11-13）

**给 Steve 的结论**（只读分析，没改原型代码、没构建、没写任何数据）
1. 正式端工厂可见 135 页，**菜单自基线 fa4cab00a9 以来没有变**（web-admin 只有 2 个修复提交，没新增页）。所以第一轮的页数还能用，只复核了原型一侧。
2. 复核后：**已有 23、部分 50、没有 62**（第一轮 22/48/65）。多出来的 3 页来自第七批：异常中心由「发现的问题」做成（部分→已有）；AI 工作台、AI 经营报告、一线角色 AI 权限三页由数字班组/自定义报表/班组配置补出一部分（没有→部分）。
3. 差在三块：**财务 12、系统 15、智能调度 7、人事 7、设备 3 共 44 页「没有」**（这些模块你关着，只记差距）；其次是**质量闭环 7 页**（CCP、质检退回、废弃、召回、冷链、参数偏离、视觉质检）；再次是**六膳门占位**：「部分」50 页里 34 页在六膳门只是「尚未迁入」。
4. 反方向：原型有、正式端没有的，主要是**六膳门专属的数据主权设计**（Cretas 为主来源 + 黑湖对照 + 对账 + 找谁核 + 快照日）和**数字班组一套**（班组首页、等我确认、工作记录与额度、发现的问题、数据疑点）。逐笔称重、入库批次、先到期先出在正式端**已有同类能力**（工序报工含副产/双出成率/FEFO 自动锁批次，收货与库存有到期日与着色），所以落正式端的是增量，不是整页新做（见第 3 节）。
5. 原型自己最大的缺口：六膳门首页已经换成「今天」，但「今天」「待办」页里的任务仍是「将先做…（骨架，尚未接数据）」。
**最值得先做的 8 项**：①「今天」「待办」接真实数据（待称重、临期批次、待确认、对账差异）；②质量闭环 7 页，其中 CCP、质检退回、废弃味美页可直达，先挂入口；③报损管理 + 源头个体档案（味美可直达）；④交付风险（生产、销售两处挂载，合并为一页）；⑤工程变更通知（味美可直达）+ BOM 调价建议；⑥把「部分」里六膳门 34 个占位按使用频率迁入；⑦销售付款申请、车辆字典（味美可直达）；⑧AI 经营报告的自动周期产出（现在只有手搭报表）。落正式端优先：数据疑点（R30/R34）、录入时间校验（R36）、已过期批次确认（R35）、「发现的问题」的闭环写法。
**未在线核对 47**（见第 8 节）；菜单可见性以源码 `menuConfig.ts` 为准。

## 1. 方法与证据码
- 正式端：`C:\Users\Steve\my-prototype-logistics` 先 `git fetch origin`，只用 `git show/grep origin/main`（当前 `114efd5a2d`，11-13 14:12）。基线 `fa4cab00a9`（11-12 22:29）。不在主目录切分支、不改文件。
- 原型：19063 当前状态（第七批之后），用内置浏览器只读打开（hash 跳页，不点保存/提交/删除，没有写任何业务数据）。数据文件 `gap-data.json` 和代码 `frame/routes.ts`、`frame-g/*`、`shell.js` 作为路由与菜单真值。
- 证据码：**S** = 正式端源码（menuConfig.ts / router / api / views，origin/main）；**G** = 原型代码与 gap-data、DECISIONS、impl 回执；**L** = 19063 实测打开（标题与页面内容）；**47** = 线上实测（本次没做）。
- 状态口径同第一轮：已有 = 两个工作区都有入口且主要动作能做/能看；部分 = 只做一部分、只读、只在一个工作区、或混在别的页；没有 = 无入口。骨架（/g/\*）不算「已有」。味美关闭模块的参考设计不构成启用或授权。

## 2. 基线之后正式端的变化
| 提交 | 日期 | 改了什么 | 对差距表的影响 |
|---|---|---|---|
| `c240b89523`（#1648） | 11-12 23:09 | 生产「工序投入产出」`views/production/ProcessIOComparison.vue`：按报工实际单位判可比，跨单位不算转化率，平均只用同单位工序，无单位显示「无单位」 | 该页不在菜单（附录路由），原型无对应页。它的规则（跨单位不算）和我们 R34「单位明显不符标数据疑点」同方向，可互相对照 |
| `56f25d795a`（#1671） | 11-13 12:54 | 智能调度「产线产能」对话框，「本厂单位」提示不再露英文配置 key | 智能调度整组原型没有，无影响 |
| 其余 25 个提交 | 11-12～13 | 全是后端、CI、组织者流程、dispatch 归档（含 #1665、#1673 把原型第六、七批归档）、AI 意图任务状态、仓库发货队列按归属过滤（#1657、#1672 后端） | `menuConfig.ts` 与 `router/` 无差异，**菜单页数不变** |
结论：新增或改动的菜单 0 个；改动的页面 2 个（均不在原型范围）。

## 3. 原型有、正式端没有（这类是将来要落到正式端的清单）
源码判断依据：在 origin/main 的 web-admin/src 里搜过 黑湖/blacklake（0 处）、数据疑点、快照日、精简视图、viewTabs、两单对照、按责任人、转待办（均 0 处）；逐笔称重相关字段（副产 `byproducts`、双出成率 `stepYieldRate/cumulativeYieldRate`、`expireDate`、FEFO 锁批次）在正式端**已有**。

| # | 原型内容（归属线、批次） | 正式端现状（源码） | 落正式端的建议 | 证据 |
|---|---|---|---|---|
| 1 | **逐笔称重报工** `/production/weigh`（A，第六批）：一项任务多笔，投入/良品/不良项/副产（多行）/皮重/起止时间，工序出成率 vs 标准，偏离标出，R25 口径（成品产出 = 最后一道工序良品） | 工序报工单已有副产、逐工序出成率与累计出成率、FEFO 自动分摊来源批次（`api/processSheet.ts`、`ProcessDataTable.vue`）。没找到「同一任务多笔称重 + 皮重 + 起止时间」的独立页 | 增量：多笔录入形态、皮重、起止时间；R25 口径与正式端 stepYield/cumulative 对齐后再定 | S G L |
| 2 | **入库批次** `/lsm/inbound-batches`（C，第六批）：批次号规则（产品码+日期+流水，可改）、生产日期、保质期→到期日、关联外部入库单、带出称重良品、库存按批次、临期/过期标色、先到期先出**建议**（只提示） | 收货面板有到期日字段、库存页到期着色、产品配置有保质期天数、销售发货/退货按 FEFO **自动**扣减（`PendingPurchaseReceivingPanel.vue`、`warehouse/inventory/index.vue`、`CreateReturnOrderDialog.vue`）。没有「关联外部单」概念 | 基本已有；增量只在「批次号规则可改」和下面 R35 | S G L |
| 3 | **对账（Cretas ↔ 黑湖）** `/lsm/reconcile`（D 共享组件，第六批）：一致/差额/只在 Cretas/只在黑湖，找谁核，数据时点，导出 CSV | 正式端没有黑湖对接，也没有同类页（「对账」在财务/供应商是别的意思） | 只有将来正式端要接外部系统才落；先留作模式 | S G L（实测称重 0 差额/只在黑湖 97；入库批次只在黑湖 275/一致 0） |
| 4 | **数字班组** `/crew` 全套（F，第六、七批）：班组首页、员工工作台、等我确认、工作记录与额度（已交办/已办成/没办成/结果不明，R29）、班组入职、主动汇报、设置/启用配置、一线手机端、功能归属 | 没有「数字员工/班组」概念。AI 相关只有：页面 AI 助手（`ai/pageAgent*`）、AiEntryDrawer、AI 受控采购、AI 报告、一线角色 AI 操作权限、可复用 Skill、定时任务写入、Agent 运行与评测 | 大项，要先定产品口径；已有的 AI 配置页可作为后台 | S G |
| 5 | **发现的问题** `/crew/issues`（F，R33）：按来源（数字员工汇报/规则预警/对账差异/数据缺口/数据疑点）分类，汇报可转待办，关闭须选结论类型再写一句 | 正式端有异常中心、异常预警、缺料异常 cases、成本损耗异常 cases，**没有**「转待办」（0 处）和「关闭须选结论」的统一写法 | 落正式端「异常中心」：补来源分类与关闭结论 | S G L（实测 20 项：汇报 7/预警 0/对账差异 2/缺口 3/疑点 8） |
| 6 | **数据疑点（R30/R34）**：出成率 ≥ 100%、起止相同、单位明显不符 → 标「数据疑点 · 找谁核」，不改原值、不进异常、不上头条 | 0 处（`label-qc` 的「疑点」是标签质检复核，含义不同） | **建议优先落**：对正式端称重/报工录入同样有用，且改动小 | S G L |
| 7 | **录入时间不晚于现在（R36）**、**建批次时已过期需确认并填原因（R35）** | 未见同类校验 | 建议落：小改动，防录入错误 | S G（impl-A/C 回执、BATCH7-AUDIT 点验通过） |
| 8 | **侧栏改组（R32）**：今天 / 数字班组 / 待办 / 发现的问题 + 「全部功能」+ 设置下沉；设置 `/g/settings` | 正式端是「工厂管理工作区 + 工作台 + 按模块分组」（`menuConfig.ts`），没有「今天」「待办」入口；有「方案与审批」「我的审批」 | 产品层面的大改，不是一次提交；先挑「今天」页落地 | S G L |
| 9 | **来源标签 / 主来源规则 / 快照日（R23、R28）**：Cretas（主来源）、黑湖值放对照列、页面写明采集时点 | 正式端是自己的实时库，不需要 | **不落正式端**，属六膳门（外部数据）专属 | S G |
| 10 | **成本页**：有称重时用 Cretas 产出/出成率/副产，副产品三种分摊口径（不分摊/按重量/按固定比例）；两张工单并排对照；待补清单按责任人导出（B） | 正式端成本有成本差异、回料差、成本损耗异常；没有「两单并排」「按责任人导出」（0 处）；副产分摊口径配置未见 | 并排对照、副产分摊口径值得评估 | S G |
| 11 | **共享交互**（A/C/D/E，第五批）：批量补充模板（预检逐行报错）、精简视图（页头切换+记住选择+打开原页面办理）、已存视图+状态计数（viewTabs）、动作权限只读对照表（BL25）、解读审批依据 | 已存视图有（`api/savedViews.ts`、BookmarkPanel，属 SmartBI 书签）；精简视图、viewTabs、状态计数 0 处；批量导入有 2 处；权限矩阵有（8 处）。均不是同一做法 | 视图+状态计数、批量补充预检可借鉴；其余是原型为「原页面不可改」所做的包装，**不用落** | S G |
| 12 | **「今天」页与 G 骨架 16 页**（G）：岗位一天、急单动线、骨架页签与主操作 | 无 | 骨架，不落；要落的是被它指向的真实功能 | G L |

## 4. 正式端有、原型没有或只做一部分（汇总；逐页见第 6 节）
- 复核后 **没有 62、部分 50**。没有的 62 页：财务 12、系统 15、智能调度 7、人事 7、设备 3（这 5 组共 44 页，账号模块关着，只记差距）；质量 7；研发 2、生产 3、仓储 2、销售 3、顶层 1（经营指标）。部分+没有共 112 页，按建议归属线（同一页只算一次）：D 32、C 22、F 6、A 26、B 16、E 10。
- 味美编译页能直接打开、工作量最小的「没有」7 页：工程变更通知、报损管理、源头个体档案、CCP 监控记录、质检退回单、废弃处理、车辆字典。
- 六膳门占位：「部分」里 34 行在六膳门只是「尚未迁入」，只有味美能用。
- 两处重复挂载（正式端本身）：缺料异常在生产/质量/采购/销售各挂一次，交付风险两处，成本差异/成本损耗异常生产与财务各一次，操作日志两项。

## 5. 按菜单组汇总（工厂可见 135 页，复核后）
| 菜单组 | 已有 | 部分 | 没有 | 第一轮 已有/部分/没有 |
|---|---|---|---|---|
| 顶层 | 1 | 2 | 1 | 1/0/3 |
| 工厂管理工作区 | 1 | 3 | 0 | 0/4/0 |
| 工作台 | 4 | 3 | 0 | 4/3/0 |
| 个人 OA | 0 | 4 | 0 | 0/4/0 |
| 研发管理 | 0 | 0 | 2 | 0/0/2 |
| 生产管理 | 5 | 10 | 3 | 5/10/3 |
| 仓储管理 | 4 | 7 | 2 | 4/7/2 |
| 质量管理 | 3 | 4 | 7 | 3/4/7 |
| 采购管理 | 3 | 5 | 0 | 3/5/0 |
| 销售管理 | 2 | 3 | 3 | 2/3/3 |
| 人事管理 | 0 | 1 | 7 | 0/1/7 |
| 设备管理 | 0 | 0 | 3 | 0/0/3 |
| 财务管理 | 0 | 5 | 12 | 0/5/12 |
| 系统管理 | 0 | 3 | 15 | 0/2/16 |
| 智能调度 | 0 | 0 | 7 | 0/0/7 |
| **合计** | **23** | **50** | **62** | 22/48/65 |
改判 4 行：异常中心 部分→已有；AI 工作台、AI 经营报告、一线角色 AI 操作权限 没有→部分。其余 131 行复核后与第一轮相同（第六、七批新做的称重、入库批次、对账、数字班组其余页，在正式菜单里没有一对一的页，主要落在第 3 节的「原型有、正式端没有」；入库任务与批次、报工审批两行第一轮已是「已有」）。

## 6. 逐页（工厂可见 135 页）
证据码见第 1 节。「第一轮→现在」只有改判行带箭头。
| 正式菜单组 | 页面 | 正式路由 | 状态 | 原型路由（现） | 归属线 | 说明 | 证据 |
|---|---|---|---|---|---|---|---|
| 顶层 | AI经营报告 | `/analytics/ai-reports` | 没有→**部分** | /unified/reports（自定义报表，两个工作区） | D | 只有人自己搭的报表，没有 AI 自动生成的周期经营报告（周报/月报定时产出）；G 骨架 /g/reports 仍是骨架 | S G L |
| 顶层 | 首页 | `/dashboard` | 已有 | /dashboard | D | 重整为「今天」：待办、数字员工在办、等我确认、发现的问题 | S G |
| 工厂管理工作区 | 经营总览 | `/workspaces/overview` | 部分 | /smart-bi/dashboard、/analytics/overview | D | 原型有驾驶舱与领域概览，缺「经营总览」一页把四块放在一起 | S G |
| 工厂管理工作区 | 异常中心 | `/workspaces/exceptions` | 部分→**已有** | /crew/issues（来源分类、转待办、关闭须写结论、数据疑点单列） | D | 第七批 F 的「发现的问题」落地（R33）；两个工作区侧栏都有入口。注意：数据来自原型规则与六膳门快照，不等于正式端异常中心接的真实异常数据 | S G L |
| 工厂管理工作区 | 方案与审批 | `/workspaces/plans-approvals` | 部分 | /workflow/pending | D | AI 方案（排产、采购）与审批合到「待办」 | S G |
| 工厂管理工作区 | 追溯与复盘 | `/workspaces/trace-review` | 部分 | 库存查询内追溯面板（C TracePanel） | C | 原型追溯只能从库存行打开，没有独立入口和复盘 | S G |
| 顶层 | AI 工作台 | `/dashboard/ai-value` | 没有→**部分** | /crew、/crew/ledger（班组首页、工作记录与额度） | F | 不是同一页：正式端是 AI 价值看板，原型是数字班组与额度（已交办/已办成/没办成/结果不明）；价值统计口径未做 | S G L |
| 工作台 | 销售老板工作台 | `/workdesk/sales-owner` | 已有 | /dashboard?role=sales | D | D4 已并入首页 | S G |
| 工作台 | 财务主管工作台 | `/workdesk/finance-manager` | 部分 | （无；骨架 /g/today?role=finance） | D | 首页岗位筛选目前只有销售/生产/仓库/采购 | S G |
| 工作台 | 质量主管工作台 | `/workdesk/quality-manager` | 部分 | （无；骨架 /g/today?role=quality） | D | 同上，缺质检岗位 | S G |
| 工作台 | 生产经理工作台 | `/workdesk/production-manager` | 已有 | /dashboard?role=production | D | D4 已并入首页 | S G |
| 工作台 | 仓管员工作台 | `/workdesk/warehouse-keeper` | 已有 | /dashboard?role=warehouse | D | D4 已并入首页 | S G |
| 工作台 | 采购员工作台 | `/workdesk/purchaser` | 已有 | /dashboard?role=purchase | D | D4 已并入首页 | S G |
| 工作台 | 质检主管工作台 | `/workdesk/quality-chief` | 部分 | （无；骨架 /g/today?role=quality） | D | 与质量主管合并为一个岗位视图 | S G |
| 个人 OA | 待我审批 | `/workflow/pending` | 部分 | /workflow/pending | D | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 个人 OA | 我发起的 | `/workflow/my-created` | 部分 | /workflow/my-created | D | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 个人 OA | 已处理 | `/workflow/acted` | 部分 | /workflow/acted | D | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 个人 OA | 抄送我的 | `/workflow/copied` | 部分 | /workflow/copied | D | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 研发管理 | 研发样品 | `/rd/samples` | 没有 | （无；骨架 /g/rd） | A | 样品 → 转正式 SKU；转产后进 A 的产品与工艺；味美编译页可直达 | S G |
| 研发管理 | 已转样品库 | `/rd/converted` | 没有 | （无；骨架 /g/rd?tab=converted） | A | 味美编译页可直达 | S G |
| 生产管理 | 生产计划 | `/production/plans` | 已有 | /production/plans | A |  | S G |
| 生产管理 | 缺料异常 | `/production/shortage-cases` | 部分 | /production/shortage-cases | A | D5：缺料列表只在 A；重整后同时出现在「发现的问题」；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 生产管理 | 交付风险 | `/production/delivery-risks` | 没有 | （无；骨架 /g/delivery） | A | 订单交期 × 计划进度 × 齐套；与销售交付风险同一页；味美编译页可直达 | S G |
| 生产管理 | 生产进度大屏 | `/dashboard/production-progress` | 部分 | /dashboard/production-progress | A | 改名车间大屏；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 生产管理 | 生产批次 | `/production/batches` | 部分 | /production/batches | A | 味美批次；六膳门为工单；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 生产管理 | 溯源查询 | `/production/traceability` | 部分 | 库存查询内追溯面板 | C | 移到「质量与追溯」 | S G |
| 生产管理 | 标准出成率 | `/production/yield-standard` | 部分 | /production/yield-standard | A | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 生产管理 | 生产看板 | `/production/kanban` | 部分 | /production/kanban | A | 改名状态看板；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 生产管理 | 报工审批 | `/production/approval` | 已有 | /production/approval、/production/weigh | A | 已加称重报工 | S G |
| 生产管理 | 领料 | `/production/material-flow` | 已有 | /production/material-flow | A |  | S G |
| 生产管理 | SKU 管理 | `/system/products` | 已有 | /system/products | A | 重整后下沉到「设置 / 主数据」 | S G |
| 生产管理 | 工序 | `/production/process-hub` | 已有 | /production/process-hub | A |  | S G |
| 生产管理 | 工程变更通知 | `/production/bom/ecns` | 没有 | （无；骨架 /g/engineering） | A | BOM/工艺改动的申请、影响范围、生效版本；味美编译页可直达 | S G |
| 生产管理 | BOM调价建议 | `/production/bom/price-adjustments` | 没有 | （无；骨架 /g/engineering?tab=price） | B | 原料价变动 → 产品成本影响 → 调价建议；味美编译页可直达 | S G |
| 生产管理 | 生产分析 | `/production/analysis-query` | 部分 | /production/analysis-query | A | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 生产管理 | 成本差异 | `/production/cost-variance` | 部分 | /lsm/cost、/analytics/overview?view=product-cost | B | 原型有成本分析，缺「标准 vs 实际」差异列表 | S G |
| 生产管理 | 成本损耗异常 | `/production/cost-deviation-cases` | 部分 | /lsm/cost | B | 差异超阈值转成「发现的问题」 | S G |
| 生产管理 | 成本汇总 | `/production-analytics/cost-summary` | 部分 | /lsm/cost | B |  | S G |
| 顶层 | 经营指标 | `/analytics/indicators` | 没有 | （无；骨架 /g/reports?tab=kpi） | D | 指标口径与目标值；与经营报告同页；味美编译页可直达 | S G |
| 仓储管理 | 无订单入库申请 | `/warehouse/unordered-inbound-applications` | 部分 | /warehouse/unordered-inbound-applications | C | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 仓储管理 | 入库任务与批次 | `/warehouse/materials` | 已有 | /warehouse/materials、/lsm/inbound-batches | C |  | S G |
| 仓储管理 | 出货管理 | `/warehouse/shipments` | 已有 | /warehouse/shipments | C |  | S G |
| 仓储管理 | 领料单 | `/warehouse/material-requisitions` | 部分 | /production/material-flow | A | D2：领退料归 A；仓库侧只读 | S G |
| 仓储管理 | 调拨单 | `/transfer/list` | 部分 | /transfer/list | C | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 仓储管理 | 报损管理 | `/warehouse/wastage-reports` | 没有 | （无；骨架 /g/warehouse-more?tab=wastage） | C | 报损 → 质量处置 → 财务报废审批；味美编译页可直达 | S G |
| 仓储管理 | 库存查询 | `/warehouse/inventory-query` | 已有 | /warehouse/inventory-query | C |  | S G |
| 仓储管理 | 盘点 | `/warehouse/stocktake-query` | 部分 | /warehouse/stocktake-query | C | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 仓储管理 | 仓储空间与现场盘点 | `/warehouse/storage-locations` | 已有 | /warehouse/storage-locations | C |  | S G |
| 仓储管理 | 原料类型字典 | `/warehouse/material-types` | 部分 | /warehouse/material-types | C | 重整后下沉到「设置 / 主数据」；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 仓储管理 | 源头个体档案 | `/warehouse/source-individuals` | 没有 | （无；骨架 /g/warehouse-more?tab=source） | C | 活体/胴体来源档案，追溯源头；味美编译页可直达 | S G |
| 仓储管理 | 物料分类字典 | `/warehouse/material-segments` | 部分 | /warehouse/material-segments | C | 下沉到「设置 / 主数据」；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 仓储管理 | 物料均价趋势 | `/warehouse/material-price-trend` | 部分 | /warehouse/material-price-trend | C | D6 放经营分析；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 质量管理 | 质检记录 | `/quality/inspections` | 已有 | /quality/inspections | E |  | S G |
| 质量管理 | 质检放行 | `/quality/release` | 部分 | /quality/inspections（单内结论） | E | 缺独立的「待放行」队列 | S G |
| 质量管理 | CCP 监控记录 | `/quality/ccp` | 没有 | （无；骨架 /g/quality-loop?tab=ccp） | E | BL 第二组 6：挂到工序画布；味美编译页可直达 | S G |
| 质量管理 | 工艺参数偏离 | `/quality/param-deviation` | 没有 | （无；骨架 /g/quality-loop?tab=ccp） | E | 偏离 → 扣留本批及下游；味美编译页可直达 | S G |
| 质量管理 | 冷链超温批次 | `/quality/cold-chain` | 没有 | （无；骨架 /g/quality-loop?tab=coldchain） | E | 味美编译页可直达 | S G |
| 质量管理 | 包装标签拍检 | `/quality/label-qc` | 部分 | /quality/label-qc | E | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 质量管理 | 视觉质检训练 | `/quality/vision-training` | 没有 | （无；骨架 /g/settings?tab=crew） | E | 属于 AI 能力配置，下沉到设置；味美编译页可直达 | S G |
| 质量管理 | 工序质检不良 | `/quality/defects` | 已有 | /quality/defects | E | 原型叫不良分析 | S G |
| 质量管理 | 质检退回单 | `/quality/returns` | 没有 | （无；骨架 /g/quality-loop?tab=returns） | E | 味美编译页可直达 | S G |
| 质量管理 | 不良品处置 | `/quality/disposition` | 部分 | /quality/inspections（不合格 → 待处置标记） | E | 缺处置单本身 | S G |
| 质量管理 | 批次召回 | `/quality/recalls` | 没有 | （无；骨架 /g/quality-loop?tab=recall） | E | 依赖 C 批次与追溯；味美编译页可直达 | S G |
| 质量管理 | 废弃处理 | `/quality/disposals` | 没有 | （无；骨架 /g/quality-loop?tab=returns） | E | 味美编译页可直达 | S G |
| 质量管理 | 缺料异常 | `/quality/shortage-cases` | 部分 | /production/shortage-cases | A | 正式端四处重复挂；重整后只在「发现的问题」；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 质量管理 | 质检标准 | `/quality/standards` | 已有 | /quality/standards | E |  | S G |
| 采购管理 | 我的请购 | `/procurement/requisitions/my` | 已有 | /procurement/requisitions/my | C |  | S G |
| 采购管理 | 待审批请购 | `/procurement/requisitions/pending-approval` | 部分 | /procurement/requisitions/pending-approval | C | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 采购管理 | 全部请购单 | `/procurement/requisitions` | 部分 | /procurement/requisitions/my | C | 精简视图可加「全部」视图 | S G |
| 采购管理 | 采购订单 | `/procurement/orders` | 已有 | /procurement/orders | C |  | S G |
| 采购管理 | AI受控采购 | `/procurement/ai-purchase` | 部分 | /procurement/ai-purchase | C | 原型叫采购建议；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 采购管理 | 缺料异常 | `/procurement/shortage-cases` | 部分 | /production/shortage-cases | A | 重复挂；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 采购管理 | 供应商管理 | `/procurement/suppliers` | 已有 | /procurement/suppliers | C | 下沉到「设置 / 主数据」 | S G |
| 采购管理 | 价格表管理 | `/procurement/price-lists` | 部分 | /procurement/price-lists | C | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 销售管理 | 销售订单 | `/sales/orders` | 已有 | /sales/orders | C |  | S G |
| 销售管理 | 客户管理 | `/sales/customers` | 已有 | /sales/customers | C | 下沉到「设置 / 主数据」 | S G |
| 销售管理 | 缺料异常 | `/sales/shortage-cases` | 部分 | /production/shortage-cases | A | 重复挂；六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 销售管理 | 交付风险 | `/sales/delivery-risks` | 没有 | （无；骨架 /g/delivery） | A | 与生产交付风险同一页；味美编译页可直达 | S G |
| 销售管理 | 出货记录 | `/sales/shipments` | 部分 | /sales/shipments | C | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 销售管理 | 销售退货 | `/sales/returns` | 部分 | /sales/returns | C | 六膳门是「尚未迁入」占位，只有味美编译页可用 | S G |
| 销售管理 | 销售付款申请 | `/sales/payment-requests` | 没有 | （无；骨架 /g/finance?tab=payment） | C | 放进财务的收付款；味美编译页可直达 | S G |
| 销售管理 | 车辆字典 | `/sales/vehicles` | 没有 | （无；骨架 /g/settings?tab=master） | C | 下沉到主数据；味美编译页可直达 | S G |
| 人事管理 | 账号管理 | `/system/users` | 没有 | （无；骨架 /g/settings?tab=org） | D | 管理员功能，不归数字员工；味美编译页可直达 | S G |
| 人事管理 | 角色权限 | `/system/roles` | 没有 | （无；骨架 /g/settings?tab=org） | D | 味美编译页可直达 | S G |
| 人事管理 | 权限矩阵 | `/system/permission-matrix` | 部分 | 原型与来源 → 动作权限对照（只读） | D |  | S G |
| 人事管理 | 员工档案 | `/hr/employees` | 没有 | （无；骨架 /g/people） | D | 味美编译页可直达 | S G |
| 人事管理 | 部门管理 | `/hr/departments` | 没有 | （无；骨架 /g/people?tab=dept） | D | 味美编译页可直达 | S G |
| 人事管理 | 工种字典 | `/hr/work-types` | 没有 | （无；骨架 /g/people?tab=worktype） | D | 计件计时工价（BL16）的基础；味美编译页可直达 | S G |
| 人事管理 | 考勤管理 | `/hr/attendance` | 没有 | （无；骨架 /g/people?tab=attendance） | D | 有效工时的另一来源；味美编译页可直达 | S G |
| 人事管理 | 账号邀请 | `/hr/whitelist` | 没有 | （无；骨架 /g/settings?tab=org） | D | 味美编译页可直达 | S G |
| 设备管理 | 设备列表 | `/equipment/list` | 没有 | （无；骨架 /g/equipment） | A | 产能约束；六膳门黑湖设备 0 条；味美编译页可直达 | S G |
| 设备管理 | 维护记录 | `/equipment/maintenance` | 没有 | （无；骨架 /g/equipment?tab=maint） | A | 味美编译页可直达 | S G |
| 设备管理 | 告警管理 | `/equipment/alerts` | 没有 | （无；骨架 /g/issues?kind=equipment） | A | 并入「发现的问题」；味美编译页可直达 | S G |
| 财务管理 | 调整审批 | `/finance/adjustments` | 没有 | （无；骨架 /g/finance?tab=approve） | B | 并入「待办」；味美编译页可直达 | S G |
| 财务管理 | 报废审批 | `/finance/disposal-approvals` | 没有 | （无；骨架 /g/finance?tab=approve） | B | 并入「待办」；味美编译页可直达 | S G |
| 财务管理 | 财务概览 | `/finance/costs` | 没有 | （无；骨架 /g/finance） | B | 味美编译页可直达 | S G |
| 财务管理 | 财务报表 | `/finance/three-statements` | 没有 | （无；骨架 /g/finance?tab=margin） | B | 味美编译页可直达 | S G |
| 财务管理 | 财务分析(Excel) | `/finance/reports` | 没有 | （无；骨架 /g/reports） | D | 与经营报告合并；味美编译页可直达 | S G |
| 财务管理 | 应收应付 | `/finance/ar-ap` | 部分 | /lsm/receivables（六膳门应收） | C | 原型只有六膳门应收，无应付 | S G |
| 财务管理 | 销售开票 | `/finance/invoices` | 部分 | /lsm/receivables（待开票只读） | C |  | S G |
| 财务管理 | 采购收票 | `/finance/purchase-invoices` | 没有 | （无；骨架 /g/finance?tab=invoice） | C | 味美编译页可直达 | S G |
| 财务管理 | 收款管理 | `/finance/payments` | 部分 | /lsm/receivables（收款只读） | C |  | S G |
| 财务管理 | 会计凭证 | `/finance/voucher-list` | 没有 | （无；骨架 /g/finance?tab=voucher） | B | 味美编译页可直达 | S G |
| 财务管理 | 凭证导出 | `/finance/voucher-export` | 没有 | （无；骨架 /g/finance?tab=voucher） | B | 味美编译页可直达 | S G |
| 财务管理 | 凭证模板 | `/finance/voucher-template` | 没有 | （无；骨架 /g/settings?tab=rules） | B | 下沉到设置；味美编译页可直达 | S G |
| 财务管理 | SKU毛利率分析 | `/finance/sku-margin` | 没有 | （无；骨架 /g/finance?tab=margin） | B | 味美编译页可直达 | S G |
| 财务管理 | 成本差异 | `/finance/cost-variance` | 部分 | /lsm/cost | B | 与生产成本差异重复挂 | S G |
| 财务管理 | 成本损耗异常 | `/finance/cost-deviation-cases` | 部分 | /lsm/cost | B | 重复挂 | S G |
| 财务管理 | 毛利红线配置 | `/finance/gross-margin-redline` | 没有 | （无；骨架 /g/settings?tab=rules） | B | 味美编译页可直达 | S G |
| 财务管理 | 会计期间 | `/finance/accounting-period` | 没有 | （无；骨架 /g/finance?tab=period） | B | 味美编译页可直达 | S G |
| 系统管理 | 操作日志 | `/system/logs` | 部分 | 单据操作日志抽屉（B6） | D | 正式端 logs 与 operation-log 两项重复 | S G |
| 系统管理 | 系统设置 | `/system/settings` | 没有 | （无；骨架 /g/settings） | D | 味美编译页可直达 | S G |
| 系统管理 | 业务阈值配置 | `/system/business-thresholds` | 没有 | （无；骨架 /g/settings?tab=rules） | D | 「发现的问题」的触发线；味美编译页可直达 | S G |
| 系统管理 | 审批链配置 | `/system/approval-chains` | 没有 | （无；骨架 /g/settings?tab=rules） | D | 味美编译页可直达 | S G |
| 系统管理 | 工作流设计器 | `/system/workflow-designer` | 没有 | （无；骨架 /g/settings?tab=rules） | D | 与审批链合并；味美编译页可直达 | S G |
| 系统管理 | 功能模块配置 | `/system/features` | 没有 | （无；骨架 /g/settings?tab=org） | D | 味美编译页可直达 | S G |
| 系统管理 | 一线角色 AI 操作权限 | `/system/ai-frontline-grants` | 没有→**部分** | /crew/settings（班组启用配置）、/g/settings 只读显示可用数字员工 | F | 只读显示与启用哪几位；一线角色逐项 AI 操作授权的编辑没做（也不应在原型里改权限） | S G |
| 系统管理 | Canvas 配置编辑器 | `/canvas-editor` | 没有 | （无；骨架 /g/settings?tab=rules） | D | 实施人员用，客户菜单可隐藏；味美编译页可直达 | S G |
| 系统管理 | 员工工牌生成 | `/system/badge-generator` | 没有 | （无；骨架 /g/people?tab=badge） | D | 味美编译页可直达 | S G |
| 系统管理 | 计量单位字典 | `/unit-dictionary` | 没有 | （无；骨架 /g/settings?tab=master） | D | 味美编译页可直达 | S G |
| 系统管理 | 操作日志（第二项） | `/system/operation-log` | 部分 | 单据操作日志抽屉（B6） | D | 与 /system/logs 合并 | S G |
| 系统管理 | 工艺保密 | `/system/process-secret` | 没有 | （无；骨架 /g/settings?tab=rules） | A | 味美编译页可直达 | S G |
| 系统管理 | 平台访问记录 | `/system/platform-access-log` | 没有 | （无；骨架 /g/settings?tab=data） | D | 味美编译页可直达 | S G |
| 系统管理 | 数据备份 | `/system/data-backups` | 没有 | （无；骨架 /g/settings?tab=data） | D | 味美编译页可直达 | S G |
| 系统管理 | 可复用 Skill | `/system/reusable-skills` | 没有 | （无；骨架 /g/settings?tab=crew） | F | 数字员工的技能库；味美编译页可直达 | S G |
| 系统管理 | 定时任务写入 | `/system/autonomous-writes` | 没有 | （无；骨架 /g/settings?tab=crew） | F | 主动汇报与定时工作的写入记录；味美编译页可直达 | S G |
| 系统管理 | Agent 运行与评测 | `/ops/agent-ops` | 没有 | （无；骨架 /g/settings?tab=crew） | F | 建议移出客户菜单，只给实施/平台；味美编译页可直达 | S G |
| 系统管理 | 建厂就绪 | `/factory-readiness` | 没有 | （无；骨架 F 入职） | F | 由 F「数字班组入职」取代；味美编译页可直达 | S G |
| 智能调度 | 调度中心 | `/scheduling/overview` | 没有 | （无；骨架 /g/scheduling） | A | 数字计划员的主战场；急单演示流程落在这里；味美编译页可直达 | S G |
| 智能调度 | 调度计划 | `/scheduling/plans` | 没有 | （无；骨架 /g/scheduling?tab=draft） | A | 味美编译页可直达 | S G |
| 智能调度 | 多方案比较 | `/scheduling/proposal-comparison` | 没有 | （无；骨架 /g/scheduling?tab=compare） | A | 味美编译页可直达 | S G |
| 智能调度 | 实时监控 | `/scheduling/realtime` | 没有 | （无；骨架 /g/scheduling?tab=realtime） | A | 与车间大屏重复，建议合并；味美编译页可直达 | S G |
| 智能调度 | 人员分配 | `/scheduling/workers` | 没有 | （无；骨架 /g/scheduling?tab=workers） | A | 味美编译页可直达 | S G |
| 智能调度 | 告警管理 | `/scheduling/alerts` | 没有 | （无；骨架 /g/issues?kind=schedule） | A | 并入「发现的问题」；味美编译页可直达 | S G |
| 智能调度 | 排产设置 | `/scheduling/settings` | 没有 | （无；骨架 /g/settings?tab=rules） | A | 味美编译页可直达 | S G |

## 7. 工厂隐藏与不纳入
工厂隐藏（「数据与分析」组 `hideForFactoryTypes:['FACTORY']`，正式端对工厂不可见，原型里有）：12 页，已有 1、部分 9、没有 2，复核后与第一轮相同。
| 菜单组 | 页面 | 正式路由 | 状态 | 原型路由 | 归属线 | 证据 |
|---|---|---|---|---|---|---|
| 数据与分析 | 经营驾驶舱 | `/smart-bi/dashboard` | 部分 | /smart-bi/dashboard | D | S G |
| 数据与分析 | AI 问答 / 数据分析 | `/smart-bi/analysis` | 部分 | /smart-bi/analysis | D | S G |
| 数据与分析 | 经营分析 | `/smart-bi/analysis-hub` | 已有 | /smart-bi/analysis-hub | D | S G |
| 数据与分析 | 异常预警 | `/analytics/alert-dashboard` | 部分 | /analytics/alert-dashboard | D | S G |
| 数据与分析 | 进销存总览 | `/analytics/supply-chain` | 部分 | /analytics/supply-chain | C | S G |
| 数据与分析 | Excel 上传 | `/smart-bi/upload` | 部分 | /smart-bi/upload | D | S G |
| 数据与分析 | 查询模板 | `/smart-bi/query-templates` | 部分 | /smart-bi/query-templates | D | S G |
| 数据与分析 | 数据完整度 | `/smart-bi/data-completeness` | 部分 | /smart-bi/data-completeness | D | S G |
| 数据与分析 | 字段映射复核 | `/smart-bi/mapping-review` | 部分 | /smart-bi/mapping-review | D | S G |
| 数据与分析 | 分析概览 | `/analytics/overview` | 部分 | /analytics/overview | D | S G |
| 数据与分析 | 知识库反馈 | `/smart-bi/food-kb-feedback` | 没有 | （无；骨架 /g/settings?tab=crew） | F | S G |
| 数据与分析 | AI 追问日志 | `/smart-bi/fallback-log` | 没有 | （无；骨架 /g/settings?tab=crew） | F | S G |
不纳入（平台 / 物流 / 餐饮口径）：
| 菜单组 | 页面 | 正式路由 |
|---|---|---|
| 数据与分析 | 收入管理报表 | `/smart-bi/revenue-report` |
| 数据与分析 | 行为校准监控 | `/smart-bi/calibration` |
| 平台治理 | AI意图配置 等 13 项 | `/system/ai-intents 等` |
| 智能调度 | 物流排线 5 项 | `/scheduling/logistics/*` |

## 8. 仍未核对
- **47 线上没有核对**：没有拿到 QA 测试账号（本次没读 `.env.test`、也没有打开线上），所以菜单可见性、路由守卫、线上是否已部署到 `114efd5a2d`，都是**未在线核对**，以源码为准。
- 19063 只实测了：称重报工、对账、入库批次、发现的问题（含数据疑点）、今天、待办、自定义报表、财务骨架共 8 页打开与关键内容；其余行的状态沿用 gap-data 与代码，没有逐页重新点开。
- 味美工作区的按钮没有逐个点；「味美编译页可直达」一列沿用第一轮结果（41 行 403 是按权限推断）。
- 「今天」页首页待办（今日待称重、临期批次，DECISIONS 第十三节 D③）是否已接真数据，未核对。
- 手机宽度（375）没看。

## 9. 顺手修的文字遗留（11-13）
| 位置 | 改动 | 是否需要构建 |
|---|---|---|
| `unified-spec\G-ia.md` 第 101 行 | 「按这 6 项列出」→「按这 3 项列出（/crew、/crew/ledger、/crew/onboard；主动汇报、手机端、功能归属不进侧栏）」 | 否，已改 |
| `unified-spec\impl-G.md` 第 190 行（G-6） | 「F 定的 4 项」→「3 项」 | 否，已改 |
| 「原型与来源」菜单首项 `accounts\unified-v1\public\shell.js` 第 202 行 | 标签「菜单与动线对照（重整方案）」→「菜单与动线对照」 | 否（public 静态文件，刷新页面生效；未实测刷新）。这是 D 的文件，按你派的任务改了一个标签，改前备份在 `unified-spec\revisions\G-v2-20261113\`（含前后哈希） |
| `/g/map` 页菜单说明 `frame-g\MapPage.vue` 第 2、30 行等（「重整后」「重整方案」字样）；`frame\routes.ts` 与 `server.cjs` 的页面标题「（重整方案）」、`GModule.vue` 隐藏栏 | **没改**：源码要构建才生效，且 routes.ts/server.cjs 是 D 的文件 | 是。记下，等下一次构建一起改 |
