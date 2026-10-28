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

## 2. 学习（说法 / 关键词 / 短语表 / 跨厂知识）

本厂规则统一在 Java `LearningContentRules.factoryLearnable` / Python `ai/learning/learning_rules.factory_learnable`，与训练样本同一套：撤回或查不到授权状态不学；提到本厂保密产品不学；配方 / 工艺参数、具体客户名、人名不学。全局（工厂字段为空、空白或 `*`）一律不由系统学习写入。

| 写入路径 | 规则 |
|---|---|
| Java `ExpressionLearningServiceImpl.learnExpression` | 本厂规则；新写入和「重提议激活」都过闸。全局只收人工预置（MANUAL），且不许提到**任何一家**工厂的保密产品名；批量（种子）只查一次全厂名单 |
| Java `KeywordLearningServiceImpl`（`learnKeywords` / `learnFromMatchedIntent` / `addKeywordsToIntent`） | 本厂规则；只写**调用方本厂**的意图行（`findByFactoryIdAndIntentCode` 且行的工厂相等），平台级 / 别厂的行不写，本厂没有这一行就不写 |
| Java `ActiveLearningServiceImpl.applySuggestion`（进不分工厂的短语表 + MANUAL 说法） | 没有工厂的建议不应用；本厂规则不过不应用；提到任何一家工厂保密产品名不应用；判断都在动短语表之前 |
| Java `ActiveLearningServiceImpl.promoteKnowledgeToGlobal` | 跨厂共享关闭时不执行；恢复后内容提到任何一家工厂保密产品名的不晋升 |
| Java `IntentAnalysisController.approvePromotion`（工厂意图 → 平台级） | 意图的名称 / 关键词 / 示例 / 描述等文字提到任何一家工厂的保密产品名 → 409 不晋升；查不了 → 503 |
| Java `KeywordPromotionServiceImpl` | 仍由常量关闭 |
| Python `ai/learning/expression_learner.py` | 本厂规则；没有工厂的记录不学（以前写成 NULL 工厂的 SEMANTIC_HIGH 行） |
| Python `ai/learning/keyword_learner.py` | 本厂规则；按（工厂, 意图）分组；`UPDATE … WHERE factory_id = $3`，只写本厂的行（以前按意图汇总全部工厂、写进第一条样本那家或全局行） |

保密产品名比对：字母不分大小写（学习会把原话转小写），汉字照原样（Java `ProcessSecretSampleFilter.mentionsAny`，Python `process_secret_names.mentions_any`）。

## 3. 读已学内容

系统学来的全局行（工厂字段为空 / 空白 / `*` 且不是 MANUAL）来自某一家工厂，不给任何工厂用（`LearningContentRules.servable`）：

- Java：`ExpressionLearningServiceImpl` 匹配与按意图列表、`IntentEmbeddingCacheServiceImpl` 向量缓存、`RAGRetrievalServiceImpl`（7 处读取）、`DynamicFewShotService`（few-shot 示例会进提示词）；
- Python：`ai/rag/retrieval.py` 的 SQL 只取 `factory_id = $2 OR (factory_id IS NULL AND source_type = 'MANUAL')`。

## 4. 语义缓存

`SemanticCacheServiceImpl.cacheResult` 按「工厂 + 原话」存完整执行结果，不分调用者。现在命中只回元数据，不回放结果（`SseSemanticCacheAdmissionContractTest` 钉着）。⛔ 要实现回放，必须先按调用者的保密访问档位隔离（工厂 + 调用者能 FULL 查看的保密产品集合）。

## 5. 已知缺口（未处理）

- **存量数据**：历史上已写进平台级意图行的关键词、已存在的无工厂学习行没有清理；读出口已不再使用系统学来的无工厂说法，但平台级意图行 `keywords` 里的存量词仍对所有工厂生效。清理要先在线上计数（只读）再定。
- **外发脱敏的大小写**：`LlmEgressRedactor` 的名字替换仍按原样大小写比对（本轮按要求不改替换逻辑），只有学习侧的判断不分大小写。
- **全局 MANUAL 种子**只在写入时查全厂保密名单；产品之后才设为保密时，已有种子不复查。
- **字段映射字典**：`LLMFieldMappingServiceImpl.saveUserMapping` 在工厂为空时写全局字典，未加保密产品检查（目前只有工厂调用）。
- **入口绑定之外的深层调用**：7 个仍传 `null` 的出口若被新入口直接调用，工厂用户按登录身份，平台账号会被拒绝外发（fail-closed，功能上表现为退回规则 / 不抽参），需要在新入口补 `analysing(factoryId)` 或显式传工厂。

## 官网承诺台账需要同步

`docs/website/data-security-claims.md` 是官网承诺台账，由官网会话维护，本文件不改它。需要官网会话更新：

- `C4-assistant-redact`（工厂 AI 助手发给模型前替换名称）：补一句「意图识别保留原名」的例外，或在页面上限定为「分析 / 对话内容」。
- `C1-no-cross-learning`：可以补上本次的证据（全局说法只收人工预置且查全厂保密产品名；关键词只写本厂意图行；Python 学习按工厂分组；读出口不用系统学来的全局行；跨厂知识晋升已关）。存量清理未做，见上「已知缺口」。
