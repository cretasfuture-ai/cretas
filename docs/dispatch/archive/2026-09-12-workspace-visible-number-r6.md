# R6 follow-up: visible copy ordinals

- Task: FIX-WORKSPACE-VISIBLE-NUMBER-R6-20260912
- Base SHA: 85e26377b0662ad7700c3fdd1aa6b4429fa0295a
- Owner: current visual acceptance task; isolated DIRECT worktree.
- Scope: WorkspaceTabs, label component tests, workspace duplicate read-only scenario and generated MCP bundle.

PR #780 was merged and deployed successfully (Web four-way hash matched; Java not selected). Its production run `2026-09-12T13-09-11-066Z` passed duplicate identity/draft assertions with zero errors/writes/blocked attempts. However, coordinator screenshot review found that at 1024px, with the home tab also present, ellipsis hid the copy numbers. Functional PASS did not establish visual PASS.

The generated suffix now has its own non-shrinking span; only the base title ellipsizes. Complete label text, tooltip, cache identities and close controls remain intact. Meaningful trailing digits and custom labels without generated metadata are not parsed as copy numbers. The visual scenario now checks actual suffix visibility and bounds at 1440/1024.

Validation: 10 target tests, vue-tsc and 77 harness tests passed. Actual Vue with a persisted fixture home tab plus three drafts passed ordinal visibility, independent dates, and closed-instance replacement. Hiding the ordinal with CSS caused the scenario to fail as required. No business writes or guard exceptions were added.

Tradeoff: short viewports show less of long base names to keep the distinguishing number visible. The original `1` suffix, sidebar automatic multi-open, order subtitles and keyboard pinning remain incomplete. Workbook main56 / UX&UI7 stay partial. Production verification follows this patch's own exact-main Web release; earlier screenshots remain failure evidence.

Scope releases on merge. Detailed local receipts: `C:/Users/Steve/cretas-visual-fixes-20260912-r3/` and `.codex-build/visual-r6b-release/`.
