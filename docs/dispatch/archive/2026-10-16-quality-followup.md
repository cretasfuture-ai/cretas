# 异常闭环独立复核与后续接线（仅本地）

P4-QUALITY-FOLLOWUP-20261016；Base 0345a9296a6b5b54a5d863aa905f236147dda86d；协调者root。
沿用codex/trace-analysis-vision-daily-review-20261015、31761/10060/18090/55447私有环境；禁止push/PR/合并/部署、生产/真实销毁/付款退款/凭证过账、视觉训练，禁止SQL业务写入。旧已验收样本和财务流水不重放。

## 复核结论与本批完成条件

P0：召回COMPLETED仅表示收回并隔离，原页面未显示报废与应收待办；废弃列表把状态作为type提交，且关键词未接入后端，容易漏看记录。补只读后续处理进度，修服务端筛选与精确成品批次入口，不改召回历史状态、不新增结案写状态。
P1：退货应收调整被驳回后缺安全重提；从退货到应收流水、凭证及从召回到报废需手工找单。复用现有退货锁、AR调整、附件上传和各列表，增加精确来源跳转及重提（只生成待审批调整，不重复入库）。照片选择直接复用上传URL/PUT工具，不再要求工厂人员手工组织URL。
本单是未收款销售退货的应收贷项；现金退款和会计凭证过账不是本批自动执行范围。无请求标识时不能用“已完成同数量退货”冒充重复请求，否则会破坏合法等量分批回收；不为假设扩大修改普通退货归因。

## 分工与允许范围

P4-FOLLOWUP-BACKEND；Owner transfer_lineage；Base同上：仅Java controller/FactoryRecallController.java、controller/DisposalController.java、dto/quality/FactoryRecallDTO.java、service/quality/FactoryRecallService.java及新增FactoryRecallFollowUpService.java、service/DisposalRecordService.java、repository/DisposalRecordRepository.java及对应Controller/Service真实行为测试和*RepositoryQueryValidationContractTest。不得修改ReturnOrder/ArAp/Voucher仓储或服务、Entity/迁移/Security、Web、ACTIVE或运行时。

P4-FOLLOWUP-WEB；Owner metric_ui；Base同上：仅web-admin/src/api/factoryRecall.ts、views/quality/recalls/index.vue及__tests__/index.spec.ts、views/quality/disposals/list.vue及同目录测试/必要局部证据上传组件；不得修改finance/sales/attachment API共享文件、后端、ACTIVE或运行时。

root唯一写Owner：新ReturnOrderFinancialFollowUpService/Controller/DTO及其测试（复用既有ReturnOrderRepository/EntityManager行锁、ArApService/Repository、Voucher只读查询，不改现有Entity/Repository）；web-admin/src/api/returnFinancialFollowUp.ts及测试、views/sales/returns/detail.vue及测试、views/finance/ar-ap/index.vue和adjustments/list.vue及对应测试；台账/证据/必要目标验证/本地运行时。子代理不提交、不运行Maven/服务/浏览器/数据库、不派孙代理；Web执行者只跑其目标Vitest，最终构建仅root一次。现有工作树其他改动不得回退。

## 冻结接口契约

GET /quality-recalls/{eventId}/follow-up，原quality read/read_write权限，同厂事件先验证，纯读取，无金额/成本/余额/账号：
{recallId,batchId,batchNumber,unit,recallCompleted,inventoryResolved,receivableResolved,completed,issues:[{code,message}],stock:[{finishedGoodsBatchId,batchNumber,origin:'ORIGINAL'|'RETURNED',unit,remainingQuantity,disposalQuantity,evidenceComplete,disposals:[{id,status,quantity}]}],returns:[{returnOrderId,returnNumber,status,quantity,receivableStatus:'PENDING'|'APPROVED'|'REJECTED'|'MISSING'|'NOT_DUE'|'NOT_REQUIRED'|'INCONSISTENT',adjustments:[{id,transactionNumber,approvalStatus}]}]}。
completed只合取上述三项，不宣称现金退款/会计结账。缺失/错厂/多行无稳定来源/数量不守恒不得默认为0或完成。库存证据复用既有审批SCRAP校验；原库存以FREEZE_INVENTORY事实为核对基准，退回以完成退货量为基准。应收只接受同厂RETURN_ORDER来源AR_ADJUSTMENT，金额不在该quality接口输出。新服务复用既有查询，不改财务Repo；需要更改契约先回报root。

GET /disposal-records新增可选status、keyword、finishedGoodsBatchId，保留type原义，0-based分页；先服务端同厂AND精确批次AND筛选后分页，返回total准确，禁止仅过滤当前页或无效上下文回落全表。可扩既有DisposalRecordRepository为JpaSpecificationExecutor并真实JPA测试；必要查询仅此Repo范围。

Web召回页读取follow-up，保留请求/批次/事件反序保护，中文显示收回/库存/应收三项；报废入口/记录跳转 /quality/disposals?finishedGoodsBatchId=...&disposalId=...&recallId=...&batchId=...；申请选择仅当前可用精确FG，已耗尽不得新建。应收入口 /finance/ar-ap?returnOrderId=...，待审批可 /finance/adjustments?returnOrderId=...。后续页面自己验证来源，不能信路由文案。

root财务：GET /return-orders/{id}/financial-follow-up返回已验证returnOrderId/returnNumber和同厂该来源adjustments（既有ArApTransaction保留价格脱敏）、voucher只读状态；POST /return-orders/{id}/resubmit-adjustment仅已完成实物销售退货，锁退货行后检查已批准拒绝、一个待审复用、驳回/缺失才重建PENDING；同来源金额必须等于负退货金额、操作者来自JWT、保留原驳回流水和四眼原则。禁止任何库存/退货再次complete、付款退款或凭证过账。

## 验证

按最终改动跑目标Java行为/控制器、真实JPA筛选启动与多工厂/分页/状态阴阳性；财务驳回→重提只新增一次、已有批准/异厂/未完成拒绝，保留旧驳回记录；Web mounted行为覆盖三状态、精确入口、反序响应、上下文过滤和照片上传失败/成功。最终一次Web构建。仅源码读型闸必须*ContractTest。
真实UI/API/只读PG：已完成batch9进度完整；未完成旧样本只读显示待办；正常业务API准备新本地测试单验证驳回→重提→独立财务审批，不重复库存，保护所有旧样本/B工厂/视觉队列。证据写Documents/cretas-connect-20261015/factory-data-p4。实现、测试、commit、用户体验与部署分开报告。

## 本地验收回执（2026-10-16）

已复用原退货、AR调整审批、附件签名上传、成品SCRAP和查询页面；新增的是来源跟进投影、精确跳转与驳回应收重提。没有改Entity/迁移/Security或新建替代业务引擎。metric_ui在质量Web完成后接手root原金融Web文件的确认防重/上下文保护与测试，root负责最终整合，均已冻结。

独立风险复核发现并修复：隔离未报废假完成、错误客户/金额应收假完成、多行历史RTN顺序猜测、未执行冻结动作被当基准、RTN再次发货/预留、重提行锁版本窗口、驳回历史与有效调整的计数。复用ReturnOrderRowLock原生FOR UPDATE；两个真实Hibernate事务覆盖陈旧version和并发只一笔PENDING。新Controller向原有价格脱敏Advice提交展示副本，真实MVC验证金额隐藏且不污染原实体。只读quality跟进不输出金额/余额/成本。

Java17类138项目标验证最终均通过，包含实际Service→JpaSpecificationExecutor真实JPA查询、控制器、退货财务前置、报废余量/跨厂和原召回回归。初次编译/测试失败及修复日志保留；只复验失败或变更切片，未变类复用已通过报告；未跑托管CI。Web原47项通过，最终报损增加上传失败、审批后余量刷新与确认期间切换批次不写入；最终数量/构建日志和源码哈希以外部validation-receipt.json为准。换行规范化只改新增行CR，不改任何语义行。

真实业务准备新增batch10 PB-PLAN-1792123566436-86C9C0C7-66632：20盒生产、出货10、独立质检HOLD复核、召回4 RECALL-20261016-B4CF46B9、退货RT-SAL-20261016-0007 e7f6cc7a-3e94-4aac-a136-b7d94173bfc8按原审批流收回10盒并隔离。准备不足包材被409拦截后，复用原生产仓8个余料通过正常领料转入；未补写库存或绕过前置条件。

实际浏览器：daily_finance_a驳回AR-20261016-3382；daily_factory_a在原退货页填写原因重提AR-20261016-3205，只新增一笔PENDING、保留原REJECTED，余额690、库存/退货单均不变；两个并发重复请求复用该笔，提交人自行审批403；daily_finance_a（工厂用户ID1）独立批准，新调整-150，余额690→540。quality角色读/重提403，异厂actor/实体403，旧已批准调整重提409。

浏览器再走原库存FG a291009b-8837-4fea-bbc6-81c8bba8f2ad及退回FG 898dcf52-ddbb-499b-afa5-d1b341509a96各10盒报废申请/批准（记录3/4），分别写严格SCRAP记录并归零。部分处置时仍待办，全部处置后recallCompleted/inventoryResolved/receivableResolved/completed均true。新入口精确定位记录3/4；耗尽时禁止新建；已批准筛选不显示待审批记录。UI现场发现批准后eligible未刷新，已修并补回归；确认期间切换上下文不发送旧批次审批。

旧批次7/8仍明确库存及应收待办；9保持完成。只读PG比对17组既有记录/快照均不变，原AR和报废1/2保留；B工厂旧指标行保留，启动新增派生版本单独按真实启动日志解释；视觉任务未改动。无真实客户消息、实物销毁、现金退款、凭证过账；新退货凭证V-2026-0014仍DRAFT。

边界：此样本验证未收款销售退货的应收冲减，不宣称现金退款或会计关账验收；历史多行缺稳定来源明确待人工核对。附件为本地隔离库存页面截图，非真实销毁照片；签名PUT→GET字节相等、前端选图/失败处理目标测试通过，原生文件选择框尚未做浏览器自动化点击验收。保留既有URL入口完成演练。证据/17张截图/validation与最终业务读回在外部factory-data-p4，账户凭据不入Git。

最终Web50项相关测试通过（25个文件；报损最终7项替换初批4项），最终Vite构建通过；最后确认上下文保护冻结后再单独vue-tsc -b通过。必要门禁与diff/ACTIVE检查通过。已成功的Java/JPA及业务读回未重复执行。

分支维持codex/trace-analysis-vision-daily-review-20261015，本地10060/18090/31761测试。只本地提交；不push、不PR、不合并、不部署，用户体验待验。
