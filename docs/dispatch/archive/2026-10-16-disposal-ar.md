# 隔离成品处置与应收调整（仅本地演练）

P3-DISPOSAL-AR-20261016；Base 2132008c1cb510fd12c43c5f429dc2034190e812；协调者root。
沿用codex/trace-analysis-vision-daily-review-20261015及10060/18090/31761/55447私有运行时；禁止push/PR/合并/部署、生产写入、真实销毁/客户消息/付款退款和视觉任务。

## 目标与当前事实

- VISION-ENTRY-A batch9 / PB-PLAN-1792118556113-78C78517-56267，原成品20盒已发10盒、隔离10盒，退货RT-SAL-20261016-0006另有隔离10盒。召回3已结案。复用废弃申请与审批，演练各10盒报废，库存减至零可用，保留原生产、出货、退货和召回历史。
- 退货生成AR_ADJUSTMENT=-150，sourceType RETURN_ORDER，sourceId 087a1a65-03de-4219-9e50-3464d68d6c9d，经办93001。客户当前余额需实时读回；UI显示提交时690。复用独立财务审批，预期仅该笔调整使余额减少150；不能把应收调整当退款或凭证过账。
- /quality/disposals已支持精确成品批次和原单位盒；/finance/adjustments已有四眼原则，经办不能自审。准备同厂独立finance_manager本地测试账号，保留原权限规则。
- 已发现成品报废actualLoss取unitPrice而不是unitCost，且退货入库未复制源unitCost。先修成本传导与计损，禁止按售价或估价冒充实际损失。

## 唯一写Owner与锁定范围

P3-DISPOSAL-COST-20261016；Base同上；Owner transfer_lineage。允许修改：

- service/DisposalRecordService.java；service/inventory/impl/ReturnOrderServiceImpl.java；service/quality/FactoryRecallService.java。
- 新增service/inventory/ReturnedGoodsCostResolver.java（若确需，负责已完成退货历史成本的精确同厂/同SKU/同单位来源校验）。
- repository/inventory/ReturnOrderRepository.java，仅新增必要的同factoryId+returnNumber派生查询，禁止改其他现有查询。
- 对应DisposalFinishedGoodsBalanceTest、DisposalRecordServiceCrossTenantTest、ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest、FactoryRecallServiceContractTest及新成本行为测试、ReturnedGoodsCostRepositoryQueryValidationContractTest。

禁止改Entity/迁移/Security/凭证会计分录、其他Repository、ACTIVE、运行时、Web、其他任务scope。不运行Maven/编译或浏览器、不写库、不提交；不派孙代理。最终同一target编译/目标验证由root停止本任务Java/Python后串行执行。

root独立负责web-admin/src/views/quality/disposals/list.vue的已有详情回读展示：实际损失、审批人及时间；复用现有格式化工具，不增加另一套报废流程。该文件与transfer_lineage范围不重叠。

实现约束：未来退货复制真实源unitCost（缺失保留未知，不能用售价）；历史RTN缺成本时只接受可证明的已完成实物销售退货、精确行索引/批号/数量/SKU/原单位和源批次归属，缺证据明确拒绝。若补齐历史成本，应与报废同事务、精确锁定本厂目标，并在既有库存调整日志中留来源、原成本、新成本、单位与单据引用；不静默修改其他历史批次。成品报废必须用成本计损，库存未知成本不得回退售价/估损。

报废后的召回查询必须保留实际收回历史；如原实现直接要求RTN现存量等于退货量，只能结合该精确RTN的SCRAP调整日志证明收到后处置的数量守恒，不能把未收到/缺失库存伪装为已收回，不自动重新结案或改原生产计量。

## 验收命令与完成条件

- 必跑DisposalFinishedGoodsBalanceTest、DisposalRecordServiceCrossTenantTest、DisposalControllerApproveTest、DisposalWorkflowIntegrationTest、ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest、ReturnOrderFinanceApprovalGateTest、FactoryRecallServiceContractTest、新成本测试及真实JPA ReturnedGoodsCostRepositoryQueryValidationTest；仅源码读取型测试必须命中*ContractTest命名。
- 完整UI：daily_factory_a申请两条报废，各10盒，使用明确标注本地演练的可访问证据URL；按既有角色规则审批扣库；独立财务账号仅审批本次150元调整，再切回daily_factory_a。
- 只读PG/API读回：两批可用0、状态DEPLETED、各一条-10 SCRAP、计损按真实成本；生产/发货数不改，召回仍显示收回10盒且已结案。AR客户实时余额减少150、目标流水APPROVED/实际balanceAfter一致、其他待审调整不变；无新增收付款或重复退货凭证。
- 阴性：超量/重复报废审批/未知成本/跨厂/财务自审拒绝；已通过且内容未变的旧证据直接复用，仅新增风险相关复验。
- 证据：Documents/cretas-connect-20261015/factory-data-p2/self-review-20261016-01/post-disposition。凭证真值只读主私有manifest，不进tracked文件。最终保留独立分支待用户体验。

## 最终本地结果（2026-10-16）

实现已冻结，唯一写Owner归root。复用原废弃申请、审批、成品调整日志及应收调整审批，无Entity/迁移/Security/凭证分录改动。

- 页面创建并批准报废记录1、2，分别关联batch9原库存与RT-SAL-20261016-0006退回库存，各10盒。原成品库存计数由produced20/shipped10变为produced10/shipped10；退回成品由produced10/shipped0变为0/0。两者可用量0、DEPLETED，各有且仅有一条-10 SCRAP日志，申请与审批身份来自实际登录态。
- 成本按unitCost=6.4165计算，各64.17元，合计128.34元。每张单分别四舍五入到分，所以比原生产成本128.33多0.01元；保留该分单舍入事实，不改写原生产成本或计量。历史单行退货缺失成本仅按同厂已完成实物退货与原批精确回溯，并与审批扣库同事务留原值、新值、来源批号和退货行；历史多行无持久行序、来源不符、未知或负成本均拒绝。
- SCRAP只有逐条匹配同厂、同成品、同数量的唯一APPROVED报废单才作为召回收回后处置证据；核验审批人与日志操作人、审批时间、连续数量链和当前库存终点，拒绝伪造来源、重复单据、正向调整、缺证据及链尾不符。
- 目标-150元AR_ADJUSTMENT由原经办以外的本厂finance_manager实际登录审批：APPROVED，实际balanceAfter=540，客户余额690→540。只为本地新财务角色通过既有工厂角色模块覆盖接口开启finance=rw/dashboard=r；全局角色矩阵与其他角色未改。其余三笔待审调整与A/B其他应收应付流水、客户余额不变；无收付款、无真实退款；原退货凭证仍DRAFT，不重复生成或过账。
- 召回3仍COMPLETED，出货10盒/收回10盒/待收0；原生产、出货、销售与退货单全部逐字段保持。旧样本7/8、其他库存/质检/退货/召回/审批及视觉任务队列快照保持；B原指标版本保持，服务启动自动新增1条派生指标版本由启动日志与计算值解释，未冒称全库零新增。
- 实际UI确认废弃详情显示64.17元、审批人、时间；应收概览540元，流水AR-20261016-7847显示-150元/540元/已审批。演练证据为仅本地对象存储中的隔离库存截图，备注明确非真实现场销毁照片，无真实物理处置。

验证：第一轮90测2失败证据保留（负成本先写回、审计断言键名），修正后同选择器90测全部通过；仅新增两条召回证据阴阳性后该类19测通过，合计92个独立后端用例，无失败/错误/跳过。真实@DataJpaTest持久化同退货号的两个工厂并执行正反查询；本地PG真实退货成本解析亦在UI审批时执行成功。Web相关4测、最终Vite构建通过。源码/编译类哈希、命令、退出码、完整日志与JUnit在外部validation-receipt.json；无hosted CI或迁移通过声明。

业务证据：ar-approved-readback.json、disposal-readback.json、before-disposal-controls.json、repeat-approval-controls.json及05–10截图。超量/跨厂申请拒绝且无状态变化；两次重复报废审批409，重复/本人应收审批拒绝，库存和AR均未再次改变。独立风险复核无剩余源码/测试设计阻断。用户体验待进行；保留本地独立分支，禁止push/PR/合并/部署，生产业务写入0。
