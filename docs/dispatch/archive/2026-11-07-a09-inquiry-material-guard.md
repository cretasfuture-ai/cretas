# A09 standalone inquiry material reference guard

- Task: A09-INQUIRY-MATERIAL-GUARD-20261107
- Owner: 01a1a954/root; implementation delegate follows this registration.
- Base: 49009fd44ad1f348c5599a460744dd7ca177aebe
- Worktree: C:/Users/Steve/cretas-a09-inquiry-guard-20261107; branch codex/a09-inquiry-material-guard-20261107.
- User latest instruction removes the former freeze. Food released InquiryQuoteServiceImpl.java, InquiryQuoteServiceImplTest.java, InquiryQuoteComparabilityEntryContractTest.java and defect-related new unit/real-PG tests; no Food writer or atomic operation remains. Scope only transferred, not Food worktree.
- Confirmed QA failure D-A09-INQUIRY-MERGED-REF: after fresh read proved source material merged/inactive, standalone create still returned200 and inserted DRAFT. Own quotes cancelled, masters/supplier/product inactive; preserve failed evidence.
- Repair: reuse existing MaterialReferenceWriteGuard transaction row lock and fresh state guard; source-PO header must precede raw-material lock where applicable. Reject merged/inactive/missing/foreign material before inserts; preserve legitimate active-material behavior. Existing internal service null handling gains no extra guard; it is not an HTTP/PG creation capability (DTO NotBlank and entity/database NOT NULL remain unchanged). Do not rewrite shared guard, Purchase, Workflow, Supplier, Repository, Entity, Security or permissions.
- Acceptance: focused unit/entry regression plus real PostgreSQL opposing transactions/concurrency, prove allowed winner ordering and no quote inserted after merge commits. Verify rollback/no partial documents and cross-tenant rejection without disclosure. Official target artifact and mandatory CI, independent monetary/concurrency review, then authorized own-QA production retest after formal deploy.
- Maven/PG execution isolated in this worktree. Root only edits this dispatch/archive, no concurrent target directory writer. No production writes, deployment, commit/push by delegate.
- Temporary concurrency expansion: third implementation delegate justified by three independent export/receipt-cost/inquiry modules, separate worktrees/target directories and owned PG ports. No shared source, pom, selector or migration changes permitted. Release this slot on final target test receipt; serialize immediately if shared files, interfaces or runtime resources collide.

Same-cause sweep: `setMaterialTypeId`, `createPurchaseOrder`, `applyQuoteToDraftLine`, `lockActiveMaterials` checked in InquiryQuoteServiceImpl and PurchaseServiceImpl. The standalone inquiry creation was vulnerable; downstream purchase conversion already uses the shared active-material guard. Its old PG cases are baseline evidence and cannot replace this new inquiry-service PG regression. Root maintains the new PG selector registration; no shared Purchase/Material/Workflow implementation changes are in scope.

Root-only CI integration appends InquiryQuoteMaterialReferencePostgresContractTest to the explicit PG selector, preserving every existing main selector. The new test file is owned only by this task; the existing shared MaterialMergeConcurrencyPostgresContractTest stays untouched.

## Explicit shared merge-scope transfer

User explicitly confirmed Food released the necessary minimal files after the final review found the source-linked OPEN quote boundary. Root accepts unique write ownership of MaterialAliasMergeService.java and MaterialMergeReferenceReplacer.java for this defect only; no other shared Material/Purchase/Workflow/Supplier implementation is transferred.

Approved policy: an active inquiry (DRAFT/INQUIRING/QUOTED/SELECTED) for the duplicate prevents merge, including a source-linked open quote. After legitimate close/cancel, historical quotation is preserved and merge may proceed. Check this within the existing material/header lock protocol and repeat the fresh decision after locks. Do not guess or silently migrate quoted prices/units. Add direct actual-service PG coverage for linked quote-first rejection/no writes, legal cancel then merge, and merge-first with a real source-PO header lock boundary latch. A mock read model may load the actual PG draft, but row locks and services must be real.

## Frozen implementation and actual validation

MaterialAliasMergeService and MaterialMergeReferenceReplacer now block OPEN inquiry references during preview and the locked fresh decision. InquiryQuoteServiceImpl takes the source purchase header before the material lock, canonicalizes the reference, and reads duplicate quotes only after the material guard. No Repository/Entity/schema/permission change.

Actual frozen target results (private a09-repair logs; manual filename tags are not execution clocks): new InquiryQuoteMaterialReferencePostgresContractTest 5/5, BUILD SUCCESS at 2026-11-07T17:09:44+08:00; selected existing MaterialMergeConcurrencyPostgresContractTest 4/4, SUCCESS17:13:13; InquiryQuoteServiceImplTest and InquiryQuoteComparabilityEntryContractTest32/32, SUCCESS17:15:49. All skipped=0. The linked request uses the actual source purchase header and material lock; OPEN quote-first blocks merge with no changes, actual cancel allows merge and preserves historical quote; merge-first rejects with no half quote. Original failed fixture/latch logs retained and not counted as passes. Private PG59058 was stopped by the execution Owner.

Independent risk final review pending. Root received exclusive source/target ownership after the delegate terminal receipt. Commit/push/PR/build/deploy/production QA retest remain separate facts; no production mutation was issued in this repair slice.

## Independent review correction before delivery

The earlier5/5 log is an actual successful runner result but DOES NOT prove the merge-first quote header wait: the two-thread executor queued the third quote task and the merge itself could trigger the shared spy latch. Root withdraws that concurrency claim; it is not a passing final gate. The original failure/success logs remain private. The sole implementation worker now changes only the new PG class: three runnable tasks, explicit quote-thread identity, independent proof that merge acquired the header before quote starts, quote header-attempt signal plus not-completed assertion before releasing the material holder. A same-transaction unflushed create then merge must also raise MATERIAL_MERGE_BLOCKED and roll back all new quote/alias/reference writes. Existing32unit and4otherPG results cover only their own unchanged ranges. Source policy itself has no new review blocker, but commit/PR is held until the strengthened final PG proves these boundaries.

## Final strengthened PG gate and risk receipt

The corrected six-method real PostgreSQL suite passed with no skips. The only subsequent change expanded the same-transaction alias assertion from duplicate-only to the complete factory, covering aliases moved/inserted to the master. That method alone was rerun successfully (1/1, zero skipped, BUILD SUCCESS2026-11-07T17:35:29+08); the other five unchanged method bodies, existing4merge regressions and32unit cases retain their passing evidence. Final PG class SHA256 F3F20118F9FF1DF004317447C96A2AC6169F20CC26C7A924F9A7F1C35BF9A7A8. No source policy change occurred during these test corrections.

Independent monetary/concurrency final review CLEAR, no remaining P0/P1 blocker, bound to the final hashes above. Same-transaction native-query AUTO flush visibility is now actually proved (JDBC sees0 before merge, OPEN blocker then rolls the entire transaction back); final alias/material/PO/quote reads cover the right target. Private PG59058 stopped and listener released. The original false-positive five-method result and earlier failed fixtures remain retained, not relabeled as final proof.

This final candidate archives the task summary and removes only its own ACTIVE addition; Root remains the unique PR/release coordinator through actual handoff. Commit/push/PR, official target build, mandatory CI, deployment and own-QA production retest are separate pending facts; no production request was issued by the code executor.
