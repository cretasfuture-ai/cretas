# 独立验收集（HOLDOUT）

批次 `INTENT-DISTILL-20261111`。用来判断「上下文统一 / 澄清修复 / 本地蒸馏模型直出」是否达标。题目**不进仓库**。

## 位置与封存

- 目录（本机，不进仓库）：`C:\Users\Steve\Documents\Cretas意图独立验收-20261111\sealed\`
- `MANIFEST.json` 的 SHA256：`f8ef128425de187d38e2fd767850eea3f2b65085df2d094d5075b5110caa641c`（取数前先核对；对不上说明题目被改过，作废）
- 内容：`A_multiturn.json`（端到端多轮，118 段 / 285 轮，只用库存与订单只读 Tool）、`B_decision.jsonl`（决策级单轮带状态，708 条，含 215 条写意图/对照句）、`fingerprints.jsonl`（题目规范化哈希 + 语义族，不含原文）、`catalog_readwrite.json`（意图→Tool→读写属性，含来源文件与行号）、`SCHEMA.md`（字段说明）。
- 人工抽查稿：`C:\Users\Steve\Documents\Cretas意图独立验收-20261111\抽查-20段.md`（A 套随机抽 20 段，种子见 MANIFEST；末尾附全部 ambiguous 题）。
- 字段格式兼容 `score_complete_read.py` 读取的 `dialogues[].turns[].{input,parameters{status,page,size},domain,basis}`；多出的字段（`dialogue_act`、`read_only`、`should_clarify` 等）见 `SCHEMA.md`。

## 使用规则

1. **只在里程碑验收时运行**。开发、调参、训练期间不得用它做回归。
2. **运行后只报分项汇总**（按对话动作 / 读写 / 语义族 / 难度的通过数与总数，A 套另报逐轮通过率）。**不得把逐题内容（原文、期望、模型输出）贴进任何训练数据、开发讨论或 PR 描述。**
3. **训练线只用指纹比对**：拿 `fingerprints.jsonl` 里的哈希去过滤训练/开发样本（算法见 MANIFEST 的 `fingerprint_hash`），不读题目原文。
4. **被打开的题目要登记并剔除**：任何人为排错打开了某道题，记下题号（`HA-xxx#轮` / `B-xxxx`），该题从下一版验收集剔除；被打开的题不再计入后续成绩。
5. **ambiguous 题单列**：带 `ambiguous: true` 的题出题人自己也拿不准，汇总时单独报，不与确定题混算；写意图（`read_only=false`）无论是否 ambiguous，只要被直出就算失败。
6. 真实结果（条数、ID）由评测时的只读数据现算，验收集不含数值答案。
7. 数据漂移：评测前后各取一次只读快照（沿用 `read_oracle.py` 的做法）。

## 已知未验证项

- 8 个已接通 Tool 的确切清单未核对，A 套把意图限定在库存与订单类（`MATERIAL_BATCH_QUERY`、`MATERIAL_EXPIRED_QUERY`、`MATERIAL_EXPIRING_ALERT`、`MATERIAL_LOW_STOCK_ALERT`、`ORDER_LIST`、`ORDER_STATUS`、`ORDER_TODAY`），个别轮目标落在未接通的意图上，评测时按接通清单过滤。
- 题中物料名与订单号（`XS2026…`）是合成的，未对 F006 数据核对。
- 目录里 `GATE_DIAGNOSE_BATCH`、`PAGINATION_PREVIOUS` 的 Tool 类没声明 AccessMode（按 fail-closed 即 WRITE），`COST_QUERY` 声明为 WRITE 但名称是查询；相关题已标 ambiguous。
