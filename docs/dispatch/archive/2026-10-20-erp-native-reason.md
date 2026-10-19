# ERP-S1-NATIVE-REASON-20261020

- Base: `53ef60a49162f7b7fc2db4e8109c9b9e7eb49dc7`.
- Worktree: `C:/Users/Steve/cretas-erp-w3-acceptance-20261019`; branch `codex/erp-native-scheduling-truth-20261020`.
- Implementation Owner: `/root/native_reason_fix`; integration/final validation/commit/PR/release Owner: `/root`. QA Chat retains exclusive device/browser/business/CSV ownership.
- This commit records a reviewed implementation. Merge, publication and real business acceptance remain separate facts, tracked in the private stage receipt.

## Problem and final behavior

Production-plan detail reads `production-plans` and uppercase `ProductionPlan` states. The legacy reason endpoint reads the separate `SchedulingPlan` entity, whose states are lowercase. The old phone paths mixed these IDs, skipped the first page of scheduling results and promised a recalculation that the server does not perform.

- Production detail now opens the existing scheduling-reason screen without forwarding a production-plan ID. Its gate uses actual nonterminal production states and the real navigator type.
- Scheduling selection uses page 0, explicit current-day bounds and lowercase nonterminal statuses. Load failures stay visible as errors. The UI presents the actual plan name and saved notes, without inventing a kg output target or headcount.
- Saving rechecks the exact scheduling-plan ID/status and sends only `{planId, reason}` through the existing scheduling client. A synchronous lock and disabled controls prevent duplicate taps; errors preserve the reason. A mismatched successful response is shown as unconfirmed, not saved.
- Success states that only the reason was recorded and times/personnel remain unchanged. Normal refresh displays the saved notes. Unsupported optimization strategies, reassignment switches and invented improvement results are removed.
- The independent What-If feature is unchanged and is not accepted by this change.

## Scope and validation

Only `PlanDetailScreen.tsx`, `AIRescheduleScreen.tsx` and their two new `reasonOnly.test.tsx` mounted tests changed. No API client, dependency, Java, database, permission, CI selector or runner change.

- Mounted coverage: distinct entity IDs, real state formats, zero-based query, correct selected scheduling request, empty/no-plan/busy rejection, failed-list visibility, pre-write status/ID rejection, preserved input, truthful success and refreshed notes.
- Root focused run: 2 suites / 10 tests passed, 0 skipped. Earlier fixture/compiler errors and old-source red evidence are retained privately and are not counted as passing tests.
- Frozen complete Jest run: 313 suites / 3315 tests passed, 0 failures/skips; reported time 145.851 seconds.
- Frozen TypeScript check: exit 0. Source/test hashes and exact commands/exits/logs are retained in `execution/native-reason-20261020/` under the private stage evidence root.
- Current commit checkpoint: publication and actual dispatcher-device business acceptance are pending. Existing F006 dispatcher credentials are absent from current credential truth; no account reset or privilege change was made.

## UX Flow Analysis

### 用户画像
调度员需要在当前计划上记录变更原因，并准确知道系统是否修改了安排。

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|---|---|---|---|
| 1 | 生产计划详情的“记录原因” | 打开调度计划列表 | 不把生产计划编号当调度计划编号 |
| 2 | 可记录的调度计划、原备注和能力说明 | 选择计划、填写原因并保存 | 空值和处理中不重复发送 |
| 3 | 记录结果与刷新后的备注 | 核对记录 | 明确时间和人员不变；失败保留输入 |

### 摩擦点清单
| # | 摩擦点 | 程度 | 来源 |
|---|---|---|---|
| F1 | 把记录原因误认为已完成重排 | HIGH | 业务结果与真实能力一致 |
| F2 | 混用计划编号或显示无效配置 | HIGH | 业务上下文与可执行操作一致 |
| F3 | 保存失败丢失输入或重复保存 | MED | 错误恢复与防重复 |

### 每个摩擦点的设计回应
- F1：入口、说明和结果仅描述记录原因，移除虚假优化结果。
- F2：选择正确类型的调度计划，显示真实备注；记录前核对编号与状态。
- F3：失败保留原因，处理中锁定操作，结果不明时提示先核对。
