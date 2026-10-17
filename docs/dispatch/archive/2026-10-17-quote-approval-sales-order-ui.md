# QUOTE-APPROVAL-ORDER-20261017

- Owner：root。Base SHA：`30eb12d834476450a5272cd37dfed6534643f726`。
- 分支：`codex/quote-approval-sales-ui-20261017`；最终HEAD、CI及手动合并以该分支对应PR记录为准。
- 范围：报价页至新建销售单的UI衔接、报价守卫、绑定报价的成交快照与税额；可空字段迁移V20261103_65；对应契约、JPA及原生PostgreSQL门禁。
- 完成条件及证据：[脱敏验收记录](../../acceptance/quote-approval-sales-order-ui.md)。报价APPROVED→真实下单→FINANCE_APPROVED；2个×含税62=124，改价、修订、未批准入口及无权页面拒绝；旧6张订单及库存/财务等控制不变。
- 本地：50项报价/PG目标通过；4有效变异断言红且字节恢复；恢复后JPA/金额等30项、严格守卫28项通过；Web22项及类型/构建通过；Python16通过2跳过；选择器5项通过。风险复核无剩余代码阻断。
- 无生产访问或发布，不改V42，不回填旧单，不改领料/需求配置。提交为本批scope最终冻结与归档，后续合并由本Chat核对精确head并手动执行，禁止auto merge；原隔离环境与私有失败/通过证据保留。
