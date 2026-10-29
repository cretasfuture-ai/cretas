# SC38-RELEASE-EOF-20261029

Owner: Root. Base: `f8678420b027e3192ab6ceab68b4302bf0fefc8b` (merged PR #1301).
Scope: only terminal blank lines in factory-charts `purchase_inventory.py`,
`test_purchase_inventory.py`, `test_wip_plan_flow.py`, `wip_plan_flow.py`, and this archive.

Exact-main release preflight rejected PR #1301's new-file terminal blank lines before the Python deployment command ran. Production remained unchanged. Remove only those terminal blank lines and retain one final LF; independently compare parsed ASTs before/after and preserve their SHA-256 evidence. No business logic, SQL, tests, permissions, migrations, configuration or dependency change.

Validation: `git diff --check`, AST identity for all four files, independent read-only review, frozen factory Python suite, normal PR checks and exact-main release preflight. The existing registry-audit-expiration CI baseline remains separate. Full evidence is private in `test-output/sc38-eof-proof-v1.json` and `sc28-python-final-sc38-eof-final-v1.{log,json}`. The narrow scope is released in this final formatting commit; merge/deployment are recorded separately on completion.
