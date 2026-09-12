# R14 sidebar fresh workspace tasks

- Task FIX-SIDEBAR-NEW-TASK-R14-20260913; Owner current task, DIRECT isolated worktree.
- Base e00bfcfafb6d3467800af42027beb8b964e7c498.
- Scope AppSidebar selection, workspace route builder and target tests; canonical sidebar scenario, registry and generated entry. No permission, API, backend, schema or cache policy changes. Scope releases on merge.

main56 / UX7 explicitly requests another instance when clicking a sidebar module that is already open. First opening keeps the menu URL. Repeated selections reuse the existing UUID/ordinal builder with the selected menu URL, preserving its explicit query defaults while excluding an old instance's filters. Navigation to an existing tab continues to activate that tab; original key, draft, reference and cache policy are unchanged.

18 targeted workspace/menu/sidebar-reactivity tests and 83 canonical harness tests pass. Actual Vue local sidebar clicks created tasks 2/3, both initially unfiltered; original and second unsent search text survived tab switches and copy cleanup. 1440/1024 screenshots show distinct 1/2/3 labels. Zero business writes/blocked attempts/console errors in the completed fixture run. The initial fixture lacked SmartBI finance-summary mocks and received 401s; that failed run is retained separately, then the local-only fixture was completed. This is not backend acceptance.

Final build/CI and production readonly results remain separate in C:/Users/Steve/cretas-visual-fixes-20260912-r3/workspace-sidebar-r14-review.md. Only this sidebar subitem is covered; order subtitles, keyboard reference access, all-module/all-field validation and measured performance are not claimed. More instances incur initialization requests and tab-strip space; no unconditional efficiency improvement claim.
