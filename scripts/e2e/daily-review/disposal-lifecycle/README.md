# 报废与账务生命周期本地验收

仅限 P34：Java 10070 / Web 31770，独立数据库 `p34_disposal_guard_20261018`。
复用已有 P31 新厂业务副本、普通登录、成本更正、期间重开、月结与锁账接口。
原 10068 / 31768 数据保持不变。凭据和完整证据放私有 Documents，不提交。

先执行共享 `build.py NON_PRODUCTION_ONLY --profile P34`，再执行共享
`runtime.py NON_PRODUCTION_ONLY --profile P34`，然后按顺序运行本目录：

```text
acceptance.py NON_PRODUCTION_ONLY init
acceptance.py NON_PRODUCTION_ONLY guards
acceptance.py NON_PRODUCTION_ONLY timeout
acceptance.py NON_PRODUCTION_ONLY plus20
acceptance.py NON_PRODUCTION_ONLY plus40
acceptance.py NON_PRODUCTION_ONLY restore
acceptance.py NON_PRODUCTION_ONLY verify
```

每阶段成功写独立回执；禁止重复执行已完成阶段。若异常中断，先检查业务状态、
business-state.json 和阶段前后快照，不能盲目重放成本更正或并发请求。

验证通用凭证作废无法脱离报废业务、伪造更正原因不能绕过；锁账和真实 PostgreSQL
凭证行锁超时的 26 张表整体回滚；两种成本/作废排队顺序、两种成本/月结顺序；
连续三次成本更正、重复幂等、唯一有效费用凭证、原/红/新关联、退货库存核价、
指标及月结快照/利润表一致。真实队列使用事务 advisory/row 锁，无 SQL 业务写入。

P34 专用 JDBC 会话参数 `lock_timeout=3s` 只用于可控故障验收，不是生产配置；无时钟替换。
最终恢复原 4.71 元损失，保留全部真实红冲和修订历史。浏览器只读回看与业务 API
验收分别记录，不将截图或 HTTP 成功单独当成完整业务通过。

使用现有浏览器正常登录新厂账号后运行 `ui.js`，只接受31770；从真实审批历史读取当前凭证号，
点击修订/原始/红字及审批回链。阻止业务写，仅允许本厂正常 activity-events 审计POST。
截图路径位于主工作区被忽略的 `.playwright-mcp`，回执另存私有证据。
