# Production readonly routing source selector repair

- Task: `ERP-S0-ROUTING-HARNESS-20261019`; Owner `/root`.
- Base: `f03437d10c6132eb3d27f00089c288f57d115d60`.
- Scope: `production-plan-routing-readonly.js` and generated `mcp-entry.js`; dispatch closeout only.
- Candidate state: review; this leaf scope is released when the PR merges. Parent `ERP-STAGES-20261019` continues.

The first post-release canonical readonly run reached the create dialog but timed out looking for the removed exact radio label “手动”. The actual mounted Vue form exposes “存货生产” with value `SAFETY_STOCK`. Select that exact option and require both its checked state and exact value. All resolver, topology, preview, tenant and mutation checks remain unchanged.

Validation: canonical Node unit/drift 39 passed, zero failure/skip; existing local Playwright fixtures 12 passed; dry-run passed. Focused headed production readonly retry used the same canonical core and fresh F006 UI login under the user's existing CLI exception. The create dialog was cancelled without submission. It completed the intended inspection with no tool error, zero business writes, zero blocked attempts and zero console/HTTP errors.

The live result remains `UNVERIFIED/data`: 155 active finished products were inspected, only qualified 1-to-1 routing was found, and the required other topologies, ambiguous and superset examples were unavailable in that bounded inventory. The original criteria were not relaxed. A preview screenshot exists, but a date popup overlaps it; do not treat node counts alone as complete visual acceptance. This repair does not certify the full business workflow.

Private evidence: `C:/Users/Steve/Documents/cretas-optimization-20261019/execution/S0-ui/` (original post-release TOOL_ERROR, focused retry, routing-repair unit receipt/fixture log). Production Java/Web remain the already-verified PR1033 release; no product rebuild or deployment is needed for this harness-only change.
