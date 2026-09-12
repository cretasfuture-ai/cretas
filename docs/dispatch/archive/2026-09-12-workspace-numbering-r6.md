# Workspace duplicate numbering R6

- Task: FIX-WORKSPACE-NUMBERING-R6-20260912
- Owner: current visual acceptance task; DIRECT, isolated existing worktree.
- Base SHA: 097f3531f655b01bb9aa04f9344865caf79151f3
- Workbook: Cretas FDE, main56 / UX&UI7 (partial; do not close the entire request).
- Product scope: workspace store duplicateRoute and its tests. Harness scope: opt-in duplicate scenario, registry, tests and generated MCP entry.

Copying a copied tab previously stacked its number (`销售订单 2 3`). Counting currently open tabs could reuse an occupied label after closing a middle tab. Millisecond IDs also merged two copies into one cache identity. Copies now carry their original base title, select an unoccupied label and use UUID identities. Business query parameters remain intact; meaningful trailing digits are not stripped. Existing cached tabs are not renamed or rekeyed.

Validation before release:

- Baseline target tests reproduced compounded labels and millisecond identity collision (3 failures / 4 tests).
- 32 workspace target tests passed; vue-tsc passed.
- Canonical harness tests: 77 passed, no skips. Acceptance rejects missing, repeated and compounded labels.
- Real Vue local fixture: 1440/1024 screenshots inspected; independent original/copied draft dates survived switching; a closed copy was not reused; errors 0, business writes 0, blocked attempts 0.
- Local evidence: `C:/Users/Steve/cretas-visual-fixes-20260912-r3/.playwright-mcp/workspace-r6-local/`.
- Production acceptance must use `workspace-duplicate-readonly` after exact-main deployment; local fixtures do not prove production acceptance or persistence.

Limits: old saved copies without base-title metadata retain their old label as the base until reopened from an original page. Original suffix `1`, sidebar automatic new instances, order subtitles and keyboard reference pinning remain outside this patch. No performance gain is claimed. The existing ten-instance cache/draft eviction boundary is not expanded or certified here.

Implementation scope releases when this PR merges; CI/deployment and live acceptance receipts remain separate facts in the local report and workbook.
