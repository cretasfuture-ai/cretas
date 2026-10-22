# S1-07 独立采购定时入口

任务 ERP-S1-07-PROCUREMENT-TIMER-20261022；Base edaa711ab0196d86199bcbaf0370bfa5f242f6f9。
唯一源码 Owner 01a15272-374d-72c0-bb20-cdfa0bd823f1；Root 01a1523e-881c-7a92-b7ab-f4b8c5a552df 独占台账/接口裁决/集成。
Worktree C:/Users/Steve/cretas-s107-timer-delivery-20261022；branch codex/s107-purchase-timer-20261022。
原 QA 01a15272-36e3-76c0-a6a1-977df6dba052 独占 canonical CSV、手机、浏览器。S1-07 原判据与未测保留，已交付 Tool/Skill 证据复用。

## 精确源码范围（16 文件；Owner ACK 前只读）

1. backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ai/PurchaseGrantTimerBinding.java
2. backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ai/PurchaseGrantTimerBindingRepository.java
3. backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/PurchaseGrantTimerExecutionService.java
4. backend/java/cretas-api/src/main/java/com/cretas/aims/service/cron/handler/PurchaseGrantTimerTaskHandler.java
5. backend/java/cretas-api/src/main/java/com/cretas/aims/controller/PurchaseGrantTimerBindingController.java
6. backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/PurchaseGrantTimerActor.java
7. backend/java/cretas-api/src/main/resources/db/flyway/V20261103_72_1__create_purchase_grant_timer_bindings.sql
8. backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/ControlledPurchaseTaskExecutionService.java
9. backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/PurchaseGrantRefusalAuditService.java
10. backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/PurchaseGrantTimerContractTest.java
11. backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/PurchaseGrantTimerPostgresLifecycleContractTest.java
12. backend/java/cretas-api/src/test/java/com/cretas/aims/service/cron/PurchaseGrantTimerSchedulerContractTest.java
13. backend/java/cretas-api/src/test/java/com/cretas/aims/repository/ai/PurchaseGrantTimerBindingRepositoryQueryValidationTest.java
14. web-admin/src/api/controlledPurchase.ts
15. web-admin/src/views/procurement/ai-purchase/index.vue
16. web-admin/src/views/procurement/ai-purchase/__tests__/DeferredPurchaseGrant.spec.ts

Root only: docs/dispatch/ACTIVE.md 与本登记文件。需要额外源码文件先给路径与必要性，禁止目录式扩大 scope。
72.1 在当前已入 main 的72之后、Claude保留73/74之前；既有68.1/69.1为先例，FlywayVersionUniquenessTest接受多段数字。发布前重新核对生产迁移最大版本；生产若已跨过72.1，不修改历史，不自动outOfOrder/repair，先裁决未部署文件的新号。

## 交付边界

复用 service.cron.DynamicScheduler 的真实 ThreadPoolTaskScheduler→TaskHandler；不修改 engine.DynamicSchedulerService、AutonomousToolRunner、Gateway、Skill、Orchestrator、供应商链或通用S6策略。绑定现有已确认grant，服务器从数据库重建当前用户身份和权限；保留旧HTTP/Tool/Skill request-only语义，禁止伪造request、继承管理员或旁路下单。
一次任务持久绑定 factory/task/grant/user/version/digest/业务快照/到期时间/执行时间/状态/结果；请求不能替换动作、数量、业务对象、身份、handler。实际触发再次核对当前状态，交易前复查授权，撤权与执行线性化，成功最多一个DRAFT；拒绝零业务写入但有审计。UNKNOWN不盲重试，按持久grant/结果恢复；拒绝manualRun冒充真实定时。正常采购工作台可设置时间、查看真实结果及刷新恢复。

## 验证与完成条件

开发先目标测试，最终冻结一次必要完整门禁和构建；不重复已通过同内容证据，不删断言/放宽权限/加known-red/把跳过算通过。

Java目标（backend/java/cretas-api）：
`mvn -Dtest=PurchaseGrantTimerContractTest,PurchaseGrantTimerPostgresLifecycleContractTest,PurchaseGrantTimerSchedulerContractTest,PurchaseGrantTimerBindingRepositoryQueryValidationTest,PurchaseGrantCallerContractTest,ControlledPurchaseTaskExecutionContractTest,PurchaseGrantRefusalAuditContractTest,FlywayVersionUniquenessTest test`

Web目标（web-admin）：`npm test -- src/views/procurement/ai-purchase/__tests__/DeferredPurchaseGrant.spec.ts`。
新增Repository/Entity必须真实JPA；生命周期必须真实PostgreSQL，隔离工作目录/target/数据库/端口。生产同主版本升级/Flyway/validate及兼容旧版冷启动按实际迁移风险验证，不能用Mockito/H2替代。最终完整检查命令由现有CI必需门禁/发布入口确定并记录准确选择器。

冻结后Root审diff/接口与权限事务风险，检查通过手动合并，clean exact origin/main官方发布，核对结构化回执。原QA以各自隔离F006 grant从真实工作台设置，等待真实定时触发，分别验撤权/自然过期/数量对象动作越界拒绝及正常执行；独立SQL前后快照覆盖binding/task/runlog/grant/token/audit/PO/item及受保护库存财务角色，阴性零业务新增、阳性恰一DRAFT。需要真实UI的不能直调API代替。原QA签canonical前不宣布S1通过。

私有证据 execution/source-s107-timer-delivery-20261022/；只读设计与共享Owner调查见 execution/coordination/s107-timer-minimal-domain-20261022/ 与 s107-shared-owner-dependencies-20261022/。源码、测试、合并、部署、业务验收分别报告。S2独立跟进，不阻塞本批。

## 2026-10-22 候选验证

真实PostgreSQL13.23的attempt03目标退出0：包括迁移72到72.1、Hibernate/JPA查询启动、旧映射冷启动、实际Canvas调度触发、撤权/自然到期/越界、并发恢复和既有入口兼容，未跳过。早期H2夹具失败及真实PG的stale-managed版本冲突原始证据均保留；后者已通过flush/clear后重新加锁读取当前绑定修复。Web和类型检查单列证据，不替代业务签收。

最终窄修复关注：定时绑定已提交但响应丢失后，直接撤销仍须保留任务引用直到真实终态核对；未确认无绑定前不得清除恢复线索。只有相关Web目标复验，不因纯前端变化重复Java。最终完整门禁、提交/PR、合并、官方发布和原QA真实界面/独立SQL验收均各以对应实际回执为准，本文不预签任何尚未完成项。

生产只读19:50确认最新迁移20261103.72且72.1未应用；正式发布仍重新受Flyway顺序门禁约束。共享权限、角色、配置、S6迁移73/74及其他Owner范围未释放。原QA只对本批独立F006授权和一次DRAFT进行有限实测，独立SQL为只读；其他已通过场景复用证据。
