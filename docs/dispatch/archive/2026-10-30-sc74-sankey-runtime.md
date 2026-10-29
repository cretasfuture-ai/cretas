# SC74 Sankey runtime compatibility

- Base: 98ae3682196ba0ae0f6dbe82fcb6667387bed385; Root coordinator, sc74_executor implementation owner.
- Trigger: physical Xiaomi sc70-b09-02-canvas.png reports unsupported __FMT__sankey_node_label; meaningful WIP details exist but graph FAIL. Python generic Sankey emits both sankey_node_label and sankey_tooltip; neither frontend registry supports these generic markers.
- Allowed product scope: frontend/CretasFoodTrace/src/utils/smartbiFactoryChartRuntime.ts, frontend/CretasFoodTrace/src/__tests__/unit/components/FactoryChartCanvas.test.ts, web-admin/src/utils/echarts-fmt.ts and its existing formatter unit test. No data, backend, role, dependency, script or ledger edits by worker.
- Acceptance: actual callback execution for emitted generic Sankey label and node/edge tooltip, correct nonfinancial quantities, HTML escaping, existing financial semantics and unknown-marker rejection retained; targeted Jest/Vitest, both typechecks, real Xiaomi WIP graph/details and Web graph if changed. Root owns CI, merge and official Web/OTA deployment.
- Worker may not spawn agents or touch the phone. Root independently continues read-only phone retries; no shared product files with phone operator.

## Candidate verification

- Generic node/edge formatter callbacks execute with numeric, zero and missing values; quantities do not acquire currency labels. Hostile node and edge names are escaped in HTML tooltip output. Existing financial formatter behavior and native unknown-marker rejection remain covered.
- Target Jest FactoryChartCanvas: 8 passed, exit 0; target Vitest echartsFmt: 2 passed, exit 0. Full RN TypeScript and full Web vue-tsc passed, exit 0. Default Web typecheck ran out of its 4 GiB heap; its failure log remains. The command-local 8 GiB retry passed without global configuration changes.
- Exact source hashes: RN runtime 7B2ECB02D78EC20002ECE2602E2786FC007B8249D8609BC77C8A9697876478B8; Web formatter E404B39B199D2EBA68934B7174AB4D883CCB4C84351E24C577972720B282E05C. Private logs: final-release worktree test-output/sc74-*.
- Candidate state: implementation and targeted verification complete; merge, Web/Android publication and physical graph retest remain separate pending steps. Original failing physical screenshot remains retained.

## Merged and published

- PR1328 merged normally at 9fb7482b8909d2eb73cc55a7eaea5d52a1652de6; reviewed candidate 548f6d5038999af27c187672903c05e56c149acd has the identical tree. All five PR checks passed. CI actually executed FactoryChartCanvas and echartsFmt: RN 340 suites / 3514 tests passed; Web 853 files / 6667 tests passed, 1 file / 5 tests skipped; full Web typecheck passed.
- Official candidate Web build succeeded once (91s), manifest Web tree 0e242635dfc59f36f95b3059789e5fb21babf253. Release preflight passed. Prewarm was attempted once and skipped because artifact transport was unreachable; no retries or infrastructure changes.
- Official Web deploy from clean exact main succeeded (34s), receipt cretas-1793294762-551.json; exit 0, exactly one RELEASE_FINAL_STATUS=deployed, trusted build reused, four-way index hash 936f9b8c5750f26f2b33b72af97e4df253c31efa01c618094698a048defb64d6. No Java/Python source or deployment in this increment.
- Android production runtime 1.0.5, timestamp 1793294763837, update d4c84b99-eb09-ab5b-fc54-06a0e4f34fbb, SHA256 00769b5e4d031682334f190b9e670555173355c100b81dabbc6f18dfc0a42464. Publication exited 0; local bundle / live manifest / CDN readback hashes agree. Device application remains a separate assertion.
- Canonical Web MCP entry was loaded by filename inside the allowed workspace root after proving SHA256 identical to exact main (33EC2185CBAA30274AE71E957D698664C6D4F4DFA36458E1D9C8F94E09D879AC); a first out-of-root filename attempt was denied without running UI actions. F006 WIP family real graph/detail/export scenario PASS, safetyPassed=true, actualBusinessWrites=0, blocked mutations / console / page errors empty. Private evidence: web-sc74-sankey/report.json. Original native WIP visual FAIL remains until the updated physical App is actually retested.
- Independent visual review did not accept the scenario's initial directory-only screenshot. A same-page element screenshot proved the graph now paints, but long batch labels overlap and clip; visual FAIL remains under SC77. SC74 implementation/merge/publication scope is released; physical graph acceptance remains explicitly with SC71/SC77 rather than being inferred from this release.
