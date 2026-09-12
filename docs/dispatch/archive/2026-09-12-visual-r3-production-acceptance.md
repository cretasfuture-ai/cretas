# VERIFY-VISUAL-R3-PROD-20260912

Base: `74f5b2c0f6cac3e4bfb77a1c898aa28f586c7f14`. Owner: current coordinator. Scope: three new production-readonly scenarios, suite registration and deterministic MCP bundle. No whitelist, authentication, product code or business-data changes.

Register workspace focus/reference scrolling, yield header and finished-stock filtering scenarios. Workspace checks both 50% and bottom synchronization, preserved draft and focus. Inventory checks a real product match, an impossible-name empty result and restoration after clearing. Yield entry is permitted only for a visible plan whose Workflow activation GET explicitly returns false: Workflow entry otherwise POSTs create-batch even when a batch already exists.

Local real Vue browser validation: eight positive/negative cases passed using canonical guard and explicit local API fixtures; controls detect broken Space close, incorrect header border, no-op inventory filtering and a business mutation. The mutation is blocked before sending, actual writes zero. Active Workflow is excluded without attempting a mutation. Core/bundle tests: 34 passed, zero skipped. Evidence: `C:/Users/Steve/cretas-visual-fixes-20260912-r3/harness-local/`.

Initial production round: workspace passed. Yield entry attempted create-batch and consequent client-error reporting; both were blocked before sending, actual writes zero, suite stopped before inventory. This was a selector safety failure, not a passed header acceptance. The new explicit activation preflight prevents that path. Original failure evidence remains at `production/.playwright-mcp/run/report.json`; final round is separate at `production-final/.playwright-mcp/run/report.json` under the evidence root. Final product/Sheet outcomes are in the external acceptance report and are not inferred from this commit.

The unified Web release of PR #774 completed before this harness change, exact main 74f5b2c0f6, DEPLOY_EXIT=0 and one RELEASE_FINAL_STATUS. Four-way index hash: `4099fe9c32d0210a6bb9f072dd774947cc97540c566e9f9fbbe9fa7ebc839e56`. This harness-only change needs no application redeployment.
