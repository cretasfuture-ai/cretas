# G-gap · 正式管理端 → 统一原型 功能差距表

来源：my-prototype-logistics/web-admin @ fa4cab00a9 (2026-11-12 22:29 +0800) src/components/layout/menuConfig.ts rawMenuConfig；路由补充 src/router/index.ts 与 modules/*。只读源码，没改仓库。**47 生产管理端没有在线核对**：AI 不登录，以源码为准。

**11-13 按 FG-AUDIT 退回 G-2、G-9 重数**：
- 口径改了两处。第一，「数据与分析」整组 12 页在 `menuConfig.ts` 里写着 `hideForFactoryTypes:['FACTORY']`，正式端对工厂是隐藏的，所以单列为「工厂隐藏」，不算进工厂可见页。第二，原来标「已有」的页里，有 34 行在六膳门只是「尚未迁入」占位，只有味美编译页能用，按「部分 = 只在一个工作区」的定义改成「部分」。
- 「没有」的行加了一列「味美编译页可直达」：11-13 在 19063 味美工作区用隐藏框架逐个只读打开，看路由守卫转到哪里。账号模块关闭的财务、人事、设备、调度、研发、系统各页，按账号权限推断为 403；审计抽查过的账号管理、设备告警、会计凭证、业务阈值、人员分配都是 403，和推断一致。

口径：一行 = 正式管理端工厂侧菜单里的一个叶子页面（rawMenuConfig，含按角色才显示的项）。餐饮、商城不纳入；只有平台管理员可见的项、物流租户的项单列为「不纳入」。不在菜单、只有路由的页面见附录，不计数。
判断标准：「已有」= 两个工作区都有入口，主要动作能做或能看（六膳门的只读视图也算）；「部分」= 只做了一部分，比如只读、只在一个工作区能用、或者混在别的页里；「没有」= 原型里没有入口。味美账号关闭的模块（财务、人事、设备、调度、研发）在原型里只做了骨架，属于参考设计，不等于启用或授权。

## 汇总

| 范围 | 页数 | 已有 | 部分 | 没有 |
|---|---|---|---|---|
| **工厂可见**（正式端工厂租户看得到） | **135** | 22 | 48 | 65 |
| 工厂隐藏（「数据与分析」组，正式端对工厂隐藏，原型里有） | 12 | 1 | 9 | 2 |
| 不纳入（平台 / 物流 / 餐饮口径） | 4 行 | — | — | — |

**结论**：在工厂可见的 135 页里，原型两个工作区都能用的只有 22 页（16%）。48 页只做了一部分，其中 34 页是六膳门「尚未迁入」占位。65 页原型里没有入口，大多集中在财务、系统、人事、调度、设备、质量闭环。

「没有」的 67 页里，味美编译页按网址能直接打开的有 9 页：AI 经营报告、AI 工作台、工程变更、报损、源头个体档案、CCP 记录、质检退回、废弃处理、车辆字典。这些页面原型可以先挂入口，工作量最小。另外实测 403 的有 10 页，404 的有 7 页（这次的味美编译包里没有这些路由），按模块权限推断为 403 的有 41 页。

按正式菜单组（只算工厂可见）：

| 菜单组 | 已有 | 部分 | 没有 |
|---|---|---|---|
| 顶层 | 1 | 0 | 3 |
| 工厂管理工作区 | 0 | 4 | 0 |
| 工作台 | 4 | 3 | 0 |
| 个人 OA | 0 | 4 | 0 |
| 研发管理 | 0 | 0 | 2 |
| 生产管理 | 5 | 10 | 3 |
| 仓储管理 | 4 | 7 | 2 |
| 质量管理 | 3 | 4 | 7 |
| 采购管理 | 3 | 5 | 0 |
| 销售管理 | 2 | 3 | 3 |
| 人事管理 | 0 | 1 | 7 |
| 设备管理 | 0 | 0 | 3 |
| 财务管理 | 0 | 5 | 12 |
| 系统管理 | 0 | 2 | 16 |
| 智能调度 | 0 | 0 | 7 |

「部分」和「没有」按建议归属线（只算工厂可见）：D 33，C 22，F 6，A 26，B 16，E 10。F 指数字班组交互层；G 只做骨架，实现归对应线。

重复挂载：「缺料异常」在生产、质量、采购、销售四个菜单里各挂一次；「交付风险」挂两处；「成本差异」「成本损耗异常」在生产、财务各挂一次；「操作日志」在系统管理里有两项。重整方案（G-ia.md）里这些都只留一处。

## 逐页

| 正式菜单组 | 页面 | 正式路由 | 状态 | 原型路由 | 归属线 | 数字员工（已有页以 F-crew-map 为准） | G 骨架 | 说明 | 工厂可见 | 味美编译页可直达 |
|---|---|---|---|---|---|---|---|---|---|---|
| 顶层 | AI经营报告 | `/analytics/ai-reports` | 没有 |  | D | 数字成本会计 | `/g/reports` | 老板/财务看的周期报告；与 D 自定义报表（N9）合并为「经营报告」 | 工厂可见 | 可直达 |
| 顶层 | 首页 | `/dashboard` | 已有 | /dashboard | D | 暂不归任何人（F） | `/g/today` | 重整为「今天」：待办、数字员工在办、等我确认、发现的问题 | 工厂可见 |  |
| 工厂管理工作区 | 经营总览 | `/workspaces/overview` | 部分 | /smart-bi/dashboard、/analytics/overview | D | 数字成本会计 | `/g/today?role=boss` | 原型有驾驶舱与领域概览，缺「经营总览」一页把四块放在一起 | 工厂可见 |  |
| 工厂管理工作区 | 异常中心 | `/workspaces/exceptions` | 部分 | /analytics/alert-dashboard | D | 暂不归任何人（F） | `/g/issues` | 原型异常预警只列规则提醒；缺按「发现 → 依据 → 转待办 → 关闭」的闭环 | 工厂可见 |  |
| 工厂管理工作区 | 方案与审批 | `/workspaces/plans-approvals` | 部分 | /workflow/pending | D | 暂不归任何人（F） | `/g/todo` | AI 方案（排产、采购）与审批合到「待办」 | 工厂可见 |  |
| 工厂管理工作区 | 追溯与复盘 | `/workspaces/trace-review` | 部分 | 库存查询内追溯面板（C TracePanel） | C | 数字质检员 | `/g/trace` | 原型追溯只能从库存行打开，没有独立入口和复盘 | 工厂可见 |  |
| 顶层 | AI 工作台 | `/dashboard/ai-value` | 没有 |  | F | 全部 | `F 班组首页` | 由 F「数字班组首页 + 工作记录与额度」取代 | 工厂可见 | 可直达 |
| 工作台 | 销售老板工作台 | `/workdesk/sales-owner` | 已有 | /dashboard?role=sales | D | 暂不归任何人（F；岗位视图显示数字文员） | `/g/today?role=sales` | D4 已并入首页 | 工厂可见 |  |
| 工作台 | 财务主管工作台 | `/workdesk/finance-manager` | 部分 |  | D | 暂不归任何人（同 F 规则；岗位视图显示数字成本会计） | `/g/today?role=finance` | 首页岗位筛选目前只有销售/生产/仓库/采购 | 工厂可见 |  |
| 工作台 | 质量主管工作台 | `/workdesk/quality-manager` | 部分 |  | D | 暂不归任何人（同 F 规则；岗位视图显示数字质检员） | `/g/today?role=quality` | 同上，缺质检岗位 | 工厂可见 |  |
| 工作台 | 生产经理工作台 | `/workdesk/production-manager` | 已有 | /dashboard?role=production | D | 暂不归任何人（F；岗位视图显示数字计划员） | `/g/today?role=plan` | D4 已并入首页 | 工厂可见 |  |
| 工作台 | 仓管员工作台 | `/workdesk/warehouse-keeper` | 已有 | /dashboard?role=warehouse | D | 暂不归任何人（F；岗位视图显示数字仓管） | `/g/today?role=warehouse` | D4 已并入首页 | 工厂可见 |  |
| 工作台 | 采购员工作台 | `/workdesk/purchaser` | 已有 | /dashboard?role=purchase | D | 暂不归任何人（F；岗位视图显示数字文员） | `/g/today?role=purchase` | D4 已并入首页 | 工厂可见 |  |
| 工作台 | 质检主管工作台 | `/workdesk/quality-chief` | 部分 |  | D | 暂不归任何人（同 F 规则；岗位视图显示数字质检员） | `/g/today?role=quality` | 与质量主管合并为一个岗位视图 | 工厂可见 |  |
| 个人 OA | 待我审批 | `/workflow/pending` | 部分 | /workflow/pending | D | 暂不归任何人（F） | `/g/todo?tab=approve` | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 个人 OA | 我发起的 | `/workflow/my-created` | 部分 | /workflow/my-created | D | 暂不归任何人（F） | `/g/todo?tab=mine` | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 个人 OA | 已处理 | `/workflow/acted` | 部分 | /workflow/acted | D | 暂不归任何人（F） | `/g/todo?tab=cc` | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 个人 OA | 抄送我的 | `/workflow/copied` | 部分 | /workflow/copied | D | 暂不归任何人（F） | `/g/todo?tab=cc` | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 研发管理 | 研发样品 | `/rd/samples` | 没有 |  | A | 数字计划员 | `/g/rd` | 样品 → 转正式 SKU；转产后进 A 的产品与工艺 | 工厂可见 | 403 |
| 研发管理 | 已转样品库 | `/rd/converted` | 没有 |  | A | 数字计划员 | `/g/rd?tab=converted` |  | 工厂可见 | 403 |
| 生产管理 | 生产计划 | `/production/plans` | 已有 | /production/plans | A | 数字计划员 |  |  | 工厂可见 |  |
| 生产管理 | 缺料异常 | `/production/shortage-cases` | 部分 | /production/shortage-cases | A | 数字计划员 | `/g/issues?kind=shortage` | D5：缺料列表只在 A；重整后同时出现在「发现的问题」；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 生产管理 | 交付风险 | `/production/delivery-risks` | 没有 |  | A | 数字计划员 | `/g/delivery` | 订单交期 × 计划进度 × 齐套；与销售交付风险同一页 | 工厂可见 | 404 |
| 生产管理 | 生产进度大屏 | `/dashboard/production-progress` | 部分 | /dashboard/production-progress | A | 暂不归任何人（F） |  | 改名车间大屏；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 生产管理 | 生产批次 | `/production/batches` | 部分 | /production/batches | A | 数字计划员 |  | 味美批次；六膳门为工单；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 生产管理 | 溯源查询 | `/production/traceability` | 部分 | 库存查询内追溯面板 | C | 数字质检员 | `/g/trace` | 移到「质量与追溯」 | 工厂可见 |  |
| 生产管理 | 标准出成率 | `/production/yield-standard` | 部分 | /production/yield-standard | A | 数字成本会计 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 生产管理 | 生产看板 | `/production/kanban` | 部分 | /production/kanban | A | 数字计划员 |  | 改名状态看板；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 生产管理 | 报工审批 | `/production/approval` | 已有 | /production/approval、/production/weigh | A | 数字计划员 |  | 已加称重报工 | 工厂可见 |  |
| 生产管理 | 领料 | `/production/material-flow` | 已有 | /production/material-flow | A | 数字计划员 |  |  | 工厂可见 |  |
| 生产管理 | SKU 管理 | `/system/products` | 已有 | /system/products | A | 数字计划员 |  | 重整后下沉到「设置 / 主数据」 | 工厂可见 |  |
| 生产管理 | 工序 | `/production/process-hub` | 已有 | /production/process-hub | A | 数字计划员 |  |  | 工厂可见 |  |
| 生产管理 | 工程变更通知 | `/production/bom/ecns` | 没有 |  | A | 数字计划员 | `/g/engineering` | BOM/工艺改动的申请、影响范围、生效版本 | 工厂可见 | 可直达 |
| 生产管理 | BOM调价建议 | `/production/bom/price-adjustments` | 没有 |  | B | 数字成本会计 | `/g/engineering?tab=price` | 原料价变动 → 产品成本影响 → 调价建议 | 工厂可见 | 404 |
| 生产管理 | 生产分析 | `/production/analysis-query` | 部分 | /production/analysis-query | A | 数字计划员 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 生产管理 | 成本差异 | `/production/cost-variance` | 部分 | /lsm/cost、/analytics/overview?view=product-cost | B | 数字成本会计 |  | 原型有成本分析，缺「标准 vs 实际」差异列表 | 工厂可见 |  |
| 生产管理 | 成本损耗异常 | `/production/cost-deviation-cases` | 部分 | /lsm/cost | B | 数字成本会计 | `/g/issues?kind=cost` | 差异超阈值转成「发现的问题」 | 工厂可见 |  |
| 生产管理 | 成本汇总 | `/production-analytics/cost-summary` | 部分 | /lsm/cost | B | 数字成本会计 |  |  | 工厂可见 |  |
| 顶层 | 经营指标 | `/analytics/indicators` | 没有 |  | D | 数字成本会计 | `/g/reports?tab=kpi` | 指标口径与目标值；与经营报告同页 | 工厂可见 | 403 |
| 仓储管理 | 无订单入库申请 | `/warehouse/unordered-inbound-applications` | 部分 | /warehouse/unordered-inbound-applications | C | 数字仓管 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 仓储管理 | 入库任务与批次 | `/warehouse/materials` | 已有 | /warehouse/materials、/lsm/inbound-batches | C | 数字仓管 |  |  | 工厂可见 |  |
| 仓储管理 | 出货管理 | `/warehouse/shipments` | 已有 | /warehouse/shipments | C | 数字仓管 |  |  | 工厂可见 |  |
| 仓储管理 | 领料单 | `/warehouse/material-requisitions` | 部分 | /production/material-flow | A | 数字计划员 |  | D2：领退料归 A；仓库侧只读 | 工厂可见 |  |
| 仓储管理 | 调拨单 | `/transfer/list` | 部分 | /transfer/list | C | 数字仓管 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 仓储管理 | 报损管理 | `/warehouse/wastage-reports` | 没有 |  | C | 数字仓管 | `/g/warehouse-more?tab=wastage` | 报损 → 质量处置 → 财务报废审批 | 工厂可见 | 可直达 |
| 仓储管理 | 库存查询 | `/warehouse/inventory-query` | 已有 | /warehouse/inventory-query | C | 数字仓管 |  |  | 工厂可见 |  |
| 仓储管理 | 盘点 | `/warehouse/stocktake-query` | 部分 | /warehouse/stocktake-query | C | 数字仓管 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 仓储管理 | 仓储空间与现场盘点 | `/warehouse/storage-locations` | 已有 | /warehouse/storage-locations | C | 数字仓管 |  |  | 工厂可见 |  |
| 仓储管理 | 原料类型字典 | `/warehouse/material-types` | 部分 | /warehouse/material-types | C | 数字仓管 |  | 重整后下沉到「设置 / 主数据」；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 仓储管理 | 源头个体档案 | `/warehouse/source-individuals` | 没有 |  | C | 数字质检员 | `/g/warehouse-more?tab=source` | 活体/胴体来源档案，追溯源头 | 工厂可见 | 可直达 |
| 仓储管理 | 物料分类字典 | `/warehouse/material-segments` | 部分 | /warehouse/material-segments | C | 数字仓管 |  | 下沉到「设置 / 主数据」；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 仓储管理 | 物料均价趋势 | `/warehouse/material-price-trend` | 部分 | /warehouse/material-price-trend | C | 数字成本会计 |  | D6 放经营分析；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 质量管理 | 质检记录 | `/quality/inspections` | 已有 | /quality/inspections | E | 数字质检员 |  |  | 工厂可见 |  |
| 质量管理 | 质检放行 | `/quality/release` | 部分 | /quality/inspections（单内结论） | E | 数字质检员 | `/g/quality-loop?tab=release` | 缺独立的「待放行」队列 | 工厂可见 |  |
| 质量管理 | CCP 监控记录 | `/quality/ccp` | 没有 |  | E | 数字质检员 | `/g/quality-loop?tab=ccp` | BL 第二组 6：挂到工序画布 | 工厂可见 | 可直达 |
| 质量管理 | 工艺参数偏离 | `/quality/param-deviation` | 没有 |  | E | 数字质检员 | `/g/quality-loop?tab=ccp` | 偏离 → 扣留本批及下游 | 工厂可见 | 404 |
| 质量管理 | 冷链超温批次 | `/quality/cold-chain` | 没有 |  | E | 数字质检员 | `/g/quality-loop?tab=coldchain` |  | 工厂可见 | 404 |
| 质量管理 | 包装标签拍检 | `/quality/label-qc` | 部分 | /quality/label-qc | E | 数字质检员 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 质量管理 | 视觉质检训练 | `/quality/vision-training` | 没有 |  | E | 数字质检员 | `/g/settings?tab=crew` | 属于 AI 能力配置，下沉到设置 | 工厂可见 | 403 |
| 质量管理 | 工序质检不良 | `/quality/defects` | 已有 | /quality/defects | E | 数字质检员 |  | 原型叫不良分析 | 工厂可见 |  |
| 质量管理 | 质检退回单 | `/quality/returns` | 没有 |  | E | 数字质检员 | `/g/quality-loop?tab=returns` |  | 工厂可见 | 可直达 |
| 质量管理 | 不良品处置 | `/quality/disposition` | 部分 | /quality/inspections（不合格 → 待处置标记） | E | 数字质检员 | `/g/quality-loop?tab=disposition` | 缺处置单本身 | 工厂可见 |  |
| 质量管理 | 批次召回 | `/quality/recalls` | 没有 |  | E | 数字质检员 | `/g/quality-loop?tab=recall` | 依赖 C 批次与追溯 | 工厂可见 | 403 |
| 质量管理 | 废弃处理 | `/quality/disposals` | 没有 |  | E | 数字质检员 | `/g/quality-loop?tab=returns` |  | 工厂可见 | 可直达 |
| 质量管理 | 缺料异常 | `/quality/shortage-cases` | 部分 | /production/shortage-cases | A | 数字计划员 |  | 正式端四处重复挂；重整后只在「发现的问题」；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 质量管理 | 质检标准 | `/quality/standards` | 已有 | /quality/standards | E | 数字质检员 |  |  | 工厂可见 |  |
| 采购管理 | 我的请购 | `/procurement/requisitions/my` | 已有 | /procurement/requisitions/my | C | 数字文员 |  |  | 工厂可见 |  |
| 采购管理 | 待审批请购 | `/procurement/requisitions/pending-approval` | 部分 | /procurement/requisitions/pending-approval | C | 数字文员 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 采购管理 | 全部请购单 | `/procurement/requisitions` | 部分 | /procurement/requisitions/my | C | 数字文员 |  | 精简视图可加「全部」视图 | 工厂可见 |  |
| 采购管理 | 采购订单 | `/procurement/orders` | 已有 | /procurement/orders | C | 数字文员 |  |  | 工厂可见 |  |
| 采购管理 | AI受控采购 | `/procurement/ai-purchase` | 部分 | /procurement/ai-purchase | C | 数字计划员 |  | 原型叫采购建议；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 采购管理 | 缺料异常 | `/procurement/shortage-cases` | 部分 | /production/shortage-cases | A | 数字计划员 |  | 重复挂；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 采购管理 | 供应商管理 | `/procurement/suppliers` | 已有 | /procurement/suppliers | C | 数字文员 |  | 下沉到「设置 / 主数据」 | 工厂可见 |  |
| 采购管理 | 价格表管理 | `/procurement/price-lists` | 部分 | /procurement/price-lists | C | 数字文员 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 销售管理 | 销售订单 | `/sales/orders` | 已有 | /sales/orders | C | 数字文员 |  |  | 工厂可见 |  |
| 销售管理 | 客户管理 | `/sales/customers` | 已有 | /sales/customers | C | 数字文员 |  | 下沉到「设置 / 主数据」 | 工厂可见 |  |
| 销售管理 | 缺料异常 | `/sales/shortage-cases` | 部分 | /production/shortage-cases | A | 数字计划员 |  | 重复挂；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 销售管理 | 交付风险 | `/sales/delivery-risks` | 没有 |  | A | 数字计划员 | `/g/delivery` | 与生产交付风险同一页 | 工厂可见 | 404 |
| 销售管理 | 出货记录 | `/sales/shipments` | 部分 | /sales/shipments | C | 数字文员 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 销售管理 | 销售退货 | `/sales/returns` | 部分 | /sales/returns | C | 数字文员 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂可见 |  |
| 销售管理 | 销售付款申请 | `/sales/payment-requests` | 没有 |  | C | 数字文员 | `/g/finance?tab=payment` | 放进财务的收付款 | 工厂可见 | 403 |
| 销售管理 | 车辆字典 | `/sales/vehicles` | 没有 |  | C | 数字文员 | `/g/settings?tab=master` | 下沉到主数据 | 工厂可见 | 可直达 |
| 人事管理 | 账号管理 | `/system/users` | 没有 |  | D | — | `/g/settings?tab=org` | 管理员功能，不归数字员工 | 工厂可见 | 403 |
| 人事管理 | 角色权限 | `/system/roles` | 没有 |  | D | — | `/g/settings?tab=org` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 人事管理 | 权限矩阵 | `/system/permission-matrix` | 部分 | 原型与来源 → 动作权限对照（只读） | D | — | `/g/settings?tab=org` |  | 工厂可见 |  |
| 人事管理 | 员工档案 | `/hr/employees` | 没有 |  | D | — | `/g/people` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 人事管理 | 部门管理 | `/hr/departments` | 没有 |  | D | — | `/g/people?tab=dept` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 人事管理 | 工种字典 | `/hr/work-types` | 没有 |  | D | — | `/g/people?tab=worktype` | 计件计时工价（BL16）的基础 | 工厂可见 | 403（模块未开通，按权限推断） |
| 人事管理 | 考勤管理 | `/hr/attendance` | 没有 |  | D | — | `/g/people?tab=attendance` | 有效工时的另一来源 | 工厂可见 | 403（模块未开通，按权限推断） |
| 人事管理 | 账号邀请 | `/hr/whitelist` | 没有 |  | D | — | `/g/settings?tab=org` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 设备管理 | 设备列表 | `/equipment/list` | 没有 |  | A | 数字计划员 | `/g/equipment` | 产能约束；六膳门黑湖设备 0 条 | 工厂可见 | 403（模块未开通，按权限推断） |
| 设备管理 | 维护记录 | `/equipment/maintenance` | 没有 |  | A | 数字计划员 | `/g/equipment?tab=maint` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 设备管理 | 告警管理 | `/equipment/alerts` | 没有 |  | A | 数字计划员 | `/g/issues?kind=equipment` | 并入「发现的问题」 | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 调整审批 | `/finance/adjustments` | 没有 |  | B | 数字成本会计 | `/g/finance?tab=approve` | 并入「待办」 | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 报废审批 | `/finance/disposal-approvals` | 没有 |  | B | 数字成本会计 | `/g/finance?tab=approve` | 并入「待办」 | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 财务概览 | `/finance/costs` | 没有 |  | B | 数字成本会计 | `/g/finance` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 财务报表 | `/finance/three-statements` | 没有 |  | B | 数字成本会计 | `/g/finance?tab=margin` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 财务分析(Excel) | `/finance/reports` | 没有 |  | D | 数字成本会计 | `/g/reports` | 与经营报告合并 | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 应收应付 | `/finance/ar-ap` | 部分 | /lsm/receivables（六膳门应收） | C | 数字成本会计 | `/g/finance?tab=arap` | 原型只有六膳门应收，无应付 | 工厂可见 |  |
| 财务管理 | 销售开票 | `/finance/invoices` | 部分 | /lsm/receivables（待开票只读） | C | 数字文员 | `/g/finance?tab=invoice` |  | 工厂可见 |  |
| 财务管理 | 采购收票 | `/finance/purchase-invoices` | 没有 |  | C | 数字文员 | `/g/finance?tab=invoice` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 收款管理 | `/finance/payments` | 部分 | /lsm/receivables（收款只读） | C | 数字成本会计 | `/g/finance?tab=payment` |  | 工厂可见 |  |
| 财务管理 | 会计凭证 | `/finance/voucher-list` | 没有 |  | B | 数字成本会计 | `/g/finance?tab=voucher` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 凭证导出 | `/finance/voucher-export` | 没有 |  | B | 数字成本会计 | `/g/finance?tab=voucher` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 凭证模板 | `/finance/voucher-template` | 没有 |  | B | 数字成本会计 | `/g/settings?tab=rules` | 下沉到设置 | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | SKU毛利率分析 | `/finance/sku-margin` | 没有 |  | B | 数字成本会计 | `/g/finance?tab=margin` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 成本差异 | `/finance/cost-variance` | 部分 | /lsm/cost | B | 数字成本会计 |  | 与生产成本差异重复挂 | 工厂可见 |  |
| 财务管理 | 成本损耗异常 | `/finance/cost-deviation-cases` | 部分 | /lsm/cost | B | 数字成本会计 | `/g/issues?kind=cost` | 重复挂 | 工厂可见 |  |
| 财务管理 | 毛利红线配置 | `/finance/gross-margin-redline` | 没有 |  | B | 数字成本会计 | `/g/settings?tab=rules` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 财务管理 | 会计期间 | `/finance/accounting-period` | 没有 |  | B | 数字成本会计 | `/g/finance?tab=period` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 操作日志 | `/system/logs` | 部分 | 单据操作日志抽屉（B6） | D | — | `/g/settings?tab=data` | 正式端 logs 与 operation-log 两项重复 | 工厂可见 |  |
| 系统管理 | 系统设置 | `/system/settings` | 没有 |  | D | — | `/g/settings` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 业务阈值配置 | `/system/business-thresholds` | 没有 |  | D | 全部 | `/g/settings?tab=rules` | 「发现的问题」的触发线 | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 审批链配置 | `/system/approval-chains` | 没有 |  | D | — | `/g/settings?tab=rules` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 工作流设计器 | `/system/workflow-designer` | 没有 |  | D | — | `/g/settings?tab=rules` | 与审批链合并 | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 功能模块配置 | `/system/features` | 没有 |  | D | — | `/g/settings?tab=org` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 一线角色 AI 操作权限 | `/system/ai-frontline-grants` | 没有 |  | F | 全部 | `/g/settings?tab=crew` | 数字员工能力随权限走（F 硬约束） | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | Canvas 配置编辑器 | `/canvas-editor` | 没有 |  | D | — | `/g/settings?tab=rules` | 实施人员用，客户菜单可隐藏 | 工厂可见 | 403 |
| 系统管理 | 员工工牌生成 | `/system/badge-generator` | 没有 |  | D | — | `/g/people?tab=badge` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 计量单位字典 | `/unit-dictionary` | 没有 |  | D | — | `/g/settings?tab=master` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 操作日志（第二项） | `/system/operation-log` | 部分 | 单据操作日志抽屉（B6） | D | — | `/g/settings?tab=data` | 与 /system/logs 合并 | 工厂可见 |  |
| 系统管理 | 工艺保密 | `/system/process-secret` | 没有 |  | A | — | `/g/settings?tab=rules` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 平台访问记录 | `/system/platform-access-log` | 没有 |  | D | — | `/g/settings?tab=data` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 数据备份 | `/system/data-backups` | 没有 |  | D | — | `/g/settings?tab=data` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 可复用 Skill | `/system/reusable-skills` | 没有 |  | F | 全部 | `/g/settings?tab=crew` | 数字员工的技能库 | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | 定时任务写入 | `/system/autonomous-writes` | 没有 |  | F | 全部 | `/g/settings?tab=crew` | 主动汇报与定时工作的写入记录 | 工厂可见 | 403（模块未开通，按权限推断） |
| 系统管理 | Agent 运行与评测 | `/ops/agent-ops` | 没有 |  | F | 全部 | `/g/settings?tab=crew` | 建议移出客户菜单，只给实施/平台 | 工厂可见 | 403 |
| 系统管理 | 建厂就绪 | `/factory-readiness` | 没有 |  | F | 全部 | `F 入职` | 由 F「数字班组入职」取代 | 工厂可见 | 403 |
| 智能调度 | 调度中心 | `/scheduling/overview` | 没有 |  | A | 数字计划员 | `/g/scheduling` | 数字计划员的主战场；急单演示流程落在这里 | 工厂可见 | 403（模块未开通，按权限推断） |
| 智能调度 | 调度计划 | `/scheduling/plans` | 没有 |  | A | 数字计划员 | `/g/scheduling?tab=draft` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 智能调度 | 多方案比较 | `/scheduling/proposal-comparison` | 没有 |  | A | 数字计划员 | `/g/scheduling?tab=compare` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 智能调度 | 实时监控 | `/scheduling/realtime` | 没有 |  | A | 数字计划员 | `/g/scheduling?tab=realtime` | 与车间大屏重复，建议合并 | 工厂可见 | 403（模块未开通，按权限推断） |
| 智能调度 | 人员分配 | `/scheduling/workers` | 没有 |  | A | 数字计划员 | `/g/scheduling?tab=workers` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 智能调度 | 告警管理 | `/scheduling/alerts` | 没有 |  | A | 数字计划员 | `/g/issues?kind=schedule` | 并入「发现的问题」 | 工厂可见 | 403（模块未开通，按权限推断） |
| 智能调度 | 排产设置 | `/scheduling/settings` | 没有 |  | A | 数字计划员 | `/g/settings?tab=rules` |  | 工厂可见 | 403（模块未开通，按权限推断） |
| 数据与分析 | 经营驾驶舱 | `/smart-bi/dashboard` | 部分 | /smart-bi/dashboard | D | 暂不归任何人（F） |  | 正式端对工厂默认隐藏此组；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | AI 问答 / 数据分析 | `/smart-bi/analysis` | 部分 | /smart-bi/analysis | D | 暂不归任何人（F） |  | 重整后由各数字员工对话承接；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | 经营分析 | `/smart-bi/analysis-hub` | 已有 | /smart-bi/analysis-hub | D | 数字成本会计 |  |  | 工厂隐藏 |  |
| 数据与分析 | 异常预警 | `/analytics/alert-dashboard` | 部分 | /analytics/alert-dashboard | D | 暂不归任何人（F） | `/g/issues` | 并入「发现的问题」；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | 进销存总览 | `/analytics/supply-chain` | 部分 | /analytics/supply-chain | C | 数字仓管 |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | Excel 上传 | `/smart-bi/upload` | 部分 | /smart-bi/upload | D | 暂不归任何人（F） |  | 下沉到「设置 / 数据」；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | 查询模板 | `/smart-bi/query-templates` | 部分 | /smart-bi/query-templates | D | 暂不归任何人（F） |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | 数据完整度 | `/smart-bi/data-completeness` | 部分 | /smart-bi/data-completeness | D | 暂不归任何人（F） | `/g/issues?kind=data` | 主数据缺口并入「发现的问题」；六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | 字段映射复核 | `/smart-bi/mapping-review` | 部分 | /smart-bi/mapping-review | D | 暂不归任何人（F） |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | 分析概览 | `/analytics/overview` | 部分 | /analytics/overview | D | 暂不归任何人（F） |  | 六膳门是「尚未迁入」占位，只有味美编译页可用 | 工厂隐藏 |  |
| 数据与分析 | 知识库反馈 | `/smart-bi/food-kb-feedback` | 没有 |  | F | 全部 | `/g/settings?tab=crew` | AI 运维，下沉 | 工厂隐藏 | 404 |
| 数据与分析 | AI 追问日志 | `/smart-bi/fallback-log` | 没有 |  | F | 全部 | `/g/settings?tab=crew` | AI 运维，下沉 | 工厂隐藏 | 404 |
| 数据与分析 | 收入管理报表 | `/smart-bi/revenue-report` | 不纳入 |  |  |  |  | 餐饮口径，对工厂隐藏 |  |  |
| 数据与分析 | 行为校准监控 | `/smart-bi/calibration` | 不纳入 |  |  |  |  | 仅平台管理员 |  |  |
| 平台治理 | AI意图配置 等 13 项 | `/system/ai-intents 等` | 不纳入 |  |  |  |  | roles 仅 platform_admin：AI意图、工厂数据备份、Skill/Tool治理、LLM用量、标签识别诊断、单盒标签精修、编码规则、AI配额、POS集成、SmartBI配置、行为校准、数据质量队列；另有平台建厂 2 项（对话建厂、建厂复核） |  |  |
| 智能调度 | 物流排线 5 项 | `/scheduling/logistics/*` | 不纳入 |  |  |  |  | 对工厂隐藏（LOGISTICS 租户） |  |  |

## 附录：不在菜单、只有路由的页面（18 组，不计数）

| 领域 | 页面 | 正式路由 | 重整后去处 | 说明 |
|---|---|---|---|---|
| 生产 | 未完成计划队列 / 备货看板 / 交货预警 | `/production/pending-queue、restock-board、delivery-warnings` | `/g/delivery` | 与交付风险合并 |
| 生产 | BOM/配方维护 / BOM版本 / BOM多级展开 | `/production/bom、bom/versions、bom/tree` | `/production/process-hub` | 原型工序配置已含 BOM 页签与生产版本 |
| 生产 | BOM达成率 / 工序投入产出 / 人效对比 / 人效看板 | `/production/bom-achievement、process-io、labor-efficiency` | `/production/wip?tab=eff` | 原型在制品与效率部分覆盖 |
| 生产 | 退料记录 / 撤回审批 | `/production/material-returns、reversals` | `/production/material-flow` |  |
| 仓储 | 总库存 / 分仓库存 / 库存批次 / 在制品WIP | `/warehouse/inventory-total、by-warehouse、inventory、wip-batches` | `/warehouse/inventory-query` | 视图合并到库存查询 |
| 仓储 | 厂商登记表 / 周转耗材 / 半成品库存与盘点 / 盐化仓 / 中转挂账 | `/warehouse/manufacturers、reusable-containers、semi-finished*、salted-deductions、transit-ledger` | `/g/warehouse-more` | 食品厂特有，原型都没有 |
| 仓储 | 盘点与期初库存 | `/warehouse/stocktakes` | `/warehouse/stocktake-query` |  |
| 采购 | 待收货与批次 / 核价单 / 采购审批规则 / 入库异常 / 采购退货 / 付款申请 / 采购发票 | `/procurement/receives、inquiry-quotes、approval-rules、exceptions、returns、payment-requests、invoices` | `/g/purchase-more` |  |
| 销售 | 运营报价 / 成品库存 / 客户跟踪 / 客户需求 / 我的提醒 / 订单审批进度 / 产品利润 | `/sales/quotes、finished-goods、customer-tracking、needs、reminders、finance-review、orders/:id/profit` | `/g/sales-more` | W5：报价味美不开放 |
| 销售 | 销售报表 10 张 | `/sales/reports/*` | `/g/reports?tab=sales` |  |
| 销售 | 商机列表 / 看板 / 漏斗 | `/crm/opportunity*` | `/g/sales-more?tab=opportunity` | CRM，建议暂不纳入 |
| 人事 | 请假 / 加班调休 / 报销 / 假期余额 / 排班日历 / 工资 / 社保 / 个税 / 考勤异常 | `/hr/*` | `/g/people` | 建议工厂版只保留排班与考勤（工时来源），工资社保不做 |
| 财务 | 进销存台账 / 退货退款详情 | `/finance/inventory-ledger、return-refunds/:id` | `/lsm/stock-summary` |  |
| 调度 | 产线产能与设备 / 创建计划 | `/scheduling/line-capacity、plans/create` | `/g/scheduling` |  |
| 系统 | 导出中心 / 导入中心 / 全局权限矩阵 / 字段血统 / Provenance / 方差阈值 | `/system/export-center、import-center、role-permissions、data-fabric/*、cost-variance` | `/g/settings?tab=data` |  |
| 个人 OA | 流转规则设置 / 工作流处理 | `/workflow/rules、admin-running` | `/g/settings?tab=rules` |  |
| 分析 | 经营报告全文 / 车间实时生产报表 / 生产数据分析 / 人效分析 / 成本分析 / 成品出厂核算 / 三价对比 | `/analytics/trends、production-report、/production-analytics/*` | `/g/reports` |  |
| 其他 | 自定义看板 / 业务视图 / 打印模板设计器 / 流程演示 | `/dashboard/widgets、saved-views、print-template-editor、demo-flow` | `/g/settings` | 业务视图已由 BL22 已存视图取代 |
