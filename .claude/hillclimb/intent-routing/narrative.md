# Intent routing eval — narrative

| round | change (one line) | test | train | all | s/case (median) | llm_fallback | notes |
|-------|-------------------|------|-------|-----|-----------------|--------------|-------|
| 0 | baseline (origin/main a1428f8611, prod intent config 2026-09-11, embedding grpc) | 0.300 | 0.330 | 0.315 | 0.08 | 2% | 200 cases × 2 reps, 0 errors, 0 rep flips |

Best so far: round 0 (test 0.300). Noise floor on test: ~±0.07 at 100 cases (reps add nothing — the pipeline is deterministic once learned expressions are reset; 0/200 cases flipped between reps).

## Baseline (2026-09-11)

**Headline `pass` = 0.315** (recomputed from 400 raw rows). Train 0.330 / test 0.300 — agree within noise; split kept.

By category (n = cases): complex-language 0.40 (40) · formatting 0.45 (20) · multi-turn 0.40 (30) · self-learning 0.50 (20) · permission 0.30 (30) · write-ops 0.23 (30) · **rag 0.00 (30)**.

By stage that decided the intent (share of rows → pass): PHRASE_SHORTCUT 36% → 0.37 · SEMANTIC 26% → 0.27 · SEMANTIC_SIM 15% → 0.30 · LLM fallback 12% → 0.26 · KEYWORD 6% → 0.46 · CLARIFY 4% → 0.14.

### What the failures look like

1. **Query routed to a write** via the Java early phrase table (`IntentKnowledgeBase`): 「今天的原料到货情况」/「难道今天没有任何原料到货吗…」→ `MATERIAL_BATCH_CREATE`（phrase `原料到货`）. Stops at write-confirm, but the user is shown an 入库 confirmation for a question.
2. **Dialect normalisation + phrase**: 「昨天的出货明细」→ `货→物料` → phrase `物料明细` → `MATERIAL_BATCH_QUERY`（出货 became 原料）.
3. **Unsupported requests never refuse**: the 25 cases labelled `UNSUPPORTED_CAPABILITY`（培训记录、安全巡检、客户投诉、生产日报…）mostly route to a plausible-sounding tool（`CUSTOMER_STATS`, `QUALITY_CHECK_QUERY`, `ORDER_LIST`）or ask a clarification whose options are all wrong; `UNSUPPORTED_CAPABILITY` itself was never produced.
4. **RAG / web route is dead**: 0/30. The knowledge router only engages when the early question-type classifier says `GENERAL_QUESTION`, which fired 4× in the whole run; knowledge questions land on `COLD_CHAIN_MONITORING`, `QUALITY_CHECK_QUERY` or `OUT_OF_DOMAIN`.
5. **Permission**: 8/15 denials work（`NO_PERMISSION`）; the rest never reach the gate because the intent chosen is a query that the role is allowed to run（e.g. 质检员「删除这条质检记录」→ `QUALITY_CHECK_QUERY`, no delete intent exists）. APPROVAL_REQUIRED cases mostly execute as `SUCCESS` — no approval chain is configured on those intents in the snapshot.

### Environment fingerprint

Local Spring Boot from origin/main `a1428f8611`; docker `cretas-eval-db`（prod schema-only + `ai_intent_configs` 417 / `ai_learned_expressions` 131 / 3 more config tables snapshotted from prod 2026-09-11; factory `EVAL01`; six role accounts）; embedding-service gRPC 9090 with `bge-base-zh-v1.5-onnx-cls`（same model prod runs）; Python 8083 local. `reset_state.sh` before every pass. `previewOnly=false`; write guard blocked every write（0 rows in business tables after the run）.

Known gaps vs prod: business tables are empty（tools return "暂无记录" — irrelevant to routing, but any intent whose *execution* feeds back into routing would differ）; `/api/ai/intent/classify` logs no token usage, so `usage` under-counts DashScope spend.

### Levers that are wired (and how each round must be run)

- `IntentKnowledgeBase.java` phrase / keyword tables — decide 36% of rows directly.
- `ai_intent_configs` name / description / keywords（DB snapshot）— feed SEMANTIC and the LLM fallback prompt.
- Java tool `getDescription()` — reaches routing only through `tool_embeddings`, which are **re-encoded only when the row is missing**: after any description change run `reset_state.sh --tool-embeddings`, restart Java, then run.
- Python `/api/ai/intent/classify` prompt（DashScope, 12% of rows）.
