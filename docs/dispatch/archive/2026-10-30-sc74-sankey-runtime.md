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
