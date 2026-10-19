# FACTORY-FINANCE-ACCEPT-20261019 本地收口

Base：`c7550d8f1509abbfcb1d9b2cc89079b7da3ab02b`。
Worktree：`C:/Users/Steve/cretas-daily-closure-release-20261018`。
Branch：`codex/factory-generality-units-readiness-20261018`。
Owner：root；保留独立分支、本地体验；未推送、合并或部署。

本轮范围与业务结果见 `docs/acceptance/factory-finance-three-item-local-20261019.md`。统一场景表及最终结构化回执：
`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p37/finance-acceptance-20261019/scenarios.json`、同目录 `final-receipt.json`。

`FACTORY-FINANCE-BRIDGE-20261019` 委派实现由root接收并统一持有，不再有独立代理写者。Java scope为 ShipmentSkuMarginService、SalesReturnCostService、ShipmentSkuMarginDTO及对应两类测试；root负责Web三文件、正常业务验收脚本、P37测试选择器和本文件。没有Entity、Repository、权限或迁移源码改动。

独立终审 `finance_risk_review` 发现的待过账状态回归已修复：先返回PENDING，退货核对字段保持空，已过账缺证继续409。无剩余产品阻塞。复核人身份在验收脚本固定为本厂既有finance_manager、用户12，管理员为2。

Java8类106项独立测试通过（105项初验后，仅受影响类16项复验）；Web4相关文件64项初验通过，取分提示修订后2受影响文件36项通过，最终类型检查及构建通过。其他切片通过证据复用，没有重跑全仓或CI。

本地前提修复：P37缺少主线既有V64表，仅复用原DDL补齐，业务行、岗位范围记录及Flyway历史未改。该副本关闭Flyway；不把手工DDL当作完整迁移验收，不应用它证明正常Flyway或生产启动。正常迁移环境属于另列范围。

验收入口（仅本地，已完成操作不能清除检查点重放）：

- `python scripts/e2e/daily-review/generality/finance_close.py NON_PRODUCTION_ONLY verify`
- `python scripts/e2e/daily-review/generality/finance_close.py NON_PRODUCTION_ONLY receipt`
- 页面 `/finance/sku-margin?periodStart=2026-10-01&periodEnd=2026-10-31`，复用“查看同期间利润表”。

已有独立分支、旧版本生产与追溯证据保留。3项技术业务验收不代替业务签署、手机真机、空库迁移或生产上线验收。
