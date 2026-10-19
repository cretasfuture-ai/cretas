# 建厂与日常泛化：合并候选与验收回执

任务：`FACTORY-INTEGRATION-MERGE-20261019`。Owner：root。
当前主仓核对点（Base）：`6c4ecfe93ff7751066ea7323e5c6750a7eae11fe`。
候选产品提交：`5d0365655685a9c4123bb046f268257da2bfb59d`。
分支：`codex/factory-generality-units-readiness-20261018`。
Worktree：`C:/Users/Steve/cretas-daily-closure-release-20261018`。

用户于本轮明确授权推送、开PR、完成必需CI和JPA门禁，全部通过后合并并核对整合版本；不授权部署。此前“不推送/不合并”的记录描述对应历史阶段，不再限制本次已授权的合并步骤。

## 实现任务归档与scope释放

以下四批实现和本地技术验收完成，已合为一个候选；本次收尾同时归档、释放各自代码写scope。状态为review候选，不能在PR实际合并前宣称merged。

| 原任务 | Base | 最终证据 |
|---|---|---|
| FACTORY-GENERALITY-20261018 | ca300ca097e1d45817ae0b3b58c0822d2628dc15 | factory-generality-local-20261018.md及原归档 |
| FACTORY-JOINT-METRICS-20261019 | 929f314dc8b7cc311c2303429b23b2fe06192d16 | factory-joint-metrics-formal-upgrade-local-20261019.md及原归档 |
| FACTORY-FINANCE-ACCEPT-20261019 | c7550d8f1509abbfcb1d9b2cc89079b7da3ab02b | factory-finance-three-item-local-20261019.md及原归档 |
| FACTORY-FINAL-CLOSURE-20261019 | a1d096b8198374c027d335ce770a7bc66cd36373 | factory-generality-final-local-20261019.md及原归档 |

候选本地9/9场景通过；单位、发布历史身份、联产成本与指标、整月退货/利润和联产来源均复用既有正式能力。没有新增Entity、Repository查询或迁移SQL。真实JPA/Flyway、负向和独立风险复核证据均保留。与当前main试合并无冲突。

## 合并门禁与整合验收

1. 最终候选PR使用`java-full-ready`运行仓库既有全量棘轮（不自动更新失败基线），并完成PostgreSQL并发、Web、Python、源码/秘密扫描等实际触发的门禁。
2. 显式对同一最终HEAD触发`jpa-gate-main.yml`的workflow_dispatch，确认真实JPA Repository上下文检查通过；不把未运行或跳过当作通过。
3. 所有适用门禁通过后锁定最终head手动squash合并，不启用自动合并，不绕过失败门禁。
4. 合并后fetch实际main，按后端/Web产品树及来源哈希核对候选与整合版本；完全相同的产品源码复用通过构建，无关CI路由/文档变化不重复编译。
5. 复用P37样本，读回建厂候选/已应用/运行配置、旧新版本引用、四个联产批次及19日指标、10月退货后经营分析/利润表，并核对真实页面。不得重放采购、报工、过账、结转等已完成交易。

本机结构化CI/合并/整合验收回执保存于：
`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p37/final-closure-20261019/integration/`。
PR、CI实际run/head、merge commit和最终整合结论以GitHub及该目录的最终回执为准；本文在推送前记录候选和执行判据，不预填结果。

任何部署、生产迁移、线上业务写入、业务负责人签署或手机真机验收均不属于此次合并授权。若CI发现产品问题，在本PR范围修复并复验受影响项；基础设施/工具失败单独归类，保留原始失败，不改断言或基线伪造通过。
