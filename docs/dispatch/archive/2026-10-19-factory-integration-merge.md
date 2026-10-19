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

## PR #1031 全量失败修复候选

`FACTORY-INTEGRATION-CI-20261019`，Base `c08fd0bf94ee`，Owner root，状态 `review`。针对性实现与本地回归已完成并冻结，本次提交释放代码写 scope；最终 CI 与合并结论仍以同一最终 HEAD 的回执为准。

- 首轮全量 run `39192559528` 在候选 `c08fd0bf94ee` 与 main `6c4ecfe93ff77` 的合并树运行，最终 XML 读数 15,967，四条新增失败，失败记录原样保留；不修改空的失败基线。
- 两条出货回归暴露全精度换算后缺少库存持久化边界：保留换算过程精度，仅在成品原生数量写入前按已有 `numeric(15,4)` 舍入并限制不超过真实库存；追溯和成本沿用同一扣减量。小于可记录精度的非零出货明确拒绝，避免零扣减。
- 原两条两位小数断言更新为已有存储契约对应的 `666.6667`，同时断言字段 scale=4。新增小重量与追溯一致、过小出货无库存/追溯写入、舍入后不超库存三项验证，没有删除断言。
- 为建厂版本漂移、原料重量单位、联产成本分摊及流程配置无效补充中文下一步提示，提示与实际读取的当前配置/历史凭据对应，不让用户修改历史快照来绕过守卫。
- 最终相关回归：16 个类、153 项、失败/错误/跳过均为零，原生出货、批次分配、成本、单位、建厂状态及联产校验共用既有测试。证据：本机 `integration/java-ci-repair-receipt.json` 与 `java-ci-repair.log`。

未改 Entity、Repository、迁移、CI 选择器、失败基线或 runner 配置；需对修复后的最终 HEAD 重新运行必需 CI 与显式 JPA 门禁，再执行人工锁定提交合并。当前不预填 CI 通过、已合并或已部署。
