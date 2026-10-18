# P31 本地财务闭环收口

复用 P30 新厂正常业务样本与已有生产、退货、报废、应收、凭证、期间、指标和页面能力。只允许本机 PostgreSQL 55447 / 数据库 `p31_finance_closeout_20261018`、Java10068、Web31768。P30源库及原Java10064/Web31764保留，不合并、不部署、不真实转账。凭据与证据只在当前用户 Documents/cretas-connect-20261015/factory-data-p31，禁止提交。

## 执行顺序

1. 核验 worktree/branch/独占Owner及P30冻结证据；使用现有 `refund-close-concurrency/build.py NON_PRODUCTION_ONLY --profile P31` 构建隔离的私有目录。运行中的P31类目录拒绝覆盖。
2. `clock_setup.py NON_PRODUCTION_ONLY` 复用既有P10请求时钟测试夹具，再使用 `refund-close-concurrency/runtime.py NON_PRODUCTION_ONLY --profile P31` 创建专用本地副本。启动不等于已验收。
3. 应用就绪后依次运行 `acceptance.py NON_PRODUCTION_ONLY init`、`cost`、`rejection`、`closing`、`races.py NON_PRODUCTION_ONLY`、`acceptance.py NON_PRODUCTION_ONLY verify`、`cross_month.py NON_PRODUCTION_ONLY`、`final_verify.py NON_PRODUCTION_ONLY`。
4. 最后使用正常登录页面登录新厂管理员，查看现有报损报废、凭证、利润表、经营指标、SKU毛利页面并留截图。正常业务写入已经通过真实API执行，页面读回不能冒充UI表单写入验收。

所有入口均需明确非生产确认参数。实际命令须以各脚本参数定义为准，首次运行前检查已有runtime、数据库、端口和checkpoint。**本批业务已完成，不得重复运行上述写步骤。** 失败先查看状态和私有回执，按已成功步骤续做未完成部分，不删除证据或重放已完成业务。

## 验收范围

- 源生产成本更正传播至多个已完成实物退货批次，含部分已报废库存；重复更正不重复凭证。
- 报废独立财务审批、真实扣库存、6711费用过账；成本更正保留原凭证、红字和新修订。财务测试账号仅通过既有工厂管理员模块授权API开通质量检验，不修改共享角色权限。
- 正常未完成退货禁止过账；正常业务驳回、财务驳回同步作废草稿并取消待审调整。历史POSTED驳回红冲分支由目标单元测试覆盖，不伪造正常业务可产生该状态。
- DRAFT凭证与本期间PENDING调整分别阻断最终锁账；独立审批、过期窗口、跨工厂和失败完整回滚对照。
- 6组退款/撤销与结账、2组调整创建与结账的真实HTTP/PG锁排队，既有账务保持一致。
- 10月31日退款，11月1日撤销遇10月锁账失败；正常反结账后保留原期红冲，11月新退款，逐期资金与应收核对。最后通过正常重结账验证最终利润快照及对账摘要一起更新。

测试时钟仅对注册的P31 JVM、专用数据库、指定工厂请求生效，使用后恢复2026-10-18，不修改操作系统时钟；不作为生产跨月证据。采购退货期间守卫有目标服务测试，本批没有额外执行采购退货完整HTTP业务。多实例压力、真实银行付款和生产验收不在本地验收范围内。回收/捐赠等未定义会计政策明确阻断，不自行猜测分录。

原P30成功证据及286项集中回归直接复用，后续控制器4项、最终摘要47项只覆盖增量；最终报告按最新XML去重统计，不能相加声称一次全量执行。每轮输入哈希、命令、日志、退出码及最终源码冻结均保存在私有证据目录。

## 本地查看

- 登录：`http://127.0.0.1:31768/login`，使用已准备的新厂管理员账号。
- 报损报废：`/quality/disposals?disposalId=5`。
- 利润表：`/finance/three-statements?tab=income-statement&factoryId=FOOD_3101_001&periodStart=2026-10-01&periodEnd=2026-10-31`。
- 经营指标：`/analytics/indicators?periodStart=2026-10-16&periodEnd=2026-10-16`。
- SKU毛利：`/finance/sku-margin`，期间选择2026-10-01至2026-10-31。
- 具体原/红字/修订凭证、退货及批次ID见私有 `cost.json`、`cross-month.json`、`final-verify.json`，不在文档复制账号密码。
