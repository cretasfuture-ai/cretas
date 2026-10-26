# S2 readiness 原Source集成子任务（2026-10-22）

- Parent：ERP-S2-READINESS-CLAUDE-20261021，状态review。
- 子任务：ERP-S2-INTEGRATE-20261022，初始queued，待实际Owner/WT/命令ACK后Root释放。
- 集成Base：e64314097e8237823049650294e78a421fdd5650；这是合入前主线，部署范围据此计算，不将历史Python/Native改动算入本批。
- 原开发Base：5a0c01ff671857ff56fdba03ed27d46808a2fa76；PR1079实际HEAD bbe7d5ee43cfb45b7593e5e2873c67f94fddf575。两个Base分别保留，不倒改历史。
- 产品唯一Owner：local_f6b118ce-f036-4ece-9a0b-322073b20c74。集成/制品/发布唯一Owner：原Source Chat 01a15272-374d-72c0-bb20-cdfa0bd823f1。ACTIVE仍仅Root 01a1523e-881c-7a92-b7ab-f4b8c5a552df写。
- 原Source Native操作已actual exit0/SAFE；原QA Native真实日期/一次保存/刷新与独立DB验收、SAFE、actual exit0已齐。旧运行版本保留窗口解除。不是整个S1/S107通过，也不跳过S0未完成项。

## 精确产品范围与职责

以下9文件只接受原PR既有diff，Source不得修改产品实现。发现实际缺陷由原Claude Owner回修并重新冻结；不得擅扩Gateway/权限、采购、供应商、Orchestrator、S6、迁移、lockfile或生产定时。

- backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactoryBusinessReadinessReader.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactoryReadinessReport.java
- backend/java/cretas-api/src/main/java/com/cretas/aims/blueprint/canonical/FactoryReadinessService.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactoryBusinessReadinessReaderContractTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactoryBusinessReadinessRepositoryQueryValidationTest.java
- backend/java/cretas-api/src/test/java/com/cretas/aims/blueprint/canonical/FactoryReadinessContractTest.java
- web-admin/src/api/factoryReadiness.ts
- web-admin/src/views/platform/factory-review/__tests__/FactoryReadiness.spec.ts
- web-admin/src/views/platform/factory-review/components/FactoryReadiness.vue

Source先只读核fresh main/HEAD/精确diff/兼容性/当前CI、既有证明和现场两机资源，回传owner-scope-ack.json：实际会话、拟复用本人clean WT/branch/HEAD、Base、9文件、完整目标命令/资源与剩余必需门禁。不得碰Claude原WT；实际ACK与Root release文件齐后才checkout/build/merge/deploy。无需转移代码Owner；Source仅在自己既有WT生成测试/可信制品/发布输出，独占target和DB端口。

## 证据复用与命令

已实际独立审阅原三项问题修复，无剩余P1/P2源码问题；同HEAD精确源哈希、真实PostgreSQL13.23启动/JPA4与unit18合计22PASS/0skip及完整命令/退出码/清理证据已存在：execution/source-owner-next-actual-entry-20261022/s2-1079-pg13-final-proof.json。原PG17和Web目标证明单列，不倒填为新执行。先核相关源码/测试/依赖闭包一致，同内容通过直接复用；新的公共契约或真实依赖变化才补对应验证，不重复旧Full/变异。

Java受影响选择器：FactoryBusinessReadinessReaderContractTest,FactoryBusinessReadinessRepositoryQueryValidationTest,FactoryReadinessContractTest。
Web受影响入口：`npx vitest run src/views/platform/factory-review/__tests__/FactoryReadiness.spec.ts`，`npx vue-tsc --noEmit`；Source核实际既有运行命令，复用同内容有效证明，必要完整检查只对最终冻结候选集中执行一次。适用JPA/PostgreSQL门禁不得用H2/skip替代。

统一可信制品：`./scripts/deploy/release-cretas.sh --phase build --base-sha e64314097e8237823049650294e78a421fdd5650 --tests 'FactoryBusinessReadinessReaderContractTest,FactoryBusinessReadinessRepositoryQueryValidationTest,FactoryReadinessContractTest' --stage-backend YES-STAGE`。
必要检查通过且exact9无额外diff后，按既有授权手动合并原PR1079 exact HEAD；不得auto/admin强合并。合入后prewarm同选择器，done/already-warm后clean exact origin/main执行官方 `--phase deploy --base-sha e64314097e8237823049650294e78a421fdd5650 --tests '<同选择器>' --confirm-prod YES-PROD`。prewarm unavailable rc3按规则继续，不要求恢复付费。
仅Java/Web，禁止重发Python/Native或生产定时。统一入口若遇真实问题先定位，按已有正式组件规则和Root裁决处理，不手工拼普通全栈发布。完整exit/finalStatus/组件/四方hash回执必须保留；部署、CI、业务验收分别报告。

## 收口与资源

两台既有本地机器按现场CPU/RAM与其他任务排程，现有runner标签、3CPU/6GB保护不动；不取消他人、不重启共享WSL/Docker、不恢复退役runner。新测试/制品独占目录/target/数据库/端口，最终版本通过证据复用。
Source完成后SAFE零在途并回传精确版本/官方回执。原QA仍独占手机、浏览器、canonical CSV，Root另启同一原QA核S2-04/S2-07真实界面与独立DB。不得以两项源码/部署称整个S2通过；S0原FAIL/未测、S107timer与S6未交付缺项保持。

## 实际代码合入收口（2026-10-22）

原 PR #1079 精确 HEAD `bbe7d5ee43cfb45b7593e5e2873c67f94fddf575` 已于 `2026-10-22T07:31:52Z` 手动合入，merge `1ea4acdfb11b530d5f3d445d3c55743991159458`。原 Claude 产品 Owner 不变；原 Source 集成 Owner 已完成本次精确9文件接收，产品源码未另改。代码任务状态 merged，释放这9文件的在飞写锁；Source仍独占本人发布worktree、制品和在途发布，不授权清理。

Root 从 ACTIVE 归档本代码任务与 scope。最终必需完整检查/真实JPA证据、可信候选 Java/Web、精确依赖树/选择器和退出码见私有 `execution/source-s2-readiness-integration-20261022/`。候选 backend tree `07aa49351b85f788bd6fad4d6f708d470b5b349e`、Web tree `94a6e4cd530eb2243dc5e8fd0fb395a3a1972c2f`；本次仅归档文档，不重复产品测试或构建。

本归档不声称正式发布或业务验收完成。发布仍由原Source从clean exact origin/main走官方入口；Root在官方回执完成后组织原QA真实S2-04/07及独立SQL。实际F006目录READY/蓝图绑定与包装换算资料尚有前置缺口，按原判据记录，不能用代码合并/制品/部署代替业务PASS。S0原FAIL/未测、S1-07采购timer和S6未交付项保持。

## 官方发布与原 QA 实际结果（2026-10-22）

本段接续前述“业务验收待执行”，历史记录保留。官方 clean exact-main `a1e9fb8e2c80b9a8110bcce8233c2261ea83dbc7` Java/Web 发布已实际完成：DEPLOY_EXIT=0、RELEASE_FINAL_STATUS 恰好一次，Java/Web build_count 均0，169秒。真实 upstream/active服务/健康和Web四方哈希来自完整统一回执，不重复探测。原Source actual CLI exit0/SAFE，产品源码零改。

同一原QA actual CLI exit0/SAFE，实际正常工厂岗位菜单、Canvas仓库、供应原料和原料资料入口操作、截图、真实请求及独立数据库 before/after 已完成。Root独立解析24项/14控制、44账号保护和488权限保护：业务零变化，仅正常认证时间元数据变化。没有把后台200、源码或发布当UI通过。

结果：S2-04仍未测，缺合格实施岗位证明及目录READY/正式蓝图样本；S2-07仍未测，部分目标页面可达，但工厂正常入口无法到达就绪报告，恢复保存正例与适用条件尚未完成。原QA登记 `ERP-S2-FACTORY-READINESS-ENTRY-001` OPEN/P2；没有伪造后端403或据无蓝图推断状态算法错误。原始判据、所有旧FAIL/未测和其他73场景原字节保留，旧18缺陷原字节保留，新增此一缺陷。canonical当前75场景13通过/1失败/61未测；全S0/S1/S2均未签收。

实际证据：私有 `execution/s2-readiness-original-qa-runtime-20261022/receipt.json`、`source-gap.json`、`canonical-diff.json`、`exit.json`；`execution/s2-readiness-original-qa-db-20261022/`；`execution/coordination/root-s2-independent-after-accepted-20261022.json`。原始raw声明hash为LF文本，Windows落盘CRLF的字节hash另列，原文件未修改。

续修任务 `ERP-S2-READINESS-ENTRY-CLAUDE-20261022` queued，Owner保留原Claude `local_f6b118ce-f036-4ece-9a0b-322073b20c74`；Base a1e9fb8，等待原Owner独立WT/branch/精确文件包/验收命令确认，writeScope=[]，不得推定已领取或交接。仅正常工厂就绪入口与恢复再读问题；平台角色矩阵不全局放宽，不自动造/应用/发布蓝图，不改真实配方、产能、岗位权限或采购/S6链。Root保留接口/登记/集成/发布责任，原QA仍独占手机、浏览器、canonical。独立只读调查可并行，不释放代码写范围。
