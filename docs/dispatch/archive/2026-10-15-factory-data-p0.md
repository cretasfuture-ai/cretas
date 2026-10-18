# 工厂数据 P0：指标口径与多 SKU 正常业务闭环

任务 P0-FACTORY-DATA-20261015；Owner root，DIRECT；Base ac2c9ba827d05319585e7232b7cef5c7056d63df。沿用 codex/trace-analysis-vision-daily-review-20261015；不 push、PR、合并、部署。既有视觉体验及队列原样保留。

## 范围与完成条件

1. 核对并纠正出成率、良品率、成本的实际业务口径、单位、时间窗和数据来源。复用既有 YieldReportService、批次和成本服务，不重造出成率/成本引擎。未知/缺数/失败与真零分开，旧历史版本不重写。
2. daily_factory_a / VISION-ENTRY-A 的 loopback 私有测试库，以现有业务 API 和实际 Web 入口完成至少两个不同 SKU 的收料、领料、报工/生产、成品入库和出货。由业务操作生成数据，不 SQL 伪造流水、血缘边、指标值或计算历史。
3. 验收实际库存/成本变化、指标 fresh 计算/版本读回、向上追溯与向下影响面、跨厂/不存在/缺关联对照；真实 UI 留下可体验入口和合成测试记录。不执行客户训练或生产数据写入。

## 初始 scope 锁

- indicator/strategy 既有出品率、单位成本策略及必要同目录共享计算器/良品率策略；IndicatorQueryServiceImpl 的版本化来源/缓存兼容最小接线；对应运行时行为与真实 PostgreSQL 目标测试。
- 既有经营指标抽屉与 API 的口径说明/必要下钻，及目标测试；不新建另一套报表。
- scripts/e2e/daily-review 下新增 P0 正常业务验收与可复用 helper；复用现有非生产业务脚本结构和真实 API，不借旧客户样本或记录。
- 本说明与 ACTIVE。业务环节发现真实缺陷时，先记录复现和扩展的精确文件，再作最小修复；平台建厂 Owner 的工作区及在飞修改不动。既有 Entity/Repository/迁移/权限默认不改；确需修改时保留真实 JPA/迁移等适用门禁。

## 验证纪律

目标单测与口径正反对照 → 实际 PostgreSQL 计算 → 本地真实登录与业务操作/读回。多 SKU 覆盖重量与包装单位，使用生产计划固定的净含量/单位换算，不用当前 SKU 覆盖历史。以真实输入量、最终产出、质量数量和已归集成本独立对账，禁止计划量冒充实际量、跨 SKU 数量直接相加、部分成本伪装完整成本。

Java 当前使用本 worktree target/classes。编译前只停止本任务记录的 Java/Python 运行时，保留 PostgreSQL、Web 和全部数据/队列；构建完成后复用现有私有启动 helper。Maven 在本 target 串行。最终冻结后运行必要目标回归、Web 类型/构建与真实 UI；仅文档变化不重跑产品测试。

## 开发发现与范围补充

- 子任务 P0-METRIC-UI-20261015；Base ac2c9ba827d05319585e7232b7cef5c7056d63df；Owner metric_ui。仅 IndicatorDetailDrawer.vue、indicator.ts、indicator-center/__tests__/IndicatorDetailDrawer.spec.ts；日期窗/中文公式/历史来源隔离。验收该 spec 的 Vitest；不运行共享 Java/Maven/浏览器、不写 ACTIVE。
- 子任务 P0-COST-WIRING-20261015；同 Base；Owner cost_wiring。仅 ProductionPlanServiceImpl.updateActualCosts 的设备/其他成本接线、YieldReportServiceImpl 的 process-sheet 工时投影、对应 service 目标测试；不动 Entity/Repository/迁移/其他方法。正常报工已有工时却在核算投影丢失；既有计划成本更新未接批次核算。测试由 root 停 Java 后串行运行，不自行编译或改运行时。
- 追加同 Owner：deriveConfirmedSettlementRequest 保留用户明确填写的质量清点三态，校验后合入服务端派生事实，未知不补零。
- 子任务 P0-TRANSFER-LINEAGE-20261015；同 Base；Owner transfer_lineage。仅 TransferServiceImpl 同厂确认的真实分批来源接线、LineageEdgeRecorder 新调拨边及必要目标测试/既有接线闸；不新增表或回填历史。scope 源于正常收料→调拨后追溯断在生产仓、调入批次单位丢失。root 停 Java 后统一验证。
- root 追加 AttachmentServiceImpl.stripQuery 保留自定义对象存储端口；原实现把已上传文件 URL 端口丢掉，导致本地凭证预览和缩略图连接失败。保留签名上传/下载门禁，目标测试复用 AttachmentServiceImplTest。
- 子任务 P0-COST-UI-20261015；同 Base；Owner metric_ui。仅批次 detail.vue 成本区、同目录 SupplementalCostsDialog.vue/目标测试；复用计划 /costs API，中文补录设备/其他费用并在保存后读取真实成本结果。未知保留空，显式零须由人确认；不改生产/库存其他操作。
- 独立风险复核追加：root 修指标 native JOIN 排除软删结算/产出行，并以真实 PG TEMP 表验证撤回后重结算不双计；拒绝 BOM 参考包材费用冒充实际成本。transfer_lineage Owner 将既有单位预检接入实际 FEFO 扣减；cost_wiring Owner 仅追加 FactoryStocktakeServiceImpl.completePendingDefectCount 的唯一且可证明旧错误形态质量重算，复用 StocktakeDefectCompletionTest。仍不改 Entity/Repository/迁移。
- 同次复核精确补充：cost_wiring Owner 校验唯一结算输出 SKU，并统一既有工时投影对跨午夜、缺失/零人数的处理；root 同时拒绝已知仅部分记录的辅料成本。所有变更只在原服务和目标测试中，root 串行编译。
- 扩展回归发现旧 TransferConsumptionLedgerTest / TransferShipBatchSelectionTest 的原料样本缺失单位，新实际扣减闸正确拒绝；transfer_lineage Owner 只给合法正例补明示 kg。TransferUnsettledConsumptionGateTest 原断言允许失败前部分扣减5kg，按新“预检前零写”更新为0且保留拒绝断言与流水阴性证据，不放宽业务门禁。
- 真实 UI/读回追加：共享原料用于批次7/8时，LineageGraphAssembler 对两批出边合计20冒充当前批次10；transfer_lineage Owner 仅修该类及既有目标测试，让直接数量只取当前 root 关联边、深层数量不猜分摊、缺单位不混加。metric_ui Owner 在原批次详情明确顶部合格产出与含不良实际产量，完成效率改为准确说明其毛产量分母，不改 Entity。修复后仅重跑受影响目标验证/构建，再重新读回。
- 页面验收追加阻断：旧 FullTrace.shipments 未接现有 SalesDelivery 发货，且 process-sheet 把原料摘要塞操作员、盒装数量标kg并计算负损耗。transfer_lineage Owner 扩展 TraceabilityServiceImpl、TraceabilityDTO 及现有目标测试：复用现有同厂成品批次分配/已发货记录投影，不写业务数据、不加Repository查询；加工行带真实输入/输出单位，不同单位不硬算损耗，不把来源元数据当姓名。root 只改 web-admin traceability API类型、index.vue 和既有测试：按实际单位显示、来源节点数只数祖先、出货表验证真实新发货。无Entity/迁移/生产修改。
- 指标真实入口追加：列表沿用跨期间lastValue/lastComputedAt，空期间查询后出现13.93却“未计算”，顶部又将所有有历史值当“正常”。metric_ui Owner 扩展 IndicatorCenterDashboard、IndicatorValueCard 与必要现有API契约/目标spec：P0四项列表与抽屉都按今日真实value，不用历史缓存拼状态；概览显示可用/缺数/失败数量而非伪阈值，未知不可沿用旧数值，显式零保留。不改后端口径引擎。

- 正常收料 UI 已生成 PO/收货草稿，凭证上传被当前本地 OSS disabled 阻断。不是业务成功，不跳过凭证门禁。追加 test/support 下仅显式本地 profile 启用的文件存储 fixture，复用原附件 Controller/Service、OssService 与 OSS 客户端接口；实际保存在代码外文件并校验读回，不触及生产 OSS。fixture 不进入生产 JAR，独立编译到代码外目录再加入本地 classpath。
- 指标采用已完工时间范围；出成率取首道实际投料与末道实际产出，良品产出率按固定净含量折成 kg 加权，成本复用出厂核算的完整实际归集结果/合格产出 kg。新来源版本 actual-flow-v2 防止旧公式缓存继续命中；旧版本保留。

## 2026-10-16 本地验收结果

实现、目标测试、真实本地 API/数据库与 Web 验收已完成，状态为 review，待用户体验。分支 `codex/trace-analysis-vision-daily-review-20261015` 保留；无 push、PR、合并、部署、生产写入、Entity/Repository/迁移变更。本地代码读取本 worktree 的 target/classes，Web 为 31761，Java 10060、Python 18090、私有 PostgreSQL 55447，未触碰另一任务的 18091。

### 口径与实际样本

- 出成率 = 合格末道产出净重 / 首道实际投料净重，跨 SKU 先按计划固定净含量转 kg 再加权；不能把 kg 与盒直接加总。
- 生产良品率 = 合格净重 /（合格净重 + 已明确清点的不良净重），合格产出不再重复扣不良。未清点、多正式批次/多输出归属不明时不猜零或 100%。
- 完整生产成本复用原实际核算；缺价、部分记录辅料、BOM 参考包材成本不能冒充完整实际成本。单位成本分母为合格产出 kg。设备/其他费用直接接原成本补录与结单重算 API；重复保存/重算不累加。
- 样本均为显式本地合成数据。正常采购收料原料100kg与包材100个，经同厂领料、逐道报工、结单、仓库入库、订单与批次分配、仓库确认出货产生数据；没有 SQL 伪造流水、血缘边、指标值或计算历史。指标 computation 元数据使用既有本地配置 bootstrap，PG TEMP 表测试独立于业务验收。

| 修复后整链样本 | 投料 | 合格产出 / 明确不良 | 实际完整成本 | 实际出货 |
|---|---|---|---|---|
| 批次7，P0体验卤制品A（散装） | 10kg | 8kg / 1kg | 122.33元 | 4kg |
| 批次8，P0体验卤制品B（500克盒装） | 10kg | 20盒 / 2盒 | 128.33元 | 10盒 |

今日共四个正常结单批次（5/6初轮与7/8修复后轮）：投料40kg、合格净重36kg、不良净重4kg；出成率90%、良品率90%、完整成本501.32元、单位成本13.9256元/kg。各成品批次均已发一半；实际原料库存合计60kg、包材44个，内部调拨未重复记作生产消耗。初轮缺陷清点通过既有盘点补录操作修正，未直接改历史 SQL。

新调拨 `d49cc476-71a0-4eb4-b3e9-56f9b503fb14` 的两条实际来源边与流水、收货批次、供应商已读回一致。原料 `9370725a-6a1a-4402-b4b5-b763831c3922` 向下影响面包含批次7/8、两成品批次及同一实际出货单 `9d410df1-f9c4-40ce-a571-f1124d933994`；两个 SKU 同单出货仍各自显示4kg/10盒。旧调拨未补造历史边，缺单位/深层归属不明的血缘数量留空。

### 验证与证据

完整日志/JSON/截图在代码外 `C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p0/`，不含登录令牌与账号密码。

- Java 原目标回归359条中旧调拨样本7处首次失败；合法样本补明示单位、失败前零写断言修正后，相关3类21条通过，未更改断言让缺单位库存放行。保留 `java-reviewed-recheck.log` 与 `java-transfer-fixture-final.log`；其余已通过同源码证据复用。
- 追溯目标6类40条通过；最终时间事实保护 GraphAssembler 20条复验通过；最终销售出货投影/加工单位2类11条通过。日志 `java-lineage-final.log`、`java-lineage-time-final.log`、`java-trace-sales-frozen.log`。修复测试字段注入前的失败日志保留。
- `FactoryActualMetricsPostgresTest` 在真实 Hibernate/PostgreSQL 会话执行：TEMP 表包含软删旧结单与替代结单、软删输出行，验证成本200而非重复计入、质量90%、工厂/日期/内部WIP排除；1条通过、0跳过。仅 TEMP fixture 写入且事务回滚，未冒充真实业务操作。
- Web 原指标/费用/质量显示目标21条、列表/卡片/抽屉/费用目标20条、追溯页面/显示目标41条均通过，重叠 spec 证据不当作独立新增覆盖。最终 `vue-tsc -b` 与 `npm run build` 退出0；日志 `web-type-final-frozen.log`、`web-build-final-frozen-all.log`。
- Python 正常业务 harness 的主机、身份、路径、续跑守卫3条通过；真实业务与只读数据库最终51项通过：`business-acceptance-final-controls.log` / `business-acceptance.json`。血缘共享来源与实际消耗证据额外2项通过：`graph-business-acceptance.json`。
- 重复确认收料返回409且库存逐行不变；不存在批次返回业务404；B账号跨A查询返回403；空期间为NO_DATA且不造零或快照。A原有指标版本逐行未修改。
- 全任务前后 B 指标条数的首次对照失败保留：原有启动调度器在01:20:02先为B的旧日均产量新增1条快照（Java日志1049-1059；ID `790afa6c-a5b9-4318-b63a-41ca2418b3b4`），早于业务验收请求。纠正验收测量后，B库存/生产仍与原基线一致，业务验收期间B全部指标历史逐行一致。没有删除该快照或掩盖后台写入；不宣称整个运行时对B零写入。
- 两个既有视觉训练任务仍 queued、attempts=0，未训练、未启用模型；原照片、标注与准备状态保留。证据 `vision-queue-readback.json`。
- 最终实际 UI：daily_factory_a 中文补录费用成功后显示122.33元；指标按2026-10-16真实重算并显示来源/历史；盒装追溯显示10kg→20盒、实际客户、已发10盒，不再把原料UUID摘要当操作员。截图 `07-trace-box-final.png`、`08-indicator-recompute-history-final.png`、`08b-indicator-history-table-final.png`、`09-trace-shipment-final.png`；早期05等截图保留为修复前证据。

### 用户体验顺序

账号继续使用 daily_factory_a，入口为工厂日常菜单，与平台对话建厂分开。

1. `http://127.0.0.1:31761/analytics/indicators`：查看实际出成率、生产良品率、完整单位成本和完整生产成本；统计日期选2026-10-16。点击卡片，体验强制重算、中文口径、数据来源和历史表格。其他旧指标不算本轮已验收范围。
2. `http://127.0.0.1:31761/production/batches/7`（散装）或 `/production/batches/8`（盒装）：对照合格产出、含不良总产量、原料消耗、逐道工时与完整成本；“补录设备和其他费用”已走过真实UI。
3. `http://127.0.0.1:31761/production/traceability?batchNumber=PB-PLAN-1792082483014-E2B5C948-83123`：查看供应商/原始收料批次/生产仓来源，向下滚动查看加工真实单位、客户及已发10盒；散装批号 `PB-PLAN-1792082480622-095A7781-80877` 同样可查询。

范围边界：本轮多SKU为两个独立单输出计划；同一计划共用投入的多输出成本/质量分摊仍保持未知，未宣称支持。未验收跨厂调拨血缘、全部历史断边回填、正式质检放行/真实工厂物理验收或财务全链对账；新指标的目标工厂配置已接通，不宣称全新环境自动seed。未执行 CI、发布或生产验收。
