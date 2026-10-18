# 退货现金退款、凭证与多行来源接线（仅本地）

P5-RETURN-CASH-VOUCHER-20261016；Base088028e116d7305a06044593320bd0f2933947b4；root唯一协调者；沿用codex/trace-analysis-vision-daily-review-20261015工作树。

用户本轮明确授权：现金退款、凭证过账、多行退货来源接通并在daily_factory_a本地模拟；发现问题直接修，已有功能直接复用，禁止合并部署。P4的本地不退款/不凭证过账限制不覆盖这次新授权，但任何真实外部支付渠道、生产、真实客户消息/实物动作仍不在本批范围。无push/PR/merge/deploy。老批次7/8/9/10及B工厂、视觉队列保留，准备新样本；不得用SQL补造业务数据。

完成条件：已收款销售退货的应收冲减与现金退回明确分开，复用既有退款/付款申请、审批、资金流水，不重复冲减或重复退款；凭证复用已有生成/过账页面和期间/借贷校验，按原退货精确查看并读回；多行退货以稳定行ID/实际入库来源关联，不用列表顺序、同SKU/数量猜来源，历史无可靠证据明确待核对。中文简单操作。API权限、工厂隔离、重复/并发、无权限和不足额阴性控制，真实UI与只读PG完整回读。

当前只读调查：cost_wiring仅现金退款路径；transfer_lineage仅多行来源路径；root仅凭证与现有follow-up入口。各代理不写文件、不操作runtime/DB/browser/Maven、不派子代理。代码分工将在接口和最小scope确认后由root登记。root目前仅台账/本地证据Owner。

P5-RETURN-LINEAGE；Owner transfer_lineage；Base同上。允许修改：entity/inventory/ReturnOrderItem.java与FinishedGoodsBatch.java（仅稳定来源字段）；repository/inventory/FinishedGoodsBatchRepository.java（同厂退货行查询）；service/inventory/impl/ReturnOrderServiceImpl.java（验证/持久来源与RTN关联）、service/inventory/ReturnedGoodsCostResolver.java、service/quality/FactoryRecallService.java与FactoryRecallFollowUpService.java（来源读取）；新增局部ReturnOrderLineage DTO/Service/Controller；对应行为测试、真实*RepositoryQueryValidationContractTest；仅一份新additive Flyway migration，版本先回报root查重。禁止现金/付款/Voucher服务、Web、ACTIVE、运行时/DB/Maven/commit。验收：新多行/同SKU多行按itemId关联，重排不改变来源；跨厂、错SKU/单位、重复关联拒绝；旧单行兼容，历史多行未知不猜；JPA持久化/查询真实执行。root统一编译和本地schema验收。

运行时10060/18090/31761，PG55447 vision_entry_probe_20261015；对象存储18391仅本地模拟。Java/Python由既有runtime.py管理，任何Maven编译前必须先停这两个自有服务（运行target/classes），不影响Web/PG或其他平台任务。目标Java/JPA串行，Web最终冻结后一次必要构建，已通过未变切片直接复用。新增Entity/Repository/查询必须真实JPA门禁和独立风险复核。

P5-RETURN-REFUND；Owner cost_wiring；Base同上。scope：PaymentRequest实体/Repository；ArApTransactionType枚举；新增退货来源专用ReturnRefundService/Controller/DTO、ReturnAdjustmentEvidence纯证据校验；ReturnOrderFinancialFollowUpService/DTO（提取已有应收证据校验并保留契约）；一份additive Flyway migration（版本先协调）；对应行为/真实JPA测试。不得启用legacy payment request的410写入口，不改VoucherService、PaymentRecord收款服务、Web、台账、其他人的lineage文件；不运行Maven/runtime/DB/browser、不提交。复用付款申请实体及状态，不新建重复审批引擎。仅本地人工登记已退款，无外部支付渠道。新入口严格绑定退货、原销售单、同厂客户、已批准准确应收冲减和真实已核销收款；应收退款为正向结清客户贷方余额，不重复负向冲减。root负责统一构建、migration验证与真实业务验收。

root追加scope：VoucherServiceImpl、VoucherRepository及真实查询门禁、退货来源过账校验、新测试；本地场景/证据脚本及最终核验。Web待接口冻结再登记唯一Owner。凭证必须非空且借贷平衡，退货必须实物完成且应收审批准确；退款凭证仅已登记实际退款可过账，复用现有现金凭证生成与现金流量表，不伪造供应商付款。

验收前补接实际资金流水展示：cost_wiring仅ReturnRefundDTO/Service的RequestView增加ledger只读摘要，复用已生成AR_REFUND交易，严格同厂/来源/请求/客户/金额核对；PriceSensitive金额字段不得带活Entity。metric_ui仅退货详情显示该摘要、混合单位数量，并给finance/ar-ap/index.vue既有类型映射增加AR_REFUND中文（及局部挂载测试）。其余范围不扩展、不新建账本或付款引擎。root统一复验受影响切片。

真实UI发现财务角色被既有销售路径白名单阻断；P5-RETURN-UI追加scope：router/index.ts仅finance父路由下新增隐藏return-refunds/:id，复用SalesReturn详情组件并用finance模块；详情watch允许该路由；finance/ar-ap与finance/adjustments原退货链接改到该财务入口，对应route/挂载测试。绝不修改guards.ts角色白名单、不赋予sales权限、不扩大任何API权限；按钮仍按原角色能力控制，后端同厂和来源校验保持。此项由metric_ui唯一写，root/风险审查验证真实daily_finance_a入口、无销售写权限对照。

root实操末端小修scope：退款表单内显示缺凭据/说明错误，避免错误藏于弹窗背后；api/voucher.ts既有VoucherType/中文映射补CASH_RECEIPT/CASH_PAYMENT，凭证页过账按钮去除POSTED术语。仅修已观察到的页面文案和反馈，不改变状态机。

P5-RETURN-UI；Owner metric_ui；Base同上。scope：web-admin/src/api/inventory.ts、views/sales/returns/detail.vue（已核实真实挂载路径，替代原误写inventory路径）、views/finance/voucher/VoucherDetail.vue及对应挂载integration测试；新增局部组件/局部API可用，不改路由、菜单、共享client、其他页面或生成类型；接口按本台账及root消息冻结。复用退货详情既有财务follow-up与凭证详情。照片/标注/建厂保持不动。root统一最终Web构建；代理只跑对应Vitest并保存本地日志，不操作浏览器/runtime/DB/git/台账，不派子代理。金额必须保持已有price权限脱敏，异步操作锁定factory/route/actor，旧响应不得污染当前页面；中文可操作错误，缺证据禁止退款/过账。

## 本地业务验收（2026-10-16）

复用既有收款、付款申请状态机、退货完成、应收调整审批、现金凭证生成/过账与现金流量表，没有另造付款/审批/台账引擎。daily_factory_a发起，daily_finance_a独立财务审批和登记；不使用平台账号代替工厂身份。新增隐藏财务退货入口复用同一详情组件，不扩大sales权限。

- 新样本RT-SAL-20261016-0008（a973937d-147c-4817-8591-aebfc1796aa1），原销售210元。三行按盒装3盒、散装1kg、另一盒装批次2盒重排，105元；每行持久化原成品批次及退回库存itemId关联，两个同SKU不同来源未混淆。
- UI完成105元应收冲减审批、210元原收款核销、105元现金退款申请/提交/独立审批/人工登记。缺付款凭据在弹窗内明确阻断，到账凭据P5-LOCAL-REFUND-105-001；退款登记是正向AR_REFUND结清贷方余额，未二次冲减销售额。
- UI完成销售V-2026-0016、退货0017、收款0018、退款0019四张凭证过账；API/PG逐张读回POSTED且非空、借贷平衡。主场景现金流105元、客户余额恢复540元。
- 另建30元退货做并发控制：同时申请返回同一请求；同时登记退款200/409，只有一笔PAID、一条AR_REFUND和一张凭证；同时过账200/409。跨退货复用退款凭据409且事务未留下半完成数据。仅本批新采购凭证过账/作废竞争最终REVERSED，唯一冲销凭证POSTED、逐科目净额为零。
- 最终现金流量表UI/API/PG一致：收款210元，退款135元，净现金75元；客户余额540元。旧草稿凭证仍未计入，未操作旧批次财务。
- 阴性控制：未收款/超额/本人审批/未批准付款/重复付款拒绝；B厂读取和质量人员财务读写403；legacy付款写入口保持410。没有获得财务模块权限的账号不进入价格投影，未将该403称作实际金额脱敏验收。
- 修复财务别名403入口、无销售权限收货按钮和返回误跳销售列表；原权限守卫未修改。独立风险复核最终PASS；原工厂/单据/操作者异步上下文保护保留。

本地证据目录：C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p5。business-acceptance-final.json、readback-final.json、concurrency-controls.json、cash-flow-final.json与17张阶段截图可复查；protected-previous-readback.json验证23组基线，旧7/8/9/10批次、B厂及视觉任务保留；旧多行来源没有猜测回填。

目标验证：Java20类145测试、其中3个真实JPA Context/query/native-lock门禁通过（沿用最终源码未变的XML及日志）；Web7文件44测试的最终覆盖通过（6文件37测试后，仅重跑新增/变更的退货挂载与召回上下文2文件15测试，不重复计算重叠8测试）。真实PostgreSQL Flyway新库baseline及62→63/64升级两场景通过，outOfOrder=false/validateOnMigrate=true；DDL仅施加本地私有测试库，业务数据由正常API/UI产生。完整Web类型检查与构建结果见validation-receipt.json及web-p5-final-build-check.log，不以目标单测替代构建。

体验入口：daily_factory_a，http://127.0.0.1:31761/sales/returns/a973937d-147c-4817-8591-aebfc1796aa1 。依次查看应收处理、现金退款/资金流水、过账凭证、多行来源。财务角色可经/finance/return-refunds/同ID复用详情。

边界：退款为人工登记的本地模拟，没有外部支付调用；每个退货单仅允许一笔全额可退申请，不提供分次退款；历史多行来源无可靠证据时明确未知。未运行托管CI、未做生产验收或生产迁移；无push/PR/merge/deploy，分支留待用户体验。
