# Intent routing eval — narrative

| round | change (one line) | test | train | all | s/case (median) | llm_fallback | notes |
|-------|-------------------|------|-------|-----|-----------------|--------------|-------|
| 0 | baseline (origin/main a1428f8611, prod intent config 2026-09-11, embedding grpc) | 0.300 | 0.330 | 0.315 | 0.11 | 2% | 200×2 reps, 0 rep flips (in-JVM cache replay) |
| 1 | dialect guard: 货/活/中 keep business compounds | 0.300 | 0.290 | 0.295 | 0.18 | 6% |  |
| 1c | no-change control (v1 jar, fresh JVM) | 0.300 | 0.270 | 0.285 | 0.20 | 7% |  |
| 2 | phrase shortcut skips read-only intents under a write verb | 0.320 | 0.300 | 0.310 | 0.23 | 6% |  |
| 3 | similarity matcher uses original sentence when input collapsed | 0.330 | 0.300 | 0.315 | 0.26 | 5% |  |

Best so far: round 3 (test 0.330). Stopped: three consecutive rounds with test delta < 0.03 (0.00 / +0.02 / +0.01) — the Step 2 plateau rule. Restart-to-restart noise on identical code is 4/200 flips (round 1c), so each round's net effect sits at the noise floor even where the mechanism is verified by unit tests.

## Per-category, baseline → v3

| category | baseline | v3 |
|---|---|---|
| complex-language | 0.40 | 0.30 |
| formatting | 0.45 | 0.35 |
| multi-turn | 0.40 | 0.50 |
| permission | 0.30 | 0.40 |
| rag | 0.00 | 0.00 |
| self-learning | 0.50 | 0.40 |
| write-ops | 0.23 | 0.30 |

## What the three rounds established

1. The three deterministic Java-side defects are real and now unit-tested: single-character dialect keys eating compounds (v1), noun phrases resolving to query intents under write verbs (v2), similarity matching on a 2–4 character residue (v3). Each fix moves a handful of cases; none moves the headline beyond noise, because the headline is dominated by three buckets the tables don't touch.
2. **RAG 0/30** is unchanged across all rounds — the knowledge/web router is gated on the early question-type classifier saying `GENERAL_QUESTION`, which happens ~4× per 200 requests. That is a routing-architecture lever (Python question-type / `AgenticRAGRouterService` gating), not a phrase-table one.
3. **UNSUPPORTED_CAPABILITY is never produced** (0/25 across all rounds): every stage prefers a plausible tool over a refusal, and the LLM fallback returns `OUT_OF_DOMAIN` only for genuinely off-topic text. A refusal path needs a confidence floor (fallback conf < X → unsupported) — a behaviour change with product implications, so it was not attempted unilaterally.
4. **APPROVAL_REQUIRED** cases execute as `SUCCESS`: no approval chain is configured on those intents in the prod config snapshot — config, not routing.

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
