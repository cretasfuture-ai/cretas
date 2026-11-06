# Comparison acceptance identity fixture correction

Task: FOOD-PROPOSAL-READONLY-IDENTITY-20261107
Owner: Food Root DIRECT, isolated harness worktree; no delegated writer.
Base: 75dbd7c6f0ada379a5a1b3442a5142b29f76dc37 (PR1522 normal merge).
Branch: codex/proposal-readonly-flat-session-20261107.
Worktree: C:/Users/Steve/my-prototype-logistics/.claude/worktrees/food-proposal-harness-20261107.

Scope: scripts/e2e/production-readonly/scenarios/proposal-comparison-readonly.js, its focused tests/proposal-comparison.test.js, mechanically generated mcp-entry.js and this archive. No Java/Web/RN/Python application byte, backend resource, auth or mutation-guard change.

Pre-execution source verification found that MobileController.getCurrentUser returns the flat dto/user/UserDTO for auth/me, not the nested login envelope assumed by the new acceptance scenario. Its existing predicate would safely stop before any calculation, but could not accept a legitimate current session. Use the actual flat id/factoryId/roleCode/isActive contract; retain exact F006/actor1309/active/approved-role and clean-login username checks. Add offline positive and independent invalid-identity cases. No compatibility data fabrication or auth response interception.

PR1522 product392bc passed mandatory CI and normal-merged75db. Precisely cherry-picked the prepared40e44 correction onto that fresh main; the original prepared branch remains preserved. Application/backend-resource trees are unchanged by this scripts-only correction. Offline canonical Node282 passed on identical source; local browser fixture16 passed in this physically isolated worktree, and the canonical MCP filename dry run succeeded. The initial inherited npx shim was unavailable; owned npm ci and the project Playwright CLI resolved the environment without editing dependencies or lockfiles. Deliver this scripts-only normal PR with its actual required checks, no force push or application redeployment. Production comparison acceptance will use the corrected canonical entry only after formal Java+Web delivery; no acceptance is claimed from these offline checks.
