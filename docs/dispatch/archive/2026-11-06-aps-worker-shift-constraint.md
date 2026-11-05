# APS-WORKER-SHIFT-CONSTRAINT-20261106

Status: merged; scope released. Owner: 01a1a954/root.
Base: 342dc816fd24deecb85ea0cdbe60a0ae9247daf4.
PR1496 head 8b58f989f410650445a1763ec8c678683d5fb6c1 normally squashed as f158ffe45aad1f793a1fe4f3327c9770b1b803a1.

Urgent-insert impact reads real HR shifts, employment dates, leave and task overlaps. Explicit worker IDs are rechecked before slot claim/plan creation; an omitted list preserves PENDING/later assignment. Responses expose counts and NOT_RESERVED. Missing facts remain unverified. No Entity, Repository, query or migration changes in this slice.

Validation: final clean combination with actual main56a93, eight classes / 67 tests / zero failures, errors or skips, including six real loopback PostgreSQL cases and six slot concurrency cases. Candidate cc3fe684 and actual merged main have identical whole tree 74132c19badb7cea41b3ba880e964e95360d9c3d. Java tree 0488874bed7c6e8ae982f58009c8d93f6ee02170; JAR SHA256 52b81cb62fb4299beef537d28e2d905a58987aefa1206f97f7a5f0bbcdb384ac. CI42109580783: fast 7583 total, zero failures/errors, 282 conditional skips; PostgreSQL successful; full suite skipped by configuration. Initial missing-hint CI failure was fixed without changing its frozen baseline. Historical fixture failures and baseline behavioral red are retained privately.

Official stage succeeded in 20 seconds. Exact-main official Java-only deployment succeeded in 141 seconds: exit0, exactly one RELEASE_FINAL_STATUS=deployed, reused build0, active blue10010 and health pass. Parent1495 had already applied ordered migration94->95; this slice performs no DDL. Web and OTA were not republished.

Postdeploy existing F006 slot impact GET returned HTTP200/success, workerAvailabilitySource=HR_SHIFT_AND_ASSIGNMENT, workerVerified=false, availableWorkers=0, workerReservationStatus=NOT_RESERVED. F006 has no HR shift assignments; this proves the unknown-data boundary rather than positive production staffing. Business writes: zero. No new plan, staff assignment or recurring schedule. Private evidence: execution/takeover-tasks-20261105/aps-worker-constraints-20261106, including version-bound target reports, CI, manifest, official build/deploy receipts and readonly before/after.

Resources released: isolated PG57052 stopped normally; no device occupied; official release locks released. Worktree/evidence retained. Remaining full APS constraints, personnel reservation, N+1 scale validation and original human signoff are not claimed complete; the original signoff blocked row remains unchanged.
