# 全量理解力考题 × 线上 r2 端到端读数 (full_replay_r2_20261006)

测的是: 线上 Python 识别接口 `/api/ai/intent/match` (只识别不执行), F006 / FACTORY / 角色 factory_super_admin, 并发 3, 每题打一次。判分直接用 `ueval.cell`。数据: `full_replay_r2_20261006_in.jsonl` / `_out.jsonl` / `_scored.jsonl`。

## 摘要 (全文数字都在下面各节, 这里只是索引)

- **头条 = UNSEEN (r2 训练正样本里没出现过) 3319 题**: 直接办对 41.2% / 点按钮办对 38.4% / 正确不办 5.7% (办对率 ueval 口径 85.3%) | **误执行 3.6%** | 错·问了 7.3% | 该办没办(判无) 1.6% | 安全·问了 2.1%。
- ALL 3519 题 (虚高): 直接 41.4% / 按钮 38.7% / 误执行 3.5%。
- rerank 回退率 0.10% (3/2905); HTTP 失败 3/3519。
- 『直接办对』只有四成, 其余办对的大头是『点按钮』—— 办对率 85% 里近一半是让用户再点一下才办对, 不是系统直接办。

## 0. 仪器健康 (先看这个再看数)

- 回放 3519 条 = 输入 3519 = 考题 3519; qid 一一对应无重复。
- HTTP 非 200 / 异常: 3 (0.09%); 记『失败』格: 3。
- **rerank 回退率**: 尝试走重排 2905 条, 其中 `rerankFallback=1` 3 条 = **0.10%** (阈值 20%)。另: 带历史所以按设计**跳过重排**走 LLM 粗选 610 条 (`rerankSkippedHistory`), 精确表命中 1 条 (`registryHit`, 不进重排), 三者都不是 3 条。
- 服务端回报 `historyTurns>0`: 611 条 (考题里带 history 的 612 条) —— 历史确实送到了。
- 回放里服务端报的 `decisionLayer==1`: 3516 条 (三层识别在线)。

> 注意: **context 题 (带历史) 走的是 LLM 粗选, 不经过 r2 重排**; 其余 (plain/implicit/trap, 无历史) 才经 r2。所以 r2 『见没见过』只对无历史的题有意义; 但头条仍按『r2 训练正样本里没出现过』统一切, 保守。

## 1. r2 见过哪些题 (公平性切分) —— 这一节是怎么定的

线上 r2 = `reranker_ft_bge-reranker-v2-m3_r2` (模型自报 `bge-reranker-v2-m3-ft-r2-20261004`)。依据:

1. `train_rr2.log` (2026-10-04 13:41, `RR_SUITE=1`): 第一行『考题训练半加入 192 条正样本』, 『正样本问句 6455』。
2. `train_reranker.build_pairs` 的逻辑: 正样本 = 例句(`intent_examples.json`) + 种子 APPROVED(`registry_seed.json`) + 考题 `suite_half(id)==0` 且 kind∈{plain,implicit,trap} 且 expect∈v3 的题; 再去掉 future 段原句, 去重。**r2 没有 r3 才加的『防泄题』剔除**。
3. 当时的考题只有 514 题 = 现在考题文件的前 514 行 (`backup_pre_r3/understanding_suite.jsonl` 与现文件前 514 行逐行相同, 已核); 2026-10-05 才追加了 3005 题, 晚于 r2 训练 (10-04)。
4. 我用 `r2_seen_set.py` 照同一口径**重建**了 r2 的训练正样本, 自检: 考题训练半 = 192 (log 192 ✔), 正样本问句 = 6455 (log 6455 ✔)。重建对得上才用它。
5. 把 3519 题逐条对这 6455 条原句: 原句完全相同 → seen; 原句不同但经产品 `normalize`(去标点/同义词) 后相同 → 也算 seen (保守)。归一化层: 用产品 ai.registry.normalize (C:/Users/Steve/cretas-intent-v2) + F006 词表。

| 切片 | 题数 | 说明 |
|---|---|---|
| ALL | 3519 | 全部考题 —— **虚高**: 含 r2 训练过的 |
| seen·原句相同 | 196 | 原句在 r2 训练正样本里 (含 192 条考题训练半, 其中 192 条被本口径抓到) |
| seen·仅归一化后相同 | 4 | 原句不同, 归一化后与训练原句相同 |
| **UNSEEN (头条)** | **3319** | 原句与归一化都不在 r2 训练正样本里 |
| STRICT | 3255 | UNSEEN 再去掉旧 514 里 suite_half==0 的全部题 (连没进训练的 context/NONE/CLARIFY 也去掉) |
| 旧514·验收半 | 257 | 基线对照: 2026-10-05 r2 同日回放 86.0% 办对 / 1.6% 误执行 的那 257 题 |
| r2训练过的192 | 192 | 阳性对照: r2 真训练过的题, 应当明显更高, 否则这把切分尺子是瞎的 |

UNSEEN 各题型题数: {'context': 611, 'implicit': 752, 'trap': 526, 'plain': 1430}

## 2. 头条: UNSEEN (r2 没见过) 的读数

| 切片 | n | 直接办对(对·自动) | 点按钮办对(对·按钮) | 正确不办(对·判无/问了) | 安全·问了 | 错·问了 | 判无(该办没办) | 误执行(错·自动) | 失败 | 办对率(ueval口径=前三列之和) |
|---|---|---|---|---|---|---|---|---|---|---|
| **UNSEEN 合计** | 3319 |  41.2% |  38.4% |   5.7% |   2.1% |   7.3% |   1.6% |   3.6% | 3 |  85.3% |
| UNSEEN·context | 611 |  38.8% |  39.4% |   6.4% |   1.3% |   5.7% |   2.8% |   5.4% | 1 |  84.6% |
| UNSEEN·implicit | 752 |  33.9% |  48.0% |   0.0% |   0.0% |  14.1% |   1.6% |   2.4% | 0 |  81.9% |
| UNSEEN·trap | 526 |  22.8% |  24.7% |  28.7% |  12.0% |   3.4% |   1.5% |   6.8% | 0 |  76.2% |
| UNSEEN·plain | 1430 |  52.9% |  37.8% |   0.0% |   0.0% |   5.8% |   1.0% |   2.3% | 2 |  90.7% |

95% 区间 (Wilson): 直接办对 41.2% [39.6, 42.9]; 误执行 3.6% [3.0, 4.3]。

### 误执行 (错·自动) 明细: UNSEEN 共 120 条 (3.6%)

- 按期望: 真意图 74, NONE 35, CLARIFY 11
- **NONE 题里的『误执行』有一部分其实是系统自己的拒答类意图**: 期望 NONE 却被自动判成 OUT_OF_DOMAIN / UNSUPPORTED_CAPABILITY / SYSTEM_HELP 的有 17 条 / NONE 误执行共 35 条。这些按 `ueval.cell` 记『错·自动』(口径不改), 但它们是『域外/不支持』类意图, 不会真去办业务。扣掉后 UNSEEN 误执行 3.1% (仅作旁注, 头条仍按 ueval 口径)。
- 真意图题被直接办成别的意图: 74 条 (2.2% of UNSEEN); 去向前 12 对 (期望→实际×次数):
  QUALITY_CHECK_SUMMARY→QUALITY_STATS×3; ACCOUNT_QUERY→ACCOUNT_TREE_LOOKUP×2; ALERT_BY_EQUIPMENT→ALERT_LIST×2; ATTENDANCE_TODAY→ATTENDANCE_STATUS×2; INDICATOR_QUERY→REPORT_QUALITY×2; MATERIAL_UPDATE→MATERIAL_ADJUST_QUANTITY×2; OPPORTUNITY_FUNNEL_STATS→OPPORTUNITY_STAGE_ALERT×2; PRICE_HISTORY_QUERY→COST_QUERY×1; ALERT_LIST→ALERT_ACTIVE×1; MATERIAL_BATCH_CREATE→MATERIAL_UPDATE×1; HR_LEAVE_APPROVE→MATERIAL_BATCH_CREATE×1; PRODUCTION_PROGRESS_DASHBOARD→ORDER_STATUS×1

### 对照切片 (同一张表头)

| 切片 | n | 直接办对(对·自动) | 点按钮办对(对·按钮) | 正确不办(对·判无/问了) | 安全·问了 | 错·问了 | 判无(该办没办) | 误执行(错·自动) | 失败 | 办对率(ueval口径=前三列之和) |
|---|---|---|---|---|---|---|---|---|---|---|
| ALL (虚高, 含r2训练过的) | 3519 |  41.4% |  38.7% |   5.4% |   2.0% |   7.4% |   1.5% |   3.5% | 3 |  85.5% |
| UNSEEN (头条) | 3319 |  41.2% |  38.4% |   5.7% |   2.1% |   7.3% |   1.6% |   3.6% | 3 |  85.3% |
| STRICT | 3255 |  41.4% |  38.8% |   5.3% |   1.9% |   7.4% |   1.6% |   3.5% | 3 |  85.5% |
| 旧514·验收半 (基线对照) | 257 |  42.8% |  35.8% |   7.8% |   3.5% |   6.2% |   1.6% |   2.3% | 0 |  86.4% |
| 旧514·全部 | 514 |  41.6% |  37.0% |   7.2% |   3.5% |   6.8% |   1.4% |   2.5% | 0 |  85.8% |
| 新增 3005 (UNSEEN 部分) | 2998 |  41.3% |  39.0% |   5.1% |   1.8% |   7.5% |   1.6% |   3.6% | 3 |  85.5% |
| r2训练过的192 (阳性对照) | 192 |  43.8% |  45.3% |   0.0% |   0.0% |   8.9% |   1.0% |   1.0% | 0 |  89.1% |

## 3. 按意图类 (期望意图在运行时目录 catalog_classified3.json 里的 `cls`) —— UNSEEN

| 切片 | n | 直接办对(对·自动) | 点按钮办对(对·按钮) | 正确不办(对·判无/问了) | 安全·问了 | 错·问了 | 判无(该办没办) | 误执行(错·自动) | 失败 | 办对率(ueval口径=前三列之和) |
|---|---|---|---|---|---|---|---|---|---|---|
| QUERY | 1765 |  47.5% |  41.0% |   0.0% |   0.0% |   7.0% |   1.8% |   2.7% | 2 |  88.4% |
| ANALYSIS | 428 |  45.3% |  43.0% |   0.0% |   0.0% |   7.9% |   0.9% |   2.8% | 0 |  88.3% |
| WRITE | 758 |  43.5% |  43.0% |   0.0% |   0.0% |   9.6% |   2.0% |   1.7% | 1 |  86.5% |
| NO_TOOL | 61 |   9.8% |  65.6% |   0.0% |   0.0% |  18.0% |   3.3% |   3.3% | 0 |  75.4% |
| NONE | 263 |   0.0% |   0.0% |  59.7% |  27.0% |   0.0% |   0.0% |  13.3% | 0 |  59.7% |
| CLARIFY | 44 |   0.0% |   0.0% |  75.0% |   0.0% |   0.0% |   0.0% |  25.0% | 0 |  75.0% |

(类别: QUERY 139 / ANALYSIS 32 / WRITE 58 / NO_TOOL 6 个意图; NONE=该判无, CLARIFY=该先问)

### 类 × 题型 (UNSEEN, 直接办对 / 误执行 / n)

| 类 | context | implicit | trap | plain |
|---|---|---|---|---|
| QUERY | 45% / 3% / n=332 | 40% / 2% / n=436 | 45% / 2% / n=166 | 53% / 3% / n=831 |
| ANALYSIS | 36% / 1% / n=78 | 29% / 5% / n=106 | 36% / 3% / n=33 | 58% / 2% / n=211 |
| WRITE | 46% / 3% / n=131 | 26% / 2% / n=192 | 44% / 1% / n=75 | 52% / 1% / n=360 |
| NO_TOOL | 0% / 14% / n=7 | 6% / 0% / n=18 | 12% / 0% / n=8 | 14% / 4% / n=28 |
| NONE | 0% / 26% / n=61 | - | 0% / 9% / n=202 | - |
| CLARIFY | 0% / 0% / n=2 | - | 0% / 26% / n=42 | - |

### 同一张按类表, ALL (虚高)

| 切片 | n | 直接办对(对·自动) | 点按钮办对(对·按钮) | 正确不办(对·判无/问了) | 安全·问了 | 错·问了 | 判无(该办没办) | 误执行(错·自动) | 失败 | 办对率(ueval口径=前三列之和) |
|---|---|---|---|---|---|---|---|---|---|---|
| QUERY | 1887 |  47.4% |  41.2% |   0.0% |   0.0% |   7.0% |   1.6% |   2.5% | 2 |  88.7% |
| ANALYSIS | 430 |  45.6% |  42.8% |   0.0% |   0.0% |   7.9% |   0.9% |   2.8% | 0 |  88.4% |
| WRITE | 834 |  43.0% |  43.3% |   0.0% |   0.0% |   9.7% |   2.0% |   1.8% | 1 |  86.3% |
| NO_TOOL | 61 |   9.8% |  65.6% |   0.0% |   0.0% |  18.0% |   3.3% |   3.3% | 0 |  75.4% |
| NONE | 263 |   0.0% |   0.0% |  59.7% |  27.0% |   0.0% |   0.0% |  13.3% | 0 |  59.7% |
| CLARIFY | 44 |   0.0% |   0.0% |  75.0% |   0.0% |   0.0% |   0.0% |  25.0% | 0 |  75.0% |

## 4. 逐意图 (UNSEEN 口径; 单意图 n 很小, 百分比只看量级, 区间很宽)

| 意图 | 类 | n(UNSEEN) | n(ALL) | 直接办对 | 点按钮办对 | 误执行 | 错·问了 | 判无 | 其它 | 主要混淆去向 (次数) |
|---|---|---|---|---|---|---|---|---|---|---|
| ABACA_WEIGHT_LOG | WRITE | 17 | 17 |  11.8% |  82.4% |   0.0% |   5.9% |   0.0% | 0 | WECHAT_CREATE×1 |
| ABACA_WEIGHT_SUMMARY | ANALYSIS | 17 | 17 |  70.6% |  29.4% |   0.0% |   0.0% |   0.0% | 0 | - |
| ACCOUNTS_RECEIVABLE_AGING | QUERY | 17 | 17 |  47.1% |  35.3% |   5.9% |  11.8% |   0.0% | 0 | FINANCE_PAYMENT_RECORD×2; SPRINT10_SHIPMENT_PENDING_TODAY×1 |
| ACCOUNT_QUERY | QUERY | 16 | 17 |  12.5% |  75.0% |  12.5% |   0.0% |   0.0% | 0 | ACCOUNT_TREE_LOOKUP×2 |
| ACCOUNT_TREE_LOOKUP | QUERY | 18 | 18 |  94.4% |   5.6% |   0.0% |   0.0% |   0.0% | 0 | - |
| ADDITIVE_BOM_COMPLIANCE | QUERY | 11 | 11 |  54.5% |  36.4% |   9.1% |   0.0% |   0.0% | 0 | BOM_RECIPE_QUERY×1 |
| ADDITIVE_CHECK | QUERY | 13 | 13 |  30.8% |  69.2% |   0.0% |   0.0% |   0.0% | 0 | - |
| ADDITIVE_COMPLIANCE_QUALITY | QUERY | 4 | 4 |  25.0% |  75.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| ADDITIVE_SMART_MATCH | QUERY | 10 | 10 |  50.0% |  40.0% |   0.0% |  10.0% |   0.0% | 0 | MATERIAL_UPDATE×1 |
| ALERT_ACKNOWLEDGE | QUERY | 14 | 14 |  42.9% |  42.9% |   0.0% |   7.1% |   7.1% | 0 | ∅(判无)×1; FINANCE_PAYMENT_RECORD×1 |
| ALERT_ACTIVE | QUERY | 6 | 10 |  33.3% |  50.0% |   0.0% |  16.7% |   0.0% | 0 | HR_EXPENSE_REQUEST_QUERY×1 |
| ALERT_BY_EQUIPMENT | QUERY | 15 | 15 |  46.7% |  40.0% |  13.3% |   0.0% |   0.0% | 0 | ALERT_LIST×2 |
| ALERT_DIAGNOSE | ANALYSIS | 14 | 14 |  28.6% |  64.3% |   0.0% |   7.1% |   0.0% | 0 | COLD_CHAIN_ALERT_ACTIVE×1 |
| ALERT_LIST | QUERY | 5 | 5 |  20.0% |  60.0% |  20.0% |   0.0% |   0.0% | 0 | ALERT_ACTIVE×1 |
| ALERT_RESOLVE | QUERY | 11 | 18 |  90.9% |   9.1% |   0.0% |   0.0% |   0.0% | 0 | - |
| ALERT_STATS | ANALYSIS | 17 | 17 |  47.1% |  47.1% |   0.0% |   5.9% |   0.0% | 0 | ALERT_DIAGNOSE×1 |
| ALERT_TRIAGE | QUERY | 11 | 11 |  63.6% |  36.4% |   0.0% |   0.0% |   0.0% | 0 | - |
| APPROVAL_ACTION_EXECUTE | QUERY | 13 | 13 |   7.7% |  76.9% |   0.0% |  15.4% |   0.0% | 0 | QUALITY_DISPOSITION_EXECUTE×1; MATERIAL_BATCH_CREATE×1 |
| APPROVAL_PENDING_QUERY | QUERY | 18 | 18 |  33.3% |  55.6% |   5.6% |   5.6% |   0.0% | 0 | ORDER_STATUS×1; REMINDER_LIST_MINE×1 |
| ATTENDANCE_ANOMALY | ANALYSIS | 15 | 15 |  60.0% |  26.7% |   6.7% |   6.7% |   0.0% | 0 | ATTENDANCE_HISTORY×2 |
| ATTENDANCE_DEPARTMENT | QUERY | 5 | 5 |   0.0% |  60.0% |   0.0% |  40.0% |   0.0% | 0 | ATTENDANCE_STATS_BY_DEPT×1; ATTENDANCE_HISTORY×1 |
| ATTENDANCE_HISTORY | QUERY | 9 | 9 |  66.7% |  33.3% |   0.0% |   0.0% |   0.0% | 0 | - |
| ATTENDANCE_MONTHLY | QUERY | 13 | 13 |  61.5% |  38.5% |   0.0% |   0.0% |   0.0% | 0 | - |
| ATTENDANCE_STATS | ANALYSIS | 4 | 4 |   0.0% | 100.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| ATTENDANCE_STATS_BY_DEPT | ANALYSIS | 18 | 18 |  33.3% |  66.7% |   0.0% |   0.0% |   0.0% | 0 | - |
| ATTENDANCE_STATUS | QUERY | 10 | 12 |  70.0% |  30.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| ATTENDANCE_TODAY | QUERY | 17 | 17 |  23.5% |  58.8% |  17.6% |   0.0% |   0.0% | 0 | ATTENDANCE_STATUS×2; ATTENDANCE_HISTORY×1 |
| BALANCE_SHEET_QUERY | QUERY | 18 | 18 |  72.2% |  22.2% |   5.6% |   0.0% |   0.0% | 0 | INCOME_STATEMENT_QUERY×1 |
| BATCH_CONSUMPTION_ADJUST | QUERY | 8 | 13 |  87.5% |  12.5% |   0.0% |   0.0% |   0.0% | 0 | - |
| BATCH_CONSUMPTION_QUERY | QUERY | 21 | 24 |  61.9% |  38.1% |   0.0% |   0.0% |   0.0% | 0 | - |
| BATCH_FULL_TRACE | QUERY | 2 | 2 |   0.0% |  50.0% |   0.0% |  50.0% |   0.0% | 0 | TRACE_BATCH×1 |
| BATCH_TRACE_BY_CUSTOMER | QUERY | 19 | 19 |  15.8% |  68.4% |   5.3% |  10.5% |   0.0% | 0 | TRACE_BATCH×2; ORDER_STATUS×1 |
| BATCH_UPDATE | WRITE | 6 | 6 |   0.0% |  66.7% |   0.0% |  16.7% |  16.7% | 0 | ∅(判无)×1; SHIPMENT_CREATE×1 |
| BOM_RECIPE_QUERY | QUERY | 14 | 14 |  78.6% |  21.4% |   0.0% |   0.0% |   0.0% | 0 | - |
| BUSINESS_LINK_QUERY | QUERY | 12 | 12 |  66.7% |  33.3% |   0.0% |   0.0% |   0.0% | 0 | - |
| CALL_FOLLOWUP_PENDING | QUERY | 15 | 15 |  26.7% |  66.7% |   0.0% |   0.0% |   6.7% | 0 | ∅(判无)×1 |
| CASHFLOW_STATEMENT_QUERY | QUERY | 18 | 18 |  61.1% |  22.2% |   0.0% |  11.1% |   5.6% | 0 | REPORT_FINANCE×2; ∅(判无)×1 |
| CLOCK_IN | QUERY | 5 | 14 |  60.0% |  20.0% |   0.0% |  20.0% |   0.0% | 0 | ATTENDANCE_ANOMALY×1 |
| CLOCK_OUT | QUERY | 14 | 16 |  71.4% |  21.4% |   0.0% |   7.1% |   0.0% | 0 | OUT_OF_DOMAIN×1 |
| COLD_CHAIN_ALERT_ACKNOWLEDGE | WRITE | 15 | 15 |  80.0% |  20.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| COLD_CHAIN_ALERT_ACTIVE | QUERY | 8 | 16 |  50.0% |  50.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| COLD_CHAIN_TEMP_QUERY | QUERY | 21 | 22 |  61.9% |  33.3% |   0.0% |   0.0% |   4.8% | 0 | ∅(判无)×1 |
| COMMISSION_PENDING_TOTAL | QUERY | 17 | 17 |  76.5% |  23.5% |   0.0% |   0.0% |   0.0% | 0 | - |
| CONVERSION_RATE_UPDATE | WRITE | 16 | 16 |  75.0% |  25.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| COST_QUERY | ANALYSIS | 13 | 13 |  23.1% |  61.5% |   0.0% |   7.7% |   7.7% | 0 | SKU_GROSS_MARGIN×1; ∅(判无)×1 |
| CUSTOMER_ACTIVE | QUERY | 13 | 13 |  61.5% |  23.1% |   7.7% |   7.7% |   0.0% | 0 | CUSTOMER_TRACKING_RECENT_QUERY×1; OUT_OF_DOMAIN×1 |
| CUSTOMER_BY_TYPE | QUERY | 16 | 16 |  62.5% |  37.5% |   0.0% |   0.0% |   0.0% | 0 | - |
| CUSTOMER_LIST | QUERY | 12 | 12 |  75.0% |  25.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| CUSTOMER_NOTIFY_BATCH | WRITE | 15 | 15 |  13.3% |  66.7% |   0.0% |  13.3% |   6.7% | 0 | SALES_NEED_CONVERT×1; CUSTOMER_PRIORITY_QUERY×1; ∅(判无)×1 |
| CUSTOMER_PRIORITY_QUERY | QUERY | 15 | 15 |  40.0% |  33.3% |   6.7% |   0.0% |  20.0% | 0 | ∅(判无)×3; OPPORTUNITY_FUNNEL_STATS×1 |
| CUSTOMER_PURCHASE_HISTORY | QUERY | 16 | 16 |  68.8% |  31.2% |   0.0% |   0.0% |   0.0% | 0 | - |
| CUSTOMER_QUALITY_STANDARD | QUERY | 18 | 18 |  72.2% |  16.7% |   5.6% |   5.6% |   0.0% | 0 | QUALITY_CHECK_QUERY×2 |
| CUSTOMER_REVENUE_TREND | ANALYSIS | 16 | 16 |  12.5% |  68.8% |   6.2% |  12.5% |   0.0% | 0 | INCOME_STATEMENT_QUERY×2; REPORT_TRENDS×1 |
| CUSTOMER_SEARCH | QUERY | 10 | 10 |  70.0% |  20.0% |  10.0% |   0.0% |   0.0% | 0 | CUSTOMER_LIST×1 |
| CUSTOMER_STATS | ANALYSIS | 8 | 8 |  25.0% |  50.0% |  12.5% |  12.5% |   0.0% | 0 | CUSTOMER_ACTIVE×1; SHIPMENT_STATS×1 |
| CUSTOMER_TRACKING_RECENT_QUERY | QUERY | 23 | 23 |  30.4% |  56.5% |   0.0% |  13.0% |   0.0% | 0 | WECHAT_RECENT_QUERY×1; OUT_OF_DOMAIN×1; CUSTOMER_ACTIVE×1 |
| DAILY_CUSTOMER_FOLLOWUP | NO_TOOL | 19 | 19 |  10.5% |  52.6% |   5.3% |  21.1% |  10.5% | 0 | ∅(判无)×2; ORDER_TODAY×1; ORDER_STATUS×1 |
| EFFICIENCY_COMPARISON | ANALYSIS | 14 | 14 |  28.6% |  42.9% |   0.0% |  28.6% |   0.0% | 0 | PROCESS_TASK_ANALYSIS×2; PROCESS_TASK_SUMMARY×1; SHIPMENT_STATS×1 |
| EQUIPMENT_MAINTENANCE | QUERY | 17 | 17 |  41.2% |  52.9% |   0.0% |   0.0% |   5.9% | 0 | ∅(判无)×1 |
| FACTORY_MR_CLOSE | QUERY | 17 | 17 |  41.2% |  23.5% |   0.0% |  17.6% |  17.6% | 0 | ∅(判无)×3; OPPORTUNITY_TRANSITION×1; ORDER_STATUS×1 |
| FACTORY_MR_GENERATE | WRITE | 16 | 16 |  62.5% |  31.2% |   0.0% |   6.2% |   0.0% | 0 | SHORTAGE_ANALYSIS×1 |
| FACTORY_MR_QUERY_PENDING | QUERY | 8 | 8 |  12.5% |  62.5% |  12.5% |  12.5% |   0.0% | 0 | MATERIAL_TODAY_RECEIVING_QUERY×2 |
| FINANCE_INVOICE_APPROVE | QUERY | 13 | 13 |  53.8% |  46.2% |   0.0% |   0.0% |   0.0% | 0 | - |
| FINANCE_INVOICE_REQUEST | WRITE | 12 | 12 |  83.3% |  16.7% |   0.0% |   0.0% |   0.0% | 0 | - |
| FINANCE_INVOICE_STATUS | QUERY | 20 | 22 |  55.0% |  35.0% |   5.0% |   0.0% |   5.0% | 0 | ∅(判无)×1; APPROVAL_PENDING_QUERY×1 |
| FINANCE_PAYMENT_RECORD | QUERY | 14 | 14 |  85.7% |  14.3% |   0.0% |   0.0% |   0.0% | 0 | - |
| FINANCE_PPT_EXPORT | QUERY | 16 | 16 |  81.2% |  18.8% |   0.0% |   0.0% |   0.0% | 0 | - |
| FINANCIAL_CHART_GENERATE | WRITE | 17 | 17 |  35.3% |  52.9% |   5.9% |   5.9% |   0.0% | 0 | INCOME_STATEMENT_QUERY×1; FINANCE_PPT_EXPORT×1 |
| FOOD_SAFETY_RECALL | NO_TOOL | 16 | 16 |   6.2% |  75.0% |   0.0% |  18.8% |   0.0% | 0 | CUSTOMER_NOTIFY_BATCH×1; QUALITY_DEFECT_QUERY×1; QUALITY_DISPOSITION_EXECUTE×1 |
| FOOD_SAMPLE_CREATE | WRITE | 15 | 15 |  73.3% |  26.7% |   0.0% |   0.0% |   0.0% | 0 | - |
| FOOD_SAMPLE_DISPOSE | WRITE | 17 | 17 |  47.1% |  47.1% |   0.0% |   5.9% |   0.0% | 0 | MATERIAL_BATCH_RELEASE×1 |
| FOOD_SAMPLE_QUERY_PENDING_DISPOSE | QUERY | 13 | 14 |  69.2% |  30.8% |   0.0% |   0.0% |   0.0% | 0 | - |
| FOOD_SAMPLE_TRACE | QUERY | 17 | 17 |  41.2% |  52.9% |   5.9% |   0.0% |   0.0% | 0 | FOOD_SAMPLE_CREATE×1 |
| HACCP_REVIEW | QUERY | 16 | 16 |  43.8% |  50.0% |   6.2% |   0.0% |   0.0% | 0 | APPROVAL_PENDING_QUERY×1 |
| HACCP_STATUS_QUERY | QUERY | 20 | 20 |  30.0% |  45.0% |   5.0% |  20.0% |   0.0% | 0 | HACCP_REVIEW×3; COLD_CHAIN_ALERT_ACTIVE×1; RELEASE_DECISION×1 |
| HR_EXPENSE_REQUEST_QUERY | QUERY | 19 | 19 |  42.1% |  42.1% |   0.0% |   5.3% |  10.5% | 0 | ∅(判无)×2; BUSINESS_LINK_QUERY×1 |
| HR_LEAVE_APPROVE | WRITE | 10 | 16 |  10.0% |  60.0% |  10.0% |  10.0% |  10.0% | 0 | APPROVAL_ACTION_EXECUTE×1; MATERIAL_BATCH_CREATE×1; ∅(判无)×1 |
| HR_LEAVE_BALANCE_QUERY | QUERY | 18 | 19 |  77.8% |  22.2% |   0.0% |   0.0% |   0.0% | 0 | - |
| HR_LEAVE_REQUEST_QUERY | QUERY | 18 | 20 |  16.7% |  61.1% |   0.0% |  22.2% |   0.0% | 0 | ATTENDANCE_MONTHLY×3; ORDER_LIST×1 |
| HR_LEAVE_SUBMIT | WRITE | 10 | 18 |  20.0% |  70.0% |   0.0% |   0.0% |  10.0% | 0 | ∅(判无)×1 |
| HR_OVERTIME_REQUEST_QUERY | QUERY | 18 | 19 |  44.4% |  38.9% |  11.1% |   0.0% |   5.6% | 0 | HR_LEAVE_REQUEST_QUERY×1; ATTENDANCE_STATS_BY_DEPT×1; ∅(判无)×1 |
| INCOME_STATEMENT_QUERY | QUERY | 17 | 17 |  47.1% |  41.2% |   0.0% |   5.9% |   5.9% | 0 | ∅(判无)×1; REPORT_FINANCE×1 |
| INDICATOR_ALERT | QUERY | 10 | 10 |  10.0% |  80.0% |   0.0% |  10.0% |   0.0% | 0 | SMART_INDICATOR_QUERY×1 |
| INDICATOR_COMPARISON | ANALYSIS | 16 | 16 |  43.8% |  56.2% |   0.0% |   0.0% |   0.0% | 0 | - |
| INDICATOR_QUERY | QUERY | 13 | 13 |   0.0% |  38.5% |  23.1% |  23.1% |   0.0% | 2 | SMART_INDICATOR_QUERY×2; QUALITY_STATS×2; REPORT_QUALITY×2 |
| INVENTORY_FREEZE | WRITE | 20 | 20 |  25.0% |  40.0% |   0.0% |  20.0% |  10.0% | 1 | ∅(判无)×2; RELEASE_DECISION×1; QUALITY_DISPOSITION_EXECUTE×1 |
| LINEAGE_QUERY | QUERY | 1 | 1 |   0.0% | 100.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| MATERIAL_ADJUST_QUANTITY | QUERY | 15 | 15 |  46.7% |  53.3% |   0.0% |   0.0% |   0.0% | 0 | - |
| MATERIAL_BATCH_CONSUME | QUERY | 15 | 15 |  26.7% |  66.7% |   0.0% |   6.7% |   0.0% | 0 | MATERIAL_BATCH_RELEASE×1 |
| MATERIAL_BATCH_CREATE | WRITE | 9 | 15 |  11.1% |  44.4% |  11.1% |  22.2% |  11.1% | 0 | ∅(判无)×1; MATERIAL_UPDATE×1; MATERIAL_TODAY_RECEIVING_QUERY×1 |
| MATERIAL_BATCH_QUERY | QUERY | 17 | 23 |  35.3% |  52.9% |   0.0% |  11.8% |   0.0% | 0 | CONTEXT_CONTINUE×1; MATERIAL_UPDATE×1 |
| MATERIAL_BATCH_RELEASE | QUERY | 22 | 22 |  45.5% |  27.3% |  13.6% |   9.1% |   4.5% | 0 | STOCK_ALERT×1; ∅(判无)×1; QUALITY_DISPOSITION_EXECUTE×1 |
| MATERIAL_BATCH_RESERVE | QUERY | 16 | 16 |  50.0% |  25.0% |   0.0% |  12.5% |  12.5% | 0 | ∅(判无)×2; ORDER_LIST×1; MATERIAL_FIFO_RECOMMEND×1 |
| MATERIAL_BATCH_USE | QUERY | 8 | 13 |  62.5% |  25.0% |   0.0% |  12.5% |   0.0% | 0 | MATERIAL_BATCH_RESERVE×1 |
| MATERIAL_BATCH_WIP_QUERY | QUERY | 13 | 13 |  61.5% |  23.1% |   0.0% |  15.4% |   0.0% | 0 | BATCH_CONSUMPTION_QUERY×1; MATERIAL_BATCH_QUERY×1 |
| MATERIAL_DISPOSAL_RECOMMENDATION | QUERY | 15 | 16 |   6.7% |  53.3% |   0.0% |  33.3% |   6.7% | 0 | QUALITY_DISPOSITION_EVALUATE×3; ∅(判无)×1; MATERIAL_EXPIRING_ALERT×1 |
| MATERIAL_EXPIRED_QUERY | QUERY | 11 | 12 |  72.7% |  18.2% |   0.0% |   9.1% |   0.0% | 0 | MATERIAL_BATCH_QUERY×1 |
| MATERIAL_EXPIRING_ALERT | WRITE | 13 | 18 |  69.2% |  15.4% |   7.7% |   7.7% |   0.0% | 0 | MATERIAL_DISPOSAL_RECOMMENDATION×1; MATERIAL_EXPIRED_QUERY×1 |
| MATERIAL_FIFO_RECOMMEND | QUERY | 16 | 16 |  50.0% |  50.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| MATERIAL_LOW_STOCK_ALERT | WRITE | 7 | 16 |  14.3% |  85.7% |   0.0% |   0.0% |   0.0% | 0 | - |
| MATERIAL_MARK_ABACA | WRITE | 15 | 15 |  66.7% |   6.7% |   0.0% |  26.7% |   0.0% | 0 | MATERIAL_BATCH_CREATE×2; RULE_CONFIG×1; MATERIAL_ADJUST_QUANTITY×1 |
| MATERIAL_TODAY_RECEIVING_QUERY | QUERY | 16 | 16 |  37.5% |  56.2% |   0.0% |   6.2% |   0.0% | 0 | SPRINT10_SHIPMENT_PENDING_TODAY×1 |
| MATERIAL_UPDATE | WRITE | 17 | 17 |  17.6% |  52.9% |  17.6% |  11.8% |   0.0% | 0 | MATERIAL_ADJUST_QUANTITY×2; BATCH_UPDATE×2; RELEASE_DECISION×1 |
| MONTHLY_FINANCIAL_CLOSE | NO_TOOL | 17 | 17 |  17.6% |  82.4% |   0.0% |   0.0% |   0.0% | 0 | - |
| OPERATION_LOG_QUERY | QUERY | 13 | 13 |  46.2% |  38.5% |   0.0% |  15.4% |   0.0% | 0 | ATTENDANCE_STATS×1; PRODUCTION_PROGRESS_DASHBOARD×1 |
| OPPORTUNITY_FUNNEL_STATS | ANALYSIS | 14 | 14 |  64.3% |   7.1% |  21.4% |   7.1% |   0.0% | 0 | OPPORTUNITY_STAGE_ALERT×2; CONTEXT_CONTINUE×1; CASHFLOW_STATEMENT_QUERY×1 |
| OPPORTUNITY_STAGE_ALERT | WRITE | 19 | 19 |  57.9% |  21.1% |   5.3% |  15.8% |   0.0% | 0 | ORDER_STATUS×3; PRODUCTION_DELIVERY_WARN_QUERY×1 |
| OPPORTUNITY_TRANSITION | WRITE | 16 | 16 |  37.5% |  50.0% |   0.0% |  12.5% |   0.0% | 0 | CUSTOMER_ACTIVE×1; RD_SAMPLE_APPROVE×1 |
| ORDER_LIST | QUERY | 17 | 17 |  29.4% |  52.9% |   0.0% |  17.6% |   0.0% | 0 | ORDER_STATUS×1; SHIPMENT_STATS×1; RETURN_ORDER_LIST×1 |
| ORDER_STATUS | QUERY | 9 | 13 |  66.7% |  33.3% |   0.0% |   0.0% |   0.0% | 0 | - |
| ORDER_TODAY | QUERY | 9 | 15 |  88.9% |   0.0% |   0.0% |  11.1% |   0.0% | 0 | ORDER_STATUS×1 |
| PDA_SCAN_TASK_GENERATE | WRITE | 18 | 18 |  33.3% |  50.0% |   5.6% |   5.6% |   5.6% | 0 | TRACE_FULL×1; TRACE_PUBLIC×1; ∅(判无)×1 |
| PERIOD_CONFIRM_CLOSE | WRITE | 14 | 15 |  14.3% |  21.4% |   0.0% |  50.0% |  14.3% | 0 | APPROVAL_ACTION_EXECUTE×2; ∅(判无)×2; PERIOD_REOPEN×1 |
| PERIOD_REOPEN | WRITE | 17 | 17 |  47.1% |  47.1% |   0.0% |   5.9% |   0.0% | 0 | REPORT_FINANCE×1 |
| PERIOD_REQUEST_CLOSE | WRITE | 16 | 16 |  50.0% |  50.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| PERIOD_STATUS_QUERY | QUERY | 23 | 23 |  17.4% |  69.6% |   4.3% |   4.3% |   4.3% | 0 | MONTHLY_FINANCIAL_CLOSE×1; ORDER_LIST×1; ∅(判无)×1 |
| PLAN_UPDATE | WRITE | 17 | 17 |  17.6% |  58.8% |   5.9% |  11.8% |   5.9% | 0 | ∅(判无)×1; RD_REQUEST_CREATE×1; BATCH_UPDATE×1 |
| PRICE_HISTORY_QUERY | QUERY | 22 | 22 |  22.7% |  63.6% |   4.5% |   9.1% |   0.0% | 0 | REPORT_TRENDS×2; COST_QUERY×1 |
| PRINT_DOCUMENT | QUERY | 15 | 15 |  60.0% |  33.3% |   0.0% |   6.7% |   0.0% | 0 | ORDER_LIST×1 |
| PRINT_TEMPLATE_CREATE_FROM_AI | WRITE | 1 | 1 |   0.0% | 100.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| PROCESSING_CAPACITY_TODAY | QUERY | 9 | 10 |  22.2% |  44.4% |   0.0% |  33.3% |   0.0% | 0 | PRODUCTION_PROGRESS_DASHBOARD×2; PROCESS_TASK_ANALYSIS×1 |
| PROCESS_TASK_ANALYSIS | ANALYSIS | 7 | 7 |  28.6% |  71.4% |   0.0% |   0.0% |   0.0% | 0 | - |
| PROCESS_TASK_QUERY | QUERY | 21 | 23 |   9.5% |  47.6% |   4.8% |  38.1% |   0.0% | 0 | PROCESS_TASK_SUMMARY×2; PRODUCTION_PROGRESS_DASHBOARD×2; PROCESS_TASK_ANALYSIS×2 |
| PROCESS_TASK_SUMMARY | ANALYSIS | 9 | 9 |   0.0% |  55.6% |  11.1% |  33.3% |   0.0% | 0 | PRODUCTION_PROGRESS_DASHBOARD×3; REMINDER_LIST_MINE×1 |
| PROCUREMENT_ORDER_CREATE | WRITE | 12 | 17 |  33.3% |  58.3% |   0.0% |   8.3% |   0.0% | 0 | STOCK_ALERT×1 |
| PRODUCTION_BATCH_CREATE | WRITE | 10 | 10 |  10.0% |  80.0% |   0.0% |  10.0% |   0.0% | 0 | PRODUCTION_PLAN_CREATE×1 |
| PRODUCTION_DELIVERY_WARN_QUERY | QUERY | 12 | 13 |  75.0% |  25.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| PRODUCTION_DEMAND_ANALYSIS | NO_TOOL | 8 | 8 |   0.0% |  37.5% |  12.5% |  50.0% |   0.0% | 0 | PRODUCTION_PLAN_CREATE×2; SHORTAGE_ANALYSIS×1; PROCESS_TASK_ANALYSIS×1 |
| PRODUCTION_DEMAND_QUERY | QUERY | 15 | 15 |  26.7% |  66.7% |   0.0% |   6.7% |   0.0% | 0 | SHORTAGE_ANALYSIS×1 |
| PRODUCTION_PLAN_CREATE | WRITE | 12 | 12 |  58.3% |  41.7% |   0.0% |   0.0% |   0.0% | 0 | - |
| PRODUCTION_PROGRESS_DASHBOARD | QUERY | 2 | 6 |  50.0% |   0.0% |  50.0% |   0.0% |   0.0% | 0 | ORDER_STATUS×1 |
| PRODUCT_CREATE_FLYWHEEL | WRITE | 14 | 14 |  35.7% |  42.9% |   0.0% |  21.4% |   0.0% | 0 | RD_REQUEST_CREATE×2; WORK_PROCESS_CONFIG_UPDATE×1 |
| PRODUCT_UPDATE | WRITE | 15 | 15 |  53.3% |  40.0% |   0.0% |   6.7% |   0.0% | 0 | MATERIAL_UPDATE×1 |
| PURCHASE_FINANCE_APPROVE | QUERY | 15 | 15 |   6.7% |  80.0% |   0.0% |  13.3% |   0.0% | 0 | FINANCE_INVOICE_APPROVE×1; PERIOD_CONFIRM_CLOSE×1 |
| PURCHASE_ORDER_APPROVE | QUERY | 10 | 16 |  30.0% |  50.0% |   0.0% |  20.0% |   0.0% | 0 | SHIPMENT_CREATE×1; APPROVAL_ACTION_EXECUTE×1 |
| QUALITY_CHECK_CREATE | WRITE | 2 | 2 |  50.0% |  50.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| QUALITY_CHECK_QUERY | QUERY | 13 | 13 |  76.9% |  23.1% |   0.0% |   0.0% |   0.0% | 0 | - |
| QUALITY_CHECK_SUMMARY | ANALYSIS | 18 | 18 |   0.0% |  61.1% |  16.7% |  22.2% |   0.0% | 0 | QUALITY_STATS×5; BATCH_FULL_TRACE×1; RELEASE_DECISION×1 |
| QUALITY_CHIEF_WORKDESK | NO_TOOL | 1 | 1 |   0.0% | 100.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| QUALITY_CRITICAL_ITEMS | QUERY | 15 | 15 |  66.7% |  26.7% |   6.7% |   0.0% |   0.0% | 0 | QUALITY_CHECK_QUERY×1 |
| QUALITY_DEFECT_QUERY | QUERY | 22 | 24 |  36.4% |  50.0% |   0.0% |   4.5% |   9.1% | 0 | ∅(判无)×2; RETURN_ORDER_APPROVE×1 |
| QUALITY_DEFECT_RECORD | QUERY | 11 | 17 |  36.4% |  63.6% |   0.0% |   0.0% |   0.0% | 0 | - |
| QUALITY_DISPOSITION_EVALUATE | QUERY | 12 | 12 |  33.3% |  66.7% |   0.0% |   0.0% |   0.0% | 0 | - |
| QUALITY_DISPOSITION_EXECUTE | QUERY | 4 | 6 |  50.0% |   0.0% |   0.0% |  25.0% |  25.0% | 0 | ∅(判无)×1; RETURN_ORDER_APPROVE×1 |
| QUALITY_RETURN_CREATE | WRITE | 6 | 7 |   0.0% |  50.0% |  16.7% |  33.3% |   0.0% | 0 | RETURN_ORDER_CREATE×2; QUALITY_DISPOSITION_EXECUTE×1 |
| QUALITY_RETURN_QUERY | QUERY | 8 | 8 |  25.0% |  37.5% |   0.0% |  37.5% |   0.0% | 0 | RETURN_ORDER_LIST×1; APPROVAL_PENDING_QUERY×1; ORDER_STATUS×1 |
| QUALITY_STATS | ANALYSIS | 5 | 5 |  80.0% |  20.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| RD_REQUEST_CREATE | WRITE | 16 | 16 |  68.8% |  25.0% |   6.2% |   0.0% |   0.0% | 0 | WORK_PROCESS_CONFIG_UPDATE×1 |
| RD_SAMPLE_APPROVE | QUERY | 15 | 15 |  66.7% |  26.7% |   0.0% |   0.0% |   6.7% | 0 | ∅(判无)×1 |
| RD_SAMPLE_CREATE | WRITE | 14 | 14 |  28.6% |  64.3% |   0.0% |   7.1% |   0.0% | 0 | RD_REQUEST_CREATE×1 |
| RECALL_LOSS_ESTIMATE | QUERY | 17 | 17 |  70.6% |  29.4% |   0.0% |   0.0% |   0.0% | 0 | - |
| RECEIVE_CONFIRM_CREATE | WRITE | 16 | 16 |  50.0% |  37.5% |   0.0% |  12.5% |   0.0% | 0 | REQUISITION_CREATE×1; RELEASE_DECISION×1 |
| RECEIVE_QUALITY_CHECK_TODAY | QUERY | 19 | 24 |  42.1% |  52.6% |   5.3% |   0.0% |   0.0% | 0 | HACCP_REVIEW×1 |
| RECEIVE_WITH_LIMIT | WRITE | 9 | 9 |   0.0% |  66.7% |   0.0% |  33.3% |   0.0% | 0 | RECEIVE_CONFIRM_CREATE×2; MATERIAL_BATCH_CREATE×1 |
| REGULATORY_REPORT | WRITE | 15 | 15 |  53.3% |  40.0% |   0.0% |   6.7% |   0.0% | 0 | BATCH_FULL_TRACE×1 |
| RELEASE_DECISION | WRITE | 19 | 19 |   0.0% |  78.9% |   0.0% |  15.8% |   5.3% | 0 | QUALITY_DISPOSITION_EXECUTE×3; ∅(判无)×1 |
| REMINDER_LIST_MINE | QUERY | 10 | 10 |   0.0% |  70.0% |   0.0% |  30.0% |   0.0% | 0 | ALERT_RESOLVE×1; MATERIAL_EXPIRED_QUERY×1; ORDER_LIST×1 |
| REPORT_ANOMALY | ANALYSIS | 10 | 10 |  60.0% |  30.0% |   0.0% |  10.0% |   0.0% | 0 | LINEAGE_QUERY×1 |
| REPORT_DASHBOARD_OVERVIEW | ANALYSIS | 10 | 10 |  80.0% |  20.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| REPORT_EFFICIENCY | ANALYSIS | 7 | 7 |  14.3% |  57.1% |   0.0% |  28.6% |   0.0% | 0 | BATCH_UPDATE×1; PRODUCTION_PROGRESS_DASHBOARD×1 |
| REPORT_FINANCE | ANALYSIS | 10 | 10 |  40.0% |  60.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| REPORT_INVENTORY | ANALYSIS | 8 | 8 |  37.5% |  62.5% |   0.0% |   0.0% |   0.0% | 0 | - |
| REPORT_KPI | ANALYSIS | 12 | 12 |  41.7% |  50.0% |   0.0% |   8.3% |   0.0% | 0 | REPORT_DASHBOARD_OVERVIEW×1 |
| REPORT_PRODUCTION | ANALYSIS | 16 | 16 |  31.2% |  68.8% |   0.0% |   0.0% |   0.0% | 0 | - |
| REPORT_QUALITY | ANALYSIS | 8 | 8 |  75.0% |  25.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| REPORT_TRENDS | ANALYSIS | 14 | 14 |  28.6% |  64.3% |   7.1% |   0.0% |   0.0% | 0 | REPORT_PRODUCTION×1 |
| REQUISITION_CREATE | WRITE | 13 | 13 |  53.8% |  30.8% |   0.0% |   7.7% |   7.7% | 0 | SHIPMENT_CREATE×1; ∅(判无)×1 |
| RETURN_ORDER_APPROVE | QUERY | 17 | 17 |  11.8% |  82.4% |   0.0% |   5.9% |   0.0% | 0 | APPROVAL_ACTION_EXECUTE×1 |
| RETURN_ORDER_CREATE | WRITE | 7 | 14 |  42.9% |  57.1% |   0.0% |   0.0% |   0.0% | 0 | - |
| RETURN_ORDER_LIST | QUERY | 17 | 18 |  41.2% |  47.1% |   0.0% |  11.8% |   0.0% | 0 | CONTEXT_CONTINUE×2 |
| REUSABLE_CONTAINER_LOSS | WRITE | 17 | 17 |  76.5% |  23.5% |   0.0% |   0.0% |   0.0% | 0 | - |
| REUSABLE_CONTAINER_QUERY | QUERY | 21 | 21 |  66.7% |  19.0% |   4.8% |   9.5% |   0.0% | 0 | PAGINATION_PREVIOUS×1; REUSABLE_CONTAINER_SHIP_OUT×1; REUSABLE_CONTAINER_RETURN_IN×1 |
| REUSABLE_CONTAINER_RETURN_IN | WRITE | 17 | 17 |  64.7% |  17.6% |   0.0% |  17.6% |   0.0% | 0 | REUSABLE_CONTAINER_LOSS×2; RECEIVE_CONFIRM_CREATE×1 |
| REUSABLE_CONTAINER_SHIP_OUT | WRITE | 16 | 16 |  50.0% |  37.5% |   0.0% |  12.5% |   0.0% | 0 | SHIPMENT_CREATE×2 |
| SALES_FORECAST_7DAY | ANALYSIS | 16 | 16 |  31.2% |  56.2% |   0.0% |   0.0% |  12.5% | 0 | ∅(判无)×2 |
| SALES_NEED_CONVERT | QUERY | 16 | 16 |  56.2% |  43.8% |   0.0% |   0.0% |   0.0% | 0 | - |
| SALES_NEED_CREATE | WRITE | 16 | 16 |  12.5% |  62.5% |   0.0% |  18.8% |   6.2% | 0 | SHIPMENT_CREATE×1; ∅(判无)×1; RD_SAMPLE_APPROVE×1 |
| SAMPLE_TO_BOM | QUERY | 17 | 17 |  17.6% |  58.8% |   0.0% |  17.6% |   5.9% | 0 | ∅(判无)×1; PRODUCT_CREATE_FLYWHEEL×1; WORK_PROCESS_CONFIG_UPDATE×1 |
| SCALE_DEVICE_DETAIL | QUERY | 15 | 15 |   6.7% |  46.7% |   0.0% |  46.7% |   0.0% | 0 | ABACA_WEIGHT_SUMMARY×2; SCALE_TROUBLESHOOT×1; SMART_INDICATOR_QUERY×1 |
| SCALE_LIST_DEVICES | QUERY | 14 | 14 |  78.6% |   7.1% |   7.1% |   7.1% |   0.0% | 0 | SCALE_DEVICE_DETAIL×2 |
| SCHEDULING_LIST | QUERY | 18 | 18 |  94.4% |   5.6% |   0.0% |   0.0% |   0.0% | 0 | - |
| SHIPMENT_CONFIRM_CREATE | WRITE | 7 | 7 |   0.0% | 100.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| SHIPMENT_CREATE | WRITE | 6 | 11 |  66.7% |  33.3% |   0.0% |   0.0% |   0.0% | 0 | - |
| SHIPMENT_QUERY | QUERY | 7 | 16 |  71.4% |  28.6% |   0.0% |   0.0% |   0.0% | 0 | - |
| SHIPMENT_STATS | ANALYSIS | 18 | 19 |  50.0% |  38.9% |   0.0% |  11.1% |   0.0% | 0 | PAGINATION_PREVIOUS×1; REPORT_DASHBOARD_OVERVIEW×1 |
| SHORTAGE_ANALYSIS | ANALYSIS | 26 | 27 |  26.9% |  42.3% |   0.0% |  30.8% |   0.0% | 0 | PRODUCTION_DEMAND_QUERY×3; MATERIAL_LOW_STOCK_ALERT×2; MATERIAL_BATCH_RESERVE×1 |
| SKU_GROSS_MARGIN | ANALYSIS | 16 | 16 |  81.2% |   6.2% |   0.0% |   6.2% |   6.2% | 0 | ∅(判无)×1; SMART_INDICATOR_QUERY×1 |
| SOURCE_INDIVIDUAL_QUERY | QUERY | 14 | 14 |  28.6% |  64.3% |   7.1% |   0.0% |   0.0% | 0 | PDA_SCAN_TASK_GENERATE×1 |
| SPRINT10_SHIPMENT_PENDING_TODAY | WRITE | 13 | 21 |  76.9% |   7.7% |   0.0% |   7.7% |   7.7% | 0 | PRODUCTION_DELIVERY_WARN_QUERY×1; ∅(判无)×1 |
| SSOP_EXECUTE_COMPLETE | WRITE | 20 | 20 |  80.0% |  20.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| SSOP_EXECUTION_TODAY | QUERY | 19 | 19 |  68.4% |  26.3% |   0.0% |   5.3% |   0.0% | 0 | SSOP_EXECUTE_COMPLETE×1 |
| SSOP_MONTHLY_AUDIT_REPORT | ANALYSIS | 17 | 17 | 100.0% |   0.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| SSOP_PROCEDURE_CREATE | WRITE | 16 | 16 |  56.2% |  37.5% |   6.2% |   0.0% |   0.0% | 0 | WORK_PROCESS_TASK_START×1 |
| SSOP_PRODUCTION_GATE_CHECK | QUERY | 18 | 18 |  44.4% |  50.0% |   0.0% |   5.6% |   0.0% | 0 | PRODUCTION_PLAN_CREATE×1 |
| SUPPLIER_ACTIVE | QUERY | 17 | 17 |  35.3% |  29.4% |  11.8% |  23.5% |   0.0% | 0 | CUSTOMER_ACTIVE×2; SUPPLIER_SEARCH×1; PRODUCTION_DELIVERY_WARN_QUERY×1 |
| SUPPLIER_BY_CATEGORY | QUERY | 17 | 17 |  35.3% |  41.2% |  11.8% |  11.8% |   0.0% | 0 | SUPPLIER_SEARCH×3; CONTEXT_CONTINUE×1 |
| SUPPLIER_CAN_ORDER_CHECK | QUERY | 12 | 12 |  25.0% |  58.3% |   8.3% |   8.3% |   0.0% | 0 | CUSTOMER_SEARCH×1; RD_REQUEST_CREATE×1 |
| SUPPLIER_DELIVERY_ETA | QUERY | 17 | 17 |  23.5% |  58.8% |   5.9% |  11.8% |   0.0% | 0 | REPORT_INVENTORY×1; ORDER_STATUS×1; MATERIAL_TODAY_RECEIVING_QUERY×1 |
| SUPPLIER_EVALUATE | QUERY | 14 | 14 |  14.3% |  57.1% |   0.0% |  28.6% |   0.0% | 0 | RETURN_ORDER_CREATE×1; REPORT_DASHBOARD_OVERVIEW×1; QUALITY_DISPOSITION_EVALUATE×1 |
| SUPPLIER_LIST | QUERY | 15 | 15 |  53.3% |  40.0% |   0.0% |   0.0% |   6.7% | 0 | ∅(判无)×1 |
| SUPPLIER_QUALIFICATION_CREATE | WRITE | 19 | 19 |  57.9% |  36.8% |   0.0% |   5.3% |   0.0% | 0 | SUPPLIER_QUALIFICATION_QUERY_EXPIRING×1 |
| SUPPLIER_QUALIFICATION_LOOKUP | QUERY | 30 | 30 |  83.3% |  16.7% |   0.0% |   0.0% |   0.0% | 0 | - |
| SUPPLIER_QUALIFICATION_QUERY_EXPIRING | QUERY | 20 | 20 |  35.0% |  55.0% |   5.0% |   0.0% |   5.0% | 0 | ∅(判无)×1; SUPPLIER_QUALIFICATION_CREATE×1 |
| SUPPLIER_RANKING | ANALYSIS | 16 | 16 |  81.2% |  18.8% |   0.0% |   0.0% |   0.0% | 0 | - |
| SUPPLIER_SEARCH | QUERY | 9 | 9 |  66.7% |  33.3% |   0.0% |   0.0% |   0.0% | 0 | - |
| TRACE_BATCH | QUERY | 2 | 4 |   0.0% | 100.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| TRACE_FULL | QUERY | 2 | 2 | 100.0% |   0.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| TRACE_PUBLIC | QUERY | 11 | 11 |  54.5% |  36.4% |   0.0% |   0.0% |   9.1% | 0 | ∅(判无)×1 |
| TRANSFER_APPROVE | QUERY | 14 | 15 |  42.9% |  50.0% |   0.0% |   7.1% |   0.0% | 0 | OPPORTUNITY_TRANSITION×1 |
| TRANSFER_CREATE | WRITE | 16 | 16 |  31.2% |  43.8% |   0.0% |  25.0% |   0.0% | 0 | SALES_NEED_CREATE×1; MATERIAL_FIFO_RECOMMEND×1; MATERIAL_LOW_STOCK_ALERT×1 |
| WAGE_COST_SUMMARY | ANALYSIS | 19 | 19 |  84.2% |  10.5% |   5.3% |   0.0% |   0.0% | 0 | COST_QUERY×1 |
| WAGE_POLICY_QUERY | QUERY | 10 | 10 |  70.0% |  30.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| WECHAT_CREATE | WRITE | 16 | 16 |  31.2% |  68.8% |   0.0% |   0.0% |   0.0% | 0 | - |
| WECHAT_RECENT_QUERY | QUERY | 20 | 20 |  80.0% |  20.0% |   0.0% |   0.0% |   0.0% | 0 | - |
| WORK_PROCESS_CONFIG_UPDATE | WRITE | 17 | 17 |  82.4% |  17.6% |   0.0% |   0.0% |   0.0% | 0 | - |
| WORK_PROCESS_TASK_ASSIGN | QUERY | 8 | 16 |  75.0% |  12.5% |   0.0% |  12.5% |   0.0% | 0 | WORK_PROCESS_TASK_SPAWN×1 |
| WORK_PROCESS_TASK_COMPLETE | WRITE | 8 | 15 |  50.0% |  12.5% |   0.0% |  37.5% |   0.0% | 0 | ALERT_BY_EQUIPMENT×1; FACTORY_MR_CLOSE×1; QUALITY_DISPOSITION_EXECUTE×1 |
| WORK_PROCESS_TASK_SPAWN | QUERY | 16 | 16 |  43.8% |  37.5% |   6.2% |   0.0% |  12.5% | 0 | ∅(判无)×2; PRODUCT_CREATE_FLYWHEEL×1 |
| WORK_PROCESS_TASK_START | WRITE | 9 | 17 |  22.2% |  77.8% |   0.0% |   0.0% |   0.0% | 0 | - |
| CLARIFY | CLARIFY | 44 | 44 |   0.0% |   0.0% |  25.0% |   0.0% |   0.0% | 33 | CUSTOMER_BY_TYPE×1; FACTORY_NOTIFICATION_CONFIG×1; FACTORY_MR_GENERATE×1 |
| NONE | NONE | 263 | 263 |   0.0% |   0.0% |  13.3% |   0.0% |   0.0% | 228 | OUT_OF_DOMAIN×16; ORDER_STATUS×1; RETURN_ORDER_CREATE×1 |

『其它』= 对·判无 + 对·问了 + 安全·问了 + 失败 (主要出现在 NONE/CLARIFY 行)。

## 5. 最差 25 个意图 (UNSEEN; 按 错·自动+错·问了+判无 的条数降序, 同条数看比例; 不含 NONE/CLARIFY 两个伪意图)

**1. PERIOD_CONFIRM_CLOSE** (WRITE): UNSEEN n=14 | 失败 9 (64%) = 误执行 0 + 错·问了 7 + 判无 2 | 直接办对 2 / 按钮办对 3
- 混淆去向: APPROVAL_ACTION_EXECUTE×2; ∅(判无)×2; PERIOD_REOPEN×1; CLOCK_OUT×1
- 例 [implicit/错·问了] 「该结的都结了 把期间定死吧 别再让动了」 → 判成 PERIOD_REOPEN (按钮 ['PERIOD_REOPEN', 'PERIOD_REQUEST_CLOSE', 'FOOD_SAMPLE_DISPOSE'])
- 例 [implicit/错·问了] 「别耗着了 直接收盘吧 今天必须关掉」 → 判成 CLOCK_OUT (按钮 ['CLOCK_OUT', 'FACTORY_MR_CLOSE'])

**2. PROCESS_TASK_QUERY** (QUERY): UNSEEN n=21 | 失败 9 (43%) = 误执行 1 + 错·问了 8 + 判无 0 | 直接办对 2 / 按钮办对 10
- 混淆去向: PROCESS_TASK_SUMMARY×2; PRODUCTION_PROGRESS_DASHBOARD×2; PROCESS_TASK_ANALYSIS×2; WORK_PROCESS_TASK_ASSIGN×1
- 例 [trap/错·问了] 「主任让我问，气调(分切装盒)到底派给谁了？」 → 判成 WORK_PROCESS_TASK_ASSIGN (按钮 ['WORK_PROCESS_TASK_ASSIGN', 'PROCESS_TASK_SUMMARY', 'TRACE_BATCH'])
- 例 [context/错·问了] 「那批啥时候能完事？」 → 判成 PROCESS_TASK_SUMMARY (按钮 ['PROCESS_TASK_SUMMARY', 'PROCESS_TASK_ANALYSIS', 'PRODUCTION_PROGRESS_DASHBOARD'])

**3. SHORTAGE_ANALYSIS** (ANALYSIS): UNSEEN n=26 | 失败 8 (31%) = 误执行 0 + 错·问了 8 + 判无 0 | 直接办对 7 / 按钮办对 11
- 混淆去向: PRODUCTION_DEMAND_QUERY×3; MATERIAL_LOW_STOCK_ALERT×2; MATERIAL_BATCH_RESERVE×1; WORK_PROCESS_CONFIG_UPDATE×1
- 例 [trap/错·问了] 「五香粉缺货了，生产线要停，赶紧看看怎么补料。」 → 判成 MATERIAL_LOW_STOCK_ALERT (按钮 ['MATERIAL_LOW_STOCK_ALERT', 'REQUISITION_CREATE', 'FACTORY_MR_GENERATE'])
- 例 [context/错·问了] 「不对，是膝软骨，帮我看看缺不缺」 → 判成 MATERIAL_LOW_STOCK_ALERT (按钮 ['MATERIAL_LOW_STOCK_ALERT', 'MATERIAL_BATCH_QUERY'])

**4. SCALE_DEVICE_DETAIL** (QUERY): UNSEEN n=15 | 失败 7 (47%) = 误执行 0 + 错·问了 7 + 判无 0 | 直接办对 1 / 按钮办对 7
- 混淆去向: ABACA_WEIGHT_SUMMARY×2; SCALE_TROUBLESHOOT×1; SMART_INDICATOR_QUERY×1; SCALE_CALIBRATE×1
- 例 [implicit/错·问了] 「SCALE-002屏幕上那个红色感叹号是啥意思」 → 判成 SCALE_TROUBLESHOOT (按钮 ['SCALE_TROUBLESHOOT', 'ALERT_BY_EQUIPMENT', 'ALERT_ACTIVE'])
- 例 [implicit/错·问了] 「帮我看看SCALE-005现在准不准」 → 判成 SMART_INDICATOR_QUERY (按钮 ['SMART_INDICATOR_QUERY', 'QUALITY_STATS', 'SCALE_TROUBLESHOOT'])

**5. QUALITY_CHECK_SUMMARY** (ANALYSIS): UNSEEN n=18 | 失败 7 (39%) = 误执行 3 + 错·问了 4 + 判无 0 | 直接办对 0 / 按钮办对 11
- 混淆去向: QUALITY_STATS×5; BATCH_FULL_TRACE×1; RELEASE_DECISION×1
- 例 [context/错·问了] 「不对 我是要看你说的那个合规到底是哪批的 再给我说下那批的质检情况」 → 判成 BATCH_FULL_TRACE (按钮 ['BATCH_FULL_TRACE', 'ADDITIVE_COMPLIANCE_QUALITY', 'TRACE_BATCH'])
- 例 [context/错·问了] 「算了 先不登记了 我就想知道这批到现在质检汇总啥样 通过率多少」 → 判成 QUALITY_STATS (按钮 ['QUALITY_STATS', 'REPORT_DASHBOARD_OVERVIEW'])

**6. DAILY_CUSTOMER_FOLLOWUP** (NO_TOOL): UNSEEN n=19 | 失败 7 (37%) = 误执行 1 + 错·问了 4 + 判无 2 | 直接办对 2 / 按钮办对 10
- 混淆去向: ∅(判无)×2; ORDER_TODAY×1; ORDER_STATUS×1; REMINDER_LIST_MINE×1
- 例 [trap/判无] 「别给我按月汇总收入了，先看看叮咚这个月的跟进任务。」 → 判成 ∅
- 例 [context/判无] 「算了 先别查了 看看今天有啥客户要跟进」 → 判成 ∅

**7. INDICATOR_QUERY** (QUERY): UNSEEN n=13 | 失败 6 (46%) = 误执行 3 + 错·问了 3 + 判无 0 | 直接办对 0 / 按钮办对 5
- 混淆去向: SMART_INDICATOR_QUERY×2; QUALITY_STATS×2; REPORT_QUALITY×2
- 例 [implicit/错·问了] 「老板天天问客单价，你这边能不能直接看到现在多少？」 → 判成 SMART_INDICATOR_QUERY (按钮 ['SMART_INDICATOR_QUERY', 'REPORT_DASHBOARD_OVERVIEW', 'CUSTOMER_STATS'])
- 例 [implicit/错·问了] 「食品安全通过率最近有没有掉红？」 → 判成 QUALITY_STATS (按钮 ['QUALITY_STATS', 'REPORT_TRENDS', 'INDICATOR_ALERT'])

**8. MATERIAL_DISPOSAL_RECOMMENDATION** (QUERY): UNSEEN n=15 | 失败 6 (40%) = 误执行 0 + 错·问了 5 + 判无 1 | 直接办对 1 / 按钮办对 8
- 混淆去向: QUALITY_DISPOSITION_EVALUATE×3; ∅(判无)×1; MATERIAL_EXPIRING_ALERT×1; QUALITY_DISPOSITION_EXECUTE×1
- 例 [implicit/错·问了] 「昨天到的菲力看着不太对劲，保质期好像过了，你给拿个主意。」 → 判成 QUALITY_DISPOSITION_EVALUATE (按钮 ['QUALITY_DISPOSITION_EVALUATE', 'RETURN_ORDER_APPROVE', 'MATERIAL_FIFO_RECOMMEND'])
- 例 [implicit/判无] 「汉堡胚再不用就过期了，看看怎么处理减少损失。」 → 判成 ∅

**9. FACTORY_MR_CLOSE** (QUERY): UNSEEN n=17 | 失败 6 (35%) = 误执行 0 + 错·问了 3 + 判无 3 | 直接办对 7 / 按钮办对 4
- 混淆去向: ∅(判无)×3; OPPORTUNITY_TRANSITION×1; ORDER_STATUS×1; PURCHASE_FINANCE_APPROVE×1
- 例 [context/判无] 「那批先关了吧，不弄了」 → 判成 ∅
- 例 [implicit/判无] 「那个单子不要再备料了，叮咚那个烤肉片的，直接结掉吧」 → 判成 ∅

**10. SUPPLIER_ACTIVE** (QUERY): UNSEEN n=17 | 失败 6 (35%) = 误执行 2 + 错·问了 4 + 判无 0 | 直接办对 6 / 按钮办对 5
- 混淆去向: CUSTOMER_ACTIVE×2; SUPPLIER_SEARCH×1; PRODUCTION_DELIVERY_WARN_QUERY×1; CUSTOMER_SEARCH×1
- 例 [context/错·自动] 「那华润呢？」 → 判成 SUPPLIER_SEARCH
- 例 [context/错·问了] 「他那个还正常吧？」 → 判成 PRODUCTION_DELIVERY_WARN_QUERY (按钮 ['PRODUCTION_DELIVERY_WARN_QUERY', 'SHIPMENT_QUERY', 'SUPPLIER_DELIVERY_ETA'])

**11. INVENTORY_FREEZE** (WRITE): UNSEEN n=20 | 失败 6 (30%) = 误执行 0 + 错·问了 4 + 判无 2 | 直接办对 5 / 按钮办对 8
- 混淆去向: ∅(判无)×2; RELEASE_DECISION×1; QUALITY_DISPOSITION_EXECUTE×1; SHIPMENT_CREATE×1
- 例 [implicit/判无] 「哎这批把子肉先给我摁住，万一有问题呢」 → 判成 ∅
- 例 [implicit/错·问了] 「上脑片那批我总觉得不对劲，先别让它出库了」 → 判成 RELEASE_DECISION (按钮 ['RELEASE_DECISION', 'QUALITY_DISPOSITION_EXECUTE', 'MATERIAL_BATCH_USE'])

**12. MATERIAL_BATCH_RELEASE** (QUERY): UNSEEN n=22 | 失败 6 (27%) = 误执行 3 + 错·问了 2 + 判无 1 | 直接办对 10 / 按钮办对 6
- 混淆去向: STOCK_ALERT×1; ∅(判无)×1; QUALITY_DISPOSITION_EXECUTE×1; MATERIAL_BATCH_RESERVE×1
- 例 [implicit/错·自动] 「刚才那个预留的鲜露我不用了 你把它放出来吧」 → 判成 STOCK_ALERT
- 例 [implicit/判无] 「这批后胸肉先别锁了 车间等着用呢」 → 判成 ∅

**13. PRODUCTION_DEMAND_ANALYSIS** (NO_TOOL): UNSEEN n=8 | 失败 5 (62%) = 误执行 1 + 错·问了 4 + 判无 0 | 直接办对 0 / 按钮办对 3
- 混淆去向: PRODUCTION_PLAN_CREATE×2; SHORTAGE_ANALYSIS×1; PROCESS_TASK_ANALYSIS×1; PRODUCTION_DEMAND_QUERY×1
- 例 [trap/错·问了] 「今天产能不看了，赶紧把缺的订单分析一下。」 → 判成 SHORTAGE_ANALYSIS (按钮 ['SHORTAGE_ANALYSIS', 'PRODUCTION_DEMAND_QUERY', 'PROCESSING_CAPACITY_TODAY'])
- 例 [implicit/错·问了] 「今儿个生产线该做啥？单子还差不少呢。」 → 判成 PRODUCTION_PLAN_CREATE (按钮 ['PRODUCTION_PLAN_CREATE', 'PRODUCTION_DEMAND_QUERY', 'PROCESS_TASK_QUERY'])

**14. MATERIAL_UPDATE** (WRITE): UNSEEN n=17 | 失败 5 (29%) = 误执行 3 + 错·问了 2 + 判无 0 | 直接办对 3 / 按钮办对 9
- 混淆去向: MATERIAL_ADJUST_QUANTITY×2; BATCH_UPDATE×2; RELEASE_DECISION×1
- 例 [context/错·自动] 「把最早那批改成100kg，其他不动」 → 判成 MATERIAL_ADJUST_QUANTITY
- 例 [implicit/错·自动] 「臀肉批次20250410的生产日期填成4月11号了，实际是4月10号，赶紧改一下」 → 判成 BATCH_UPDATE

**15. HACCP_STATUS_QUERY** (QUERY): UNSEEN n=20 | 失败 5 (25%) = 误执行 1 + 错·问了 4 + 判无 0 | 直接办对 6 / 按钮办对 9
- 混淆去向: HACCP_REVIEW×3; COLD_CHAIN_ALERT_ACTIVE×1; RELEASE_DECISION×1
- 例 [context/错·问了] 「也查下整体通过没」 → 判成 HACCP_REVIEW (按钮 ['HACCP_REVIEW', 'QUALITY_CHECK_SUMMARY', 'RD_SAMPLE_APPROVE'])
- 例 [context/错·自动] 「那HACCP状态呢」 → 判成 COLD_CHAIN_ALERT_ACTIVE

**16. MATERIAL_BATCH_CREATE** (WRITE): UNSEEN n=9 | 失败 4 (44%) = 误执行 1 + 错·问了 2 + 判无 1 | 直接办对 1 / 按钮办对 4
- 混淆去向: ∅(判无)×1; MATERIAL_UPDATE×1; MATERIAL_TODAY_RECEIVING_QUERY×1; MATERIAL_BATCH_WIP_QUERY×1
- 例 [trap/判无] 「华润送的花椒粉到了，先按入库，别做出库。」 → 判成 ∅
- 例 [context/错·自动] 「等等，供应商改成雨润，其他不变。」 → 判成 MATERIAL_UPDATE

**17. PROCESS_TASK_SUMMARY** (ANALYSIS): UNSEEN n=9 | 失败 4 (44%) = 误执行 1 + 错·问了 3 + 判无 0 | 直接办对 0 / 按钮办对 5
- 混淆去向: PRODUCTION_PROGRESS_DASHBOARD×3; REMINDER_LIST_MINE×1
- 例 [implicit/错·问了] 「调度那边催我报数 我这儿还没汇总呢」 → 判成 REMINDER_LIST_MINE (按钮 ['REMINDER_LIST_MINE', 'REPORT_DASHBOARD_OVERVIEW', 'SHIPMENT_STATS'])
- 例 [plain/错·自动] 「给我看看今天工序都干到哪了」 → 判成 PRODUCTION_PROGRESS_DASHBOARD

**18. EFFICIENCY_COMPARISON** (ANALYSIS): UNSEEN n=14 | 失败 4 (29%) = 误执行 0 + 错·问了 4 + 判无 0 | 直接办对 4 / 按钮办对 6
- 混淆去向: PROCESS_TASK_ANALYSIS×2; PROCESS_TASK_SUMMARY×1; SHIPMENT_STATS×1
- 例 [implicit/错·问了] 「老王和小李都做装盒，老王一天400，小李到下班才300，是不是该管管？」 → 判成 PROCESS_TASK_ANALYSIS (按钮 ['PROCESS_TASK_ANALYSIS', 'SMART_INDICATOR_QUERY', 'WAGE_POLICY_QUERY'])
- 例 [implicit/错·问了] 「上个月卤制一天出8锅，这个月咋感觉连6锅都费劲。」 → 判成 PROCESS_TASK_ANALYSIS (按钮 ['PROCESS_TASK_ANALYSIS', 'REPORT_EFFICIENCY', 'COST_QUERY'])

**19. OPPORTUNITY_FUNNEL_STATS** (ANALYSIS): UNSEEN n=14 | 失败 4 (29%) = 误执行 3 + 错·问了 1 + 判无 0 | 直接办对 9 / 按钮办对 1
- 混淆去向: OPPORTUNITY_STAGE_ALERT×2; CONTEXT_CONTINUE×1; CASHFLOW_STATEMENT_QUERY×1
- 例 [context/错·问了] 「那昨天呢？昨天那个阶段数跟今天比变没变？」 → 判成 CONTEXT_CONTINUE (按钮 ['CONTEXT_CONTINUE', 'REPORT_TRENDS', 'INDICATOR_COMPARISON'])
- 例 [implicit/错·自动] 「最近商机都卡在哪一步了？每个阶段多少单子，总共涉及多少钱，加权后能有多少？」 → 判成 OPPORTUNITY_STAGE_ALERT

**20. SUPPLIER_EVALUATE** (QUERY): UNSEEN n=14 | 失败 4 (29%) = 误执行 0 + 错·问了 4 + 判无 0 | 直接办对 2 / 按钮办对 8
- 混淆去向: RETURN_ORDER_CREATE×1; REPORT_DASHBOARD_OVERVIEW×1; QUALITY_DISPOSITION_EVALUATE×1; OUT_OF_DOMAIN×1
- 例 [implicit/错·问了] 「鲜丰蔬菜这几次送的菜都不新鲜 要不要换一家」 → 判成 RETURN_ORDER_CREATE (按钮 ['RETURN_ORDER_CREATE', 'SUPPLIER_RANKING', 'PRODUCT_UPDATE'])
- 例 [plain/错·问了] 「看看鲜丰蔬菜这季度表现咋样」 → 判成 REPORT_DASHBOARD_OVERVIEW (按钮 ['REPORT_DASHBOARD_OVERVIEW', 'CUSTOMER_STATS', 'INDICATOR_QUERY'])

**21. CUSTOMER_PRIORITY_QUERY** (QUERY): UNSEEN n=15 | 失败 4 (27%) = 误执行 1 + 错·问了 0 + 判无 3 | 直接办对 6 / 按钮办对 5
- 混淆去向: ∅(判无)×3; OPPORTUNITY_FUNNEL_STATS×1
- 例 [implicit/错·自动] 「销售那边催着要客户清单，要按重要程度和商机进度排好序的。」 → 判成 OPPORTUNITY_FUNNEL_STATS
- 例 [plain/判无] 「给我把客户按重要程度和商机阶段排个序。」 → 判成 ∅

**22. MATERIAL_MARK_ABACA** (WRITE): UNSEEN n=15 | 失败 4 (27%) = 误执行 0 + 错·问了 4 + 判无 0 | 直接办对 10 / 按钮办对 1
- 混淆去向: MATERIAL_BATCH_CREATE×2; RULE_CONFIG×1; MATERIAL_ADJUST_QUANTITY×1
- 例 [implicit/错·问了] 「蒜蓉辣酱这种一箱一个重量的 采购根本不数箱子 到货就得过秤」 → 判成 RULE_CONFIG (按钮 ['RULE_CONFIG', 'MATERIAL_ADJUST_QUANTITY', 'SHIPMENT_STATS'])
- 例 [implicit/错·问了] 「蚝油每箱都差个半斤八两的 别让采购填箱数了」 → 判成 MATERIAL_ADJUST_QUANTITY (按钮 ['MATERIAL_ADJUST_QUANTITY', 'BATCH_CONSUMPTION_ADJUST', 'PURCHASE_ORDER_APPROVE'])

**23. MATERIAL_BATCH_RESERVE** (QUERY): UNSEEN n=16 | 失败 4 (25%) = 误执行 0 + 错·问了 2 + 判无 2 | 直接办对 8 / 按钮办对 4
- 混淆去向: ∅(判无)×2; ORDER_LIST×1; MATERIAL_FIFO_RECOMMEND×1
- 例 [context/判无] 「算了 不释放了 还是给腌黄瓜计划留着」 → 判成 ∅
- 例 [implicit/错·问了] 「下午要上线轻卤门腔 牛前腱别让别的单子领走」 → 判成 ORDER_LIST (按钮 ['ORDER_LIST', 'MATERIAL_FIFO_RECOMMEND', 'MATERIAL_BATCH_CREATE'])

**24. SALES_NEED_CREATE** (WRITE): UNSEEN n=16 | 失败 4 (25%) = 误执行 0 + 错·问了 3 + 判无 1 | 直接办对 2 / 按钮办对 10
- 混淆去向: SHIPMENT_CREATE×1; ∅(判无)×1; RD_SAMPLE_APPROVE×1; SALES_NEED_CONVERT×1
- 例 [implicit/错·问了] 「叮咚那边催了好几次了，说轻食鸭胸下礼拜一定要到货」 → 判成 SHIPMENT_CREATE (按钮 ['SHIPMENT_CREATE', 'ORDER_STATUS', 'SHIPMENT_CONFIRM_CREATE'])
- 例 [implicit/判无] 「昨天跟叮咚吃饭，他们讲对去骨卤猪蹄挺感兴趣，先别急着发货」 → 判成 ∅

**25. TRANSFER_CREATE** (WRITE): UNSEEN n=16 | 失败 4 (25%) = 误执行 0 + 错·问了 4 + 判无 0 | 直接办对 5 / 按钮办对 7
- 混淆去向: SALES_NEED_CREATE×1; MATERIAL_FIFO_RECOMMEND×1; MATERIAL_LOW_STOCK_ALERT×1; MATERIAL_BATCH_USE×1
- 例 [context/错·问了] 「再来二十箱」 → 判成 SALES_NEED_CREATE (按钮 ['SALES_NEED_CREATE', 'RECEIVE_WITH_LIMIT', 'MATERIAL_BATCH_CREATE'])
- 例 [implicit/错·问了] 「眼肉都堆在一库，二库那边急着要。」 → 判成 MATERIAL_FIFO_RECOMMEND (按钮 ['MATERIAL_FIFO_RECOMMEND', 'REPORT_INVENTORY', 'ORDER_STATUS'])

## 6. 覆盖: 235 个活跃工厂意图在被测集合里有没有题

- 全部考题 (3519): 覆盖 219 / 235; **零题 16 个**。
- UNSEEN (头条集合): 覆盖 219 / 235; 零题 16 个。
- 考题里出现、但不在 235 清单里的意图码: [] (应为空)

全部考题里零题的意图 (这些**完全没量到**, 头条数字对它们沉默), 并按『为什么没题』归因 (归因来自 `prod_active_factory_codes.txt` = 出题计划的 223 码清单 与 `gen_understanding_suite.SKIP`):

- CONTEXT_CONTINUE (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- FACTORY_CONFIG_AGENT (WRITE): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- FACTORY_NOTIFICATION_CONFIG (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- OUT_OF_DOMAIN (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- PAGINATION_PREVIOUS (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- PRODUCT_TYPE_QUERY (QUERY): 在计划里, 但出的题经验题人投票后都改判成别的意图/NONE/CLARIFY, 没有留下以它为期望的题
- RULE_CONFIG (QUERY): 出题时故意跳过 (SKIP)
- SCALE_CALIBRATE (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- SCALE_TROUBLESHOOT (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- SCHEDULING_SET_AUTO (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- SCHEDULING_SET_DISABLED (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- SCHEDULING_SET_MANUAL (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- SMART_INDICATOR_QUERY (NO_TOOL): 在计划里, 但出的题经验题人投票后都改判成别的意图/NONE/CLARIFY, 没有留下以它为期望的题
- STOCK_ALERT (WRITE): 在计划里, 但出的题经验题人投票后都改判成别的意图/NONE/CLARIFY, 没有留下以它为期望的题
- SYSTEM_HELP (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)
- UNSUPPORTED_CAPABILITY (QUERY): 不在出题计划的 223 码清单里 (235 清单比计划多出的 12 个: 系统/元意图或较新增补)

UNSEEN 里零题但 ALL 里有题的意图 (只量到 r2 见过的说法):

- (无)

更严的口径: 只数**非陷阱题** (plain/implicit/context) 的覆盖, UNSEEN 里 216 / 235 有题; 只有陷阱题或完全没题的 19 个: BATCH_FULL_TRACE(2), CONTEXT_CONTINUE(0), FACTORY_CONFIG_AGENT(0), FACTORY_NOTIFICATION_CONFIG(0), LINEAGE_QUERY(1), OUT_OF_DOMAIN(0), PAGINATION_PREVIOUS(0), PRINT_TEMPLATE_CREATE_FROM_AI(1), PRODUCT_TYPE_QUERY(0), RULE_CONFIG(0), SCALE_CALIBRATE(0), SCALE_TROUBLESHOOT(0), SCHEDULING_SET_AUTO(0), SCHEDULING_SET_DISABLED(0), SCHEDULING_SET_MANUAL(0), SMART_INDICATOR_QUERY(0), STOCK_ALERT(0), SYSTEM_HELP(0), UNSUPPORTED_CAPABILITY(0)

⚠ 陷阱题是『表面像 A 实际不是 A』, 期望码常是别的意图/NONE; 它的 `source` 是 A 但 `expect` 不是 A —— 本报告一律按 `expect` 计覆盖, 所以 PRODUCT_TYPE_QUERY / SMART_INDICATOR_QUERY / STOCK_ALERT 虽然出过 3 道 source 题, 覆盖仍是 0。

UNSEEN 题数 1~4 的意图 (读数噪声大): 11 个: ADDITIVE_COMPLIANCE_QUALITY(4), ATTENDANCE_STATS(4), BATCH_FULL_TRACE(2), LINEAGE_QUERY(1), PRINT_TEMPLATE_CREATE_FROM_AI(1), PRODUCTION_PROGRESS_DASHBOARD(2), QUALITY_CHECK_CREATE(2), QUALITY_CHIEF_WORKDESK(1), QUALITY_DISPOSITION_EXECUTE(4), TRACE_BATCH(2), TRACE_FULL(2)

## 7. 延迟与 token

| 路径 | n | 客户端 ms p50 | p90 | p99 | 服务端 totalMs p50 | p90 |
|---|---|---|---|---|---|---|
| 全部 | 3519 | 737 | 1212 | 1718 | 735 | 1206 |
| 无历史·走 r2 重排 | 2905 | 703 | 919 | 1303 | 700 | 915 |
| 带历史·LLM 粗选(跳过重排) | 610 | 1212 | 1563 | 1963 | 1209 | 1559 |
| 精确表命中 | 1 | 2 | 2 | 2 | 0 | 0 |

- 响应的 `timingMs` 里**没有 token 字段** (键: decisionClarify, decisionClarifyP, decisionLayer, decisionMs, decisionNone, decisionNoneP, historyTurns, registryHit, registryMs, rerankFallback, rerankMs, rerankOk, rerankSkippedHistory, retrievalMs, shortlistOk, totalMs), 所以实测 token 读不到。
- 能数的是调用次数: 决策 LLM (`decisionMs`) 3515 次; 带历史时另有 LLM 粗选 (`shortlistOk`) 613 次。按任务给的约 1.5k token/次 估: 决策 ≈ 5.3M token, 粗选另计 (**估算, 非实测**)。

## 8. 自检 (摆出来, 不只是声明)

### 8.1 被参数化的那一维真的在变

- 考题 distinct id 3519 / distinct 原句 3476 / distinct expect 221 (含 NONE、CLARIFY; 意图码 219)。
- UNSEEN 里 distinct expect 221; distinct 原句 3276。
- 回放输出里 distinct bestMatch 230 (+判无 216 条) —— 仪器不是恒返回同一个东西。
- 回放里各 cell 都有读数: 对·自动 1456, 对·按钮 1363, 对·判无 159, 对·问了 31, 安全·问了 71, 错·问了 259, 错·自动 123, 判无 54, 失败 3。
- 带历史的题 distinct history 首句数: 612 (不是同一段历史重放)。

### 8.2 旧 514 题重打分 vs 2026-10-05 r2 基线 (86.0% 办对 / 1.6% 误执行, 验收半 257 题)

- **仪器核对**: 把 10-05 同日回放文件 `ueval_r2_sameday_20261005_out.jsonl` 用本脚本的打分重打, 验收半 n=257: 办对 86.0% / 误执行 1.6% (10-05 记录 86.0% / 1.6%) —— 一致, 打分尺子可信。
- **本次回放**的旧 514·验收半 n=257: 办对 86.4% / 误执行 2.3% (直接 42.8% / 按钮 35.8%), 与基线差 办对 +0.4 点 / 误执行 +0.7 点。
- 旧 514 全部 n=514: 办对 85.8% / 误执行 2.5%。
- 同一批 514 题两次回放 (10-05 vs 本次) 逐条对比: bestMatch 相同 490/514 (95.3%), 判分格相同 486/514 (94.6%) —— 这是决策 LLM 的重放抖动量级。

### 8.3 阳性对照: 切分尺子能不能看出『训练过』

- r2 训练过的 192 题: 直接办对 43.8% / 办对 89.1% / 误执行 1.0% (n=192)
- 同题型、同意图范围的旧 514 验收半 (r2 没见过) : 直接办对 48.0% / 办对 89.1% / 误执行 1.1% (n=175)
- 差 -0.1 点办对。⚠ **端到端层面看不出训练效应** (差距在噪声内: n≈190 时 95% 区间约 ±5 点)。这不是切分尺子瞎了 —— 『哪些题是训练过的』的识别是对的 (重建的 192 题在 3519 题里被原句匹配全部抓到, 见 §1 表, 192/192); 推测原因 (未单独验证): r2 只负责向量→前 K 候选的排序 (orchestrator `_rerank_candidates`), 最终选哪个、要不要确认由决策 LLM 定, 重排层的训练效应可能被决策层抹平; 要证实得在重排层单独量见过/没见过题的 @K, 本次是端到端测量没做。离线重排层 @K 的验收读数在 `suite_rr_eval.log` (r2 验收半 @1 70.3% / @3 85.7% / @5 92.0%)。
  (注: 192 题全是无历史的 plain/implicit/trap, 走 r2 重排; 验收半对照取同题型同意图范围。**所以 ALL 与 UNSEEN 只差 0.2 点不是巧合, 也不是切分没做 —— 是训练效应本来就小。**)

### 8.4 『没见过』的切分有没有造成数字偏差

- 新增 3005 题里被判 seen (原句或归一化撞训练正样本) 的有 7 条; 剩下 2998 条是 UNSEEN。 seen 部分办对 85.7% vs UNSEEN 85.5%。

## 9. 文件

- `full_replay_r2_20261006.md` 本报告; `full_replay_r2_20261006_in.jsonl` 回放输入; `full_replay_r2_20261006_out.jsonl` 回放原始输出; `full_replay_r2_20261006_scored.jsonl` 逐题判分 + seen 标记; `full_replay_r2_20261006_per_intent.csv` 逐意图 (UNSEEN 与 ALL 两套); `full_replay_r2_20261006_r2seen.json` r2 训练正样本重建。
- 脚本: `sim/r2_seen_set.py` (重建 r2 训练集, 带自检) / `sim/full_replay_report.py` (本报告) / 复用 `ueval.py build`+`cell`、`prod_replay_server.py`。
