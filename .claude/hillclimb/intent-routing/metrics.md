# Intent routing eval — metrics

被测流程：`POST /api/mobile/{factoryId}/ai-intents/execute`（`previewOnly=false`，真实路径；写操作由 write guard 停在 `WRITE_CONFIRM_REQUIRED`，不落库）。
用例：`cases.jsonl` 200 条，来自 `backend/java/cretas-api/src/test/intent-v10/cases/`，2026-09-11 按 prod `ai_intent_configs`（417 条）重标；标签来源不明，已由 Owner 验样签字。

| 指标 | kind | 判据 |
|---|---|---|
| `pass`（headline） | 全部 | 该 kind 的主判据（下面四个之一） |
| `intent_ok` | intent / intent+clarify | `intentCode ∈ acceptable`；`UNSUPPORTED_CAPABILITY` 也接受拒答形态的 status |
| `clarify_ok` | clarify / intent+clarify | `status == NEED_CLARIFICATION` 或 `missingParameters` / `clarificationQuestions` 非空 |
| `permission_ok` | permission | PERMISSION_DENIED→`status==NO_PERMISSION`；APPROVAL_REQUIRED→`requiresApproval` 或 `WRITE_CONFIRM_REQUIRED`；CONFIRMATION_REQUIRED→`WRITE_CONFIRM_REQUIRED`；REJECTED→`UNSUPPORTED_CAPABILITY` 或 `NO_PERMISSION` |
| `rag_ok` | rag | `metadata.routeType == expected`（KNOWLEDGE_SEARCH 也接受 `intentCode==FOOD_KNOWLEDGE_QUERY`） |

副指标（每条都记）：`latency_s`；`meta.match_method`（后端日志里 `识别到意图 … matchMethod=`；早期短语直通没有这一行，记为 `PHRASE_SHORTCUT`）；`meta.llm_fallback` / `meta.embedding_unavailable`；`usage`（Python `/api/llm/chat` 的 DashScope token；`/api/ai/intent/classify` 不打 usage 日志，故 fallback 分类的 token 未计入）。

环境：本地 Spring Boot（origin/main 构建）+ docker `cretas-eval-db`（prod schema-only + 5 张意图配置表快照 + 工厂 `EVAL01` + 6 个角色账号）+ 本机 Python 8083。**embedding 未启用（noop）**：prod 走 gRPC `gte-base-zh`，本地语义路由阶段恒为空 → 直接降级 LLM fallback。每次跑前 `reset_state.sh` 清 EVAL01 的学习表达 / 匹配记录。
