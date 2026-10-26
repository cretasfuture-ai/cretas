# S1-07 独立采购定时入口

任务 ERP-S1-07-PROCUREMENT-TIMER-20261022；Base edaa711ab0196d86199bcbaf0370bfa5f242f6f9。
唯一源码 Owner 01a15272-374d-72c0-bb20-cdfa0bd823f1；Root 01a1523e-881c-7a92-b7ab-f4b8c5a552df 独占台账/接口裁决/集成。
Worktree C:/Users/Steve/cretas-s107-timer-delivery-20261022；branch codex/s107-purchase-timer-20261022。
原 QA 01a15272-36e3-76c0-a6a1-977df6dba052 独占 canonical CSV、手机、浏览器。S1-07 原判据与未测保留，已交付 Tool/Skill 证据复用。

## 精确源码范围（18 文件；原16加CI确认的2文件）

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
17. scripts/cron/soft-delete-orphans.sql
18. web-admin/src/views/procurement/ai-purchase/index.spec.ts

Root only: docs/dispatch/ACTIVE.md 与本登记文件。需要额外源码文件先给路径与必要性，禁止目录式扩大 scope。
新增17/18已于f45ecfe候选官方构建退出0、自有PG停机且端口关闭后登记，仍由同一原源码Owner唯一写。17只用原生成器同步@Table数512到513的实际漂移，不改生成器/hasdel/策略；18只补新GET接口在旧无定时夹具中的明确空绑定模拟，保留原业务断言。实际失败job119811575846/119811575791和日志保留；不是资源失败或known-red。
补齐模拟后的原测试进一步发现：UNKNOWN恢复为明确PENDING后，页面仍显示不确定态的“刷新授权状态”。原断言保留；允许在既有第15文件恢复最小显示条件，同时保留定时绑定/未知结果/缺版本的实际刷新与恢复入口，不通过改文案规避断言。范围仍18，Java与可信JAR不变，相关两spec和类型检查按实际结果复验。
72.1 在当前已入 main 的72之后、Claude保留73/74之前；既有68.1/69.1为先例，FlywayVersionUniquenessTest接受多段数字。发布前重新核对生产迁移最大版本；生产若已跨过72.1，不修改历史，不自动outOfOrder/repair，先裁决未部署文件的新号。

## 交付边界

复用 service.cron.DynamicScheduler 的真实 ThreadPoolTaskScheduler→TaskHandler；不修改 engine.DynamicSchedulerService、AutonomousToolRunner、Gateway、Skill、Orchestrator、供应商链或通用S6策略。绑定现有已确认grant，服务器从数据库重建当前用户身份和权限；保留旧HTTP/Tool/Skill request-only语义，禁止伪造request、继承管理员或旁路下单。
一次任务持久绑定 factory/task/grant/user/version/digest/业务快照/到期时间/执行时间/状态/结果；请求不能替换动作、数量、业务对象、身份、handler。实际触发再次核对当前状态，交易前复查授权，撤权与执行线性化，成功最多一个DRAFT；拒绝零业务写入但有审计。UNKNOWN不盲重试，按持久grant/结果恢复；拒绝manualRun冒充真实定时。正常采购工作台可设置时间、查看真实结果及刷新恢复。

## 验证与完成条件

开发先目标测试，最终冻结一次必要完整门禁和构建；不重复已通过同内容证据，不删断言/放宽权限/加known-red/把跳过算通过。

Java目标（backend/java/cretas-api）：
`mvn -Dtest=PurchaseGrantTimerContractTest,PurchaseGrantTimerPostgresLifecycleContractTest,PurchaseGrantTimerSchedulerContractTest,PurchaseGrantTimerBindingRepositoryQueryValidationTest,PurchaseGrantCallerContractTest,ControlledPurchaseTaskExecutionContractTest,PurchaseGrantRefusalAuditContractTest,FlywayVersionUniquenessTest test`

Web目标（web-admin）：`npm test -- src/views/procurement/ai-purchase/index.spec.ts src/views/procurement/ai-purchase/__tests__/DeferredPurchaseGrant.spec.ts`；元数据闸：`bash scripts/tests/test-soft-delete-orphans-sql-drift.sh`及`bash scripts/tests/test-soft-delete-scan-root-covers-entities.sh`。
新增Repository/Entity必须真实JPA；生命周期必须真实PostgreSQL，隔离工作目录/target/数据库/端口。生产同主版本升级/Flyway/validate及兼容旧版冷启动按实际迁移风险验证，不能用Mockito/H2替代。最终完整检查命令由现有CI必需门禁/发布入口确定并记录准确选择器。

冻结后Root审diff/接口与权限事务风险，检查通过手动合并，clean exact origin/main官方发布，核对结构化回执。原QA以各自隔离F006 grant从真实工作台设置，等待真实定时触发，分别验撤权/自然过期/数量对象动作越界拒绝及正常执行；独立SQL前后快照覆盖binding/task/runlog/grant/token/audit/PO/item及受保护库存财务角色，阴性零业务新增、阳性恰一DRAFT。需要真实UI的不能直调API代替。原QA签canonical前不宣布S1通过。

私有证据 execution/source-s107-timer-delivery-20261022/；只读设计与共享Owner调查见 execution/coordination/s107-timer-minimal-domain-20261022/ 与 s107-shared-owner-dependencies-20261022/。源码、测试、合并、部署、业务验收分别报告。S2独立跟进，不阻塞本批。

## 2026-10-22 候选验证

真实PostgreSQL13.23的attempt03目标退出0：包括迁移72到72.1、Hibernate/JPA查询启动、旧映射冷启动、实际Canvas调度触发、撤权/自然到期/越界、并发恢复和既有入口兼容，未跳过。早期H2夹具失败及真实PG的stale-managed版本冲突原始证据均保留；后者已通过flush/clear后重新加锁读取当前绑定修复。Web和类型检查单列证据，不替代业务签收。

最终窄修复关注：定时绑定已提交但响应丢失后，直接撤销仍须保留任务引用直到真实终态核对；未确认无绑定前不得清除恢复线索。只有相关Web目标复验，不因纯前端变化重复Java。最终完整门禁、提交/PR、合并、官方发布和原QA真实界面/独立SQL验收均各以对应实际回执为准，本文不预签任何尚未完成项。

生产只读19:50确认最新迁移20261103.72且72.1未应用；正式发布仍重新受Flyway顺序门禁约束。共享权限、角色、配置、S6迁移73/74及其他Owner范围未释放。原QA只对本批独立F006授权和一次DRAFT进行有限实测，独立SQL为只读；其他已通过场景复用证据。

## 2026-10-22 正式交付与原判据签收

任务状态：`merged`；原18文件源码scope已释放。原源码Owner实际退出且声明无后续写入，Root完成集成与交付；原QA仍唯一持有canonical CSV、手机和浏览器，其他任务Owner不变。

- 源码：PR #1111，最终候选`0efa71c242af1fbed93253231fd85a4666429ee2`；13:52:57 UTC手动合并为`e9d5fdc1657ae47048a349de4f6dcee66ba16985`。
- 检查：完整Java run39779262852/job119826173725、真实PostgreSQL并发job119826173410、Fresh DB run39779262729/job119826004758、Web run39779262679及其余必需检查实际通过。完整Java为16660通过、139既有跳过、0失败/错误；新增定时29项均执行通过。既有跳过及条件未运行项不计通过，未删断言或增加known-red。真实PG13.23目标门禁单列通过证据。
- 发布：clean exact-main `e9d5fd`统一官方发布成功；`DEPLOY_EXIT=0`、唯一`RELEASE_FINAL_STATUS=deployed`，迁移72.1应用成功，Java/Web可信制品复用、build_count=0。统一回执已覆盖真实upstream、健康及Web四方哈希；未重复构建、发布或同内容验证。
- 业务：原QA实际正常工作台授权/定时绑定、真实Canvas-Cron撤销拒绝及自然过期拒绝，配有界面截图、持久拒绝审计和独立数据库读回。数量/对象/动作严格请求体拒绝明确标作API辅证，不冒称UI操作或已到达grant执行层。已有Tool/Skill、旧会话撤权和管理员错主体证据复用，未重做其他九场景。
- 最终过期子例：一次自有token短暂行竞争，持有上界1.984秒，跨过原到期时间后ROLLBACK，精确PID事务/锁为0；真实cron返回授权过期拒绝，持久审计、无采购结果/消费。独立最终读回exit0，14组业务控制零变化、旧单据及角色投影不变，任务禁用且run终结。该控制条件不称自发调度延迟，未声称采得产品连接等待trace。

原QA于23:21签收S1-07，canonical仅这一行从未测改为通过，原判据及其他74行保持原字节；S1为10/10通过，全表14通过/1失败/60未测。canonical SHA256：`8ea09ed24f76536d4bf5422be172fcb637981428f656d31031f90e1f953cd955`。原QA实际CLI退出0、SAFE；本次最终裁决无新增浏览器、登录、API、SQL或业务写入。

**必须保留的试验失败**：前两次expiry测试工具分别因数字PK被脱敏、过时PENDING状态谓词而未取得预期行竞争；两次真实cron均在原到期时间前合法生成测试草稿。原定一份、实际两份DRAFT/明细，原聚合AFTER仍exit2/FAILED；不删除、作废、改绿或追认合规。修复私有工具后才取得最终真实expiry拒绝，新增业务单据0。两份草稿及失败范围记录保留，库存、收货、审批、资金、权限配置及非F006业务未变化。S1-07签收的是原表受控采购试点当前授权能力，不代表这次旧试验符合单草稿范围，也不代表通用Gateway或全自主采购已验收。

私有证据根：`C:/Users/Steve/Documents/cretas-optimization-20261019/execution/`。

- 源码/检查：`source-s107-timer-delivery-20261022/`、`coordination/s107-full-gh-artifact-20261022/`。
- 合并/官方发布：`coordination/s107-manual-merge-20261022/`、`coordination/s107-official-release-20261022/reports/cretas-1792677866-1901.json`。
- 实际UI/触发/历史失败：`s107-timer-original-qa-runtime-20261022/`、`s107-timer-original-qa-expiry-retry-20261022/`、`s107-timer-original-qa-expiry-predicate-repair-20261022/`。
- 独立SQL：`s107-timer-independent-db-20261022/timer-after-ready.json`保留原FAILED；`final-e-after-ready.json`是最终过期子例实际通过。
- 原QA正式签收：`s107-timer-original-qa-final-signoff-20261022/receipt.json`、`criterion-review.json`、`canonical-diff.json`、`scope-incident.json`、`safe.json`与实际`exit.json`。

S0真实配方/产能/物理基线未补齐项保持原状态；S2工厂管理员就绪入口仍由原Claude Owner独立接续，最新有界回执检查没有新增精确范围包。S6/C2/C3不因此释放共享scope，也不成为本次S1收口条件。本归档只改两份调度文档，不触发新的产品测试、构建或生产发布。
