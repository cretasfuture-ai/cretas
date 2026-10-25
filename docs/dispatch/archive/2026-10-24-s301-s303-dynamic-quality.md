# ERP-S301-S303-DYNAMIC-QUALITY-20261024

Status review; Base `4a48c5b49c3a5f5214c68a3add5081ee409d88b2`; code Owner `/root/s305_correction_boundary` released at `1dfd75e020306e1f07a241f7cb321da825ae62d8`; Root now owns integration and delivery.

2026-10-24 final CI correction: Root additionally owns the existing `backend/java/cretas-api/src/test/java/com/cretas/aims/service/QualityInspectionCountBalanceContractTest.java` and `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProcessingServiceImplQualityRatePrecisionContractTest.java`. Actual full job 120617119273 exposed missing batch repository injection in the former; same-cause inspection found the latter's positive cases use the same incomplete legacy fixture. Supply same-factory batch and empty binding repository fixtures, preserving every original assertion and production guard. Target selector: `QualityInspectionCountBalanceContractTest,ProcessingServiceImplQualityRatePrecisionContractTest,ProcessingServiceImplDynamicQualityStandardContractTest`. Preserve the original full failure; let its run finish collecting remaining failures before pushing the consolidated correction. No baseline, workflow, permission, Repository, Entity or production-source change is authorized by this test-fixture correction.

Worktree `C:\Users\Steve\cretas-s301-dynamic-quality-20261024`; branch `codex/s301-dynamic-quality-20261024`. Original QA alone owns phone, browser and canonical CSV. Actual native baseline S3-01 FAIL, ERP-S3-QI-DYNAMIC-STANDARD-001; S3-03 remains untested. No business acceptance claimed.

## Exact scope

- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProcessingController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/config/QualityCheckItemBindingDTO.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/quality/DynamicQualityFormDTO.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/quality/DynamicQualityInspectionRequest.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/quality/QualityInspectionSnapshotDTO.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/ProcessingService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProcessingServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QualityInspectionServiceImpl.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProcessingServiceImplDynamicQualityStandardContractTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProcessingServiceImplInspectionValidationTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/QualityInspectionServiceImplTemplateTest.java`
- `frontend/CretasFoodTrace/src/__tests__/gates/navigatorMountContextReachability.test.ts`
- `frontend/CretasFoodTrace/src/__tests__/gates/retiredScreensStayRetired.test.ts`
- `frontend/CretasFoodTrace/src/__tests__/unit/api/qualityCheckItemApiClient.test.ts`
- `frontend/CretasFoodTrace/src/__tests__/unit/api/qualityInspectorRecordMapping.test.ts`
- `frontend/CretasFoodTrace/src/__tests__/unit/navigation/qualityInspectorRoutes.test.ts`
- `frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorDynamicStandard.test.tsx`
- `frontend/CretasFoodTrace/src/__tests__/unit/screens/qualityInspectionSubmissionLogic.test.ts`
- `frontend/CretasFoodTrace/src/__tests__/unit/screens/qualityInspectorHomeContract.test.ts`
- `frontend/CretasFoodTrace/src/navigation/QualityInspectorNavigator.tsx`
- `frontend/CretasFoodTrace/src/navigation/qualityInspectorRoutes.ts`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/QIBatchSelectScreen.tsx`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/QIFormScreen.tsx`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/QIInspectListScreen.tsx`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/QIRecordDetailScreen.tsx`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/QIRecordsScreen.tsx`
- `frontend/CretasFoodTrace/src/screens/quality-inspector/QIScanScreen.tsx`
- `frontend/CretasFoodTrace/src/services/api/qualityCheckItemApiClient.ts`
- `frontend/CretasFoodTrace/src/services/api/qualityInspectorApi.ts`
- `frontend/CretasFoodTrace/src/types/qualityInspector.ts`
- `web-admin/src/views/quality/standards/__tests__/qualityStandardProductBinding.mount.spec.ts`
- `web-admin/src/views/quality/standards/list.vue`

No Repository, Entity, migration, permissions, label-QC implementation, profile/settings or OTA/APK changes. Any expansion requires Root conflict review. Do not modify frozen PR #1124.

## Interfaces and compatibility

{
  "form_read": "GET /api/mobile/{factoryId}/processing/batches/{batchId}/quality-form -> batch/product context, standardRevision, ordered items {bindingId,itemId,itemVersion,label,valueType,unit,effectiveStandardValue,effectiveMinValue,effectiveMaxValue,effectiveIsRequired}",
  "dynamic_submit": "POST /api/mobile/{factoryId}/processing/batches/{batchId}/quality-inspections (new independent batch URL endpoint) with {standardRevision, answers[...]}; do not silently downgrade absent revision to legacy. The old query endpoint remains a separate compatibility route with the policy above.",
  "submit_rules": [
    "batchId path must resolve in factory and match the loaded product context",
    "standardRevision mismatch returns 409 with refresh action; no inspection write",
    "unknown answer/binding/item, duplicate answer, missing required item, or wrong product binding returns 4xx with actionable field target",
    "out-of-range value persists as FAIL with server-derived result; it is not rejected as malformed",
    "client result/passCount/totalScore cannot override server-derived FAIL",
    "snapshot stores standard revision, binding/item IDs, item versions, effective values/limits/unit/required and observed answers"
  ],
  "detail": "GET /api/mobile/{factoryId}/processing/quality/inspections/{inspectionId} returns saved snapshot; absent snapshot returns standardBasis=UNKNOWN",
  "legacy_endpoint_policy": "The existing POST /processing/quality/inspections?batchId=... remains readable for already deployed callers and records without dynamic context are explicitly standardBasis=UNKNOWN. It must not bypass required bindings: when the resolved product has any enabled binding, the legacy endpoint returns actionable 409 requiring the dynamic form/revision; only products with no enabled binding remain compatibility-allowed. Existing legacy records are never backfilled.",
  "new_dto_service": "No new service is required: extend ProcessingService/ProcessingServiceImpl and QualityInspectionServiceImpl. Add the three typed DTOs under dto/quality only if the current Map boundary cannot safely carry revision/items/snapshot; no repository/entity/migration expansion."
}

Use product binding, existing customFields immutable server snapshot, JWT actor and factory-scoped batch. Bound products cannot bypass required items through legacy submission. Out-of-range measurements persist as FAIL; malformed/unknown/missing/revision-mismatch requests write nothing. Historical records without snapshot remain UNKNOWN. Preserve all existing unrelated permission and tenant assertions.

## Target validation

- cwd `backend/java/cretas-api`: `mvn -Dtest=ProcessingServiceImplInspectionValidationTest,QualityInspectionServiceImplTemplateTest,ProcessingServiceImplDynamicQualityStandardContractTest test`
- cwd `frontend/CretasFoodTrace`: `npm test -- --runInBand --runTestsByPath src/__tests__/gates/navigatorMountContextReachability.test.ts src/__tests__/gates/retiredScreensStayRetired.test.ts src/__tests__/unit/api/qualityCheckItemApiClient.test.ts src/__tests__/unit/api/qualityInspectorRecordMapping.test.ts src/__tests__/unit/navigation/qualityInspectorRoutes.test.ts src/__tests__/unit/qualityInspectorDynamicStandard.test.tsx src/__tests__/unit/screens/qualityInspectionSubmissionLogic.test.ts src/__tests__/unit/screens/qualityInspectorHomeContract.test.ts`
- cwd `web-admin`: `npx vitest run src/views/quality/standards/__tests__/qualityStandardProductBinding.mount.spec.ts --maxWorkers=1`

Final complete gates and official release are Root responsibilities. Runtime UI and independent database readback required before original QA signs S3-01/S3-03. No synthetic QA values may be called actual factory quality standards.

## UX Flow Analysis

{
  "用户画像": "质检员 — 低技术素养、在现场按批次扫码检查 — 需要看到产品/批次/当前检验标准后逐项录入，错误时能保留已填数据并知道下一步。",
  "用户旅程": [
    {
      "步骤": 1,
      "用户看到": "发起质检入口和待检批次卡片，含产品名、批次号、当前工序/状态",
      "用户操作": "选择或扫描一个批次",
      "期望结果": "回显身份并进入对应标准表"
    },
    {
      "步骤": 2,
      "用户看到": "标准版本、项目名称、单位、上下限、必检标识和当前产品",
      "用户操作": "逐项填写实测值",
      "期望结果": "边界预先可见，缺必检项不能提交"
    },
    {
      "步骤": 3,
      "用户看到": "提交前的产品/批次/标准版本摘要",
      "用户操作": "提交",
      "期望结果": "合格或不合格由服务端根据实测值判定；超范围事实保存为 FAIL"
    },
    {
      "步骤": 4,
      "用户看到": "成功记录或明确的标准已更新提示",
      "用户操作": "遇到 409 刷新标准后重新确认",
      "期望结果": "保留已填数据并避免旧版本误提交"
    },
    {
      "步骤": 5,
      "用户看到": "历史记录的标准版本/快照；旧兼容记录标记依据未知",
      "用户操作": "重开详情",
      "期望结果": "按保存快照展示，不按当前标准重算"
    }
  ],
  "摩擦点清单": [
    {
      "编号": "F1",
      "描述": "产品/批次身份不清会导致错批次提交",
      "严重程度": "HIGH",
      "来源规则": "fool-proof Rule 2"
    },
    {
      "编号": "F2",
      "描述": "质检员看不到单位和上下限容易录入错误",
      "严重程度": "HIGH",
      "来源规则": "ux-flow 内联规则/Rule 1"
    },
    {
      "编号": "F3",
      "描述": "标准更新后继续提交旧表会产生不可追溯记录",
      "严重程度": "HIGH",
      "来源规则": "业务判据 + 4xx next action"
    },
    {
      "编号": "F4",
      "描述": "失败后清空已填数据会迫使现场重录",
      "严重程度": "MEDIUM",
      "来源规则": "ux-flow 错误恢复"
    }
  ],
  "每个摩擦点的设计回应": [
    "F1 → 表单标题和批次卡同时显示产品名、批次号、当前工序；扫码后先回显再允许提交。",
    "F2 → 每个项目显示单位、有效范围和必检标签；唯一主提交按钮按 invalid/缺项禁用。",
    "F3 → 服务端以 revision 校验并返回 409 + 刷新动作；前端保留答案。",
    "F4 → 网络/业务错误显示后端具体 message 和下一步，保留当前草稿。"
  ]
}

## 2026-10-24 actual-entry scope correction
Root verified that navigator registration alone leaves the actual QI home without a batch-inspection or inspection-history action. Existing label-QC actions must remain. The same unique Owner may additionally modify `frontend/CretasFoodTrace/src/screens/quality-inspector/QIHomeScreen.tsx` and the already registered mounted/home/navigation tests to expose real user actions into the correctly mounted stacks. No other scope transfer. Existing profile/settings Owner is unaffected. Run targeted home, navigation and dynamic-form tests; preserve label-QC assertions.

## Tenant binding correction scope
Root confirms newly exposed product binding can accept an existing foreign-factory product through `existsById`. Same Owner may modify `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QualityCheckItemServiceImpl.java` and add `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/QualityCheckItemBindingTenantContractTest.java`. Use existing tenant-aware repository methods; no Repository/Entity/migration expansion. Target command appends `QualityCheckItemBindingTenantContractTest` to the existing Java selector. Required real-service positive same-factory and negative foreign/deleted product must assert no binding write on rejection. Product code cannot rely on the Web picker for tenant isolation.

## Final acceptance gaps and evidence scope, 2026-10-24
Owner remains `/root/s305_correction_boundary`, Base remains `4a48c5b49c3a5f5214c68a3add5081ee409d88b2`. Read-only review of `235f982483a03671c09e323d3068b3498a4c2c53` confirmed the original five fixes but found snapshot items absent from history and no stale-standard refresh action. Complete these within the existing registered form/detail/API/type files; preserve answers and use only persisted history. Correct the existing Web mounted test to use the actual product selector, without internal-state injection or `any`. Add missing positive/negative service cases within the existing contract test.

Additional exact test scope is `frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorDynamicRecord.test.tsx` and `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/QualityProductBindingPostgresRepositoryQueryValidationTest.java`. The latter must run actual JPA/PostgreSQL and prove same-factory active product true, foreign and soft-deleted false for the existing repository method; no Repository/Entity changes. Reuse the existing disposable-target guard and own random schema; no production database, no shared schema changes. Root assigns the stopped/idle S307 disposable local PostgreSQL instance only after checking no in-flight test, and retains final execution control. No CI workflow scope is released. The test must be explicitly selected in the candidate build and reported as executed, never count an environment skip as success. Add the new RN file to the focused Jest command. Final release remains held pending fixes, target checks, applicable complete gates, and original QA actual acceptance.

## Actual QA blocker: declared product unit absent from batch creation
Original QA on 2026-10-24 created two isolated software products with unit `盒`, then could not create their batches: normal UI offers only `kg/箱/件/吨`. No batch request was sent. Evidence is private `execution/s301-isolated-preparation-original-qa-20261024/batch-A-actual-unit-options.png` and `phase-prepared.json`; Root independently read back exactly two QA products and no other changes. Extend the same unique Owner scope by `web-admin/src/views/production/batches/list.vue` and `web-admin/src/views/production/batches/__tests__/batchCreateProductUnit.mount.spec.ts`. Use the selected product's declared unit in the normal form and include that unit in actual options, preserving deliberate legacy choices without inventing conversions. No arbitrary unit text, backend/schema changes, or edits to actual product master data. Acceptance: mounted real selector interaction with 盒 and kg products, switch product without stale unit, posted quantity/unit unchanged, and existing batch-list tests. This is a directly reproduced prerequisite defect, not an extension of frozen PR1124.

## Required scan/manual batch-number lookup wiring
Root traced the newly exposed QIScan normal scan/manual input action: `qualityInspectorApi.getBatchByQRCode` posts to `/processing/batches/scan`, which is not mapped in ProcessingController. The JSON barcode branch with an exact batch ID already uses the existing same-factory detail endpoint. Preserve that path; wire batch-number input to an actual read-only lookup, not paginated fuzzy-match fallback. The existing registered ProcessingController/ProcessingService/ProcessingServiceImpl and qualityInspectorApi files remain owned by the same executor. Use existing `ProductionBatchRepository.findByFactoryIdAndBatchNumber`; no Repository/Entity/query or permission expansion. Add exact test files `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/ProcessingQualityBatchLookupContractTest.java` and `frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorBatchLookup.test.ts`. The lookup must retain tenant authentication and the quality permission, reject empty/unresolvable input honestly, return the persisted identity, and never select a fuzzy or other-factory match. Cover the actual mapped method, precise repository invocation, and the actual API client request; test failures may not trigger invented data. Existing JSON-with-batchNumber barcode input must resolve the declared number, never stringify the entire JSON as a number. No batch state transition, production write, or new background activity. This closes the original S301 scan entry requirement before candidate freeze.

## Final normal-entry corrections (Root 2026-10-24 02:31 +08)

Original QA normal quality-manager UI proved that the standard editor has no value-type selector: RANGE A saved disabled; BOOLEAN B was not sent. Evidence is `execution/s301-disabled-standards-original-qa-20261024/standard-B-blocked.json` and the actual form screenshot. The already registered standards Vue and mounted test remain with the same Owner. Expose the existing RANGE/NUMERIC/TEXT/BOOLEAN types and a normal boolean value control, preserve existing edit compatibility, and cover the actual form/request. This is a reproduced prerequisite defect; no existing production standards may be changed by implementation.

Root also confirmed JSON batch-number scanning passed the whole JSON to the exact lookup. The existing QIScan file stays in scope. The requested real component callback test was created by the same Owner at `frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorBatchLookupScreen.test.tsx`; Root observed and accepted this exact test-only path now (not a claim it was previously listed). Only this additional test path is released; preserve prior tests and test actual plain/JSON-number/JSON-ID callbacks. Backend lookup negatives remain in its already registered contract file. Final selectors must fail if no tests execute; standalone MockMvc is not a production authentication proof. All original FAIL evidence stays intact.

Source corrections delivered at `1dfd75e`: parsed batchNumber reaches exact lookup, actual component scan callback controls pass, and the standard editor exposes the existing types. Root tightened the new negative test from generic RuntimeException to actual BusinessException400 / ResourceNotFoundException and verified blank input makes no repository call. `mvn -Dtest=ProcessingQualityBatchLookupContractTest,BusinessException409HintCoverageContractTest,GateSelectorCoverageContractTest test` finished exit0, 13/0/0/0 at 2026-10-24 02:36 +08; evidence `execution/coordination/s301-s303-implementation-20261024/root-final-lookup-target-exit.json`, exact test hash B6C45A8F5F60EDB1DBB461A9836EC01F6F9CDD6DFB731D5003D6CE10FD52EB00. Earlier actual PG product-binding proof and stale-refresh component proof remain reusable for their unchanged source; they do not prove the new lookup's live authentication. Final candidate checks, official backend/Web/native publication, real UI and independent SQL remain pending. No S301/S303/S312 business signoff is implied.
