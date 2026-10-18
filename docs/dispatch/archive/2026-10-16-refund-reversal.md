# 退款撤销闭环（本地）

任务 P7-REFUND-REVERSAL-20261016；Base ef8fbd25789f1175569e6e7a174391c16242dbd1；root 唯一协调者。复用 C:\Users\Steve\cretas-analysis-provenance-20261015、codex/trace-analysis-vision-daily-review-20261015；P6 本地交付见 2026-10-16-refund-closure.md。本轮仅本地独立分支、模拟资金，不 push/PR/merge/deploy、不生产写、不真实支付、不 SQL 补造业务事实。

目标：daily_factory_a 发起已付款退款的撤销，独立财务审批，原退款保留；同一事务追加反向应收流水、调整客户余额、复用凭证红冲/未过账作废，并核对现金报表。保持工厂隔离、四眼审批、凭据唯一、期间结账、失败整体回滚、重试不重复落账。优先使用现有 PaymentRequest.CANCELLED/cancelledAt 和 AR_ADJUSTMENT 负数/审批字段，不新增实体、枚举、迁移或支付引擎。撤销不是删除历史，必须确认款项已收回的凭据。

root scope：ReturnRefundService、ReturnRefundDTO、ReturnRefundController、ReturnRefundEvidence、新增纯 ReturnRefundReversalEvidence、ArApServiceImpl 的来源保护及对应行为/事务/控制器测试；必要时局部拓展已有测试 helper。root 负责 dispatch、最终串行 Maven、运行时、真实 UI/API/PG、diff 和本地 commit。

P7-REVERSAL-VOUCHER；Owner reversal_voucher；Base 同上；scope：VoucherService.java、impl/VoucherServiceImpl.java、VoucherServiceImplTest，以及必要 CashFlowService.java/对应测试（先核对既有红冲统计，非缺陷不改）。新增专用 reverseReturnRefund(factoryId, requestId, reason, actorId) 返回处理后的原 Voucher；只在原请求 PAID、有 APPROVED 的 RETURN_REFUND_REVERSAL/AR_ADJUSTMENT 负金额证据时，锁定并验证原 RETURN_REFUND 凭证。DRAFT 复用 VOID；POSTED 复用现有 reversePostedVoucher；禁止通用作废保护解除。复用 root 纯类 ReturnRefundReversalEvidence.requireApprovedReversal(ReturnOrder,PaymentRequest,ArApTransaction)，及既有 ReturnRefundEvidence 校验原证据。不得改 root/UI/repository/entity/migration scope，不得 runtime/DB/browser/Maven/git/ACTIVE，不派子代理。验收由 root 串行跑 VoucherServiceImplTest、CashFlowServiceTest 与相关行为测试，含无审批/错来源/闭期间拒绝、DRAFT/POSTED 阳性与红冲现金统计。

P7-REVERSAL-UI；Owner reversal_ui；Base 同上；scope：web-admin/src/api/inventory.ts、views/sales/returns/detail.vue、views/finance/ar-ap/index.vue、views/finance/voucher/VoucherDetail.vue 及对应局部测试。按项目 design skill 复用 Element Plus 与已有弹窗，不改 router/guards/共享 request/权限/依赖。冻结接口：POST /return-orders/{id}/refund/reversal {requestId,paymentReference,reason}；/reversal/approve {requestId,reversalTransactionId}；/reversal/reject {requestId,reversalTransactionId,reason}；返回精确 ReturnRefundDTO。RequestView 新 reversal?: {status,reason,requestedAt,ledger:LedgerView,voucher?:VoucherView}；原 request.status=CANCELLED 表示已付款退款已撤销，eligibilityStatus=REVERSED（refundableAmount 可用于重新申请）；原 ledger/voucher 留存；reversal.ledger 为现有 AR_ADJUSTMENT、负 amount、sourceType RETURN_REFUND_REVERSAL、sourceId requestId，pending 不改变余额；ledger.operatedBy 是发起人，approvedBy 是独立财务人。接口通过 requestId 精确绑定，approval 还绑定 ledgerId。允许读取历史精确 request；详情按 query 调 getReturnRefundByRequest；ledgerId 可匹配原 ledger 或 reversal.ledger，二者不可猜测/串行。AR/AP reversal 行可复用现有退款导航；VOUCHER_REVERSAL 原凭证双向链接已具备则直接复用。工厂人员中文“申请撤销退款”，财务“确认款项已收回并撤销/驳回”，必填原因/收回凭据，重复提交防护、tenant/actor/route/sequence 保护。可运行本范围 Vitest，root 最终类型检查/构建；不得 DB/browser/runtime/Maven/git/ACTIVE/子代理。

运行时仍为 10060/18090/31761，PG55447 原私有测试库；任何 Maven 前先停自有 Java/Python，不影响另一任务 18091。演练优先使用 P6 新增 15 元退款，保留原 105/30 元体验数据及旧 7/8/9/10、B 工厂、视觉任务。完整证据放 Documents\cretas-connect-20261015\factory-data-p7，测试环境确认后只经正常业务 API/UI 生成撤销。

P7-REVERSAL-RISK；Owner reversal_risk（只读）；Base 同上；scope 为本轮退款撤销 Service/DTO/controller、纯证据、专用凭证冲销、通用应收入口来源保护和 UI diff，检查资金一致性、双眼审批、重试/重申请/历史来源、并发锁、失败回滚、未过账与已过账分支、期间/跨厂隔离。禁止所有写入及 DB/browser/runtime/Maven/git/ACTIVE/子代理。root 统一跑目标 Java/Web 和本地真实业务；审查只报告具体可复现阻断与已确认正确，不扩新付款引擎。

独立审查修正：退款撤销与普通收付款共用工厂级凭据查重，原先不同客户的先查再写存在竞态。P7-FINANCE-REFERENCE-LOCK；Owner reversal_voucher；scope 追加纯组件 service/finance/FinancePaymentReferenceLock.java、ArApServiceImpl 仅 recordArPayment/recordApPayment 的锁与规范化接入、PayableSettlementServiceImpl 同一凭据锁及其 RecordArPayment/PayableSettlement 目标测试。root 保留 ArApServiceImpl 已有撤销来源保护，代理不得覆盖；不要改 Entity/Repository/迁移/其他业务规则。冻结 acquire(String factoryId,String reference):String，非空 trim 后以 factory+reference 取得 pg_advisory_xact_lock，事务释放；空 reference 不声明唯一性。组件构造注入 EntityManager，acquire 用 MANDATORY 保证真实事务；4条业务写入口都在 exists→insert 前调用。root 的 ReturnRefundService 两处接入同一组件，不再私有一份锁。验收追加通用 AR/AP/核销相关目标测试，并用真实 API 让跨客户撤销与普通收款核验竞争同一凭据，不能用单独 PG 锁探针替代业务并发。

UI 收尾追加给 reversal_ui：现有 views/finance/adjustments/list.vue 及其目标挂载测试。退款撤销的 AR_ADJUSTMENT 仍进入既有待审队列，必须显示中文专用来源、禁用普通单条/批量审批与勾选、精确指回原退款；不得仅依赖后端409留下死入口。复用 getReturnRefundByRequest、现有 finance alias 和同厂/身份/请求序号保护，不改权限或路由。撤销原因400/收回凭据100的前端边界与后端一致，驳回80。

## 已保存的本地业务证据

证据根目录 C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p7。所有业务事实均由正常本地 API 创建或审批，SQL 仅用于只读核对；模拟账号 daily_factory_a（工厂用户93001），独立财务 daily_finance_a（本厂用户1），没有借用平台身份。

- 新增一个独立客户，通过既有订单、实际发货分配、现金收款核验、实物退货、应收调整审批和退款登记准备15元并发对照。复用既有可用库存1盒；没有 SQL 补造订单、收款或退货。
- 普通收款核验与另一客户的退款撤销竞争同一个带空格/无空格凭据，真实 API 为200/409，PG规范化凭据仅1行。两个不同客户的退款撤销同凭据为409/200、PG仅1行。两次同源批准为200/200，但只产生一次反向流水与冲销。
- 申请/驳回阶段余额540、现金报表0均不变；批准原15元退款撤销后应收概览、客户余额、对账单期末均525，现金报表15。原凭证V-2026-0029变为REVERSED，新镜像V-2026-0034为POSTED；按科目/客户辅助核算合计借贷净额为0，原过账审批人与时间保持原值。
- 撤销后额度恢复，正常重新申请15元并审批/登记付款，生成DRAFT凭证；再申请并批准撤销，原DRAFT改VOID，不虚造已过账现金收入。最终余额525、现金15，两个原退款与撤销完整历史均可按精确requestId读取，旧链接不会被新申请替换。
- 无收回凭据、自行审批、错撤销流水、通用调整审批、质检角色和跨工厂阴性被拒绝。23组历史保护检查通过，旧105/30元退款仍PAID/POSTED，旧7/8/9/10、B工厂原始记录、视觉任务与schema均保留；仅原15元退款/原凭证/客户余额和新业务发货的源库存1盒出现预期变化。

后端最终18类121测试通过（java-final-with-reference-lock.log），含原付款回滚、撤销整体回滚、真实控制器路径、原凭证审批保留、凭据事务锁/规范化、通用AR/AP与应付核销回归。无Entity/Repository/枚举/迁移改动，既有JPA/Flyway证据按未变哈希复用；本轮PG native advisory锁、跨入口并发和资金一致性由真实本地API/PG验收，不拿H2/Mockito替代。Web类型检查和构建已通过，最终队列回归结果与内容哈希另见validation-receipt.json。

浏览器连接仍缺少本机browser-service插件目标文件，未复制/伪造插件、未切换浏览器绕过限制。实际演练通过真实业务API完成，界面由挂载测试与构建核验；本轮没有实点浏览器截图，不宣称完整UI业务验收。用户可用daily_factory_a从本地页面体验；测试服务保持运行。

当前原15元退款体验入口：/finance/return-refunds/2b3e1b62-3836-4bee-a9e0-bace3edfbfd7?refundRequestId=367330fa-28fa-4867-b81c-5105285f15d6&ledgerId=f6ede738-b287-4364-84e3-6adca85a7572。最终状态是退款已撤销、恢复待退15元，客户总应收525。仅本地模拟现金和凭据，未接真实支付渠道。禁止push/PR/merge/deploy的用户边界不变。

最终页面回归按切片合计25文件71测试通过：原统一运行中另外5文件47测试已通过且源码未变；新队列的template包装暴露ElementPlus固定列首按钮无事件问题，先用HEAD原组件证明基线02用例通过，再恢复原按钮结构并保留专用来源保护，20文件24测试全部通过（logui-queue-regression-final.log），未改fixture或删断言。最终类型/构建日志web-final-queue-build.log，对应最终哈希以validation-receipt.json为准；早期失败日志保留，不算通过证据。

未来发布门禁（本轮未授权、未执行）：真实浏览器UI/Hosted CI/生产验收尚未完成。若将来产生正式撤销数据，不能把“无迁移”解释为只回退代码即可安全恢复业务；版本回退前须先确认专用PENDING撤销为0或由新版按来源驳回/完成，并核对旧版对CANCELLED和VOUCHER_REVERSAL的只读兼容。本轮只保留本地分支与模拟数据，不执行发布或回退。

## 本地浏览器补验与页面修正（P7-UI-20261016）

用户追加授权：恢复当前浏览器，用daily_factory_a查看原15元退款撤销、应收余额、资金流水及红字凭证；检查问题后继续修复，仍不合并部署。Base e601fbc206bc0acf4ad14d44e7f088cd15b13fe7；root唯一写Owner；scope仅sales/returns/detail.vue、finance/voucher/VoucherDetail.vue、finance/ar-ap/index.vue及各自已有挂载测试、dispatch。验收为三模块目标Vitest、build:check、真实浏览器只读导航及本地API/PG读回；不重复运行未变后端/业务写演练。

浏览器根因是已安装Codex 26.1013.3656.0对应的browser 26.1013.31256缓存缺失。仅从已安装应用包恢复对应缓存，384文件SHA-256逐项一致，config.toml哈希不变、旧缓存和产品数据未改；清除此前失败连接缓存后重新连接既有tab 1。恢复回执在factory-data-p7/browser-cache-repair-receipt.json。该操作不增加权限、不更改配置或复制工厂数据。

实点发现并修复：原退款凭证REVERSED/VOID直接显示英文，现复用现有VOUCHER_STATUS_LABEL中文；“查看本单应收流水”实际仅显示原退货应收调整，改为“查看退货应收调整”。凭证页面被工作任务缓存后仍监听全局route.params.id，离开到退款页时误用退货ID读取凭证并弹“凭证不存在”；现仅在FinanceVoucherDetail且本任务激活时读取，离开/卸载作废旧请求，重新激活读取当前凭证。新增三个行为用例先复现3失败/8通过，再修复为目标3文件39测试通过；保留全部原断言。

本轮只读查看现有模拟单据，不新增退款、撤销、收付款或凭证。逐页截图、AX、脱敏请求状态和最终构建见factory-data-p7/browser-ui-20261016；本节最终回执另存该目录，不覆盖前一轮API/PG验收事实。Hosted CI、合并、部署和生产验收仍未执行。

财务概览补验发现：从退货调整专用上下文进入应收页后，切换到overview没有调用已有loadOverview，实际停留“暂无数据”；API/PG总余额525不变。目标挂载用例先复现1失败/3通过（overview-before-fix.log），仅在既有activeTab watcher接回overview查询，最终统一验证包含应收应付已有回归。

最终本地补验通过：三模块7文件55项测试、最终build:check退出0（web-final-tests.log、web-final-build.log）；仅新增两行行尾修正，规范化源码哈希相等（line-ending-receipt.json），未改变已测行为。真实daily_factory_a浏览器中依次验证原撤销记录、红字凭证V-2026-0034（借库存现金15、贷应收账款15，已过账）、原凭证V-2026-0029（已冲销）、返回原申请流水、应收概览525和应收列表原撤销行。列表“查看撤销退款”精确返回原requestId/ledgerId，未被重申请替代。最终停在原15元撤销详情，收回凭据P7-LOCAL-RECOVERY-POSTED-001、独立审批人及540→525均可见。

本轮浏览器验收补齐前一轮缺失的只读实点证据；验收区间API异常响应0、控制台error 0、财务业务写请求0。既有activity-events自动使用日志POST单独登记，不伪称所有HTTP写入均为0；框架既有onScopeDispose警告不计作error消失。API/PG只读回读仍为应收525、现金15、旧105/30退款仍PAID/POSTED。逐页截图及脱敏请求状态见browser-readback-receipt.json，源文件哈希和本地提交回执见同目录validation-receipt.json/local-commit-receipt.json；未变后端121项证据直接复用，未重复创建模拟业务。独立分支保留，仍无push/PR/merge/deploy、无生产/真实资金动作。
