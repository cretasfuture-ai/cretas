# 页面 → 后端接口：逐页对照表

日期：2026-11-18。范围：统一原型菜单的 85 个路由，加 `ydwm-v1/tools` 里的 4 个服务，共 88 项。
和 `BACKEND-MERGE-MAP.md` 的分工：那张表按**规则**对照（融合时要对齐什么口径），这张表按**页面**对照（每个页面要的数据和动作，后端有没有）。

## 怎么读

- 五个只读会话各按一块业务逐页核对：读原型里渲染该页的组件，列出这页要的数据和动作，再到 Java / Python 里找接口，读 Controller 和 Service 关键方法确认字段和动作真的有。明细在 `page-map/` 下五个文件，每页一节，带接口路径和文件:行。
- 状态：**有** = 每项数据和动作都有接口；**部分** = 缺的几项写在明细里；**缺** = 没有对应接口。原型专用的片段（本机存储、`/__local/` 路由等）不算缺，明细里单独标注。
- 很多页有两种变体：味美 / Java 原生，和六膳门读黑湖。下表「状态」默认指原生那一套；六膳门读黑湖的部分，正式后端没有任何读取接口，统一记在缺口 D1。
- **只核了接口和字段在不在，没有逐条比对规则实现**；明细里写「规则未核」的就是没比对。「有」不等于规则等价。

## 统计

| 状态 | 数量 |
|---|---|
| 有 | 23 |
| 部分 | 59 |
| 缺 | 6（/crew、/crew/ledger、/crew/onboard、/collab/tasks、/lsm/reconcile，服务 ledger-facts） |

## 一、Steve 已裁决的口径（2026-11-18，11 项全部按推荐）

| 编号 | 裁决 | 落地要求 |
|---|---|---|
| D1 六膳门读黑湖 | 混合：订单 / 财务等黑湖单据走只读镜像；称重报工和入库批次由 Cretas 原生接管，两边做差异对账 | 先做黑湖只读镜像读取通道（B-G1、C-G1、D-G3、E-B3）；对账接口依赖它 |
| D2 BOM 调价建议 | 拆成两个入口：Java 提案审批页不动，新增只读「成本影响 + 售价建议」页 | 新增只读 `GET /bom/price-impact`（C-G2），不改审批写库语义 |
| D3 入库批次入口 | 收货任务新增「无采购单来源」，批次由收货流程生成；`POST material-batches` 墓碑保持停用 | `WarehouseReceivingController` 新增来源；批次撤销带必填原因（D-G8、G9） |
| D4 报损口径 | 统一走 Java：批次 + 照片 + 审批 + 扣库存，六膳门原型页对齐 | 只改原型；Java 补导出、单位 / 名称字段（D-G12） |
| D5 交付风险档位 | 保留 Java 三档（RISK / UNKNOWN / OK），原型改三档；UNKNOWN 即「算不出」 | 只补只读全量评估接口，不写实例（B-G10） |
| D6 采购建议 | 两个入口都留：原型保持只读需求缺口列表，Java 的 BOM 展开建议仍用于缺料异常 | 不改后端，原型页按口径命名（B-G13） |
| D7 CCP 偏离扣留 | 保持自动扣留（出事这一批 + 工艺图下游，延续 11-03 裁定） | 原型改成自动扣留，Java 不动（E-A9） |
| D8 成本口径 | 两套并存、显式标名：「工单材料人工口径」与「全成本口径」 | 新增汇总接口标明口径名，不替换现有数字（E-C3） |
| D9 六膳门委外 | 迁到 Cretas 原生闭环，不再读黑湖委外 | 列表补已发 / 退回 / 产品 / 单位、日期范围、批量单号、导出（C-G13） |
| D10 协同任务 | 并入工作流实例与问题处置，不新建协同任务模块 | 任务 / 评论 / 状态流转挂到 `WorkflowInstance` 与 `IssueDisposition`，页面改接这两块（A-G3） |
| D10 数字班组额度 | 回读业务结果：交办后读对应业务单据状态，成功 = 办成，失败 = 没办成，读不到 = 不明，不猜 | 新建数字班组模块（A-G1），确认执行复用现有业务接口 |
| D11 标准出成率 | 沿用 `WorkProcess`：补允许偏离、历史、撤销；成品级仅作汇总参考，不新增第三份标准 | 改 `WorkProcess` 与称重页读取（C-G8） |

## 二、不用裁决、可以直接补的缺口

大小：小 = 一个接口或参数，中 = 一组接口加存储，大 = 新模块。

**采购 / 销售**（B 组）
- 请购「我的 + 状态」过滤：`PurchaseRequisitionServiceImpl` 里 status 与 requesterId 互斥，选了状态就列出全厂的单。小
- 供应商 / 客户账本汇总：`getSupplierStatistics`、`getCustomerStatistics`、`getCustomerPurchaseHistory` 是未实现的空壳，返回 0 或空列表。中
- 采购草稿的单价来源与参考价留痕（priceSource、referencePrice、偏离百分比）。中
- 价格表更新与停用：`PriceListController` 没有 PUT。小
- 应收按单据维度筛选、待开票清单。中
- 跨客户的客户价目表列表与平均成交价。小
- 退货列表补客户名与来源单号；发货单补检索条件（关键字、状态、日期）；车辆载重单位与车牌规范。小

**生产**（C 组）
- 称重：副产品多行且限产品目录；不良按字典项分摊；撤销单笔称重；待称重任务总览；服务端校验核实（产出不超过投入等）。中
- 生产计划列表补产品 / 单位 / 批量单号筛选与四态计数；领料单列表补关键字 / 计划号 / 日期与按物料汇总；车间大屏补时间进度与最近报工。小到中
- 工单 × 工序在制矩阵；工序标准工时与班次规则；补充 Excel 批量导入（BOM 投料工序 / 产品路线 / 工序标准）。中
- 委外列表补已发 / 退回 / 产品 / 单位、日期范围、批量单号、CSV 导出。小

**仓储**（D 组）
- 原料批次列表缺 `availableQuantity`、`qualityStatus`、`remainingQuantity`；成品列表缺仓库名；盘点 / 调拨列表字段与状态枚举对齐（盘点 `page` 是 0 起，原型发 1 会跳过第一页）。小
- 成品分仓汇总与导出；进销存按仓库维度含成品；批次级库龄与呆滞；补货建议公式对齐。中到大
- 出库单据统一查询与导出；采购均价趋势服务端聚合与导出；调整记录查询；按仓库分层的布局存储。中
- 源头个体补类型 / 进场日期 / 撤销原因 / 导出。小

**质量 / 成本 / 对账**（E 组）
- 检验阶段与草稿 / 作废；选项型检验项与版本日志；不良项字典与报工分项补录；检验结果自动提醒。中
- CCP 跨批次列表与独立作废；退回单与废弃单字段补齐；召回等级 / 范围 / 原料批次召回；包装标签七项清单。小到中
- 工序不良率：`by-process` 接口按 `process_category` 汇总，不按单位分组，跨单位直接求和，也不算不良率。小
- 成本：工单人工按有效人时分摊、产品→工单→工序汇总、成本信号（离散度 / 占比）、副产品分摊对齐、库存估值口径、补充存储与 Excel 导入导出。中到大
- 对账（称重 ↔ 黑湖、入库批次 ↔ 黑湖）：依赖 D1。大

**班组 / OA / 数据分析**（A 组）
- 数字班组整套（启用配置、等我确认收件箱、工作记录与额度、入职记录）：依赖 D10。大
- 产品级口述工艺整理；主数据完整度汇总；数据完整度校验规则落库；经营报告逐数字依据下钻与上期对比。中
- 自定义报表（生产工单 / 报工 / 成本数据源，聚合与图表，周期快照）。大
- 审批中心 `/my-created`、`/acted`、`/copied` 缺 `moduleCode` 与关键词 / 岗位 / 发起人筛选。小
- `/smart-bi/share`、`restaurant-ops/summary` 没找到，要先核是否真被页面用到。小

**只改原型、不改后端**
- 销售交期字段用 `requiredDeliveryDate`、请购期望日用 `expectedDate`；各状态枚举补全；`param-deviation`、`cold-chain` 两页后端已有，原型页应改为接入；状态看板 4 列泳道是前端工作。

## 三、逐页状态

明细文件：A = `page-map/A-班组OA数据分析.md`，B = `page-map/B-采购销售.md`，C = `page-map/C-生产.md`，D = `page-map/D-仓储.md`，E = `page-map/E-质量成本对账.md`。

| 组 | 页面 | 状态 |
|---|---|---|
| A | /crew | 缺（仅第 3 项有）。 |
| A | /crew/ledger | 缺。 |
| A | /crew/onboard | 缺（有 1、3、4 的近似物，但没有页面要的口述整理、上岗条件、入职记录）。 |
| A | /crew/settings | 部分（仅第 2 项；启用开关、调整记录、岗位映射无）。 |
| A | /crew/issues | 部分（关闭/转待办/恢复与 7 类问题源都有；缺数字员工汇报与协同任务规则预警两类来源）。 |
| A | /g/today | 部分。 |
| A | /g/todo | 部分（等我确认、协同任务缺）。 |
| A | /g/settings | 部分（跳转目标多数有接口；数字班组相关列、主数据缺口汇总缺；多项为规则未核）。 |
| A | /workflow/pending、/workflow/my-created、/workflow/acted、/workflow/copied | 四页均 有。 |
| A | /collab/tasks | 缺。 |
| A | /analytics/ai-reports | 部分。 |
| A | /smart-bi/dashboard | 有（分享一项未核）。 |
| A | /analytics/overview | 部分（制造业部分有；餐饮汇总接口未找到）。 |
| A | /smart-bi/analysis-hub | 有（各接口存在，字段与口径规则未核）。 |
| A | /analytics/supply-chain | 有。 |
| A | /analytics/alert-dashboard | 有。 |
| A | /smart-bi/analysis | 有。 |
| A | /smart-bi/upload | 有。 |
| A | /smart-bi/data-completeness | 部分。 |
| A | /smart-bi/mapping-review | 有。 |
| A | /smart-bi/query-templates | 有。 |
| A | /unified/reports | 部分（模板 CRUD 骨架有，数据源范围、聚合/图表、周期快照缺）。 |
| A | /quality/vision-training | 有（原型页只写缺口，正式后端与 web-admin 都有）。 |
| B | /procurement/requisitions/my 我的请购（味美标题「我的请购」，六膳门标题「采购需求」） | 部分（LSM 缺）。 |
| B | /procurement/requisitions/pending-approval 待审批请购 | 有（味美/Java）；LSM 缺（G1）。 |
| B | /procurement/orders 采购订单 | 部分（LSM 缺）。 |
| B | /procurement/ai-purchase 采购建议 / AI 受控采购 | 部分。味美侧有更强的受控采购与建议接口，但与原型这页「按需求缺口只读列表」口径不同；LSM 缺（G1）。 |
| B | /procurement/suppliers 供应商 | 部分（LSM 缺）。 |
| B | /procurement/price-lists 价格表 | 部分（LSM 无数据，页面本身是空态）。 |
| B | /sales/orders 销售订单 | 部分（LSM 缺）。 |
| B | /sales/shipments 发运记录 | 部分（原生接口都有，但缺检索条件；LSM 缺）。 |
| B | /sales/returns 销售退货 | 部分（LSM 缺）。 |
| B | /lsm/receivables 应收与收款 | 部分。 |
| B | /sales/customers 客户 | 部分（LSM 缺）。 |
| B | /lsm/customer-prices 客户价目表 | 部分。 |
| B | /sales/vehicles 车辆字典 | 有（字段齐全；规则差异见缺口）。 |
| B | /sales/payment-requests 销售付款申请 | 有（后端有；原型页本身是空态，六膳门变体按「原型专用」处理，不应进后端，原因：黑湖无此数据且财务模块在原型关闭）。 |
| B | /production/delivery-risks 交付风险 | 部分（LSM 缺）。 |
| B | /production/shortage-cases 缺料异常 | 部分。味美/Java 的缺料异常能力比原型强，但口径是「按销售订单 BOM 展开」，与原型「按采购需求缺口」不是同一份数据。 |
| C | /production/plans 生产工单 | 部分 |
| C | /production/material-flow 生产领退料 | 部分 |
| C | /production/batches 生产批次 | 部分（正式端批次对象齐全；规则未核；LSM「工单当批次」的对象命名要 T2 决定，MIGRATION-PLAN 已写「按工厂显示对象名」） |
| C | /production/approval 生产报工（LSM 只读）/ 报工撤回审批（味美） | 部分 |
| C | /production/weigh 称重报工（重点） | 部分 |
| C | /production/analysis-query 生产分析 | 部分（接口族在、字段口径规则未核） |
| C | /production/kanban 状态看板 | 部分（数据有；4 列泳道视图正式端没有现成页，前端工作） |
| C | /dashboard/production-progress 车间大屏 | 部分 |
| C | /production/wip 在制品与效率 | 部分 |
| C | /production/process-hub 工序配置 | 部分 |
| C | /production/yield-standard 标准出成率 | 部分 |
| C | /system/products 产品 / SKU 管理 | 有（关联工单数等聚合字段规则未核，属展示项，小） |
| C | /production/bom/ecns 工程变更通知 | 有（Java 全流程在；LSM 占位页是原型专用，不应进后端；生效版本与 BOM/工艺版本的衔接规则未核） |
| C | /production/bom/price-adjustments BOM 调价建议（重点） | 部分（只有「采购价→BOM 行价提案+审批」这一半；原型的「成本影响+售价建议+what-if」整块缺） |
| C | /lsm/outsource 委外收发（重点） | 部分（原生闭环齐，且超出原型只读范围；但原型的黑湖委外数据无通道，列表字段与检索与原型有差） |
| D | /warehouse/materials | 部分（味美收货任务=有；六膳门黑湖入库单读取=缺；明细行补金额/批次=原型专用）。 |
| D | /warehouse/unordered-inbound-applications | 部分（原型要的「黑湖对照单据」=缺；原型没做的「申请/审批/收货」Java 已完整有）。 |
| D | /warehouse/shipments | 部分（Cretas 原生发货/出库=有；跨场景统一「出库单据」聚合视图、黑湖镜像、导出=缺）。 |
| D | /transfer/list | 部分（Cretas 原生=有；调出在途不计入调入仓库存等规则未核；导出缺、仓库名缺、黑湖对照缺）。 |
| D | /warehouse/inventory-query | 部分（原料/成品/仓库/汇总=有；「可用」字段、成品分仓汇总、成品导出=缺；黑湖余额/未返回补充=原型专用+缺镜像）。 |
| D | /lsm/stock-summary | 部分（原料进销存+金额+导出=有；仓库维度、成品、期间内分场景明细、批次级库龄呆滞=缺；补货=部分/规则未核；黑湖版进销存=缺镜像）。 |
| D | /warehouse/inventory-adjustments | 有（成品调整动作与字段齐；原料调整也有）。六膳门「黑湖办理」只读说明=原型专用。 |
| D | /lsm/inbound-batches | 部分（批次库存/先到期先出/临期过期=有；手工建批次（含黑湖行关联、称重取数、过期原因）、撤销留原因、对账、黑湖待建清单=缺）。这一页是融合 |
| D | /warehouse/stocktake-query | 有（动作全有；列表字段名与页码需适配，规则未核）。六膳门「黑湖没有盘点」=黑湖镜像缺。 |
| D | /warehouse/storage-locations | 部分（仓库档案与布局保存=有；按仓库分层布局、底图存储、版本号乐观锁=缺/差异）。 |
| D | /warehouse/material-types | 有（Cretas 原生）；六膳门「黑湖来源类型计数」=原型专用。 |
| D | /warehouse/material-segments | 有（原型本身无数据需求；正式能力完整）。 |
| D | /warehouse/wastage-reports | 部分（报损创建/审批/扣库存=有，且比原型多出审批与扣库存；原型「无批次/无照片/本地撤销/不扣库存」与正式口径冲突，导出缺）。 |
| D | /warehouse/source-individuals | 部分（档案 CRUD/按耳标反查/溯源详情=有；类型、进场日期字段、撤销留原因、导出=缺）。 |
| D | /warehouse/material-price-trend | 部分（可用现有接口由前端组装，无服务端聚合/权限一致的趋势接口，导出缺）。 |
| E | /quality/inspections 质量检验 | 部分 |
| E | /quality/standards 检验标准 | 部分 |
| E | /quality/defects 不良分析 | 部分 |
| E | /quality/ccp CCP 监控记录 | 部分 |
| E | /quality/returns 质检退回单 | 部分 |
| E | /quality/disposals 废弃处理 | 部分 |
| E | /quality/recalls 批次召回 | 部分 |
| E | /quality/param-deviation 工艺参数偏离 | 有（原型自己写了「没有」，实际后端已有，原型页应改为接这三组接口；扣留本批及下游规则未核） |
| E | /quality/cold-chain 冷链超温批次 | 有（原型侧只写缺口，后端已具备；温度采集来源规则未核） |
| E | /quality/label-qc 包装标签拍检 | 部分（拍检流程有，七项人工清单与判定无） |
| E | /lsm/cost 成本分析（六膳门） | 部分 |
| E | /lsm/reconcile 对账（Cretas ↔ 黑湖） | 缺（黑湖↔Cretas 差异对账接口两边都没有；Python 只有黑湖单边的库存流水核对） |
| E | 服务：product-cost-workspace（ydwm-v1/tools/product-cost-workspace.cjs，味美） | 部分 |
| E | 服务：ledger-facts（ydwm-v1/tools/ledger-facts.cjs） | 缺 |
| E | 服务：report-insights（ydwm-v1/tools/report-insights.cjs） | 部分 |
| E | 服务：list-analysis-service（ydwm-v1/tools/list-analysis-service.cjs） | 部分 |

## 四、没覆盖到的

- 规则实现：各页只确认了接口存在与返回字段，库存扣减、调拨在途、先到期先出、盘点复核、状态机权限与自批限制等规则没有逐条比对；明细里写「规则未核」的都在此列。
- `/smart-bi/*`、`/analytics/*` 在原型里是 web-admin 页面的旧拷贝编译页，A 组按 web-admin 同名页面的真实调用加后端路由核对，返回字段与口径未核。
- 供应商 / 客户导入规则、报价、沟通记录字段、供应商详情里的委外任务页签（Java 里没检索到，web-admin 有 `outsource.ts`，路径未核）。
