# S4 自动采购建议 PostgreSQL 参数缺陷

- Task: CLAUDE-TASKS-S4-PG-PO-20261105；Owner 01a1a954/root。
- Base SHA: 072bf5681969a55029d929d00a6101a28ec92a15。
- Worktree C:/Users/Steve/cretas-s4-po-postgres-20261105；branch codex/claude-s4-po-postgres-20261105。
- 实证：本组S4销售单财审后自动生产计划创建，SupplyChainOrchestrator 报自动采购建议失败；PG拒绝原生查询 `LIKE CONCAT('PO-AUTO-', ?, '-%')`，SQLSTATE 42P18，无法确定参数$2类型。主干PurchaseOrderRepository.countAutoSuggestionsOn仍为该查询。

## Scope锁

- 执行者仅可修改 PurchaseOrderRepository.java、已有/新增 PurchaseAutoSuggestionRepositoryQueryValidationTest.java、PurchaseAutoSuggestionPostgresContractTest.java，以及为新增PG测试进入实际CI选择器所必需的 .github/workflows/java-gate-pr.yml 精确选择器行（不改其他配置）。上述Java目录均为backend/java/cretas-api/src/{main,test}/java/com/cretas/aims相应repository/integration目录。
- 保留native查询含软删计数、factory/date过滤；不改实体/迁移/业务状态/供货选择/金额/编号算法并发策略。
- 执行者独占此worktree Maven target；只跑目标JPA+真实PG，证据放本组私有目录s4-po-*；本地PG17使用新目录/随机端口，不动他人服务/Docker/WSL；成功或失败都停自己的PG进程。
- 验收：先在真实PG证原查询42P18，再修后真实JPA启动及PG查询通过，覆盖同日、本厂/外厂、异日、软删行仍占编号。协调者审查diff/门禁选择器/结构化回执后PR。
- 禁止commit/push/合并/部署、改ACTIVE或其他组文件。协调者独占台账写入。

## 新增同组 S4 幽灵占线缺陷 scope（2026-11-05）

第四UI夹具的同线候选被本组第三个已取消APS计划的pending子任务挡住。独立SQL已证明第三parent plan cancelled、child pending；当前ShortageScheduleQueryRepository.findOverlappingIn仅过滤child状态，漏过滤parent状态，而相邻findNotStarted/findLiveByPlanIds已限定live parent。

允许同一执行者额外修改 repository/shortage/ShortageScheduleQueryRepository.java 与已有 test repository/ShortageScheduleRepositoryQueryValidationTest.java；保持factory/line/overlap/exclude谓词，新增live parent过滤（draft/confirmed/in_progress），不将cancelled/completed的旧子任务当有效占用。新增真实JPA/PG回归，负向保留真实活跃parent的冲突、异厂与边界不误伤。该测试已在PG CI选择器中，禁止删减断言或直接改生产历史任务绕开bug。证据私有s4-cancelled-*。不扩大到其他调度查询或修改状态/数据。

## 实现与冻结验证

- 自动采购查询改为 `LIKE ('PO-AUTO-' || CAST(:dateStr AS VARCHAR) || '-%')`；保留native查询、factory/date条件及软删占号。
- 产线冲突查询保留原factory/line/时间半开区间/exclude/child live条件，新增parent live过滤；取消/完成的父计划不占线。
- H2/JPA：`mvn test '-Dtest=PurchaseAutoSuggestionRepositoryQueryValidationTest,ShortageScheduleRepositoryQueryValidationTest' '-Dcretas.testIncludes=**/*RepositoryQueryValidationTest.java'`，exit0；8项中7通过，1项PG专用场景跳过，日志s4-po-shortage-jpa-final.log。
- 独立本地PG17：环境CRETAS_WORKFLOW_PG_VERIFY=true、URL仅127.0.0.1随机端口且库名以_test结尾，由DisposablePostgresTargetGuard约束。`mvn test '-Dtest=ShortageScheduleRepositoryQueryValidationTest' '-Dcretas.testIncludes=**/*ShortageScheduleRepositoryQueryValidationTest.java'` exit0，7/0/0/0；`mvn test '-Dtest=PurchaseAutoSuggestionPostgresContractTest' '-Dcretas.testIncludes=**/*PurchaseAutoSuggestionPostgresContractTest.java'` exit0，1/0/0/0。均真实PostgreSQLDialect，两个自建PG集群已停止，REMAINING_PIDS为空。私有日志s4-shortage-postgres-final.log、s4-po-postgres-final.log及对应-stop.txt。
- 旧SQL真实PG红证42P18；修后真实repository/PreparedStatement绑定、本厂/外厂/异日/软删行计数通过。幽灵占线包含活跃parent正对照与cancelled/completed parent、异厂及时间边界反例。
- 同因扫描：PO-AUTO CONCAT无其他命中；S4相邻查询已有live parent条件。LineScheduleRepository.findByProductionLineAndTimeRange目前无生产或测试调用方，不扩本scope；未来启用需另登记并补租户与live child/parent回归。
- CI原PG选择器63→64，仅追加PurchaseAutoSuggestionPostgresContractTest，无删除或重复-Dtest；本地机械检查、diff检查和dispatch检查通过。新增测试将由该实际PG run执行，非仅编译。
- 独立风险终审无P0–P2阻塞；审阅六个代码/测试/CI文件的SHA256已绑定私有回执。无Entity/迁移/权限改动。
- 当前仅实现和本地测试完成，尚未合并或部署。第四组F006 UI现场保留待修复上线后复测，不把已通过的后端子断言当完整业务场景PASS。
