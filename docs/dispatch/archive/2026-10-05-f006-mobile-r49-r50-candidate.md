# F006 R49/R50 reviewed candidate — 2026-10-05

R49 Base a2cda2ac64bb5e7d20f790618c16cefee7bb7fff; R50 Base 7fe33ae1d32fd757b2bd66a27eaaa546f7c6975f. Root is integration/release owner. Source locks are released in this final candidate commit; merge/deployment/runtime acceptance remain pending until separately read back.

R49 uses the rehydrated current user and canonical price permission at synchronous gateway execute/preview serialization. It preserves existing domain permissions and restores previous thread context on success/failure. Real gateway, real policy, production Jackson module and concrete inventory tool: old implementation RED 7/3 failures; seven target classes GREEN 106/0/0. No live AI leakage or full mobile/Mac mini acceptance is asserted.

R50 adds permission OR annotations to four auxiliary GET routes. Statistics accepts warehouse/inventory or production read/read_write because production planning consumes it; low-stock/alerts/expiry-warnings accept only warehouse/inventory read/read_write. Real controller/interceptor/canonical resolver fixture: corrected old implementation RED 104/50 failures; targeted GREEN reported in private source freeze. Initial wrong expiry fixture path is excluded instrumentation. Missing/unknown actors deny before business. Source fields and price masking are preserved.

Runtime baseline: 18 roles/72 reads all returned 200 before fix. Under verified live module grants the chosen compatible policy requires 36 denials, with 12 statistics and 8 warning readers. This does not prove 36 disclosures of nonempty data. No role grants, JWT, Entity, Repository/query declarations, migrations, service row-scope or business data changes.

Pending gates: independent security review of eight source files, trusted artifact, actual fast contracts and full Java suite on final HEAD, merge, unified main deployment, 18-role route readback and task log/artifact evidence. R48 inventory data-scope and low-stock fixes must remain preserved. R51 AI async actor propagation is a separate source-only risk; it is not fixed or accepted by this candidate. The entire phone campaign remains incomplete.
