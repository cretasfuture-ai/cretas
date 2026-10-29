# SC66 pending quality labels

- Owner Root; DIRECT. Base 63615ec58bfa8880df2409ec885039dde3796dc7.
- Scope: frontend/CretasFoodTrace/src/screens/quality-inspector/QIRecordsScreen.tsx
  and QIRecordDetailScreen.tsx; coordinator dispatch/archive only.
- Trigger: actual Xiaomi F006 quality record list correctly showed PENDING as
  待检, but its detail hardcoded 待复检 and both grade badges exposed UNKNOWN.
- Correct pending labels reuse records.pending; absent grade displays an em dash.
  Preserve result mapping, disposition permissions, persisted inspection data,
  source tests and all prior failed evidence. No backend/security/data changes.
- Acceptance: record mapping target tests and RN typecheck, then exact merged-main
  Android OTA and read-only physical list/detail checks of the same old pending
  inspection. Do not create another inspection or repeat completed batch11018.

- Validation: existing record-mapping5 passed; complete RN TypeScript passed.
- Source frozen for review; product scope released on merge. Final native OTA and
  exact old pending UI recheck remain delivery steps; no repeated QC submission.

