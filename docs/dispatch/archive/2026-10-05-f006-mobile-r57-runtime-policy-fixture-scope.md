# F006 R57 runtime policy compatibility gate — 2026-10-05

Root DIRECT sole Owner. Base `218a7fab05a8caee1a7d826acbe7a3e78aa00f7c`; PR920 is open, unmerged and undeployed. Candidate 36-class trusted build passed 372 tests and fast CI actually executed new contracts. Full Java run37248782639 observed one failure in `RuntimeToolPolicyLoaderTest.loadsAllTenCompleteExplicitRuntimePolicies`: the old common inventory assertion still required SKILL_WORKFLOW only for stock, while R53 deliberately admits stock AI_INTENT_DISPATCH. Ratchet14276/red1/baseline0 retained privately; never suppress or add this failure to baseline.

Only reopened source file: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/gateway/descriptor/RuntimeToolPolicyLoaderTest.java`. Preserve every assertion; for stock assert exactly SKILL_WORKFLOW plus AI_INTENT_DISPATCH, for both other inventory policies retain exactly SKILL_WORKFLOW. All action/permission/risk/type/version/privacy/egress and malformed YAML controls remain intact. No production policy/code, grants, flags, API, provider, device or DB changes.

Acceptance: Java21 four loader tests; then immutable new14-file candidate/review, 37-class trusted artifact (previous36 plus RuntimeToolPolicyLoaderTest), and actual fast/full CI on the same new head before merge/deploy. Prior v3 review/build and CI receipts remain frozen. New trusted artifact is necessary after this integration test change and full-suite failure; no reuse as proof for a different tree.

Scope archived/released in v4 candidate: actual LoaderTest four cases passed, with all three inventory source sets now asserted exactly. Production hashes unchanged. Final 14-file review,37-class trusted build and same-head actual fast/full CI remain pending.
