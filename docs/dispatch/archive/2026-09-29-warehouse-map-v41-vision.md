# WAREHOUSE-MAP-V41-VISION-20260929

- Owner: Codex; Base SHA: `4f4950b9c61d8f189893bbd204421f9e5555a54d`.
- PR: https://github.com/cretasfuture-ai/cretas/pull/844. Scope lock released in the final candidate commit; production rollout and acceptance are tracked in the release receipt.
- Scope: warehouse layout recognition in Java, dedicated `warehouse_vl` routing and output cap in Python, targeted tests and router golden snapshot. Other vision routes remain on `vl`.
- Live capability probe (2026-09-29): Alibaba Cloud account A production key and host, two image requests to `deepseek-v4.1-flash`, HTTP 200 in 2.8 s and 2.0 s. The second used the Java warehouse layout prompt and returned parseable JSON with a cold-storage zone, rack 1, and entrance; image usage was 254 tokens. Synthetic image only; no F006 business write.
- Safety gate: `LLM_WAREHOUSE_V41_ENABLED=1` is required before the new model is called. Default is off. On 2026-09-29 the owner confirmed long-term use of this model; pay-as-you-go charges may occur. The account-specific free-quota auto-stop status is unknown. The other registered vision candidates remain fallback routes.
- Target validation: `python -m pytest -q tests/test_llm_router_registry.py tests/test_llm_router_chains.py tests/test_llm_router_fallback.py tests/test_llm_vision_latency_budget.py` in `backend/python` → 88 passed; `mvn -q '-Dtest=WarehouseLayoutControllerTest,PythonLLMClientUsageLogTest' test` in `backend/java/cretas-api` → exit 0; `git diff --check` → pass.
- Pending: PR/CI, production deployment with explicit model enable, and live warehouse-map image recognition acceptance. Code/test success does not imply these later steps are complete.
