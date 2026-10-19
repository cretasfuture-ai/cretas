# ERP S0 unit-plan visibility

ERP-S0-UNIT-HINT-20261020, root DIRECT. Base a8539649847199cadbb4bd11a2164a0822b98a25, root-only WT C:/Users/Steve/cretas-erp-w3-acceptance-20261019, branch codex/erp-unit-plan-20261020. Exact code scope frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx and existing src/__tests__/unit/screens/YieldStepReportScreenOperatorExits.test.tsx; root alone dispatch/archive. No backend/DTO/entity/migration/limits/API/business/CSV/permissions changes; QA retains phone/browser/runtime/CSV ownership. After the first full run actually failed on the old output-label assertion, scope also includes the single expected-label update in existing YieldStepReportScreenImbalanceConfirm.test.tsx; preserve all its quantity/unit/limit assertions.

Current actual native closure14CLOSED/1OPEN: original operator submitted1989 0.9kg->0.81kg and1990 0.8kg->1box, independently approved. UNIT remainsOPEN/S0-06FAIL because explicit missing-input-plan hint and genuine same-unit planned-input positive are unproven. OriginalFAIL/criteria preserved. Quantities are QA fixtures, not physical production.

Read-only source trace confirmed task plannedQuantity/plannedUnit is OUTPUT. Frontend's optional plannedInputQuantity has no backend DTO/entity/toDTO source. Frozen Workflow/BOM ports hold recipe reference standardQuantity/unit/revision, with nullable/multiport/quantityMode/scaling semantics; they are not an authoritative batch-task input plan. Missing task input plan must stay unknown, including equal input/output units. Do not infer it from output quantity, packaging or yield; do not create substitute production tasks or mutate frozen recipes to manufacture a positive.

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像
操作员，低技术素养；在车间确认任务、选料、填写实际投入和产出，需要区分计划产出与缺失的投入定额。

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|---|---|---|---|
| 1 | 原品名/批次/当前工序及明确的计划产出 | 核对任务 | 产出计划不被误当投入量 |
| 2 | 投入定额未设置及联系生产主管的提示 | 依真实领料和现有单位/库存边界填写 | 不显示假计划或据此制造上限 |
| 3 | 原提交/审批/已投入摘要 | 原正常操作 | 写入、幂等与审批规则保持原有语义 |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|---|---|---|
| F1 | 缺定额时只隐藏计划，操作员不知道发生了什么 | HIGH | fool-proof1/5，错误恢复 |
| F2 | 计划数量未明确写产出，等单位时仍可能误解为投入 | HIGH | fool-proof2 |

### 每个摩擦点的设计回应
- F1：两个真实页面分支都显式提示投入定额未设置、按实际领料填写并联系生产主管；不补假数，不放宽现有库存/单位限制。
- F2：已有任务计划行明确为计划产出，保持原数值/原单位，不成为投入上限。保留品名/批号/工序上下文。
- 不增加点击目标/输入项；现有按钮间距、触摸反馈、数字字号、加载、错误数据保留及审批/幂等路径均不在本批修改。Rule3无新增自由文本，Rule4无新增写入，Rule5提供可执行的主管确认指引。

Acceptance: actual-mounted existing component tests cover missing/null input plan with batch1box, same-unit task output plan labelled as output with no derived input limit, and already-explicit input plan retaining its current branch. First reproduce new assertions on old source; then target tests, TypeScript and one frozen RN full suite as applicable. Actual phone/UI evidence follows the normal signed OTA path and sole QA Owner after merge. This patch alone cannot close UNIT: genuine batch-task input-plan contract/data positive remainsHOLD, S0-05/history/human timing also remain unproven. Reuse unchanged Java proof; no Java full rerun for display-only RN changes.

Actual first checks: new assertions on old screen failed2/passed14; corrected screen target16/16 passed. TypeScript exit0. First full RN311suites/3305tests found one failure: ImbalanceConfirm still expected the old literal `计划数量  10 盒`; all other3304 passed. Update this expectation to the intended explicit output label, keeping all input kg/output box and no-derived-cap assertions, then run the two affected mounted suites and one necessary frozen full run. Original failed logs retained privately; no assertion is removed.

Strict read-only runtime discovery found79/79 nondeleted F006 task plannedQuantity NULL,176/176 frozen input-port standardQuantity NULL and no separately stored task input-plan column. No source exists for a genuine input-plan positive; this UI patch cannot be called that contract's implementation or whole S0 acceptance.

Final frozen validation: affected mounted suites22/22 passed; full RN311suites/3305tests,0failed/0skipped, exit0 in75.814s; TypeScript exit0 on the identical final screen (only a string expectation changed afterward). Evidence root C:/Users/Steve/Documents/cretas-optimization-20261019/execution/unit-plan-20261020/, including original red/first full failure, target-v2-green.log, full-v2-results.json and full-v2-exit.json. Final source scope is screen14 changed lines, OperatorExits45 added test lines, ImbalanceConfirm one label expectation; no dependency or quantity logic changes. Implementation scope archived/released for PR review; merge, signed OTA publication/application and actual mounted phone verification remain separate pending facts. UNIT remains OPEN and no canonical acceptance row is changed by root.
