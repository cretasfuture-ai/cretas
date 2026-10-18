# P34 报废与账务生命周期一致性

Base 2f519de714a68ed1f71cc1e85972a153e8d524b7；Owner root，worktree C:/Users/Steve/cretas-analysis-provenance-20261015，branch codex/trace-analysis-vision-daily-review-20261015。用户授权第三方复核并按优先级修复；本地独立分支，禁止push/合并/部署/生产/真实银行。

P0：通用VoucherService.voidVoucher尚未保护DISPOSAL_RECORD/REVISION，OPEN期间可单独冲费用而不恢复实物报废。先真实方法失败测试，再限制通用入口；成本更正改走校验报废记录/原凭证/金额/工厂/期间的专用服务方法，复用真实红字实现，不使用布尔开关或伪造来源。前端同时禁用手动作废并用中文说明。

Scope：service/voucher/VoucherService.java、impl/VoucherServiceImpl.java、service/finance/DisposalCostAccountingService.java、对应既有VoucherServiceImplTest/DisposalCostAccountingServiceTest；web-admin凭证详情及既有集成测试；scripts/e2e/daily-review/refund-close-concurrency的build/runtime/support添加P34本地隔离配置，以及必要P34验收脚本/说明；ACTIVE和本归档。无Entity/Repository/迁移/共享角色配置变更。

## 独立复核与修复结果

P0 已修：通用作废入口无条件拒绝两种报废来源；填写“报废成本更正”也不能绕过。
正常更正改走内部专用方法，锁工厂、报废行和凭证行，核对本厂审批状态、来源、日期、金额、
科目与期间，再复用现有红字实现。无新控制器、模型、数据库字段、查询或迁移。

P2 已补接：浏览器发现原始报废凭证没有返回审批入口，修订凭证已有。
复用既有审批页和路由上下文检查，支持原始来源的正整数记录号。新增真实挂载失败测试后修复。
原始与修订凭证隐藏通用作废，中文提示通过成本更正同步处理。

已有直接复用：审批分权与库存报废、退货子批核价、原/红/修订历史、成本结转、损益结转、
经营指标、重开/月结/锁账、应收与现金报表。没有另建一套报废或财务流程。

## 最终验收

- Java 31 类 351 项通过，失败/错误/跳过均 0，含真实 JPA Context；XML 汇总已保存。
- Web 5 文件 34 项通过，最终构建成功。最后增量仅 Vue 与其测试，Java 源哈希未变化，复用 351 项证据。
- 七阶段 init/guards/timeout/plus20/plus40/restore/verify 全 PASS。
- 通用作废在 OPEN 和 LOCKED 均拒绝，原凭证、红字、修订及跨厂请求有反例；26 表无副作用。
  已审批报废删除也拒绝，现有 Controller 实际 HTTP 契约是 400。
- PostgreSQL 真行锁冲突：请求等候凭证行后超时，HTTP500、约 3.98 秒，26 表完整回滚。
  500 是受控数据库故障的实际响应，不把失败响应称为业务成功。
- 四个真实 advisory FIFO 队列：结账先于更正→更正409；更正先于结账→二者200；
  更正先于通用作废、通用作废先于更正→更正200/作废409，无死锁与半链。
- 连续 +20/+40/恢复基准：源及两个退回子批单位成本 10.4165/11.4165/9.4165；
  0.5盒 × 单位成本四舍五入，损失 5.21/5.71/4.71。每次重复更正不新增凭证。
- 各阶段利润表、账务净费用与锁账快照相同。最终收入720、成本378.12、费用4.71、净利润337.17；
  经营指标完整生产成本561.32。应收0，全部期间账面资金净额0，10月/11月期末资金15/0。
- 最终13条报废凭证历史：6张已冲销、6张真实红字、1张有效修订 V-2026-0297；原/红按科目净零。
  保留原审批人与审批时间、0.5盒报废数量和全部库存数量；新增成本日志均数量0，旧实物日志不变。
- 正常新厂账号登录，真实点击审批→修订→审批、审批→原始→红字→原始→审批；
  最终 UI 回执无页面异常/HTTP错误，业务写0，停在31770报废审批详情。
  本地审计 activity-events 的正常请求不伪造成功，也不算业务库存/财务变更。
- 原 P31 数据库每阶段26表精确不变；10068/31768的原4.71元示例保留，无生产或真实银行操作。

## 测试故障与证据边界

首轮锁冲突脚手架误用 Hikari 配置，自定义数据源未采纳；控制锁释放后真实更正成功。
保存 HARNESS_FAILURE_NOT_ROLLBACK_PASS，通过正常成本接口恢复，保留额外真实红冲历史。
只重启本任务 Java，改为专用 JDBC 会话 options=-c lock_timeout=3s 后取得真实回滚证据。
这是本地测试参数，无时钟替换、SQL业务DML或生产配置变更。

校验器还纠正了两项期望：已结转6711应排除PL_CLOSING后核对营业损失；
成本更正本来会追加数量0审计日志，应验证历史不可改与新增日志合法，不能要求日志完全不增加。
所有失败和修正保留；未降低真实成本、损失、唯一有效凭证、期间报表或源库不变的断言。

私有证据：C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p34。
关键文件 java-behavior-red.log、java-final.log、java-351-summary.json、web-original-link-red.log、
web-final-tests.log、web-final-build.log、七阶段JSON、timeout-detail.json、四个队列JSON、
plus20-unit-cost-supplement.json、ui-first-failure.json、ui-final.json、最终全scope哈希与终审回执。
截图保留初次失败与修复后版本。完整证据与凭据不进入Git。

本切片独立只读深度终审无未解P0/P1，限当前新厂数据与本地流程；不代表所有工厂或生产已验收。
不新增假想入口（例如当前产品无法正常创建的DRAFT报废凭证），不扩大为整套ERP审计。
分支仅本地提交，不push、不开PR、不合并、不部署；用户体验仍独立于测试通过。
