# R13: original workspace tab ordinal

- Task: FIX-WORKSPACE-ORIGINAL-NUMBER-R13-20260913
- Base: 27b64b8f38f1ba1a8afbeb73d64c8f4ed8e82285
- Owner: current task; isolated DIRECT worktree.
- Scope: WorkspaceTabs label projection and tests; canonical duplicate scenario/tests and generated entry. No store, cache, route, API or business-data changes.

main56 / UX7 asks that the original tab also show 1 after a copy opens. Valid generated copy metadata now identifies a path/title group; its canonical original receives a display-only suffix in the existing non-shrinking ordinal span. The original route, key, title and dirty state are unchanged. Once all copies close, its lone display name has no suffix. Same titles on unrelated routes and malformed copy labels do not number the original; meaningful trailing digits remain part of the name.

Validation: 13 component/cache/keyboard target tests and 83 harness tests pass. Actual Vue fixtures passed 1440/1024 ordinal visibility, stable original key, independent dates, replacement of a closed copy and original date retention after cleanup. The existing 11-draft capacity scenario also passed with 10 copies closed and original date retained. Zero console errors and business writes/blocked attempts. Screenshots were inspected; narrow titles still truncate, so no whole-workspace usability or efficiency claim.

Evidence: C:/Users/Steve/cretas-visual-fixes-20260912-r3/.playwright-mcp/workspace-r13-visible-local/ and workspace-r13-capacity-local/. Standard final commit build/CI/deploy and production readonly receipts follow. main56 still contains sidebar multi-open, order subtitles and other UX requests, so only the numbered-original subitem can close. This scope releases on merge.
