# SC84-GATEWAY-CI-DETERMINISM-20261030

- Status: review. Base `5b77af88bd8f38b0fff66003a10909cf83f9c889`; executor sc28_final_risk_review, coordinator Root. The test-file lease is released in this commit.
- Two Linux CI attempts at caption candidate `f016034e01a6e0315b8ac2129b654d7dc25f0f03` failed the unchanged Python 3.8 fallback success test because its synthetic 100ms budget expired under scheduling load. Both failed runs remain evidence; local 28-test success did not replace them.
- Only the compatibility success test freezes its event-loop clock before its deadline. It still executes the real fallback child task, adapter, transaction cleanup and success assertions. The separate timeout, cancellation and cancellation-suppression tests retain real clocks. Production gateway, its 15-second default and fail-closed checks are unchanged.
- Exact modified test SHA-256: `C35DD43AF3D01911918475CA7225C7DFD1F85D2769C7A870D36D42C46FC60ED8`.
- Target validation: `PYTHONDONTWRITEBYTECODE=1 python -m pytest tests/agent_runtime/test_read_tool_gateway.py -q -p no:cacheprovider`, exit 0, 28 passed, 2 warnings. Coordinator independently reviewed the six-line test-only diff.
- Required next evidence: exact-commit Linux Python Gate must execute the gateway test without the new failure. The separately registered historical `test_registry_audit_date_is_not_stale` failure is not waived, renamed or falsified; provider-console work is excluded by the user. Merge and deployment remain separate facts.
