# P31 财务闭环后续项集中收口

Base 716c3474e3df9361cb339cef167266cb3bfbf2fb；branch codex/trace-analysis-vision-daily-review-20261015，worktree C:/Users/Steve/cretas-analysis-provenance-20261015，root协调/唯一台账写者。用户要求综合盘点并一次完成当前链路后续项，继续本地独立分支，禁止push/合并/部署/生产写入/真实转账。

本批清单：1退货后源成本更正同步（含报废重估）；2报废费用过账；3退货驳回凭证原子处理；4待过账/待审调整的锁账拦截；5最终月结快照与实际账一致；6跨月、采购退货期间与并发/回滚API补验；7本地中文操作与可查看入口。跨月优先使用自然历史日期的真实业务API，不修改系统时钟。真实银行/生产验收不在本地授权内。

## Scope锁

- P31-DISPOSAL，Owner disposal，Base同上。仅service/DisposalRecordService.java、新service/finance/DisposalCostAccountingService.java及其目标测试、既有Disposal目标测试必要fixture；如需要DisposalRecordRepository方法和真实JPA测试先回报协调者再扩scope。不改Entity/迁移/凭证核心/ReturnOrder/ProductionPlan/期间服务/台账/提交/运行时。实现成品实物报废审批在同一工厂锁和期间守卫内复用VoucherService生成库存转费用凭证，缺失成本/非支持处置方式明确阻断，四眼保留；提供源成本更正调用的单批次已批准报废重估方法，历史凭证红冲并保留版本，不覆写POSTED。验收由root统一串行Maven：DisposalCostAccountingServiceTest,DisposalFinishedGoodsBalanceTest,DisposalRecordServiceCrossTenantTest,DisposalWorkflowIntegrationTest及实际使用的新增门禁。
- P31-REJECTION，Owner rejection，Base同上。仅event/ReturnOrderRejectedEvent.java、listener/voucher/ReturnOrderVoucherListener.java、service/inventory/impl/ReturnOrderServiceImpl.java、相关ReturnOrderService接口/controller必要actor传递、对应驳回/事务目标测试。解决驳回null actor；优先同步同事务作废/红冲+factory→row锁序，使失败整体回滚，不以after-commit日志冒充成功；正常actor来自认证，无借用创建人/伪造。兼容调用点需明确，不改成本/报废/期间/台账/提交/运行时。验收root统一Maven：ReturnOrderVoucherListenerRejectTest,ReturnOrderVoucherTransactionContractTest,ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest及新增驳回事务测试。
- root：ProductionCostConsistency、ProductionPlanServiceImpl及新ReturnedCostSynchronizationService；AccountingPeriod/MonthClose及目标测试；本地运行时/验收脚本、必要JPA门禁和最终读回。共享service/finance/DisposalCostAccountingService由disposal独占，接口与root先约定。无需前端改动时不重复UI开发。

两个执行者同worktree不同scope，不运行Maven/业务API/服务、不互相回退，不生成子代理。root保留构建目录和运行时唯一Owner，最终串行测试/真实HTTP/PG/UI/独立风险复核。所有凭据和证据仅Documents私有目录，禁止tracked真值。

终审扩大修复：P31-AR-LOCK，Owner rejection，Base同上，唯一新增scope为service/finance/impl/ArApServiceImpl.java及对应调整事务/服务测试；统一recordAdjustment/approve/reject/cancel的factory→transaction/counterparty锁序，创建与批准按transactionDate期间守卫，禁止并发写穿锁账。不得修改退款/Voucher核心/期间/其他scope，root统一验收ArAp相关目标测试和真实PG两种锁序。disposal追加修正单记录SCRAP日志证据精确绑定及已有凭证幂等完整性；root追加退回后再次出货的来源边缺失/部分缺失守卫。均属于原清单闭环安全性，不新增业务模块。

P30本地提交716c3474e3及127项测试/六组并发/全部分多批次退货证据保留于前份归档；不重复当作本批新增通过。

实际HTTP发现并扩root scope：DisposalController审批入口只允许warehouse/quality写，拒绝财务主管，但服务明确允许finance_manager。仅审批入口补finance:read_write作为已有任一权限，保留quality_inspection模块和服务角色守卫；新增Controller认证actor/权限契约目标回归。不得通过修改共享角色权限绕过；本地副本保留403证据，校验PENDING未扣库存后仅重启P31加载修复，原P14/P30不动。

## 本地收口结果

2026-10-18：本批7项实现/接通完成，状态review，仅本地独立分支。P31专用Java10068/Web31768与数据库p31_finance_closeout_20261018；原Java10064/Web31764、P30运行时及源库保持。私有证据根为C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p31。没有push、PR、合并、部署、生产迁移、真实银行转账；不把本地通过表述为生产或用户验收通过。

| 后续项 | 复用与本批补齐 | 实际验收 |
|---|---|---|
| 退货后的成本同步 | 复用生产成本、退货来源和库存审计，补源批次到已完成实物退回批次的成本传播、二次出货证据及期间守卫 | 正常成本接口两次+20后+40；多个退回子批次最终单位成本9.4165，已报废部分保留审计，重复更正不重复会计记录 |
| 报废费用与成本更正 | 复用报废审批/库存扣减、VoucherService和红冲；6711费用、1405库存；缺失证据或不支持会计政策阻断 | 报废记录5，0.5盒；独立财务用户12审批，首次4.21原凭证冲销并产生4.71修订；原/红/新各有唯一对应 |
| 退货驳回 | JWT真实actor、factory→row锁、同步同事务作废/红冲；保留事件幂等兜底，财务驳回取消待审应收调整 | 正常业务驳回与财务驳回后草稿VOID、挂起调整取消；未完成退货过账409，不制造越级正常流程 |
| 锁账完整性 | 复用现有调整窗口/正常反结账；最终锁账单独检查本期DRAFT、PENDING；应收调整写入统一工厂锁和期间检查 | 草稿及待审分别409；同人审批403；真实窗口过期后批准400；失败完整快照相同；异厂成本/调整/报废403无副作用 |
| 最终月结报表与摘要 | 成本→损益结转→最终报表→复用同一对账检查→同事务保存 | 修复旧摘要利润715.29与净利337.17矛盾；最终快照及摘要均337.17、无待审、对账PASS，AR/AP与1122/2202差0 |
| 并发与跨月 | 复用已有退款、撤销、付款、凭证和PG事务锁；测试时钟只作用于P31指定工厂请求 | 6组退款/撤销与结账+2组调整创建与结账真实PG排队通过；10月31日退款、11月1日撤销被原期锁账拦截且回滚；正常反结账后原期红冲，11月新退款结清 |
| 现有页面与入口 | 直接复用报废、凭证、三大报表、期间、SKU毛利、经营指标页面，无新增平行页面 | 正常UI登录新厂账号，六类页面金额/来源/审批人/凭证链接可见，无pageerror；截图与API/DB读回配套 |

关键账实：10月收入720.00、净销售成本378.12、毛利341.88、报废费用4.71、净利润337.17。完整生产成本指标561.32（10月16日生产口径），不拿它当已出货成本。退款演练客户余额0、待审0；22组已撤销退款原/恢复流水逐组净0；10月末资金15，11月末0，均与相应期间总账一致。11月新退款用于结清恢复后的客户贷项，不把跨月时间戳改成原月。

SKU页面为未扣退货的出货口径：收入795.00、成本419.20、毛利375.80；实物退货收入75.00、原始成本41.0825，扣除后收入720、成本378.12，再扣4.71报废费用得净利337.17。页面保留“退货/折让/期间费用口径不同”的提示及同期间利润表链接；不声称两种毛利口径应直接相等。成本用原精度419.1992-41.0825后统一舍入，不能先舍入再相减。

最终摘要修复新增root scope：MonthCloseService接口及实现、AccountingPeriodServiceImpl和两个既有测试、ClosingReadinessService共享本期pending计数。最终报表严格同厂同月，子账读失败保留WARNING，不伪造PASS；最终pending查询失败抛出回滚。@Lazy MonthCloseService打破既有服务依赖环，真实Spring重启与业务重结账已通过。

## 验证证据与边界

- 目标Java：先一次28类286项集中运行全部通过；审批入口修复追加Controller4项；最终摘要修复后对受影响AccountingPeriod/MonthClose/ClosingReadiness共47项复验。最终29份XML去重294项，全部零failure/error/skip，包含真实JPA查询启动验证。不是一次294项全量，也不是全仓测试。java-full-results.json、java-controller-green.log、java-final.log、最终输入freeze与命令/退出码保留；其余已通过内容不重复跑。
- 真实API/PG：init/cost/rejection/closing/verify/races/cross-month/final-verify.json全部PASS。最后一次正常反结账→月结窗口→force-lock用于验证最终摘要修复；不重放已完成退款。summary-only变动由47项目标回归和最终真实读回补验，原8组锁序/跨月证据直接复用。
- UI：新厂管理员正常登录31768，报废实际损失4.71及审批人可见；V-2026-0189借6711/贷1405各4.71；跨月红字V-2026-0270关联原V-2026-0265，银行/应收各15；利润表、期间、SKU毛利、指标显示与读回一致。UI只读核验，业务写入是正常HTTP接口验收，未声称所有表单点击均验收。
- 财务账号还需质量检验模块准入，演练通过既有管理员Canvas账号模块权限API仅给本厂用户12显式GRANT；未修改共享角色默认权限。两次403保留，拦截时审批PENDING、库存未扣、无费用凭证。正常业务驳回用有销售权限的管理员，财务驳回用独立财务账号，未扩大财务角色销售权限。
- 常规SUBMITTED退货不能过账，真实HTTP确认409；历史异常POSTED退货驳回红冲仅目标单测覆盖。采购退货期间守卫有服务测试，本批没有另行跑采购退货完整HTTP。真实银行、多实例压力、生产/外部验收仍未执行。
- P31日期夹具仅注册JVM/数据库/FOOD_3101_001，恢复2026-10-18，不修改OS时钟。全部业务SQL DML=0，仅只读核验及PG控制事务锁。P30源库26表exact unchanged；原体验库/服务不重启、不写入。源码冻结/证据无凭据提交；一行CRLF→LF格式修正单独保存规范化等价证据，不重跑产品测试。

后续体验入口与脚本顺序见scripts/e2e/daily-review/finance-closeout/README.md。当前7项本地收口后不再自行扩为新业务模块；真实支付和生产验收不能用本地模拟替代。
