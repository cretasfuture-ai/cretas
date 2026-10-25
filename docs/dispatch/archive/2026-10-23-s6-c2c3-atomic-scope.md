# S6 C2/C3整改范围释放

```json
{
  "taskId": "ERP-S6-B3C-CLAUDE-20261022",
  "state": "claimed",
  "owner": "local_f6b118ce-f036-4ece-9a0b-322073b20c74",
  "actualClaudeSession": "e0a8b090-3310-4b3b-a971-aa296062628c",
  "baseSha": "13874247c5b47897e86511cd095dc3bebe6447e1",
  "worktree": "C:/Users/Steve/my-prototype-logistics/.claude/worktrees/claude-tasks-login-guide-47cc2c",
  "branch": "codex/claude-erp-s6-b3c-scheduled-writes-20261022",
  "writeScope": [
    "backend/java/cretas-api/src/main/java/com/cretas/aims/engine/AutonomousGrantIssuer.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/engine/AutonomousGrantService.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/engine/AutonomousToolRunner.java",
    "backend/java/cretas-api/src/main/java/com/cretas/aims/engine/ExecutionReconciliationReporter.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/engine/AutonomousScheduledWriteIsolatedPostgresContractTest.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/engine/AutonomousToolExecutionContractTest.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/engine/ExecutionReconciliationRepositoryQueryValidationTest.java"
  ],
  "frozenCandidateDependencies": [
    "backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ai/AutonomousToolGrantRun.java",
    "backend/java/cretas-api/src/main/resources/db/flyway/V20261103_80__autonomous_grant_run_outcome.sql",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/integration/AutonomousGrantOutcomeMigrationPostgresContractTest.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/engine/AutonomousRunAsEndToEndPostgresContractTest.java",
    "backend/java/cretas-api/src/test/java/com/cretas/aims/engine/AutonomousRunAsGatewayPolicyContractTest.java"
  ],
  "migration80Reserved": true,
  "acceptedCurrentHead": false,
  "productionWritesReleased": false,
  "atUTC": "2026-10-23T14:53:21.812684+00:00"
}
```

本次只释放以下修正后的实现，原HEAD仍HOLD，不回溯接受先写/推送，不把PG17/源码CI当PG13/生产验收。

1. consumeForScheduledFire起始即对config用PESSIMISTIC_WRITE/FOR UPDATE，不用FOR SHARE升级锁。config→grant固定锁顺序。
2. Issuer REQUIRES_NEW唯一物理事务；grants.consume改REQUIRED加入外层，budget扣减、grant_run插入、config RUNNING同事务。RUNNING仅消费成功且replay=false写；replay早返不得将SUCCESS降回RUNNING。
3. 删除独立markRunning/Runner调用；gateway执行/outcome仍事务外。没有Gateway/采购/供应商/Orchestrator/runtime policy/生产cron写scope。
4. 真实scheduled fire到RUNNING-update故障触发器，独立读回budget/grant_run/config全部回滚、业务0；trigger唯一命名/finally清理。恢复REQUIRES_NEW的变异必须杀死，不能用直接consume替代整链。
5. 删除/换tool/run-as/cron/revoke/expired/deleted授权的到点与并发边界保留。cron改变仅重排，不能旧binding执行。
6. Reporter只改unresolved grant-run为DB count + capped sample；真实JPA >limit 验证总数准确样本封顶，不扩其他四类。
7. 只在上列B3C worktree/分支实施，不回写旧#1093/#1075；7可写+5冻结依赖分开，若要改冻结文件先回传。V78/79/80保留S6；S307独立修复用77_1，最终fresh-main唯一性与out-of-order=false验证必需。
8. Java重测试先协调资源：22:51观察到S6-v72与S6-pg13两JVM在运行，不取消，不再无窗口叠加重任务。Root S307待一个受限窗口，独立target/DB。不要重复同内容已过门禁；改动相关目标与明确变异、冻结后必要完整门禁保留。

验收命令：H2 mvn -B test -Dtest=AutonomousToolExecutionContractTest,AutonomousGrant*Test,DynamicSchedulerServiceTest；PG使用独立PG13，CRETAS_WORKFLOW_PG_VERIFY=true及私有连接，mvn -B test -Dtest=AutonomousScheduledWriteIsolatedPostgresContractTest,AutonomousGrantIssuePostgresContractTest,AutonomousGrantRepositoryQueryValidationTest,AutonomousRunAsEndToEndPostgresContractTest,ExecutionReconciliationRepositoryQueryValidationTest,AutonomousGrantOutcomeMigrationPostgresContractTest。保留完整argv、HEAD/tree、exit、PG版本、XML，skip不算通过。若PG13不可用就明确未验，不以PG17替代。

请原Owner按该范围实施并回传，不需用户重复确认；Root负责最终接口/diff/证据/合并发布，原QA仍独占UI/CSV。文件已发出不推定Owner已读取/实施。
