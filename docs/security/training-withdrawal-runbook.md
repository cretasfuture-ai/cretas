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
四类「还没训练过的副本」逐类计数，结果写进工单：

```bash
# SmartBI 蒸馏样本
sudo -u postgres psql -d smartbi_prod_db -tA -c \
  "SELECT count(*), min(created_at), max(created_at) FROM smart_bi_distillation_samples WHERE factory_id = '<factory_id>'"
# Java 意图训练样本 / 主动学习样本 / 排程训练数据
sudo -u postgres psql -d cretas_prod_db -tA -c \
  "SELECT 'ai_training_samples', count(*) FROM ai_training_samples WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'active_learning_samples', count(*) FROM active_learning_samples WHERE factory_id = '<factory_id>'
   UNION ALL SELECT 'scheduling_training_data', count(*) FROM scheduling_training_data WHERE factory_id = '<factory_id>'"
```

另外确认两件事并记入工单：
- 该厂有没有专属模型（目前平台没有任何专属模型；如有，记录模型名和版本）。
- 有没有正在排队或运行的训练任务（排程训练开关 `ml.training.enabled` 线上为关；每周外部打分已自动跳过撤回工厂）。

### 4. 复核后删除（第 5–10 个工作日）
复核人看过第 3 步的计数并批准后执行。每张表都在事务里删除，并校验删除数量等于计数，不等就回滚：

```sql
-- smartbi_prod_db，以下 :n 换成第 3 步的计数
BEGIN;
DELETE FROM smart_bi_distillation_samples WHERE factory_id = '<factory_id>';
-- 返回的 DELETE 数必须等于计数；不等就 ROLLBACK 并查原因
COMMIT;
```

```sql
-- cretas_prod_db，同样逐表在事务里删除并核对数量
BEGIN;
DELETE FROM ai_training_samples      WHERE factory_id = '<factory_id>';
DELETE FROM active_learning_samples  WHERE factory_id = '<factory_id>';
DELETE FROM scheduling_training_data WHERE factory_id = '<factory_id>';
COMMIT;
```

删除后再计数一次，四类都应为 0，结果写入工单。

不删除的数据（写进回复里说明）：该厂系统里的业务数据本身（订单、库存、报表、对话记录等），这些是客户正在使用的系统数据，不是训练副本，撤回不影响它们的显示和使用。

### 5. 专属模型与通用模型
- **专属模型**：如有，停用并删除模型文件，记录到工单。
- **通用模型**：此前已训练进通用模型的部分无法逐条撤出（条款第十条）。之后每次通用模型更新时，训练导出会自动跳过撤回工厂（撤回标记已接入导出过滤），无需人工操作；在工单里记下「下次通用模型更新时间」备查。

### 6. 书面回复（第 15 个工作日前）
复核人签发，模板：

> 贵方于 <收函日期> 提出的训练撤回申请已处理完毕：
> 1. 自 <设标记日期> 起，平台不再采集贵方数据用于训练，贵方数据也不再进入训练导出、评估和抽样。
> 2. 尚未用于训练的副本已于 <删除日期> 删除：智能问答样本 <n1> 条、意图样本 <n2> 条、主动学习样本 <n3> 条、排程数据 <n4> 条。
> 3. <有/无> 为贵方训练的专属模型；<如有：已于 <日期> 停用并删除>。
> 4. 此前已用于训练通用模型的数据无法逐条撤出，但其中不含可识别贵方的信息；今后的通用模型更新不再包含贵方数据。
> 5. 贵方系统中的业务数据和日常使用不受影响。

### 7. 归档
工单留存：来函扫描件、撤回标记截图或查询结果、计数与删除记录、复核人批准、书面回复。工单里不要粘贴客户的业务数据内容。

---

## 三、恢复授权
客户之后重新书面授权：用 `--scope private|shared_anon --contract <合同编号>` 重新登记，撤回标记会自动清除。没有新合同编号不能重新授权（数据库约束）。
