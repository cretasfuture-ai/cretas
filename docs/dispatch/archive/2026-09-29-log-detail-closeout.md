# OPT-LOG-DETAIL-20260929 — closeout

- Status: merged and deployed. Base `eaff438cbff06b381752f1b3e643e5413cea62cc`; PR [#837](https://github.com/cretasfuture-ai/cretas/pull/837), squash merge `5dfd8d0aa503f8a87066a60a4d91cc9536d1f5a2`.
- Scope: make insights prewarm opt-in while the Python Agent route is disabled; move expected no-device push logs to DEBUG; keep low-stock sweep summaries while moving per-material details to DEBUG; add stable transfer actions and validation-block reasons; remove raw tokens, activation/verification codes, and phone numbers from touched Java logs.
- Validation: `LowStockDualAlertServiceTest` passed; Web `vue-tsc`, 25 targeted tests, and build passed. Exact candidate release built both Java and Web and staged the Java JAR. PR CI passed fast-contracts, PostgreSQL reversal concurrency, Vitest, field names, source sync, NUL-byte, and tracked-secret checks; optional final-full-suite was skipped by workflow.
- Production: unified release receipt `C:\Users\Steve\.cache\cretas\release-reports\cretas-1790655290-568.json` has `status=deployed`, `exit_code=0`, active blue `10010` health pass, Web HTTP 200, and equal four-way index hashes. `RELEASE_FINAL_STATUS=deployed` appeared once. The deployment reused the exact candidate Java and Web trees.
- Acceptance limit: no post-deploy user click sample yet proves transfer action names in `activity_events`; the next scheduled no-device push and 4-hour insights prewarm windows had not occurred at closeout. No production business write was performed.
- Scope lock released.
