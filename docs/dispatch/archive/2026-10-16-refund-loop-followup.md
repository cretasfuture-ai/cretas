# 退款撤销闭环复核与补接（本地）

P8-REFUND-LOOP-20261016；Base ae99bad50c2c7b4ac570072e30e1247b7983cbc3；worktree C:\Users\Steve\cretas-analysis-provenance-20261015；branch codex/trace-analysis-vision-daily-review-20261015；root唯一写Owner，接续同分支P7的review范围（P7未合并、未部署，前轮证据见2026-10-16-refund-reversal.md）。

用户授权：第三方角度复核退款撤销、应收余额、资金流水和凭证冲销的剩余漏接，按优先级修复，复用已有功能，用daily_factory_a在本地验收。禁止push/PR/merge/deploy、生产业务写、真实支付和SQL业务DML；保留原105/30/15元体验记录及其他工厂、视觉任务。只读PG核验允许；如需新演练单据，只通过既有正常业务API/UI并限定VISION-ENTRY-A。

当前切片：先核对核心金额/审批/来源证据，优先修资金正确性，再修跨页面或身份切换后的陈旧数据展示。root scope暂定web-admin/src/views/finance/ar-ap/index.vue及已有或新增局部挂载测试、对应现有财务API调用与必要中文状态；后端如有独立审查证实的具体缺陷，先在本条追加文件scope与验收命令再修改，不新建会计引擎或重复开发支付功能。

P8-REFUND-LOOP-AUDIT-20261016；Owner reversal_risk，只读；Base同上；scope ReturnRefundService/ReturnRefundEvidence/ReturnRefundReversalEvidence、专用VoucherServiceImpl、ArAp聚合/statement和CashFlowService关联路径及现有测试。禁止写文件、Maven、DB、browser、runtime、Git写入和子代理；仅回传可复现问题、优先级及最小修复/验收建议。root并行检查页面金额、来源入口、缓存和财务核对流程。

完成标准：修正已证实漏接，复用未变P7后端/业务证据；变更范围Vitest、最终build:check、daily_factory_a真实本地浏览器正向与相应阴性、API/PG独立数字读回；最终内容哈希、命令退出码和截图落Documents\cretas-connect-20261015\factory-data-p8。源码与测试冻结后一次最终验证，只有新缺陷/源码变化才追加相关复验。任务仍停在独立本地分支，等待用户体验。

确认P1：跨月收回的AR撤销流水日期与原期红字凭证不一致。已核对AccountingPeriodServiceImpl.assertOpen的CLOSED后20天调整窗口，最终保留现有原期调整政策：AR撤销按原退款ledger/voucher一致的会计日期记录，实际收回/审批时间仍保留createdAt/approvedAt；原期LOCKED仍全事务拒绝，不擅改成当前期冲销。另逾期概览累加原始发票金额，已结清仍计数，部分收款虚高。逾期/账龄在Service层复用outstandingAmount优先、历史NULL保守原值的既有口径，排除剩余0；不改Repository/Entity/枚举/迁移，不引入新的信用分配引擎。

P8-REFUND-PERIOD；Owner reversal_voucher；Base同上；scope service/inventory/ReturnRefundService.java、service/voucher/ReturnRefundEvidence.java、ReturnRefundReversalEvidence.java、impl/VoucherServiceImpl.java、对应VoucherServiceImplTest.java/ReturnRefundReversalContractTest.java/ReturnRefundPaymentTransactionContractTest.java/ReturnRefundServiceContractTest.java必要局部测试。最终日期契约：请求付款捕获一次paidAt锚，原AR/voucher共用其日期；新撤销AR日期绑定经过原ledger/voucher核对的原会计日期，完成态验证原ledger/reversal ledger/original voucher/mirror日期一致。POSTED/DRAFT继续复用原期间红冲/VOID和既有调整窗口；不改变通用期间政策，不重写既有历史数据，不加入“当前期开放即绕过原期锁”。旧日期不一致明确报需财务核对。不得改其他文件、Maven/runtime/DB/browser/git/dispatch或子代理。root串行验VoucherServiceImplTest、ReturnRefundReversalContractTest、付款/撤销事务与CashFlow回归，含跨月会计日期一致、原期锁整笔回滚、同日基线和单时间锚。

P8-FINANCE-READBACK-UI；Owner reversal_ui；Base同上；scope仅web-admin/src/views/finance/ar-ap/index.vue及ar-ap/__tests__/对应现有或新增局部挂载测试。修概览请求乱序、跨工厂/actor旧值残留/不刷新、读取失败仍留旧值；加入清晰读取中/错误状态，复用现有查询和Element Plus。逾期卡片使用现有overdueReceivable/overduePayable与overdueARCount/overdueAPCount字段；余额列明确交易后历史快照；账龄提示其为发票剩余口径，与含退款及撤销的净应收不同。不改路由/权限/API/依赖，不引入新页；可跑本范围Vitest，root最后统一构建和UI验收；不得Maven/DB/browser/runtime/git/dispatch/子代理。

root backend scope追加service/finance/impl/ArApServiceImpl.java、service/finance/ArApServiceImplGetAgingAnalysisTest.java及必要新增FinanceOverview目标测试与验收脚本。ReturnRefundReversalContractTest写Owner已交reversal_voucher，root仅验证、不并发改。root保留唯一Maven/build资源Owner；所有源码和测试冻结后统一必要回归，不与运行中Java共享target/classes写入。

独立复核追加P1：原期补记撤销后，statement若按最后一笔balanceAfter快照取期初，会漏记或重复计算后续交易。root scope增加既有ArApRefundStatementContractTest与ArApRefundRepositoryQueryValidationContractTest：复用不变的工厂/往来方分页查询（500条，唯一ID稳定次序）重放有效历史，期初/本期采用同一signed和审批口径；REPEATABLE_READ保证翻页与本期明细同快照。Repository/Entity/迁移不变，仍运行真实JPA上下文及已删除、异厂、未批准对照。RED证据java-red.log 74项中5项按预期失败；statement-red.log 3项失败。UI独立目标回归4文件20项通过，后端实施完成后统一最终验证。

终审追加同页账龄跨租户陈旧展示：P8-FINANCE-READBACK-UI在原2文件scope内继续补aging请求的factory/actor/route/sequence校验、清旧值、自动重读与错误提示，并覆盖迟到响应/切换/失败。首轮Web build:check退出0证据保留，但此源码变化后由root再构建一次；后端源码冻结、最终Maven不受此次前端修改影响。

本地整链读回又证实账龄旧字段漏接：DTO返回total/days1_30/days31_60/days61_90/days91_120/days120plus，原页却读totalBalance/days1to30等，合计会假显示0且区间错标为91-180/180+。root在同页复用真实DTO字段，标签改为91-120/120天以上；真实DTO形状挂载测试先RED，再4文件24项PASS。首次验收脚本误用同一旧字段导致KeyError，业务操作已完成且持久化；仅修脚本读取字段，重用已成功步骤，未重复退款或改账。

最终执行证据：Java22类163项全部通过（java-final.log/command.json、java-validation-receipt.json），含真实H2 Hibernate Repository Context和查询执行、晚批准原期撤销的夹窗对账、软删/异厂/审批阴性、分页/AP符号和回滚；未改Repository/Entity/迁移。首次编译遗漏私有错误helper，已修并保留java-compile-failure.log，最终选择器完整重跑通过。Web最终4文件24项通过（ar-ap-fields-final.log），最终build:check退出0（web-fields-final-build.log）；只有UI后续变化才追加Web验证，未重复已冻结后端测试。CRLF转LF等价记录ui-line-ending-receipt.json，git diff --check通过。

正常业务API演练：daily_factory_a创建/提交15元银行退款并发起撤销，daily_finance_a独立审批、登记模拟已付款、过账及批准撤销；自批409且金额不变。新request6467ce92-dae8-4ab1-a094-f907197a5fb5；原凭证V-2026-0036 REVERSED，对应V-2026-0037 POSTED。应收525→540→525，已过账资金合计15→0→15，原/反向流水及两凭证同会计日，原凭证与红字按科目净额0；四个日期窗口statement与独立只读PG重算一致。仅更新既有主客户version/updated_at，旧105/30/15及23组保护数据通过，schema不变；B启动指标新增由运行日志解释，业务/视觉历史未改。详见business-final.json、final-live-readback.json、protected-previous-readback.json。

真实浏览器daily_factory_a：概览525/1370、逾期0；AR账龄未清840（含原发票口径，与净AR525不同）、AP账龄1370；查看V-2026-0037两分录借银行存款15/贷应收15，跳转原V-2026-0036已冲销，再返回精确request资金流水。截图browser/01-07保留。首次整页资源事件超缓冲不作网络完整证据；另一次新鲜SPA读回network-final.json无截断、无失败响应、无财务写入，自动activity-events独立计。最终静态风险复核SCOPED PASS（含账龄7字段对齐），执行证据由root负责。

交付状态review：仍为独立本地分支；私有Java10060/Python18090、Web31761已恢复。未push/PR/merge/deploy、未真实支付、未SQL业务DML、未生产写、未跑托管CI。未验边界：私有业务库真实跨月故障注入和超过500条翻页时的并发写；跨月政策、查询及回滚已有隔离测试，不能替代真实银行对账。当前浏览器保留新15元银行退款撤销详情，等待用户体验。所有完整证据位于C:\Users\Steve\Documents\cretas-connect-20261015\factory-data-p8。
