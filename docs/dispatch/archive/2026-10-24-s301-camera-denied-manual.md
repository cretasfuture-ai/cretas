# ERP-S301-CAMERA-DENIED-MANUAL-20261024

State in-progress; sole Owner Root. Base7d4603b6d3f7373967a1217e2a275d228239418a. Implementation in existing isolated C:/Users/Steve/cretas-s315-semi-input-20261024, distinct Native directory from S315 worker. No simultaneous Git index operation; Root commits after worker safe handback. OriginalQA owns phone/browser/canonical.

Exact writable files:
- frontend/CretasFoodTrace/src/screens/quality-inspector/QIScanScreen.tsx
- frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorBatchLookupScreen.test.tsx

Actual QA denied-permission page cannot directly enter batch number while granted branch can. Source trace confirms denied action only navigates QIInspectList. Scope is direct manual prompt reuse, explicit manual-input wording and honest loading state. Do not claim the list navigation itself is wrong, change camera permissions, widen business access, skip API lookup, change standards or submit quality records.

Target: existing qualityInspectorBatchLookupScreen.test.tsx with original scan assertions preserved; render denied branch, click manual input, assert exact lookup then QIForm identity, unknown batch remains rejected, no camera grant call. Run Jest --runInBand with isolated cache; reuse identical locked dependency installation read-only where supported, otherwise isolated install without changing lockfile. Full checks/official signed OTA/originalQA real denied-permission retest remain later, not implied by unit success.

## UX Flow Analysis

User: frontline quality inspector using a phone, may decline camera access but knows the batch number.

| Step | Visible | Action | Expected |
|---|---|---|---|
| 1 | Camera permission explanation and manual input alternative | Choose manual input | Existing required batch-number prompt |
| 2 | Entered batch number | Confirm | Same exact authorized lookup, loading feedback |
| 3 | Real batch/product form or truthful failure | Inspect context or retry | No automatic quality submission |

Friction: manual entry currently depends on camera permission or list eligibility. Response: reuse existing cross-platform prompt directly on denied branch, preserve scanner/list routes elsewhere. Five rules: required input remains bounded; actual batch identity comes from server; no new free-text business decision; lookup creates no business entity; denied permission has usable next action. Existing prompt/error/retry behavior retained. This narrow slice does not claim whole-screen touch metrics or network acceptance; originalQA measures those separately.

## Actual offline finding and exact extension 09:24

OriginalQA B actual disconnected default network, pressed submit once; form retained values but displayed raw English Network Error. Root viewed submit-offline-observed.png; independent31 business groups equal/A2/B0. Add only transport-error presentation to this Native recovery slice. Preserve backend business refusal wording, standard-stale decisions, inputs, request counts and guards; transport detection must not swallow server errors. Reuse existing quality translation network retry text in the dynamic form.

Additional four exact files:
- frontend/CretasFoodTrace/src/screens/quality-inspector/qualityInspectionSubmission.logic.ts
- frontend/CretasFoodTrace/src/screens/quality-inspector/QIFormScreen.tsx
- frontend/CretasFoodTrace/src/__tests__/unit/screens/qualityInspectionSubmissionLogic.test.ts
- frontend/CretasFoodTrace/src/__tests__/unit/qualityInspectorDynamicStandard.test.tsx

Target tests: original batch lookup screen, submission logic and mounted dynamic-standard screen. Must prove transport message translated, unchanged input/note, no fake success/navigation, backend refusal still preferred, ordinary message retained. No payload/quality result/permissions mutation. First target5passed; reuse until related source/test changed. Isolated npm ci --legacy-peer-deps matches CI policy; normal npm ci failed lock interpretation and read-only external dependency experiment lacked Expo tsconfig resolution. Original failure logs retained; incidental npm yarn.lock update reverted only to pre-command identical tracked content. No dependency/lockfile change in delivery.

Source freeze24f71a6694: exact6files, lookup5 and logic/mounted25 targets passed with original assertions. Source scope released to Root integration under ERP-S0S3-ACCEPTANCE-20261023. No new OTA or real post-fix QA yet. Original evidence coordination/s301-camera-denied-manual-20261024 preserved.
