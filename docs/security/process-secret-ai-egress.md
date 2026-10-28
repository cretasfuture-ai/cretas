# 工艺保密产品与 AI：外发、学习、缓存的边界

**最后更新**: 2026-10-29（Steve 当天的原则与裁定；同日复审补齐学习 / 检索 / 外发的跨厂路径）

## 原则（Steve 2026-10-29）

授权用户的 AI 对话里可以出现工艺保密产品；但这些内容**不许流到别的工厂**，也**不许被学走、在外部复现**。

## 1. 发给外部大模型（egress）

| 出口 | 保密产品名 | 代码位置 |
|---|---|---|
| 意图识别（用户原话 → 识别意图 / 选层） | **不脱敏，原名发出**（已知例外，见下） | Java `LlmEgressGuard.utterance`；Python `common/llm_client.py` 的 `_names_for_this_request`（`utterance_egress` 上下文：`ai/matcher/llm.py`、`ai/matcher/decision.py`、`ai/router/llm_tier_selector.py`、`llm/api/endpoints.py`） |
| 其余所有出口（对话、分析、报表、抽参、表单、SmartBI…） | 换代号；换不了就不发 | Java `LlmEgressGuard.texts / structured / textsWithRecord / combined`；Python `common/llm_client.py` 默认模式 |

### 已知例外：意图识别保留原名（Steve 2026-10-29 裁定，有意保留）

意图识别把用户原话连同其中的工艺保密产品名不脱敏发给外部模型。理由：识别链路不加库查询，替换名字会改坏原话、降低识别准确率。除意图识别以外的所有外发一律把保密产品名换代号。

- ⛔ 不要把别的出口改走 `utterance` / `utterance_egress` 来绕过脱敏。
- 现状核对（2026-10-29）：Java 的 `LlmEgressGuard.utterance` 在 `src/main` 里没有调用点，Java 侧的意图识别出口（`LlmIntentFallbackClientImpl`、`AIIntentServiceImpl` 的 Python 匹配）实际走的是 `texts(..., factoryId, ...)`，**会**替换保密产品名；例外实际生效在 Python 侧的 `utterance_egress` 路径。Java 经 `/api/llm/*` 代理发出的内容在 Java 侧已经换过代号。

### 按哪家工厂的名单脱敏（fail-closed）

`LlmEgressGuard` 决定按哪家工厂的保密产品名脱敏，顺序：

1. 调用点显式传入的工厂；
2. 服务端入口绑定的「本次分析的工厂」`LlmEgressGuard.analysing(factoryId)`（路径上认定的工厂，⛔ 不取请求体里客户端给的值）；
3. 不是平台账号时，本次执行的登录身份工厂（请求 token / SSE 工作线程上的可信身份）；
4. 以上都没有，或平台账号既没显式工厂也没有入口绑定 → **拒绝外发**（日志 `refused (no factory context)`）。以前名单为空、原文照发。

入口绑定（11 处）：`IntentExecutionOrchestrator.execute / executeWithExplicitIntent / confirm`、`SseStreamingService` 流式工作线程、`ToolDispatchService.executeWithTool / executeToolPreview`、`IntentRecognitionPipelineServiceImpl.recognizeIntentWithConfidence`、`SlotFillingServiceImpl.checkAndStartSlotFilling`、`LongTextHandler.processForIntent(input, factoryId)`、`LLMFieldMappingServiceImpl.analyzeAndSave / analyzeAndSaveAll`。

显式传工厂的出口：抽参 `ToolDispatchService.extractParametersWithLLM(factoryId, …)`（执行 / 预览 / 流式预抽）、消歧 `IntentDisambiguationService.disambiguate(…, factoryId)`、图表 `AdaptiveChartGenerator.generateAdaptive(factoryId, uploadId, …)` 及其 `ChartSufficiencyEvaluator.evaluate / evaluateInitial(factoryId, …)`。图表还要求上传属于路径上的工厂（`findByIdAndFactoryId`），别厂的上传按不存在处理。

仍以 `null` 调 `LlmEgressGuard` 的 7 处（比较赛、纠错 Agent、长文本摘要、槽位兜底抽参、选工具、字段映射 ×2）靠上面的入口绑定解析工厂；从这些入口之外调用时，工厂用户按登录身份，平台账号拒绝外发。

只在 Spring 容器里的实例上生效；单测用的 `LlmEgressGuard.standalone()` 不查保密产品名，行为不变。

### Java 与 Python 的 fail-closed 对照

| 情形 | Java `LlmEgressGuard` | Python `common/llm_client.py` |
|---|---|---|
| 有工厂（显式 / 入口绑定 / 登录身份） | 按那家的保密产品名换代号 | 按那家（`_llm_factory`：JWT 的 factoryId；Java 内部调用带的 `X-Factory-Id`；公开前缀上带了能验过的 Bearer 也按 token 的工厂，见下） |
| 平台账号（Java `PLATFORM_ADMIN`；Python token 里的 `PLATFORM`）没有显式工厂 | 拒绝外发 | 拒绝外发（`PLATFORM` 不算工厂） |
| 服务端内部调用、没带工厂（Java→Python 带 `X-Internal-Secret` 但没带 `X-Factory-Id`） | — | 按**所有工厂**的保密产品名换代号（`_egress_all_factories`），不拒绝：这批调用不针对某一家，拒绝会让 Excel 解析 / 平台建厂等内部流程整体失效，多换代号无害 |
| 操作手册问答（`/api/food-kb/manual-chat*`）、平台需求向导（`client_requirement/ai_consultant.py`），没有工厂 | — | 按**所有工厂**的保密产品名换代号（内容是产品手册 / 建厂前需求，不是某一家工厂的数据） |
| 其余没有工厂（定时任务、无身份的后台协程、未登录的公开路径） | 拒绝外发 | 拒绝外发（`NoEgressFactoryError` → `EgressRedactionError`，不发、不换候选重试）；入口明确报「AI 分析没有发出：无法确定这份数据属于哪家工厂，请登录后重试」（`egress_refusal_message`），⛔ 不返回空串、不拿统计兜底冒充 AI 结果 |
| 意图识别原话模式（已知例外） | `utterance()` 不查名单、不拒绝 | `utterance_egress` 不查名单、不拒绝 |
| 查保密产品名出错 | 拒绝 | 拒绝 |

Python 侧以前：内部分支从不设 `_llm_factory`，Java 带了 `X-Factory-Id` 的调用也按「没有工厂」原名发出；没有工厂时名单为空、原文照发。

公开前缀（`/api/insight/`、`/api/chart/`、`/api/food-kb/manual-chat` 等，`auth_middleware.PUBLIC_PREFIXES`）不要求登录；以前完全不看 Bearer，登录了的工厂用户在这些页面上的 AI 分析也拿不到工厂（复审 H3 的回归）。现在带了能验过的 Bearer 就按 token 的工厂设外发工厂和 RLS 租户（`PLATFORM` 不算工厂）；没带 / 验不过照旧放行（不新增 401），外发按「没有工厂」处理。洞察客户端（`smartbi/services/insights/llm_client.py`）收到 `EgressRedactionError` 不再当供应商故障重试、也不返回空串，洞察 / 对话入口返回 `success=false` 和上面那句说明。

**平台账号**用其它按工厂取数的 SmartBI 接口（对话、分析、洞察等）时外发被拒，这是设计如此：平台账号不属于任何一家工厂，要分析某家的数据须经该厂的路径 / 内部调用带上工厂。

Python 侧现在会被拒绝的调用（没有工厂、也不是内部调用 / 原话模式）：
- 平台账号 JWT（factoryId 为 `PLATFORM` 或空）直接调 Python 里会走大模型的接口（SmartBI 对话 / 分析、Food KB 问答、餐饮 Agent 等）；
- 启动时起的后台协程 / 定时任务里没带工厂的大模型调用（例如 `main.py` 里的 warmup、ETL、benchmark 循环，若它们调用大模型）；
- 未登录访问公开前缀（`/api/ai/`、`/api/efficiency/` 等）里会走大模型、又不是原话模式的接口；
- `food_kb/services/reranker.py` 拒绝时退回检索原顺序（不发）。
以上按代码路径归类，未逐个线上实测；正常工厂请求（JWT 带工厂、Java 带 `X-Factory-Id`）不受影响，单测 `tests/test_egress_factory_fail_closed.py` 覆盖两侧。

## 2. 学习（说法 / 关键词 / 短语表 / 跨厂知识）

本厂规则统一在 Java `LearningContentRules.factoryLearnable` / Python `ai/learning/learning_rules.factory_learnable`，与训练样本同一套：撤回或查不到授权状态不学；提到本厂保密产品不学；配方 / 工艺参数、具体客户名、人名不学。全局（工厂字段为空、空白或 `*`）一律不由系统学习写入。

| 写入路径 | 规则 |
|---|---|
| Java `ExpressionLearningServiceImpl.learnExpression` | 本厂规则；新写入和「重提议激活」都过闸。全局只收人工预置（MANUAL），且不许提到**任何一家**工厂的保密产品名；批量（种子）只查一次全厂名单 |
| Java `KeywordLearningServiceImpl`（`learnKeywords` / `learnFromMatchedIntent` / `addKeywordsToIntent`） | 本厂规则；只写**调用方本厂**的意图行（`findByFactoryIdAndIntentCode` 且行的工厂相等），平台级 / 别厂的行不写，本厂没有这一行就不写 |
| Java `ActiveLearningServiceImpl.applySuggestion`（进不分工厂的短语表 + MANUAL 说法） | 没有工厂的建议不应用；本厂规则不过不应用；提到任何一家工厂保密产品名不应用；判断都在动短语表之前 |
| Java `ActiveLearningServiceImpl.promoteKnowledgeToGlobal` | 跨厂共享关闭时不执行；恢复后内容提到任何一家工厂保密产品名的不晋升 |
| Java `IntentAnalysisController.approvePromotion` / `getPendingPromotions`（工厂意图 → 平台级） | 只给平台账号（工厂账号 403，`system:read_write` 不够）；意图的名称 / 关键词 / 示例 / 描述等文字提到任何一家工厂的保密产品名 → 409 不晋升；查不了 → 503；审批回执只回意图代码，不回整条意图 |
| Java `AIIntentConfigController` 新建 / 修改 / 启停 / 删除 / 回滚 / 批量回滚 / 版本历史（`IntentConfigWriteGuard.loadOwned` / `resolveTarget`） | 工厂取路径，请求体里的 `factoryId` / `id` 不认；其余操作按（路径工厂, 意图代码）取本厂的行（服务签名带工厂，不再有不分工厂的版本）；批量回滚只回滚本厂自己的行；平台级行（路径 `PLATFORM`）只给平台账号，写入和回滚出来的旧版本都不许提到任何一家工厂的保密产品名（409 / 查不了 503）；权限查询用按工厂的重载 |
| Java 参数提取规则删除 / 清理、意图优化建议采纳 / 拒绝、标注任务完成 | 按（ID, 路径工厂）改（`@Modifying` 查询带 `factory_id`），别厂的 404；采纳的操作人取登录身份，不信请求参数 |
| Java `LlmIntentFallbackClientImpl.tryCreateIntentSuggestion`（新意图建议 / 工厂级自动建意图） | 原话过本厂学习规则才建 / 累加示例 |
| Java `UpdateIntentTool`（AI 对话改意图） | 只改调用方本厂的行；`getIntentByCode` 退回来的平台级行不改 |
| Java `ActiveLearningController` 审批 / 驳回 / 应用学习建议 | 按（建议 ID, 路径工厂）取，别厂的建议 404 |
| Java `LLMFieldMappingServiceImpl.saveToDatabase`（自动分析的字段映射） | 没有工厂不写（以前写成全局字典行） |
| Java `KeywordPromotionServiceImpl` | 仍由常量关闭 |
| Python `ai/learning/expression_learner.py` | 本厂规则；没有工厂的记录不学（以前写成 NULL 工厂的 SEMANTIC_HIGH 行）；与 Java 同一去重键（小写 + 去首尾空白后 SHA-256），置信度 ≥ 0.90 才激活（Java 分层写入规则） |
| Python `ai/learning/keyword_learner.py` | 本厂规则；按（工厂, 意图）分组；每次现读本厂那一行的关键词（不用启动时不分工厂的快照）；遵守本厂自动学习开关（`factory_ai_learning_config.auto_learn_enabled`，没有配置行 = 开）；`UPDATE … WHERE factory_id = $3`，本厂没有这一行就不写 |

保密产品名比对：字母不分大小写（学习会把原话转小写），汉字照原样（Java `ProcessSecretSampleFilter.mentionsAny`，Python `process_secret_names.mentions_any`）。

## 3. 读已学内容

系统学来的全局行（工厂字段为空 / 空白 / `*` 且不是 MANUAL）来自某一家工厂，不给任何工厂用（`LearningContentRules.servable`）：

- Java：`ExpressionLearningServiceImpl` 匹配与按意图列表、`IntentEmbeddingCacheServiceImpl` 向量缓存（全量重建与增量都把空 / 空白 / `*` 归为全局）、`RAGRetrievalServiceImpl`（7 处读取）、`DynamicFewShotService`（few-shot 示例会进提示词）；
- 意图名 / 描述兜底（`RAGRetrievalServiceImpl.getIntentName`、`DynamicFewShotService.getIntentName`）：先本厂行，再只取平台级行（factory_id 为空），不再按意图代码跨厂取；
- Python：`ai/rag/retrieval.py` 的 SQL 只取 `factory_id = $2 OR (factory_id IS NULL AND source_type = 'MANUAL')`。

## 4. 语义缓存

`SemanticCacheServiceImpl.cacheResult` 按「工厂 + 原话」存完整执行结果，不分调用者。现在命中只回元数据，不回放结果（`SseSemanticCacheAdmissionContractTest` 钉着）。⛔ 要实现回放，必须先按调用者的保密访问档位隔离（工厂 + 调用者能 FULL 查看的保密产品集合）。

## 5. 已知缺口（未处理）

- **存量数据**：历史上已写进平台级意图行的关键词、已存在的无工厂学习行没有清理；读出口已不再使用系统学来的无工厂说法，但平台级意图行 `keywords` 里的存量词仍对所有工厂生效。清理要先在线上计数（只读）再定。
- **外发脱敏的大小写**：`LlmEgressRedactor` 的名字替换仍按原样大小写比对（本轮按要求不改替换逻辑），只有学习侧的判断不分大小写。
- **全局 MANUAL 种子**只在写入时查全厂保密名单；产品之后才设为保密时，已有种子不复查。
- **字段映射字典**：`LLMFieldMappingServiceImpl.saveUserMapping`（人工确认的映射）在工厂为空时仍写全局字典，未加保密产品检查；自动分析的映射已不写全局。`ExcelDynamicParserServiceImpl.parseExcel` 用请求对象里的工厂，当前 `src/main` 没有调用方，新接调用方时必须填服务端认定的工厂。
- **入口绑定之外的深层调用**：7 个仍传 `null` 的出口若被新入口直接调用，工厂用户按登录身份，平台账号会被拒绝外发（fail-closed，功能上表现为退回规则 / 不抽参），需要在新入口补 `analysing(factoryId)` 或显式传工厂。
- **改平台级意图的提示**：工厂账号改 / 启停 / 删除 / 回滚一个只有平台级行的意图代码，现在返回 404「本厂没有这个意图配置」（以前会改到平台级行）；新建一个已被别处占用的代码返回 409「这个意图代码已被占用，请换一个代码」（`ai_intent_configs.intent_code` 全库唯一，提示不透露占用方）。前端如要区分「平台已有」需另行设计。
- **`@PreAuthorize` 不生效**：工程没有开启方法安全（无 `@EnableMethodSecurity`），`AIIntentConfigController` 上的 `hasAnyRole('FACTORY_SUPER_ADMIN','FACTORY_ADMIN')` 不拦任何人，平台账号能走到 `PLATFORM` 写路径，由 `IntentConfigWriteGuard` 判平台账号；角色限制实际靠 `@RequirePermission`。若将来开启方法安全，这些注解会挡住平台账号（`ProcessSecretCrossFactoryRound4Test` 会红）。
- **Python 没有工厂会被拒绝的调用**未逐个线上实测（见上方对照表下的清单）。
- **Python 自动学习开关**只接到关键词学习；说法学习（`expression_learner`）与 Java 意图管线一样按全局配置，不看本厂开关。

## 官网承诺台账需要同步

`docs/website/data-security-claims.md` 是官网承诺台账，由官网会话维护，本文件不改它。需要官网会话更新：

- `C4-assistant-redact`（工厂 AI 助手发给模型前替换名称）：补一句「意图识别保留原名」的例外，或在页面上限定为「分析 / 对话内容」。
- `C1-no-cross-learning`：可以补上本次的证据（全局说法只收人工预置且查全厂保密产品名；关键词只写本厂意图行；Python 学习按工厂分组；读出口不用系统学来的全局行；跨厂知识晋升已关）。存量清理未做，见上「已知缺口」。
