# FACTORY-JOINT-METRICS-20261019 本地收口

Base：`929f314dc8b7cc311c2303429b23b2fe06192d16`。
Worktree：`C:/Users/Steve/cretas-daily-closure-release-20261018`。
Branch：`codex/factory-generality-units-readiness-20261018`。
Owner：root；保留独立分支、本地用户体验。未推送、未合并、未部署。

`FACTORY-JOINT-METRICS-JAVA-20261019` 的委派实现已由root接收并完成最终修正；子代理独立写scope释放，后续该切片由root统一持有。独立风险审查的具体发现均已修复并通过相关测试及运行时核验。

复用内容、最终修改、278项Java/78项Web验证、正式v1→v2业务、历史行比较、缺费用阴性、跨厂403及零费用补录回滚证据见：
`docs/acceptance/factory-joint-metrics-formal-upgrade-local-20261019.md`。

必要验收入口：

- `python scripts/e2e/daily-review/generality/joint_upgrade.py NON_PRODUCTION_ONLY --stage <stage>`，依次prepare/activate/business/complete-costs/metrics/history；已有写检查点直接复用，禁止清除状态重放。
- `node scripts/e2e/daily-review/generality/ui-runner.mjs --joint --keep`。
- `python scripts/e2e/daily-review/generality/joint_receipt.py NON_PRODUCTION_ONLY`。
- Maven使用现有P37 build.py私有输出，最终受影响切片73项通过；完整本批类汇总从16份XML回读278项，其他同源通过证据复用。

运行时：P37 PostgreSQL副本、Java10072、Web31772。凭证只读使用既有私有文件，SQL业务写入0。浏览器保留新厂管理员的19日指标页。原18日缺费用样本未被修改。7个未开工无批次计划按既有发布规则仅切换钉选版本，其余已有业务/财务记录不变；不把这7个计划宣称为“完全未变”。
