# S315 Web settlement delivery

- Task: TASKS-S315-SEMI-ENTRY-20261106; Owner: 01a1a954/root.
- Base: eacf526cf33daa707f4f45f86eae471d076cf4a5.
- PR #1486 merged as b5b348f0c2025096c6d8b0cd4f731ff97ca77475; source candidate 272161dbd659c1ef70504f922ba45040d025018f.
- Shared semi-output input and separate variance reason/note fields delivered. Two affected files retained their previously tested content through main integration.
- Target tests: 21 passed; combined type check passed. Final Web CI 42069914209: 7011 passed, 5 skipped; all four checks successful.
- Official Web-only build: 73 seconds. Official Web-only deploy: 41 seconds, exit 0, one deployed final status, reused artifact and complete four-way hash verification. Java not selected; no new OTA.
- Both existing F006 QA plans settled through actual production-manager UI. Normal plan stocked 0.4kg semi inventory with provenance; rework preserved its original 0.6kg source consumption and full long variance note.
- Business acceptance remains open: normal-plan cost readback exposed a second subtraction of already apportioned semi cost. Follow-up TASKS-S315-COST-BASIS-20261106 retains the same plan; no repeat settlement or manual SQL adjustment.
- Web implementation scope released; private screenshots, request IDs, exact business IDs and journals remain in the registered local acceptance evidence directory. This archive is not a claim of completed S315 acceptance.
