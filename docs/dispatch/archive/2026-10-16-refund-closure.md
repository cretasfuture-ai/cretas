# 退款、凭证与多行来源的后续闭环

任务P6-REFUND-CLOSURE-20261016；Base 6f039b31a77d7e83d1f43a2a7868a11096e8ab52；root唯一协调者，复用C:\Users\Steve\cretas-analysis-provenance-20261015、codex/trace-analysis-vision-daily-review-20261015。P5已完成本地实现与体验，记录留在2026-10-16-return-cash-voucher.md，未合并部署；本轮接管同一范围。

授权：从第三方角度检查并按优先级补接现金退款、凭证过账、多行退货来源，已有直接复用。仅本地独立分支测试、模拟资金；禁止push/PR/merge/deploy、生产数据写入、真实支付渠道或外部通知，禁止SQL补造业务数据。保留已体验的105/30元退款及旧7/8/9/10批次、B工厂、视觉任务。

第一切片只读调查，完成条件是定位可复现的业务或接线缺口，按数据/资金一致性优先于导航与易用性排序，不重建已可用引擎。root调查页面之间真实跳转和展示；p0_risk_review只读检查凭证过账/作废与退款台账证据一致性；transfer_lineage只读检查同SKU多行退货来源与出货累计上限。禁止代理操作DB/browser/runtime/Maven/git或修改文件/台账，不派子代理；回传任务ID、Base、证据路径/行号、影响、最小复用方案及目标验收命令。发现同一原子问题不重复调查。

运行时10060/18090/31761，PG55447私有vision_entry_probe_20261015；源码target/classes由runtime.py运行，任何Maven编译前先停本任务Java/Python。UI沿用当前in-app daily_factory_a，不建新浏览器。必要代码scope和Owner在定位后追加登记，已有未变的昂贵门禁证据直接复用。

P6-CASH-EVIDENCE；Owner cost_wiring；Base同上。scope：VoucherServiceImpl、ReturnVoucherPostingGuard、新增纯证据ReturnRefundEvidence、对应VoucherServiceImplTest/ReturnVoucherPostingGuardContractTest及新行为/事务目标测试。只修RETURN_REFUND通用作废导致现金账与业务账脱节、失效/错误凭证被幂等复用、过账与只读证据门禁不一致。复用原凭证与退款实体/流水；不新增撤销引擎、不改其他业务作废语义。纯类冻结API：static void requireMatchingLedger(ReturnOrder,PaymentRequest,ArApTransaction)，以及static void requireMatchingVoucher(String factoryId,PaymentRequest,Voucher)，失败BusinessException409，voucher仅DRAFT/POSTED、CASH_PAYMENT、同厂同请求来源、正金额/双分录/客户/现金或银行科目严格一致。root的ReturnRefundService复用它，代理不得改该服务/DTO/controller或Web。禁止Repository/Entity/migration/runtime/Maven/DB/browser/git/ACTIVE，不派子代理。验收命令root串行跑目标VoucherServiceImplTest,ReturnVoucherPostingGuardContractTest及新增行为/事务测试；测试必须证明退款不能通用作废、错误复用不落账、过账错凭据/销售/方式/操作者拒绝。

P6-REFUND-NAV-UI；Owner metric_ui；Base同上。scope：web-admin/src/api/inventory.ts、api/voucher.ts、views/finance/voucher/VoucherDetail.vue、views/finance/ar-ap/index.vue、views/sales/returns/detail.vue及这些模块对应测试；不改router/guards/共享request/依赖/其他页面。冻结API：GET /api/mobile/{factoryId}/return-refunds/{requestId} 返回既有ReturnRefund DTO，request.id必须等请求ID；仅同厂精确退款来源，错请求/历史不匹配报错，绝不退到全表。Ledger新增可空balanceBefore、approvedByName、operatedByName；仍按PriceSensitive保留null。凭证来源RETURN_REFUND可打开/finance/return-refunds/:returnOrderId并定位资金流水；全量AR/AP中的RETURN_REFUND行同样可追原退货。复用详情展示批准姓名及编号、登记前后客户余额，零余额按货币正常展示（修复现有通用amountLabel将0解释可退额度的误文案）；PAID可退金额明确已结清。REVERSED中文已冲销且无再次作废/过账按钮；退款凭证通用作废按钮隐藏并说明已有现金事实需财务核对。所有异步导航核对factory/actor/route/request/sequence，错误可见、无权限不展示按钮。目标Vitest仅对应挂载与API测试；root最终类型检查/构建。不得DB/browser/runtime/Maven/git/ACTIVE，不派子代理。

root代码scope：ReturnRefundDTO、ReturnRefundService、新增精确退款来源只读Controller及目标合同测试；增加同厂UserRepository既有findAllById姓名映射（禁止平台身份回退），balanceBefore由精确不可变流水balanceAfter-amount计算。复用纯证据类验证已付款凭证和流水；不改Entity/Repository/迁移。root最后统一真实UI/PG、目标Maven与Web构建。

P6-PHYSICAL-RETURN-SOURCE；Owner transfer_lineage；Base同上。scope仅ReturnOrderServiceImpl、ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest和局部新增行为ContractTest；不改DTO/Entity/Repository/migration或其他文件。修实物退货必须原销售单、退款-only不得占实物数量、同SKU同批次多发货的来源歧义。复用实际发货allocation和召回已有SHIPMENT_ID证据；无明确证据拒绝猜测，有marker必须验证同订单实际发货与按该出货上限累计，既有未归属行拒绝多出货分摊，单次发货/不同批次独立来源仍可用。禁止root/其他代理scope、ACTIVE、Maven/runtime/DB/browser/git/子代理；root停服务后统一目标测试，不重复开发新训练/库存/付款模型。必要接口扩展先回报，不自行增DTO或发货UI。

root追加测试scope：ReturnRefundPaymentTransactionContractTest，在隔离H2中用真实退款+VoucherServiceImpl方法和Spring事务证明VOID/REVERSED已有凭证触发异常时，先前写入的流水与客户余额回滚；有效DRAFT复用同路径阳性对照。此闸不冒充JPA或PG并发门禁。独立终审追加同源多批按稳定ID排序加锁，transfer_lineage补行序控制，root用本地PG实际业务接口双线程反向行序创建/完成验证。

真实UI发现P0汇总漏接：客户余额540、应收概览405，差额恰为P5退款135。P6-REFUND-AR-AGGREGATION；Owner root（工具刷新后原协作者已结束，已核唯一写者）；Base同上。追加scope：ArApTransactionRepository.java、ArApServiceImpl仅客户对账单发生额计算、真实*RepositoryQueryValidationContractTest及局部statement行为测试。AR_REFUND按APPROVED正向结清纳入应收总额、客户汇总、期初与对账结余；待审批/驳回的AR调整与退款保留历史但不计余额。旧AR收款/贷项仍按绝对值扣减，不改AP、权限、Entity或migration。新增真实JPA启动/执行查询gate，含已审批/待审批/驳回/跨厂/软删与旧mixed-sign收款对照。修复后PG/API核对540；Web未变复用41测试与构建。

环境刷新恢复：原自有Java/Python/Vite/私有PG进程在工具刷新后均已断开，不能把重启前成功当重启后仍可访问。使用原C:\Users\Steve\Documents\assembly-private-pg-20261007\data及postmaster.opts恢复55447，等待异常退出WAL恢复，不重建测试库或补造业务数据。UI精确跳转已在刷新前实点保存01/02/04截图；浏览器插件目标26.1013.31256本机缺失，禁止伪造/复制插件，最终如仍不可连接须明确区分最后汇总页面未重新可视核验。

## 本轮交付（2026-10-16，本地 review）

第三方检查按资金、库存来源、页面接线排序，复用现有退款申请、应收流水、凭证、退货明细、发货分配与页面；未重建支付、库存或凭证引擎。

| 优先级 | 查到的断点 | 已完成的最小修复 |
| --- | --- | --- |
| P0 | 应收汇总漏计已批准的 AR_REFUND，客户余额与概览不一致；待审批调整可能污染期初和发生额 | 修正总应收、客户汇总、期初与客户对账单口径，未审批记录仍保留审计历史但不计余额，旧混合正负收款按绝对值扣减 |
| P0 | 现金已登记后，通用作废或幂等复用失效凭证可能造成业务账与会计账脱节 | 退款来源凭证禁止通用作废/冲销；生成、复用、过账与已付款读回共用严格证据校验；错误凭证导致事务整体回滚 |
| P0 | 无原销售单的实物退货、仅退款占用实物额度、同批多次发货来源不清、反向明细行序加锁 | 复用真实发货分配与已有 SHIPMENT_ID 来源；按实际出货和累计数量校验，歧义拒绝猜测；多批先按稳定 ID 加锁 |
| P1 | 凭证和应收流水不能直达指定退款，审批人仅编号，历史零余额文案不清 | 复用退款详情增加精确只读来源入口；按点击的请求和流水双重核对，展示同厂审批/登记姓名、凭据和该流水前后余额；异步切换租户/身份清空旧数据 |

本地正常业务验收（账号 daily_factory_a；审批使用已有独立工厂财务账号，不共享平台身份）：

- 两张多行退货采用相反批次行序，并发创建、并发完成均为 200/200；另完成一张实物退货。三笔新退款 30+30+15=75 元，分别审批、登记现金、过账。
- 仅退款的数量对照保留 DRAFT，未产生库存/资金/凭证；之后实际退货仍能使用其未占用的实物额度。无原销售单、超出实际出货、跨工厂/无财务权限、无效退款来源、退款凭证通用作废等阴性对照被拒绝。
- 私有 PostgreSQL 恢复并重启最终源码后，只读 API/PG 核对：概览=客户余额=对账单期末=540 元，累计已批准退款=210 元，现金净额=0；三笔新 PAID/POSTED 退款与重启前完整相等，对账单全部 22 条历史保留。
- 原 105/30 元退款、旧批次 7/8/9/10、B 工厂原始记录、视觉任务及 schema 均保留。启动调度新增的 B 指标版本逐条由真实启动日志解释，不算业务修改或旧记录覆盖。

验证证据：C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p6。

- Java 最终 23 类 175 测试通过，复用同源码已通过的目标日志及 latest Surefire XML；追加聚合四类共 15 测试（java-aggregation-final.log）。新 Repository 门禁实际启动 Hibernate 并执行查询，使用 H2 PostgreSQL 兼容模式，不能称为真实 PostgreSQL JPA 测试；运行时 PostgreSQL 查询由 aggregation-after.json 的真实接口读回背书。未变的三项旧 JPA/Flyway 证据按哈希复用。
- Web 7 文件 41 测试、类型检查及生产构建通过，之后没有 Web 源码变化；不重复构建。退款凭证→精确资金流水、应收流水→原退款的真实页面实点截图留在 01/02/04 文件。刷新后浏览器服务插件文件缺失，最终汇总修正未重新截图，不把 HTTP 200 当 UI 验收。
- business-final.json 保存正常业务演练与并发结果；source-controls.json 保存精确来源/权限阴性；aggregation-after.json 保存最终真实 API/PG 一致性；protected-previous-readback.json 保存 23 组历史保护检查；validation-receipt.json 保存最终源码哈希、测试数和边界。
- 新增应收聚合范围独立只读终审 SCOPED PASS；只代表该本地代码、查询门禁无阻断，不等于生产授权、CI 或部署。

保留边界：本地登记现金付款是人工凭据流程，未连接银行/支付渠道；通用退款撤销被明确阻止，尚无完整退款反向资金/凭证引擎；既有缺来源的多出货历史退货不能自动猜测归属；现有退款限制及审批分离继续生效。以上后续能力不冒充本轮已完成。无 Entity/迁移变更，无业务 SQL 补造，无真实支付或生产写入。仅本地独立分支 commit；不 push、不 PR、不合并、不部署，等待用户体验。

体验入口：保留原 105 元退款，/finance/return-refunds/a973937d-147c-4817-8591-aebfc1796aa1?refundRequestId=b259ce06-85af-4494-b720-58ef3351365d&ledgerId=d3230499-26e9-4ab6-998f-7a5656b31870。新增三笔退货可在销售退货列表按 RT-SAL-20261016-0011/0012/0013 查阅，以 business-final.json 的实际 ID/单号为准。仍使用 31761 本地入口、10060/18090 私有服务、55447 原测试库。
