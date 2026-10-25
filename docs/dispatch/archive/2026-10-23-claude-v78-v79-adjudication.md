# V78/V79 改号裁决与原 Owner 续验（2026-10-23）

Owner 不变：`local_f6b118ce-f036-4ece-9a0b-322073b20c74`。Root 实核 main `f9b72649a99ca2528c525896f44f957c22b9da4f` 已部署 V77；原 Base/历史先实施事实保留，不回填授权。

裁决：接收两个 PR 的纯改号切片进入 review；两个 PR 尚 OPEN，均 HOLD 合并/发布。`d978a7e11b` 是 origin/main 合入功能分支，不是 #1105 已正式合入 main。V78/V79 SQL 分别与旧 V75/V76 字节一致，可复用无关同内容证据。生成 SQL 仅出处戳/实体数变化，dirty 戳不证明最终 HEAD 来源。

编号保留：#1105 用 V20261103_78，#1093 用 V20261103_79。V80 仅未来 C3 候选编号，未释放 C3 文件写 scope；AutonomousGrantIssuer/AutonomousToolRunner/ExecutionReconciliationReporter 新增接口、生产任务和共享采购/供应商/Gateway/Orchestrator 继续 HOLD。禁止改 main 已安装 V77。

当前生产实测 PostgreSQL **13.23**（readOnly=on，业务写入0）。原 PG17 与旧 V75/V76 生产形态证据不能证明当前 V77 基线的 V78→V79 实际 Flyway 顺序/历史、ddl-auto=validate 和冷回滚。原 Owner 只在独占隔离目录/target/库/端口继续：V77 基线→V78→V79→候选应用 validate→停止候选→V77 旧应用冷启动 validate，并补 PG13 适用验证。保留旧行/迁移前后完整读回、实际命令/环境变量名（不带值）、退出码、XML、健康、停止/清理及未验项。不能将主动停止 exit1 吞成成功，不能用 ScriptUtils 或 PG13 schema 导出冒充真实 PG13。

目标验证：`CRETAS_WORKFLOW_PG_VERIFY=true`，私有独立 PG 配置下 `mvn -B test -Dtest=AutonomousRunAsRepositoryQueryValidationTest,AutonomousRunAsMigrationPostgresContractTest,AutonomousGrantRepositoryQueryValidationTest,AutonomousGrantMigrationPostgresContractTest,WarehouseTransferMovementMigrationPostgresContractTest,FlywayVersionUniquenessTest`；不存在/更名测试必须先核实选择器，零跳过；真实应用启动/冷回滚另列命令和结果。先按当前精确 HEAD 跑受影响目标，已有同内容证据复用，不另触发无关全量。

仅下列实际文件属于接收切片，任何新增 tracked helper/测试/共享组件须回传精确 scope 再释放：

## PR #1105 / HEAD 66a845fe1a7658005ab19d2097d831dc3573d41c

- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/config/FactorySchedulerConfig.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/config/FactoryTriggerChain.java`
- `backend/java/cretas-api/src/main/resources/db/flyway/V20261103_78__autonomous_run_as_user.sql`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/integration/AutonomousRunAsMigrationPostgresContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/config/AutonomousRunAsRepositoryQueryValidationTest.java`

## PR #1093 / HEAD 5c4bc278baeaaeb00b59d7a3097fde659a4b4ca1

- `backend/java/cretas-api/src/main/java/com/cretas/aims/engine/AutonomousGrantService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ai/AutonomousToolGrant.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ai/AutonomousToolGrantRun.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ai/AutonomousToolGrantRepository.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ai/AutonomousToolGrantRunRepository.java`
- `backend/java/cretas-api/src/main/resources/db/flyway/V20261103_79__autonomous_tool_grants.sql`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/engine/AutonomousGrantRepositoryQueryValidationTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/integration/AutonomousGrantMigrationPostgresContractTest.java`
- `scripts/cron/soft-delete-orphans.sql`

S2 原 Owner 保留；尚无新的独立 worktree/Base/精确文件/命令 ACK，writeScope=[]。请原 Owner 回传入口续修包；Root 不推定接管。原 QA 独占手机/浏览器/canonical CSV。S2/S6 不阻塞已冻结 #1117 交付。

Root 证据：execution/coordination/claude-renumber-v78-v79-review-20261023/，含原回执快照、实际 PR/main、独立风险审阅及生产 PG 版本。接收源码范围、测试、合并、部署和业务验收分别记录。
