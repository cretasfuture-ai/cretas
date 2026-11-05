# F006-DEMO-COST-20261106

- Owner: root; implementation network_bootstrap, tightened and validated by root.
- Base SHA: 2de52fb66e7caaf892d9430afba23e8b81d5e57d (integrated before final review).
- Scope released: web-admin/src/demo/f006Flow.ts and its independent cost-capture tests.
- Step 19 visibly supplies simulated equipment 12.80 and other 7.20 fees. The existing tenant-scoped plan cost endpoint recalculates actual posted settlement costs. Owned plan/batch matching, finite nonnegative visible input and actual COMPLETE/zero missing count are required before progression. Failed requests retain the current step and reuse the existing warehouse receipt.
- Validation: seven target Vitest files, 35 tests passed; vue-tsc -b passed. Private logs under F006演示-20261105. Existing held formal session ended through UI; actual independent database all 620 tables match baseline, Redis/attachments clean, all nine ended sessions reject business access.
- Delivery: PR merge, trusted Web build, required CI, Web-only production release and fresh formal full-flow/cost/reset acceptance are subsequent delivery evidence; this archive does not claim those pending steps have passed.
- Separate main Java changes are outside this Web-only release scope. No JAR or sandbox snapshot rebinding requested by this slice.
