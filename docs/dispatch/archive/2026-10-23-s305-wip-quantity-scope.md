# ERP-S305-WIP-QUANTITY-20261023

- Base SHA: `7b211d798fdd7f7519d820ba2503b7cea72eb32f`.
- Unique implementation write Owner: `/root/s305_wip_quantity`; Root retains integration/review/release decisions. Root's previous S305 source is frozen, immutable in PR1117; Root will not concurrently edit these product files. This follow-up must not push to, cancel, expand, or block PR1117 without a new concrete reason.
- Worktree: `C:/Users/Steve/cretas-s305-wip-quantity-20261023`, branch `codex/s305-wip-quantity-20261023`.
- Confirmed defect evidence: private `execution/coordination/s305-downstream-quantity-gap-20261023/receipt.json`. Actual F006 report23989 OUTPUT0.81kg, task1989, WIP383 produced0.81/consumed0.80/available0.01. Current correction applies report/task output changes without WIP reconciliation. No unsafe correction has been executed.

## Exact product write scope

1. backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceImpl.java
2. backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/WipInventoryService.java
3. backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java
4. backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/WipLedgerEntries.java
5. backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceContractTest.java
6. backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProductionReportCorrectionFlowContractTest.java
7. backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipOutputCorrectionContractTest.java (new)
8. backend/java/cretas-api/src/test/java/com/cretas/aims/service/wip/WipOutputCorrectionPostgresContractTest.java (new)

## Root interface extension after 84cfb4f8aa — 2026-10-23

The executor released the initial eight files cleanly at84cfb4f8aa6db943c34452d5a77ad5e3ead06682 with28 target passes including2 actual isolated PostgreSQL17 tests. This is not integration acceptance: the executor and Root both confirmed that existing report reversal rejects report-linked ADJUST and `replayMovingAverage` ignores the new correction ADJUST. This necessary consumer compatibility is now released to the same sole executor, with unchanged Base7b211 and no change to frozen PR1117. ACTIVE/Owner scan found no separate active reversal writer.

Additional exact write files:

9. backend/java/cretas-api/src/main/java/com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java
10. backend/java/cretas-api/src/test/java/com/cretas/aims/service/reversal/ReplayMovingAverageTest.java
11. backend/java/cretas-api/src/test/java/com/cretas/aims/service/reversal/ReportCorrectionLedgerReplayContractTest.java (new)

Replay must incorporate only the explicitly identified quantity-correction ADJUST into net produced quantity while preserving the original total cost, unknown-cost propagation and all ordinary stocktake/reversal semantics. A downstream INPUT reversal after consuming corrected stock must not erase the correction quantity. Direct reversal of an already quantity-corrected source report may conservatively reject before any mutation with a specific actionable conflict explaining the supported correction route; do not misreport its valid correction ledger as corruption, or implement a broad new reversal workflow. Unknown/ordinary ADJUST keeps all existing rejection behavior.

The initial implementation unconditionally clears WIP unitCost even with a known accumulated cost. Correct this within the original WIP scope: unchanged known accumulated cost and changed positive produced quantity give a derived unit cost using existing rounding conventions; unknown cost stays unknown. A real zero cost may remain zero; never synthesize unknown cost as zero, never discard known cost without a concrete unsupported-path rejection. Keep immutable original ledger and capture honest correction snapshots.

Prevent precision drift between report/task/WIP persistence: verify actual quantity column/DTO semantics, and reject unsupported proposed precision before mutation rather than silently round differently. No Entity/schema edit is released.

Target selector extends the four original classes with `ReplayMovingAverageTest,ReportCorrectionLedgerReplayContractTest,BusinessException409HintCoverageContractTest`. The existing hint ratchet is read/run only; fix product hints rather than its baseline or assertions. All new409 branches require actionable hints. Preserve original tests; add known/unknown cost, positive/negative correction replay, downstream reversal, direct reversal refusal, precision refusal and scoped rollback assertions. The earlier strict-Mockito fixture failure is not behavioral red proof; preserve it as a tool/test-fixture failure, then capture one actual guard/wiring mutant-red and restored-green with exact source hashes and only the affected target. No repeated full/build and no production operations. Return updated exact files/commit/tests/real-PG/unsupported paths and SAFE_CODE_OWNER_RELEASED.

No Entity/Repository/query/migration/Security/controller/frontend/CI/release/ACTIVE/canonical edits by the executor. Necessary additions return an exact EXPANSION_REQUEST before modification. Only Root may later append the new real PostgreSQL test to the existing CI PG selector after reviewing its execution contract; no whole-workflow rewrite.

## Bounded behavior and acceptance

Trace actual normal posting semantics first, including output/good/defect quantities, semiOutputs, FINISHED/multiple postings and existing cost/audit rules; do not assume every report maps to one WIP row. Keep submit/approval tenant, role, report/plan locks, terminal/settled and duplicate guards. A notes-only zero inventory delta must preserve stock and avoid fake adjustment rows.

Implement a dedicated atomic correction operation for proven simple, same-unit, unconsumed WIP only. Lock the actual WIP using existing repository methods, preserve accrued cost/null semantics, recompute quantity/availability and append immutable clearly identified report-correction ledger evidence (correction ID and report ID). Never mislabel it as ordinary reversal or guess kg/box conversions. Recheck on approval before workflow transition. Existing downstream consumption, missing/ambiguous posting, unit mismatch, unsupported multi-output or other unreconciled stock path must reject quantity-changing corrections with actionable 409 and zero report/task/workflow/WIP/ledger effects; do not reject lawful notes-only correction solely because an output was consumed. Existing real consumed383 must reject unsafe quantity changes; choose an existing genuinely unconsumed QA output for later business validation only after Root fresh readback, not by inventing inventory.

Tests must prove old behavior red, safe increase/decrease and zero delta, actual consumed/missing/unit/complex-path refusal, idempotency and rollback. Preserve every existing assertion and permission/tenant/approval/settlement guard; no known-red, skip-as-pass, fixture weakening or fake success. Use actual PostgreSQL transaction testing for atomic rollback and competing consumption/recheck; mocks alone do not close stock correctness. No schema mutation is authorized; an unavoidable query/Entity change requires explicit scope expansion and applicable true JPA gate.

Development target selector: `ProductionReportCorrectionServiceContractTest,ProductionReportCorrectionFlowContractTest,WipOutputCorrectionContractTest,WipOutputCorrectionPostgresContractTest` with the repository's existing real-PG opt-in flags/wrapper, recording actual executed classes and failures. Read existing helpers; do not rebuild an E2E scaffold. No full suite/build/deployment by executor. Root later freezes the follow-up once, runs necessary final gates and reviews actual version integration with1117; no duplicate full or candidate rebuild during development.

Resources: Windows native isolated worktree/target/generated files; at most2CPU/2GiB per JVM, free physical memory>=8GiB before Maven, only one Maven process in this worktree, sequential targeted commands. Original Windows-light runner remains3CPU/6GiB and Aurora full remains untouched. Any real-PG test cluster must use existing installed binaries, a new owned data directory/database/port55328, never shared55327 or Docker/WSL maintenance. No browser/phone/production mutation or grandchildren. Stop at a safe checkpoint if resources unavailable and return evidence, not a fabricated pass.

Receipt: task/Base/actual files/commit/commands+exit+executed classes/logs, original red/restored green, real-PG atomic evidence, unsupported paths and remaining business acceptance; `SAFE_CODE_OWNER_RELEASED`. No whole S3-05 PASS from source or notes-only acceptance.


## Root 接收与 CI 接线范围（2026-10-23）

执行者最终 6bd4e8bf62a4d1af96830a1cc40e22fca4c45d3d 已明确 SAFE_CODE_OWNER_RELEASED；原11文件在 Root 集成分支与原提交逐文件 blob 相同，复用 PG13.23/55项及 mutation-red/restored-green 目标证据，不重复执行。原 Base7b211保留；Root 集成 Base为实际 main7378eb962c9dd13b2594a1a6d63ffceb5c4a5533，WT仍 C:/Users/Steve/cretas-s107-timer-delivery-20261022，branch codex/s305-wip-delivery-20261023，Root唯一集成写 Owner，原执行 worktree 保持冻结供独立审阅。

Root 仅新增第12个精确文件 `.github/workflows/java-gate-pr.yml`：在既有 `postgres-reversal-concurrency` 的真 PostgreSQL 命令选择器追加 `WipOutputCorrectionPostgresContractTest`，不改 job if/runner/资源/断言/失败判据，不把全量中该 opt-in 类的 skip 当执行。现场 ACTIVE 没有该文件在途写者；旧 Line C opt-in ticket 仍 queued，开放 #747 仅 scripts 不触及该文件。现有迁移/Repository/JPA 门禁全部保留；新 PG 测试是实际服务事务+JDBC回读，不冒称 Hibernate gate。

最终候选冻结后统一跑必要完整 CI、官方 Java 构建（原目标集合+新4个WIP/replay类+JavaPrGateTieringContractTest；真实PG零跳过）和独立风险复核。只变 Java，不重复 Web/RN 构建或已通过场景。合入/部署/原QA真实纠错前后数据库验证分别记录；S305保持原FAIL。新候选posting23991只是只读认证，不授权把功能量当物理生产事实。


## 已确认 P1 成本精度缺陷，原 Owner 窄修（2026-10-23）

独立风险审阅 FAIL/HOLD：6bd4 的 apply 按持久化 accumulated_cost scale2 算单价，而 correction-aware replay 从 IN quantity×unitCostAtTxn 未舍入成本重建单价，导致同一更正数量0.82/累计成本10.13，单价12.3537→12.3476。原PG裸numeric fixture绕开了生产精度。原55green/变异证据真实保留，不能签该成本判据。

唯一代码 Owner 重新指定 /root/s305_wip_quantity，原WT C:/Users/Steve/cretas-s305-wip-quantity-20261023、Base6bd4e8bf62a4d1af96830a1cc40e22fca4c45d3d。仅本节6文件可窄修：
- `service/wip/impl/WipInventoryServiceImpl.java`
- `service/reversal/impl/ReportReversalServiceImpl.java`
- `test/service/wip/WipOutputCorrectionContractTest.java`
- `test/service/wip/WipOutputCorrectionPostgresContractTest.java`
- `test/service/reversal/ReplayMovingAverageTest.java`
- `test/service/reversal/ReportCorrectionLedgerReplayContractTest.java`

main/test完整前缀沿用上方11文件。Root集成副本保持原内容只读，不与执行者同改；Root单独拥有已登记CI选择器。先以生产 numeric(14,2)/unit_cost scale4（数量按Entity实际精度）复现原6bd4的 apply→下游OUT→正REVERSE/replay 成本漂移，再同一断言修绿。仅 correction-aware 成本口径统一，不扩为全局成本重构，不只改断言承认两个单价。真实PG13独占55328/原私有PG13目录，绝不用13读17目录；单Maven/CPU2/JVM2GiB/free≥8GiB。命令为原7类55目标+新增实际round-trip测试，日志/rc/XML/停止与SAFE回执；已通过无关测试及ADJUST语义变异不重复。

保留原数量/成本/未知null/普通ADJUST拒绝/租户/审批/下游/回滚断言，不改Entity/Repo/查询/迁移/UI/CI/发布/ACTIVE/canonical/生产。发现更广依赖先回报；冻结后再独立复核，必要完整检查只对最终候选集中一次。

## 同批补齐未知工时保持（2026-10-23 11:15+08）
Root实际BEFORE：report23989.total_work_minutes=NULL、task1989.actual_minutes=NULL；当前已发布applyApproved对全NULL reports无条件sum=0/setActualMinutes，会在notes-only或quantity-only纠错时将未知工时写成0。Root在提交/批准前设置root-business-hold.json并撤回该次QA activation.released，未声称已出现数据库写入。原QA保留已观察实际状态，不取消/重放；原S305 FAIL不变。

同一执行Owner /root/s305_wip_quantity 新增两个精确文件 scope：ProductionReportCorrectionServiceImpl.java、ProductionReportCorrectionServiceContractTest.java（完整main/test路径沿用原11文件登记），当前P1续修切片从6扩为8，Base仍6bd4，Root不同时修改这8文件。先以真实NULL形态目标断言复现不应被改变的task.actualMinutes；只在确实更正工时时才更新对应工时汇总，纯备注/数量更正不得把未知工时填0或改已有工时。保留原已知工时更正和聚合断言，不重构工时/报工模型，不添加Entity/schema/API。使用原目标选择器受影响测试，成本/时间两个已证实问题同批冻结、同批完整检查，避免再发一轮版本。Root持有CI/台账/集成；原Owner仍不碰QA/生产。

## 同一P1的累计成本可表示性边界（2026-10-23）
66010d delta复核：工时修复与CI接线可接收；原成本P1仍HOLD。新replay将scale4单价快照回乘数量，不保证scale2累计成本无损：合法quantity1000/cost10.13，单价0.0101回乘后累计10.10。原0.82样例绿不足以签普遍守恒，57目标证据真实保留。

原执行者仍唯一代码Owner，原WT/branch不变，续修Base66010d68fef655ccd1bead9574c6dd53a07cb2c2，原8文件scope不扩大。最小修复为数量更正前检查成本快照可无损回乘；不满足明确409+行动提示并零写。对有已知累计成本且更正后产量归零也需明示可表示性限制，不能丢掉已知成本；unknown仍null。不加schema/Entity字段或把累计成本塞进语义为单价的字段。真实PG生产precision负例999.99→1000/10.13（及零量边界）先红后绿，保留小量positive/完整roundtrip/分钟断言。Root集成副本保持66010d同内容，不与执行者同改；最终freeze后才触发必要完整CI/build。


## 最终冻结接收（2026-10-23 12:05+08）

原唯一执行者 aad677f3e6f318c2ac8b16239f439eaa745252b6 已释放代码 Owner；Root逐文件接收原11文件（集成761f9ce9ab），风险复核 PASS_FOR_SOURCE_FREEZE。原57目标与新增7+5行为证据保留；最终12 green使用main.skip/manual javac，不能替代正常主编译。green日志实际成功却被receipt误记失败，原始版本另存；真正编译阻塞日志仍待澄清。不以该元数据或旧class冒充完整通过。

Root唯一集成Owner，精确产品范围11文件不变；仅新增既有java-gate-pr.yml真实PG选择器一个类。源码冻结，正常clean package及原判据必要最终CI集中一次；受影响目标为原7类，加权限/Security/PartialUnique PG/Workflow/DecisionType及selector/架构/hint门禁。新PG为独立13.23/55329/专属目录数据库，CPU2/JVM2GiB/free>=8GiB，原55327及他人任务不动。真实JPA/PG门禁保留，skip不当PASS。

实际候选23991/task1990/WIP384只读认证为原单IN/未消耗/1盒/累计成本2.22；与已消耗23989不同。不以功能数量代表物理生产。后续真实纠错必须官方发布后新鲜BEFORE、正常岗位入口、独立审批和逐阶段SQL核对；当前S305仍FAIL，无生产纠错写入。纯文档main069d不触发产品重测。


## 冻结源码交付归档（最终候选）

任务 ERP-S305-WIP-QUANTITY-20261023，状态review：原执行者与Root产品写scope均冻结释放，不授予其他Owner修改；后续检查/合并/官方发布/原QA业务验收继续由在飞ERP-S0S3-ACCEPTANCE-20261023跟踪。源码aad677与Root11文件blob一致，最终backend tree e24febaa0fd9c334487a72c8b94b5ee47ac0bf54。Root唯一附加CI文件仅追加真实PG类，原门禁全部保留。

Root干净正常4801主源码及2025测试源码编译、15目标选择器实际125项0fail/error/skip，其中WIP unit7/真实PG13.23五项；全量CI仍待执行，不能签合并或业务。官方Java-only build出口0/一个RELEASE_FINAL_STATUS=built，制品f3bc706a8d5d52ac103f9b09c7262b94913e261146de70750233b8dcd26a6bcb已预上传，未安装/切流；Web/RN未变、不重建。私有证据coordination/s305-final-build-20261023包含命令/退出码/源码树/实际测试inventory。缺失的原始失败编译日志仍UNKNOWN，不以该陈述认定产品编译失败；本次正常clean证据已补齐。

S3-05原FAIL不变，尚未生产数量纠错。后续必须最终完整检查→手动合并→clean exact origin/main官方Java发布→原QA真实UI与独立DB逐阶段核对，不能以源码或制品签业务。原FAIL/旧证据和不支持路径均保留。
