# 训练撤回操作手册（训练条款第十条）

**适用**：客户书面要求撤回训练授权、退出训练。
**时限**：收到书面撤回之日起 **15 个工作日**内处理完毕，并书面告知客户（Steve 2026-10-24 确认）。
**原则**：先设撤回标记（当天立即止血），再计数、复核、删除；删除前必须先把计数给复核人看。

---

## 一、角色

| 角色 | 谁 | 做什么 |
|---|---|---|
| 接收人 | 商务 / 客户成功 | 核验来函、登记工单、起算时限、最后发书面回复 |
| 执行人 | 工程值班 | 设撤回标记、计数、删除、留证据 |
| 复核人 | Steve 或其指定的人 | 看计数后批准删除；签发书面回复 |

---

## 二、步骤

### 1. 接收与登记（第 1 个工作日）
- 核验来函：盖章或授权人签字、公司名称、对应的系统工厂编号（`factory_id`）。
- 登记工单：工单号、`factory_id`、收函日期、截止日期（收函日 + 15 个工作日）。

### 2. 设撤回标记（第 1 个工作日，当天完成）
设上之后立即生效：不再采集该厂样本；训练导出、每周外部打分、评测冻结、抽样全部跳过该厂；Java 侧意图样本和主动学习样本也不再写入。

```bash
ssh root@47.100.235.168
cd /www/wwwroot/cretas/code/backend/python
PYTHONPATH=.:smartbi venv-current/bin/python -m scripts.set_training_consent \
    --factory <factory_id> --withdraw --by <执行人> --note "<收函日期> 书面撤回, 工单 <工单号>"
```

核对（应看到 `withdrawn_at` 有值、`consent_scope = none`、`contract_no` 为空）：

```bash
sudo -u postgres psql -d smartbi_prod_db -c \
  "SELECT factory_id, consent_scope, contract_no, withdrawn_at, withdrawn_by, withdrawal_note
     FROM training_consent WHERE factory_id = '<factory_id>'"
```

### 3. 只读计数（第 2–5 个工作日）
下面十张表里该厂的行都是「AI 留下的副本」（训练样本、对话记录、兜底日志、语料、回答缓存、挂起待重判的样本），
逐表计数，结果写进工单。清单以 2026-10-29 MOCK_REST 只读探针（E 项：哪些表还存着该厂的 AI 副本）为准；
以后新增存 AI 副本的表，同时加进本节和第 4 节。

```bash
# SmartBI 库: 蒸馏样本 / AI 对话会话 / 大模型兜底日志 / 时间短语语料 / 回答缓存
sudo -u postgres psql -d smartbi_prod_db -tA -c \
  "SELECT 'smart_bi_distillation_samples', count(*), min(created_at), max(created_at) FROM smart_bi_distillation_samples WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'smart_bi_chat_session', count(*), min(created_at), max(created_at) FROM smart_bi_chat_session WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'smart_bi_llm_fallback_log', count(*), min(created_at), max(created_at) FROM smart_bi_llm_fallback_log WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'ai_time_phrase_corpus', count(*), NULL, NULL FROM ai_time_phrase_corpus WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'narrative_cache', count(*), min(created_at), max(created_at) FROM narrative_cache WHERE factory_id = '<factory_id>'"
# 主库: 意图训练样本 / 主动学习样本 / 排程训练数据 / 挂起待重判的样本 / AI 对话记忆
sudo -u postgres psql -d cretas_prod_db -tA -c \
  "SELECT 'ai_training_samples', count(*) FROM ai_training_samples WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'active_learning_samples', count(*) FROM active_learning_samples WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'scheduling_training_data', count(*) FROM scheduling_training_data WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'training_sample_secrecy_holds', count(*) FROM training_sample_secrecy_holds WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'ai_intent_flywheel_turns', count(*) FROM ai_intent_flywheel_turns WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'conversation_memory', count(*) FROM conversation_memory WHERE factory_id = '<factory_id>'"
```

`training_sample_secrecy_holds`（Flyway V20261104_22）放的是「名单一时查不出来、等重判」的样本，存的是**原话原文**（没脱敏）：
待判（PENDING）的行，放行或过保留期（默认 7 天）就删；重判时提到了保密产品 / 人员 / 客户的行标 REJECTED **保留不删**（原文当场丢掉，只留原因和时间），
留给人工复核，也不会进训练。撤回标记设上以后，重判任务放行前会再查一次授权，撤回的不会再写进样本表；
表里剩下的（PENDING 和 REJECTED 都算）仍要按本节删掉。
`ai_intent_flywheel_turns`（Flyway V20261114_87，意图飞轮）每轮对话一条：保密关的厂存原文，保密厂只存脱敏导出与原文密文；撤回时整厂删。
SmartBI 蒸馏样本里重判没过的行同理：带 `secrecy_rejected` 标记保留（只记原因、名单版本、时间，不带原文）、导出和训练都排除，撤回时随 `smart_bi_distillation_samples` 一起删；挂起行（`secrecy_recheck`，标记里带原文）过 7 天由采集路径顺手删掉。

另外确认两件事并记入工单：
- 该厂有没有专属模型（目前平台没有任何专属模型；如有，记录模型名和版本）。
- 有没有正在排队或运行的训练任务（排程训练开关 `ml.training.enabled` 线上为关；每周外部打分已自动跳过撤回工厂）。

### 4. 复核后删除（第 5–10 个工作日）
复核人看过第 3 步的计数并批准后执行。每张表都在事务里删除，并校验删除数量等于计数，不等就回滚：

```sql
-- smartbi_prod_db，每张表一个事务；返回的 DELETE 数必须等于第 3 步的计数，不等就 ROLLBACK 并查原因
BEGIN; DELETE FROM smart_bi_distillation_samples WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM smart_bi_chat_session         WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM smart_bi_llm_fallback_log     WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM ai_time_phrase_corpus         WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM narrative_cache               WHERE factory_id = '<factory_id>'; COMMIT;
```

```sql
-- cretas_prod_db，同样逐表在事务里删除并核对数量
BEGIN; DELETE FROM ai_training_samples           WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM active_learning_samples       WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM scheduling_training_data      WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM training_sample_secrecy_holds WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM ai_intent_flywheel_turns      WHERE factory_id = '<factory_id>'; COMMIT;
BEGIN; DELETE FROM conversation_memory           WHERE factory_id = '<factory_id>'; COMMIT;
```

删除前先把这些行导出加密备份（与 2026-11-03 MOCK_REST 清理同样做法：age 加密、明文文件 0 个，
每张表 count_before = 导出条数 = 删除条数），备份路径写进工单。删除后再计数一次，十一张表都应为 0，结果写入工单。

不删除的数据（写进回复里说明）：该厂系统里的业务数据本身（订单、库存、报表等），这些是客户正在使用的系统数据，
不是 AI 留下的副本，撤回不影响它们的显示和使用。AI 对话会话与对话记忆（`smart_bi_chat_session`、`conversation_memory`）
属于 AI 副本，按上面删除；删除后该厂用户在 AI 对话里看不到之前的会话上下文。

### 5. 专属模型与通用模型
- **专属模型**：如有，停用并删除模型文件，记录到工单。
- **通用模型**：此前已训练进通用模型的部分无法逐条撤出（条款第十条）。之后每次通用模型更新时，训练导出会自动跳过撤回工厂（撤回标记已接入导出过滤），无需人工操作；在工单里记下「下次通用模型更新时间」备查。

### 6. 书面回复（第 15 个工作日前）
复核人签发，模板：

> 贵方于 <收函日期> 提出的训练撤回申请已处理完毕：
> 1. 自 <设标记日期> 起，平台不再采集贵方数据用于训练，贵方数据也不再进入训练导出、评估和抽样。
> 2. 尚未用于训练的副本已于 <删除日期> 删除：智能问答样本 <n1> 条、意图样本 <n2> 条、主动学习样本 <n3> 条、排程数据 <n4> 条，
>    以及 AI 对话会话、对话记忆、问答兜底记录、时间短语语料、回答缓存与待复核样本共 <n5> 条。
> 3. <有/无> 为贵方训练的专属模型；<如有：已于 <日期> 停用并删除>。
> 4. 此前已用于训练通用模型的数据无法逐条撤出，但其中不含可识别贵方的信息；今后的通用模型更新不再包含贵方数据。
> 5. 贵方系统中的业务数据和日常使用不受影响。

### 7. 归档
工单留存：来函扫描件、撤回标记截图或查询结果、计数与删除记录、复核人批准、书面回复。工单里不要粘贴客户的业务数据内容。

---

## 三、恢复授权
客户之后重新书面授权：用 `--scope private|shared_anon --contract <合同编号>` 重新登记，撤回标记会自动清除。没有新合同编号不能重新授权（数据库约束）。
