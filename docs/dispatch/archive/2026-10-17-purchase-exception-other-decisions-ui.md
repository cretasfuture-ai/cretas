# 采购异常其他决定类型：真实 UI 补验

- Task: `PURCHASE-EXCEPTION-OTHER-DECISIONS-20261017`; Owner: root.
- Base: `46e1cb94c813b7f3e4d32130319665a76a547569` (PR #1009 merged).
- Scope: only the missing real UI acceptance for `ACCEPT_OVER`, `ACCEPT_SHORT`, and `REQUEST_RESUPPLY`; no product source or test changes.
- Environment: reused loopback Web/Java/PostgreSQL and the existing private test attachment bucket. No production access, deployment, migrations, Docker or WSL.
- Runtime reuse: the running decision implementation is the version accepted by #1009. Its subsequent 409 action-hint-only increment was separately target-tested and CI-tested in #1009; no runtime restart or duplicate full suite was necessary here.

## Results

| Scenario | Real UI and persisted result | Side-effect check |
| --- | --- | --- |
| UNDER_RECEIVE → ACCEPT_SHORT | Owner selects “按实收”, submits 200; refresh and detail show RESOLVED / ACCEPT_SHORT, operator and notes | No new stock, payable, return, or receipt changes |
| UNDER_RECEIVE → REQUEST_RESUPPLY | Owner selects “补货”, submits 200; refresh and detail show RESOLVED / REQUEST_RESUPPLY | Records the decision only; no automatic replenishment order, stock, payable or return is generated |
| OVER_RECEIVE → ACCEPT_OVER | Owner selects “接收”, submits 200; refresh and detail show RESOLVED / ACCEPT_OVER | Existing confirmed receipt remains effective once; no additional stock, payable or return is generated |
| UNDER_RECEIVE → ACCEPT_OVER | Actual UI submission returns 400, “少收异常不能使用超收决策” | Exception, receipt, PO, stock, payable and return snapshots unchanged |
| OVER_RECEIVE → ACCEPT_SHORT | Actual UI submission returns 400, “超收异常不能使用少收决策” | Same zero-write assertions pass |

All three successful decisions have a persisted decision time, expected actor, and null return-order association. The five observed decision responses are `400, 200, 200, 400, 200`; no unexpected failed business HTTP responses occurred.

## Fixture and accounting separation

The two shortage cases reuse pending exceptions from previously accepted partial receipts. A new over-receipt fixture was created through UI: copy an existing PO, submit it, use an independent account for OA approval, then receive 11 boxes of flour and 10 packs of bags with an uploaded receipt document. The confirmed receipt creates exactly two inventory batches: 110 kg and 100 个, and one payable row of 580.00. These are receipt effects, measured before the acceptance decision. The subsequent ACCEPT_OVER decision changes none of them. Total return-order count remains 10 throughout this slice.

The first attachment upload was blocked by the browser's local-only guard. The existing private **test** bucket was verified before allowing only its attachment prefix for this test factory. Upload, attachment registration and receipt confirmation then succeeded. The existing receipt draft was continued; no second receipt was created. A stale UI locator and an OA response matcher were corrected without repeating the already successful approval. These were harness issues, not product failures.

## Restoration and controls

- The temporary factory-super-admin procurement override was granted and removed through Canvas UI; both writes returned 200. The original role override map is restored.
- Factory settings, organization/user roles, data-scope configuration, warehouses and approval configuration match the initial snapshot. Inventory tolerance was not changed.
- Receipt processing updates the two materials' moving-average price and audit time; all other material fields remain unchanged. The decision steps themselves do not change material records.
- Comparing the previous slice's existing-factory snapshot finds 502 of 505 tables unchanged. The three differences are scheduler activity: behavior metrics at 01:00 and automatic weight adjustment/scheduling configuration at 02:00, before this slice's first decision at 07:59. The original failed comparison is retained. This is not reported as a fresh all-505-tables unchanged result; no fresh pre-slice full-control baseline was captured.

## Evidence and verification reuse

Private evidence under the existing `assembly-integration-assessment-20261015/target-ui` directory:

- `decisions-real-ui-evidence.json`: sanitized decision responses, factory-scoped network statuses and fixture references.
- `decisions-assertions.json`: successful persisted-state, zero-write negative-control, accounting and restoration assertions.
- `return-db-decisions-{before,mismatch,short,resupply,over-ready,over-mismatch,over,final}.json`: read-only database snapshots.
- `decisions-{accept-short,request-resupply,accept-over,under-mismatch,over-mismatch,permission-restored}.png`: actual UI screenshots.
- `decisions-control-comparison.json`: the retained historical-control comparison, including the three scheduler differences.

The task-specific assertion script completed with exit code 0. Product source and test contents are unchanged from #1009, so its final Java 15,318 / zero red / zero baseline, PostgreSQL 94 / zero failures or skips, Web 5,606 passing / five historical skips, real JPA and mutation evidence are reused. No local product full suite or UI scenario from #1009 was repeated. This PR contains only this acceptance note; applicable documentation checks and exact-head CI are checked before manual merge.

## Capability boundary

All three remaining **decision-entry** paths are now UI-accepted. REQUEST_RESUPPLY currently stores a requested replenishment decision; automatic supplier follow-up, replenishment-order generation and subsequent supplemental receiving are not implemented or accepted by this slice. Production deployment and production acceptance remain unperformed. No product defect requiring a source change was found within this scope.
