# 日常异常复核、销售退货与批次召回

P2-QUALITY-RECALL-20261016；Base 7574545ec7a747e085e548a83263fc4ea0c10d6c；root唯一协调者与台账写Owner。
沿用本分支/私有本地运行时与daily_factory_a。用户明确授权本地完整操作、发现问题直接修；不push/PR/合并/部署，不生产写入、不实际通知客户/监管、不训练或改动视觉真值。

## 当前事实与范围

处置执行/审批、库存隔离/放行、销售退货与财审/成品隔离入库均已有服务。RecallEvent/RecallAction只实现事件台账和部分工具行动，无批次范围/退货/结案门禁。复用这些实体、Repository和BusinessLink关联字符串UUID，不复制现有业务引擎，不为此新建表或伪造通知/监管完成。

- P2-RECALL-SERVICE-20261016，Owner transfer_lineage：新增service/quality/FactoryRecallService、controller/FactoryRecallController和dto/quality/FactoryRecallDTO及目标行为测试；复用RecallEvent/Action/BusinessLink、TraceabilityService真实出货投影、现有按出货单号读取和ReturnOrderService。当前切片是单个生产批次的定量召回，未证明归属的历史或多批输出不猜。root停Java后统一Maven，代理不运行共享target。
- root：CanvasFoodSafetyController只阻止其通用状态编辑绕过新批次召回结案门禁；QualityDispositionInventoryService放行时调用新召回active守卫，避免隔离中的召回被旧放行路径解除；对应目标行为测试。共享旧资讯台账/无召回路径保持兼容，不改Entity/Repository/迁移。
- P2-RETURN-UNIT-20261016，Owner cost_wiring；Base同上。仅ReturnOrderServiceImpl销售实物退货单位/真实批次投影和必要原有同目录目标测试；复用既有FG/ProductType/Unit服务，禁止改Entity/Repository/迁移/财务状态机/主控制器/ACTIVE/运行时。已发现原实现一律写“件”，本轮kg/盒必须保留实际单位。目标ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest及新增行为测试，root停Java后串行Maven。
- P2-RECALL-UI-20261016，Owner metric_ui；Base同上。仅新增web-admin/src/api/factoryRecall.ts、views/quality/recalls/index.vue及目标行为测试，必要router/index.ts与components/layout/menuConfig.ts日常质量入口；quality/disposition/list.vue当前用户ID、按batchId精确批次上下文/复用审批与中文说明，已有目标测试。root独占traceability/index.vue、quality/inspections/list.vue入口接线及其测试，双方不得重叠。接口见下节；代理不写ACTIVE、不Maven/构建/浏览器/运行时，Web窄测试可用。
- root：scripts/e2e/daily-review正常业务验收及必要守卫；不SQL写业务事实。真实UI、API、只读PG/队列、库存/财审/退货/召回计量与跨厂/重复/缺数对照；保留旧P0/P1证据及样本，不重放原已完成写入。
- root补充：sales/returns/detail.vue及同目录新目标测试，复用原审批/财审/收货动作，只加经真实召回详情核对的批号/单位与返回入口，避免“有食物/完成出货”等混淆；不新建退货表单或新状态机。cost_wiring统一既有退货写状态与异步凭证的行锁，不改Entity/Repository。
- root补充：TraceabilityServiceImpl及原同名行为测试，修复拆批发货时旧item仅记录首批导致后续allocation漏投影；按当前厂、精确FG和SKU核allocation，不改Repository。原普通销售退货的批次派生/单位一致性由cost_wiring在原服务范围补齐，避免召回正例掩盖旧入口回归。
- root复核修正：旧处置放行按计划释放所有成品，可能越过本批召回守卫释放同计划其他批次；已限定同厂、同原批号、同SKU，保留计划查询并补兄弟批次/SKU阴性，退货RTN不随原批放行。
- 实际UI修正：处置watch缺首次immediate导致初次入口空白，已修并补真实挂载/反序用例。审批沿用禁止自审，daily_factory_a申请后由已有daily_quality_a（93003）复核，再回daily_factory_a办理退货；不伪造或借传用户ID。退货详情隐藏内部关联标识、中文解释应收调整，退货“已完成”与召回“已结案”区分。
- 实际PG修正scope：P2盒装快速创建→提交撞上ReturnOrderVoucherListener异步vflag更新，409且事务回滚，保留失败证据。cost_wiring追加该listener及原ReturnOrder写状态方法同一事务行锁、对应行为/财审回归；不改凭证会计语义/Repository/Entity/迁移。旧无源单手工退货保留兼容，但不贡献召回收回量；仅退款也不贡献实物收回量。
- 最终并发实现：共享ReturnOrderRowLock先参数化native SQL锁定同厂、未删除退货行，再plain refresh读取最新版本；Service的@Transactional与listener的REQUIRES_NEW复用该顺序，避开Hibernate follow-on锁先读旧版本的窗口。凭证生成失败先回滚生成事务，再用独立事务持久化FAILED；REJECTED跳过生成。真实JPA测试覆盖旧版本窗口与失败后FAILED落库；最终6组41项通过，保留两次实际PG失败证据，另以全新草稿无延时提交检验修复。

Entity/Repository/Query/迁移若确需变更先扩scope并保留真实JPA/PG门禁；当前优先复用既有方法。共享Maven target串行，编译前停止本任务Java/Python，保留Web/PG及他人18091；冻结后目标Java/Web测试、类型与构建、真实整链验收。

## 日常召回接口（当前厂路径）

`/api/mobile/{factoryId}/quality-recalls`：GET `?batchId=`列表；GET `/preview?batchId=`；POST `{batchId,reason}`发起并隔离本批剩余成品（同批在办幂等）；GET `/{id}`详情；POST `/{id}/customer-ack` `{shipmentId,note}`人工记录联系/客户回复凭据（不发送消息）；POST `/{id}/returns` `{shipmentId,quantity,reason}`由真实出货派生销售退货草稿（复用原财审/入库）；POST `/{id}/close` `{note}`仅全部实际出货已通过COMPLETED实物退货并隔离入库、客户回执齐全、原批库存隔离才可完成。未到齐返回具体剩余数。服务端核用户、厂/批/单归属，客户端不指定客户/价格/源单。

预览/详情：`batchId,batchNumber,productName,unit,remainingInventory,shipments[]`；shipment含`shipmentId,shipmentNumber,customerName,quantity,unit,returnedQuantity,pendingReturnQuantity,remainingReturnQuantity,acknowledged,returnOrders[]`；退货摘要`id,returnNumber,status,quantity`。详情额外`id,eventCode,status,reason,completedAt,canClose,blockers[]`。数量不跨单位加总；无实际出货、无可定量证据不显示已完成，不能手填“已回收”。

## UX Flow Analysis

### 用户画像

质量负责人和仓管，熟悉产品/批号、不熟悉模型和数据库ID。围绕已发现异常批次确认影响、联系记录、收回和结案。

### 用户旅程

|步骤|用户看到|操作|期望结果|
|---|---|---|---|
|1|批号/品名与异常记录|进入异常复核|原处置界面锁定本批，理由中文，审批按原规则|
|2|已发客户/数量/剩余库存|发起召回|真实隔离剩余库存，保留事件与批次关联|
|3|每单已收回/待收回/可登记数量|记录联系依据、登记实际退货量|自动带客户/源订单/产品/批次，进入原销售退货审批|
|4|退货详情的当前下一步|提交→业务审批→财审→收货完成|真实创建隔离成品批次，不自动放行，不重复入库|
|5|全部回收与回执是否齐全|确认结案|服务端验证实际单据/库存后完成，缺项明确提示|

### 摩擦点清单

|#|摩擦点|严重性|规则|
|---|---|---|---|
|F1|手填UUID和英文状态|HIGH|防呆2/3|
|F2|不知道还可以退多少|HIGH|防呆1|
|F3|重复登记/重复入库|HIGH|防呆4|
|F4|事件台账冒充冻结或结案|HIGH|真实证据/防呆5|
|F5|审批后不知道去哪里|MED|防呆5/错误恢复|

### 每个摩擦点的设计回应

- F1：只选择真实出货行、中文动作/状态，责任人从当前账号取；隐藏内部ID输入。
- F2：显示已发/已回/待处理/还可登记，数值max与服务端上限一致，不跨SKU或单位相加。
- F3：同批在办召回复用、待办退货计入上限、现有退货状态机保护重复完成；服务锁定批次/召回事务。
- F4：真实库存和COMPLETED退货回读构成结案条件，通知仅人工凭据，不发短信/邮件，不伪造监管上报。
- F5：每步一个主动作，链接原异常处置/退货详情/追溯；错误保留输入并提示补哪个步骤。复用项目Vue/ElementPlus设计规范。

## 本地验收约束

- 仅VISION-ENTRY-A的既有合成批次7（散装kg）/8（500g盒装），UI daily_factory_a。保留P0/P1正常经营与异常隔离事实；不改B厂、原生产计量和视觉队列。
- 首批走真实页面异常HOLD复核与审批、召回发起、联系凭据、两次2kg退货、原单提交/业务审批/财审/收货、结案；盒装10盒再走同一后端业务流程核单位。
- 缺回执/未收齐/待审批退货不能结案；超量/过多小数/重复完成/通用事件编辑/跨厂写入须拒绝；并发相同退货请求复用同一草稿。
- 只读PG核原批隔离、RTN批次实际数量/单位、DecisionAuditLog真实审批人、RecallAction/BusinessLink。退货COMPLETED证明系统收货与隔离入库记录，不代表现实物流、客户实际通知、资金已退款或监管报告。
- 必要Java目标：FactoryRecallServiceContractTest、CanvasFoodSafetyControllerTest、QualityDispositionInventoryServiceTest、ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest、ReturnOrderFinanceApprovalGateTest、ReturnOrderPurchaseReturnDefectiveTest、TraceabilityServiceImplTest、TraceabilityServiceImplLineageTest、FoodSafetyGatesContractTest、LocalOssAcceptanceContractTest。Web目标测试、vue-tsc和vite构建；Python保护测试复用原helper。
- 证据目录位于本机Documents/cretas-connect-20261015/factory-data-p2；凭证始终私有，不写入tracked文件。当前未修改Entity/Repository/Query或迁移，不宣称此轮新增或通过JPA变更门禁。

## 本地完成回执（2026-10-16）

- daily_factory_a真实页面发起散装异常HOLD，自审被拒；已有daily_quality_a独立审批后切回daily_factory_a，完成召回、客户联系演练凭据、两次2kg实物退货、业务/财务审批、隔离收货与结案。盒装批次复用相同业务API完成10盒退货，页面回读已完成、单位盒、源订单和金额；未调用生产或外部消息发送。
- 原批剩余4kg/10盒保持DEFECTIVE，三张实物退货单真实创建同单位RTN隔离库存；原生产计量、原质检样本、已发4kg/10盒历史及视觉队列均保留。原出成率90%、生产良品率90%、单位实际成本13.9256元/kg、实际总成本501.32元未被退货冲写。
- 未收齐、缺联系依据、超量、5位小数、重复完成、绕过通用事件编辑、跨厂读写阴性均按预期拒绝；并发同量退货请求复用草稿。全新并发控制单创建后无延时提交成功，PG读回SUBMITTED+CREATED，再正常驳回并读回单据REJECTED/凭证VOID，无审批、退款或库存写入。
- B厂原指标历史逐行未变，但本任务10:00服务启动触发既有指标scheduler追加1条40kg日产量版本。原始全量相等失败回执保留，未重置baseline；新增行按真实启动日志的factory/code/期间/数值/时间区间逐一归因。额外保护测试拒绝异厂、错值、错时间、错指标、错期间、缺日志与原记录篡改，不能无依据放过新增行。
- Java目标共107项通过（其中最终退货/凭证6组41项，真实JPA事务窗口2项）；Web范围55项通过；Python17项及7个子用例通过；Web类型检查与最终构建单独保存结果。未修改的已通过切片复用原日志，失败与中间修正证据均保留；不宣称托管CI或生产验收。
- 实际页面又修正首次处置空白、异步旧回包、结案后列表状态未刷新；将“服务端结案”改为“核对与结案”，结案后禁用重复按钮。浏览器仍为daily_factory_a，最终停在quality/recalls?batchId=8，可点退货单RT-SAL-20261016-0003或返回本批追溯/质检/处置。最终截图09-boxed-recall-final.png。
- 独立只读风险审查未见本范围剩余代码阻断；数据库验收38项通过，详见recall-acceptance.json；完整哈希/测试报告见本机final-receipt.json。后台启动追加的B指标历史作为已归因的观察保留，不表述为“B厂整表零新增”。
- 当前仅本地独立分支待用户体验，不push、不PR、不合并、不部署；COMPLETED表示系统演练单据与隔离入库记录，未发生真实客户通知、物流回收、资金结算、监管报告、训练或模型激活。旧无源单手工退货保留，不能计入该批次召回回收量；缺乏定量批次归属证据的历史数据明确拒绝猜配。
