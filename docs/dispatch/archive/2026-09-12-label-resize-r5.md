# FIX-LABEL-RESIZE-R5-20260912

- Owner /root/visual-r5; base 9a7078e7491d99baedac1c9df9c200d253427d3f.
- Scope: web-admin/vite.config.ts, tooling/elementPlusTableResize.ts, src/plugins/__tests__/elementPlusTableResize.spec.ts.
- Label-QC readonly acceptance encountered browser ResizeObserver delivery errors and blocked client-error telemetry. Local actual Vue reproduction: 20 synthetic rows; 1440→1280→1024→1440 and completed/review queue switching produced three errors.
- Isolated experiments: disabling top scrollbar observer did not help; deferring either table observer alone did not help; deferring both table layout observers removed the errors. No experiment monkey-patch is shipped.
- Vite build and dependency prebundle apply the same bounded adapter to Element Plus table/style-helper.mjs only. It coalesces observer callbacks to a frame and cancels pending work when the Vue scope stops. Upstream import drift fails explicitly. No dependency files, browser globals, telemetry, guard or error filters modified.
- Real Vue final check without the diagnostic observer wrapper: zero window/page errors; 1024 viewport top scrollbar80/body80, max163, rows20; both queue switches preserved. These are synthetic local checks, not production business acceptance.
- 18 targeted tests passed; full vue-tsc passed. Tests cover import drift, unrelated-module exclusion, scheduling/coalescing/latest geometry, exception propagation, disposal cancellation and existing wheel behavior.
- Tradeoff: table layout reactions move to the next animation frame; no claim of speed improvement. Column definitions, table layout, row actions and permissions unchanged. Production acceptance and merge/deploy are separate receipts.
- Evidence: C:/Users/Steve/cretas-visual-fixes-20260912-r3/label-r5; local runner ../label-layout-r5.mjs; original experiments preserved in named logs.
