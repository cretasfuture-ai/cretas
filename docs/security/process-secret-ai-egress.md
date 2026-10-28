# 工艺保密产品与 AI：外发、学习、缓存的边界

**最后更新**: 2026-10-29（Steve 当天的原则与裁定）

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

`LlmEgressGuard` 优先用调用点显式传入的工厂；没传时才取本次执行的登录身份所属工厂。

- 平台账号（`PLATFORM_ADMIN`）替某厂分析时，调用点**必须显式带工厂**；不按 token 推断，没带就拒绝外发。
- 显式工厂和登录身份工厂都没有（定时任务、没绑身份的异步线程）时，**拒绝外发**（日志 `refused (no factory context)`）。以前这种情况名单为空、原文照发。
- 只在 Spring 容器里的实例上生效；单测用的 `LlmEgressGuard.standalone()` 不查保密产品名，行为不变。
- 抽参（`ToolDispatchService.extractParametersWithLLM`）在执行、预览、流式预抽三处都传服务端认定的工厂（⛔ 不取请求 context 里客户端给的 factoryId）。

## 2. 学习（说法 / 关键词 / 跨厂知识）

- 本厂学到的说法（`ExpressionLearningServiceImpl.learnExpression`）与训练样本同一套规则：撤回或查不到授权状态不学；提到本厂保密产品不学；配方 / 工艺参数、具体客户名、人名不学。新写入和「重提议激活」都过这道闸。
- 全局说法（工厂字段为空或 `*`）只收人工预置（MANUAL），并且不许提到**任何一家**工厂的保密产品名。系统从某家工厂学到的说法不能变成全局；读出口也不把这类历史行给任何工厂用。
- 跨厂知识晋升（`ActiveLearningServiceImpl.promoteKnowledgeToGlobal`）在共享开关关闭时不执行；开关恢复后，内容提到任何一家工厂保密产品名的不晋升。
- 学习建议应用（新关键词 / 新说法会进不分工厂的短语表）：提到任何一家工厂保密产品名的不应用。
- 关键词跨厂晋升（`KeywordPromotionServiceImpl`）仍由常量关闭。

## 3. 语义缓存

`SemanticCacheServiceImpl.cacheResult` 按「工厂 + 原话」存完整执行结果，不分调用者。现在命中只回元数据，不回放结果（`SseSemanticCacheAdmissionContractTest` 钉着）。⛔ 要实现回放，必须先按调用者的保密访问档位隔离（工厂 + 调用者能 FULL 查看的保密产品集合）。

## 官网承诺台账需要同步

`docs/website/data-security-claims.md` 是官网承诺台账，由官网会话维护，本文件不改它。需要官网会话更新：

- `C4-assistant-redact`（工厂 AI 助手发给模型前替换名称）：补一句「意图识别保留原名」的例外，或在页面上限定为「分析 / 对话内容」。
- `C1-no-cross-learning`：可以补上本次的证据（全局说法只收人工预置且查全厂保密产品名、跨厂知识晋升已关）。
