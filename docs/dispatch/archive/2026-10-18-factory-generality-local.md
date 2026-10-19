# FACTORY-GENERALITY-20261018 本地交付台账

- Base：`ca300ca097e1d45817ae0b3b58c0822d2628dc15`
- 分支：`codex/factory-generality-units-readiness-20261018`
- Worktree：`C:/Users/Steve/cretas-daily-closure-release-20261018`
- 唯一最终写 Owner：root。
- 权限：独立分支、本地验收；不推送、不合并、不部署。
- 最终结果以 `docs/acceptance/factory-generality-local-20261018.md` 和本机 P37 证据为准。

协作切片均汇回本批；子任务不代表已合并或已上线：

| 任务 ID | 原 Owner | 范围及验收 |
| --- | --- | --- |
| FACTORY-GENERALITY-UNITS-20261018 | generality_units | FgQuantityUnitConverter、ShipmentCostRows；目标 Java 测试 |
| FACTORY-GENERALITY-METRICS-20261018 | generality_metrics | 指标适用性 API、服务、Web 及测试；Java Contract/Vitest |
| FACTORY-GENERALITY-GUIDANCE-20261018 | generality_metrics | 发布身份提示、SOP/AI 助手与对应测试；Vitest/Pytest |
| FACTORY-GENERALITY-OUTPUT-COST-20261018 | generality_units | 复用正式报工分摊事实；ReportedOutputCostAllocationServiceTest |
| FACTORY-GENERALITY-FLOW-20261018 | generality_flow → root | P37 断点续跑业务脚本；API 写入、SQL 只读回读、幂等与回滚 |
| FACTORY-GENERALITY-COST-READ-20261018 | p36_release_risk | BatchBomBinding、OrderCostBreakdownService 与现有测试；root 统一运行 |

root 另负责建厂就绪、平台身份修复、发布历史身份保护、多产出结单接入、原料重量和生产汇总，以及最终浏览器与本地整链验收。补修结单完整性校验早于幂等返回和容器误入成品清单；正式产出过滤同时约束租户、结单、计划、批次和SKU，共用现有产出行，含真实只读PostgreSQL正反/变异对照。无 Entity、Repository 查询或新迁移改动。

最终本地目标报告：33类467项Java零失败/错误；目标Web测试、类型和构建通过，保留全量Web三个超时及其隔离重跑结果。API/DB/UI和范围边界见验收文档及P37本地回执；没有push、PR、合并或部署。root保留本分支唯一写Owner。
