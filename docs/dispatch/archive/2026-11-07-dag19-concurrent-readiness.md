# DAG19 同期需求与进度

Owner：食品 Root，独立 worktree `cretas-food-plan-readiness-20261107`，分支 `codex/plan-concurrent-readiness-20261107`。Base `f34a984b5e3e48ad2061e28edff967b686be5f9f`。初始登记为 `571a1f4da84c6f66e8a918c7c9d77f16f93fef91`，提交前汇入已正式交付的 #1530（仅既有回放卡 CSS/归档）；本切片已验证源码字节保持不变。

原 Artifact 19 要求：缺料预警把同期其他计划尚未占用的需求算进去，给出能完成的比例，是否先做一部分由工厂决定。用户另明确要求实际完成进度，不能把物料分配进度当作实际产量。

本切片只读，不改变 N1 默认裁定、工厂开工开关、强制开工原因、现有报工锁与预留，也不扩称完整 APS。已交付 APS 单位/资源确认保持原 Owner 交付。

允许修改范围：后端 `service/production/PlanConcurrent*`、`dto/production/PlanConcurrent*`、`controller/PlanConcurrent*` 及其对应测试；Web 新 `PlanConcurrentReadiness` 组件/API与对应测试、`views/production/plans/list.vue` 接线；本任务 dispatch 行和本归档。其他 Entity/Repository/APS/预留写入口不在当前 scope，若确需扩大须先更新登记及必要验证。

Root scope 补充：允许在 `service/production/PlanCostVarianceCalculator.java` 抽出原有标准用量的无价格纯读取接口，以及相应契约测试。原成本计算继续调用同一核心，禁止复制一套标准料公式、改变成本/价格/份额/出成率语义。最终同时运行原成本计算目标回归。此项不涉及 Entity/Repository 或预留写入。

实现前需冻结同期窗口、剩余/已占用去重、单位和实际产量来源。日期、不可变 BOM/单位或实际产量口径不足时明确 UNVERIFIED，不返回确定的 0 或 100%。取消/完成计划不新增需求；并发读必须有一致快照。物料匹配字段保留旧语义。

独立纯计算任务 `FOOD-DAG19-DEMAND-MATH-20261107`：Base 同上，执行 Owner `artifact_merge_safety`，仅允许新增 `service/production/PlanConcurrentDemandCalculator.java` 与 `service/production/PlanConcurrentDemandCalculatorContractTest.java`，不能修改其他源码、dispatch、迁移、依赖或生产对象。Root 负责实际来源、授权、快照、接线与最终交付。验收：对应 Maven 目标测试；若共享准备未就绪可先回传源码和未执行项，不启动全量。

纯计算契约：输入必须已按明确日期窗口/库存所有权池筛选并归一到同一物料单位。每条计划需求为锁定标准总需求、已正式消耗、仍持有预留三项独立事实；未占用需求为 max(标准需求−已消耗−持有预留,0)，库存可用量已经减过所有预留，不重复扣。当前计划剩余标准需求为 max(标准需求−已消耗,0)。不擅定排程优先级，返回“其他同期计划优先”下界与“本计划优先”上界，均包含本计划仍持有的预留。该区间是物料参考，实际完工/入库比例另列；不下达分配或开工裁定。任一覆盖/单位/计量事实不足，整体比例 UNVERIFIED；不得用未知零填补。取消/完成计划不争用新需求。

独立 Web 任务 `FOOD-DAG19-READINESS-WEB-20261107`：Base 同上，执行 Owner `artifact_param_isolation`，仅允许新增 `web-admin/src/api/planConcurrentReadiness.ts`、`views/production/plans/components/PlanConcurrentReadiness.vue` 及该组件的 `__tests__/PlanConcurrentReadiness.spec.ts`。Root 负责 list.vue 接线、依赖安装、最终构建和实际只读验收。DTO 真值为新 `PlanConcurrentReadinessDTO.java`；手动 GET `/{factoryId}/production-plans/{planId}/concurrent-readiness`，严格响应/比例/null处理，切厂换计划或关闭销毁后拒绝晚响应，错误保留明确重试入口；不调用写接口。验收：该组件 Vitest 及必要类型检查，不主动全量/不运行生产/不提交、不写 dispatch。

独立真实 PG 测试任务 `FOOD-DAG19-READINESS-PG-20261107`：Base 同上，执行 Owner `second_customer_package`，仅允许新增 `src/test/java/com/cretas/aims/integration/PlanConcurrentReadinessPostgresContractTest.java`（后端 cretas-api）。真实 Hibernate/既有 Repository + 新 service，使用 `DisposablePostgresTargetGuard` 与现有 PG 环境开关，验证竞争需求、单位、终态、实际入库进度及 REPEATABLE_READ 下并发提交不撕裂。Root 统一顺序 Maven/自有 PG 运行；执行者不启动 DB/Maven/生产、不修改产品源码或 dispatch。

只读验收脚本 scope 补充：Root 唯一修改 `scripts/e2e/production-readonly/core/run-suite.js` 的新场景登记；`artifact_merge_safety` 唯一新增 `scenarios/plan-concurrent-readiness-readonly.js` 和 `tests/plan-concurrent-readiness.test.js`。任务 `FOOD-DAG19-READINESS-HARNESS-20261107`，Base 同上；沿用唯一 canonical entry、mutation guard、零写入与证据 schema，不放宽红线、全局脱敏或计数。只针对显式自有 F006 QA 计划，通过可见生产计划详情点击核算，记录实际 GET 与截图；Node 目标测试后 Root 跑既有 harness 全量回归。脚本执行不授权新生产写入。

Root 同时负责根据 canonical source 确定性重建 `scripts/e2e/production-readonly/mcp-entry.js`，不手改 bundle、不放宽 drift gate。首次 Node 296 中 294 通过，2 个相同 bundle drift 断言正确拒绝旧 entry；更新 source 后必须重建并复验。

独立风险审查修正（均在未合并候选内）：全厂聚合只允许 unrestricted creator scope，受限账号即使拥有目标也 403，不能推断隐藏需求或静默排除而虚增比例；目标 PLAN_PATH 和整个 cohort 产品/固定配方均需 actor-aware VIEW_FULL，审计失败同样拒绝。仓库复用 WarehouseResolver.productionPickingWarehouseIds；有效期按 max(业务今天,计划生产日期)。已完成后撤回待批 PENDING_APPROVAL 不增需求；任何 WIP 回投引用不能直接抵扣原料标准需求。对应真实 PG 与权限反例补齐后才交付。

完成门禁：改动相关 Java/真实 PG 快照与并发证据、Web 目标测试、必需 CI；候选官方构建、normal merge、exact-main 正式发布；自有 F006 只读 UI/API/SQL 验收。实现、测试、commit/push、PR、部署和业务验收分别记录。

候选源码收尾（2026-11-07）：独立终审 CLEAR；7 类目标 53 项全部通过，含真实 PostgreSQL 7 项（并发 reader 保留旧库存快照、独立 writer 已提交新库存、停用/线边仓、过期和撤回待批等），自有 PG 正常停止并回读无 marker/监听。真实 PG 的保密 guard mock 仅代表隔离测试厂无保密策略，不作为保密授权集成证明；新调用方权限/保密拒绝与审计失败传播由对应 Contract/MVC 测试覆盖。Web 14 项与 vue-tsc 通过；canonical Node 296 项全部通过。源码 task 归档释放随最终候选提交；Root 在协调状态继续独占本切片 CI、构建、合并、发布与业务验收，未将候选验证表述为线上完成。

保留原始失败：首次 Root 私有多类编译 include 选择器导致 NO_TESTS；后续数值 0 与 0.0 的表示断言及测试 Repository hook 未触发屏障已修复，原日志/XML未覆盖；测试 helper AOP import 修正后最终 53/53。产品风险修正均在未发布候选内完成，不代表线上曾失败。暂无 Entity/Repository/迁移/RN/OTA 改动。

官方组合补充：首个候选 18b4eb 的 53 类/358 项中，唯一 BareUnitEqualityRatchetContractTest 拒绝纯计算器两处裸单位比较；整体 build 失败，未发布、未取得 Java 可部署制品，PG 正常停止。保持棘轮基线，改为同一单位契约换算系数必须精确等于 1；公斤/kg 同义可同，g/kg 和只/个不能偷换数量。新增正反例后相关 3 类/28 项通过。另真实只读 schema 确认物料名称 varchar255，展示名称支持 512 UTF-16 code units（可容纳 255 个补充字符），ID 边界未扩大；Web 16 项/tsc、Node 297 项通过。canonical 读已取消 QA 时显式选择全部状态。限定源码增量终审 CLEAR；新 head 仍须取得自身必需 CI、53 类官方制品与正式/业务回执，不复用旧失败 head 的完成说法。

CI scope 补充（Root 唯一 Owner）：允许仅在 `.github/workflows/java-gate-pr.yml` 的现有 PG 显式选择器末尾加入 `PlanConcurrentReadinessPostgresContractTest`，不移除任何既有 selector、不改 runner/权限/阈值。head255aab 官方53类360项、实JAR/manifest均已通过；其 PG CI 实际455项日志没有新class（0 hits），不能当作新7项的CI证据。因此补齐固定车道后要求新head实际日志确认新7项执行。此改动仅CI/文档，backend/Web源码与测试树未变，既有官方制品按内容哈希复用，不无理由重建。full当前排除了PG opt-in，不能替代该真实PG车道。
