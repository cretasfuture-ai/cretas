# F006 joint-analysis positive acceptance

- Task: FADJOINT28; Owner: Root; Base SHA: c32239bf30bd8322dab043421df9e2d88cd507d0.
- Scope: production-readonly core/run-suite.js, factory-analysis scenario and its test, README, generated mcp-entry.js, this archive. No product or deployment changes.
- User explicitly authorized one complete F006 production/quality/cost test dataset and Web/native positive acceptance. No non-F006 writes, inventory movements, settlements, migrations or permission changes.
- Fixture: production batch 11002 / QA-JOINT-20261028-001; quality c8c23739-175a-48ec-9d57-4d589b8a0527; product 27be0257-70ac-4ec2-b6d7-b1c1df120312 (QA-S3-1027-卤牛肉); existing same-factory plan f941a8ea-e30f-4c74-92bd-2595b68b4ffc; workflow 375 version 1 copied by live DB trigger.
- Explicitly synthetic analysis fixture, not physical production or accounting settlement. Output 100 boxes, good 96; inspected 20/pass 19/fail 1; costs 800+250+150+50=1250 CNY. Production yield 96% and inspection pass 95% remain distinct.
- Business source inserts: two rows, zero existing rows updated; exact one-row normal Silver ETL upsert. Before/after hashes prove existing F006 production/quality, inventory, products, plans and workflows unchanged, non-F006 production/quality unchanged, unrelated F006 Silver unchanged.
- Evidence: .playwright-mcp/f006-joint-positive-20261028/f006-authorized-seed-1793155528.json. Initial checks stopped before any write on the flat unified-login shape and on unreviewed insert triggers; no constraints were disabled. Trigger review led to the valid existing plan snapshot.
- Acceptance harness can select an explicit authorized product rather than assuming the first product has complete linked data. Unknown products fail closed; write guard and whitelist are unchanged. Commands: node --test scripts/e2e/production-readonly/tests/unit.test.js; bundle build/check; canonical MCP; native App.
- Harness validation: 144 Node tests passed, zero failed/skipped; 13 actual local browser fixtures passed. Bundle regenerated and diff check passed. Evidence test-output/fadjoint28-full-unit.log and fadjoint28-fixture.log. Product services unchanged, no republish needed.
- Status: review; positive Web/native validation pending. No persistent shared scope lock remains outside these six explicitly owned harness/archive paths.
