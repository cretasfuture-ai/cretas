# ERP-S1-PURCHASE-RECOVERY-UI-20261020

DIRECT implementation Owner: Root Chat01a1523e-881c-7a92-b7ab-f4b8c5a552df.
Base28d02b70af193b87597cc9d67687fa62f8b13324; isolated worktree C:/Users/Steve/cretas-s1-purchase-recovery-ui-20261020; branch codex/erp-s1-purchase-recovery-ui-20261020.

Exact scope: web-admin/src/api/smartbi/intent-chat.ts; web-admin/src/views/smart-bi/AIQuery.vue; web-admin/src/views/smart-bi/components/chat/AiOperationCard.vue; web-admin/src/api/__tests__/intent-chat.purchaseRecovery.spec.ts; web-admin/src/views/smart-bi/components/chat/__tests__/AiOperationCard.spec.ts; web-admin/src/views/smart-bi/__tests__/AIQuery.stdLayout.spec.ts; this archive. No backend, ACTIVE, QA CSV/browser/phone, supplier/Workdesk/permissions/CI/lockfile modifications. The separate backend Chat01a15712-62f2-7313-a46b-2d02972e77eb remains sole writer of its16-file A/B scope and ACTIVE. Root does not write its worktree.

Reason: read-only cross-layer tracing found that OUTCOME_UNKNOWN was not mounted as an operation card, no authenticated status GET existed in the mapper/UI, and a transport-error confirmation could return to idle with newly generated idempotency keys. These are actual recovery gaps in the proposed S1 loop; not grounds to claim a deployed business failure.

Contract frozen from the backend WIP: PREVIEW resultData.grantId and confirmableAction.previewData.grantId provide preview-{previewId}; confirmation OUTCOME_UNKNOWN returns resultData.grantId. GET /api/mobile/{factoryId}/ai-purchase-task-grants/{grantId} returns the existing envelope with {grantId,state,purchaseOrderId,grossAmount,quantity,version,replay}. No invented statusToken. This UI must reject mismatched/malformed status responses, preserve unknown and never auto-confirm/revoke or claim approval. Support preexisting generic confirmations without broadening their business permissions.

Implementation: scoped type-safe status GET, stable confirmation-attempt identity for the same token/snapshot, visible unknown/recovery state in the actually mounted confirmation card, explicit query-only action after a lost response, stale-factory/response protection. Reuse current Element Plus/project design tokens. No new native UI or second browser.

Acceptance: meaningful API mock tests for exact endpoint/envelope/ID binding and stable-vs-changed proof identities; mounted actual AiOperationCard unknown/lost-response/query error/query result/no duplicate confirm; mounted AIQuery propagation; retain existing tests. Development targeted Vitest with max2 workers, then one final required complete Web suite, vue-tsc and production Vite build after source/tests freeze. Runtime UI/independent DB readback remain QA-owned and separate. No live API calls during frontend fixtures, no guessed user/session/production mutation.

Only Root-owned local npm/test/build directories; install own dependencies (no junction or shared node_modules). Source and test evidence private execution/s1-purchase-recovery-ui-20261020. Coordinate convergence with the backend Owner at a safe checkpoint; do not trigger another Java full suite solely for this Web change, and do not publish from a feature branch.
