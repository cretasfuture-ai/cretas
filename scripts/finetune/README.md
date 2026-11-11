# 意图分类器增量微调

## 概述

### 审核后的飞轮数据入口

`export_training_data.py` 和 `pipeline.sh` 接受以下三个参数（必须同时提供）：

```bash
python scripts/finetune/export_training_data.py \
  --reviewed-flywheel-file /absolute/path/reviewed.jsonl \
  --flywheel-labels-file /absolute/path/current-model-info.json \
  --flywheel-holdout-file /absolute/path/holdout.jsonl \
  --output /absolute/path/new-training-data.jsonl
```

这条命令导出数据，不训练、不加载模型；`pipeline.sh` 仍会运行裁判/合成等原有步骤，
仅需导出时直接使用 exporter。不要为了接入数据而运行完整流水线或旧部署示例。
标签目录使用本次目标分类器 `/api/classifier/info` 的 `labels` 或 `label_mapping.json` 的
`label_to_id`。不要把教师概率写成审核结论，也不要猜测旧标签与现有标签的映射。

每条审核记录至少包含 `record_id`、`text`、`label`、`group_id`、`split: "train"`、
`review: {status: "approved", reviewer: "...", basis: "..."}`，以及
`provenance: {source_kind: "synthetic" | "factory", evidence_refs: ["..."]}`。
真实工厂记录还必须提供 `factory_id` 并经过现有授权/撤回/敏感内容过滤；所有来源仍经过
全厂保密词检查。审核字段是受控数据文件的审计记录，不是身份认证或安全授权。

`holdout.jsonl` 每行包含 `text`、可选 `group_id`。缺失/空的保留集、未知标签、缺审核依据、
评测来源冒充训练、重复审核原话均拒绝。已知同源改写须共用 `group_id`；规范化相同文本及
字符相似度≥0.85的近似句也拒绝。相似度不能识别所有语义改写，不能替代同源分组。
保留集还会从旧数据、ZPD、shadow、feedback 等全部导出来源中排除，防止旁路泄漏。
本轮已经用于评测的样本只保留为测试/裁决证据，不直接回灌。合格记录的审核来源随
`flywheel_evidence` 字段进入导出文件，微调加载器仍使用 `text`/`label`。

默认持久保护清单是 `data/evaluation_holdout.jsonl`，不传飞轮参数时也强制应用；文件缺失或为空则停止。
额外传入的 holdout 是追加保护。直接运行增量训练器时，新数据和旧数据回放也会排除默认保护集。
这份清单仅登记已知评测原话，不能证明历史模型没学过它们；当前样本与旧语料有重合，比较结果仅作描述。
导出会拒绝跨来源同一句话的矛盾标签，输出不能覆盖任何输入/保护清单，写入使用原子替换。

增量训练必须指定已有本地模型目录；`--label-mapping` 可选，但传入时必须与模型目录的
`label_mapping.json` 完全一致。训练保留整个分类头的ID，未在当前数据出现的类也不删掉，
不允许通过 `ignore_mismatched_sizes` 静默重建分类头。非通用 `LABEL_n` 的模型config映射也必须一致。
`/info` 标签数组仅用于审核入口的标签名校验，不提供训练类别ID；训练ID以目标模型sidecar为准。
新数据和旧数据回放不能是同路径/同内容文件；流水线对已合并语料不再重复传成旧数据。
训练前会拒绝矛盾标签并去除规范化相同原话，避免精确重复跨入随机训练/验证两侧。

本目录包含增量微调意图分类器的脚本和数据，用于解决 CLASSIFIER 层的混淆问题。

## 问题意图

| 混淆对 | 说明 |
|--------|------|
| PROCESSING_BATCH_LIST ↔ MATERIAL_BATCH_QUERY | "在产批次" vs "原料批次" |
| CLOCK_IN ↔ ATTENDANCE_TODAY | "打卡/签到" vs "今日出勤" |
| SHIPMENT_STATS ↔ MATERIAL_BATCH_QUERY | "发货统计" vs "原料查询" |

## 文件结构

```
scripts/finetune/
├── README.md                          # 本文档
├── generate_training_data.py          # 生成增量训练数据
├── incremental_finetune.py            # 增量微调脚本
├── data/
│   ├── incremental_training_data.jsonl  # 生成的训练数据 (JSON Lines)
│   └── incremental_training_data.csv    # 生成的训练数据 (CSV)
└── models/
    └── incremental/                   # 微调后的模型 (训练后生成)
```

## 防止灾难性遗忘的策略

| 策略 | 参数 | 说明 |
|------|------|------|
| 低学习率 | `--lr 5e-6` | 比初始训练低 10-20 倍 |
| 数据回放 | `--replay-ratio 0.5` | 混入 50% 旧数据 |
| 冻结早期层 | `--freeze-layers 8` | 只训练最后 4 层 |
| 少量 epoch | `--epochs 2` | 避免过拟合 |
| 小批次 | `--batch-size 8` | 稳定梯度更新 |
| 早停 | 内置 | 验证集无提升时停止 |

## 使用方法

### 1. 生成训练数据

```bash
cd scripts/finetune
python generate_training_data.py
```

输出:
- `data/incremental_training_data.jsonl` - JSON Lines 格式
- `data/incremental_training_data.csv` - CSV 格式

### 2. 执行增量微调

```bash
# 基本用法
python incremental_finetune.py \
    --new-data data/incremental_training_data.jsonl \
    --model-path models/chinese-roberta-wwm-ext-classifier/final \
    --output-path models/incremental

# 完整参数
python incremental_finetune.py \
    --new-data data/incremental_training_data.jsonl \
    --old-data data/original_training_data.jsonl \
    --model-path models/chinese-roberta-wwm-ext-classifier/final \
    --output-path models/incremental \
    --epochs 2 \
    --batch-size 8 \
    --lr 5e-6 \
    --replay-ratio 0.5 \
    --freeze-layers 8
```

### 3. 部署新模型

```bash
# 复制到服务器
scp -r models/incremental/final root@47.100.235.168:/www/wwwroot/python-services/models/chinese-roberta-wwm-ext-classifier/

# 重启 Python 服务
ssh root@47.100.235.168 "systemctl restart python-services"
```

## 训练数据格式

### JSON Lines (推荐)
```jsonl
{"text": "查看在产批次", "label": "PROCESSING_BATCH_LIST"}
{"text": "帮我打卡", "label": "CLOCK_IN"}
{"text": "发货统计", "label": "SHIPMENT_STATS"}
```

### CSV
```csv
user_input,intent_code
查看在产批次,PROCESSING_BATCH_LIST
帮我打卡,CLOCK_IN
发货统计,SHIPMENT_STATS
```

## 添加新训练样本

编辑 `generate_training_data.py` 中的 `TRAINING_SAMPLES` 字典:

```python
TRAINING_SAMPLES = {
    "YOUR_INTENT": [
        "样本1",
        "样本2",
        "变体表达...",
    ],
}
```

## 验证微调效果

```bash
cd tests/ai-intent
python comprehensive_test.py 500 12345
```

## 注意事项

1. **不要过度微调**: 2-3 个 epoch 足够，过多会导致过拟合
2. **保持数据平衡**: 各意图样本数量应大致相等
3. **保留旧数据**: 始终混入旧数据防止遗忘
4. **验证后再部署**: 先在测试环境验证效果
5. **备份原模型**: 部署前备份原有模型

## 环境依赖

```bash
pip install torch transformers scikit-learn
```

## 常见问题

### Q: 微调后其他意图准确率下降了怎么办?
A: 增加 `--replay-ratio` (如 0.7-0.8) 或减少 `--epochs`

### Q: 微调效果不明显怎么办?
A: 增加训练样本数量，或减少 `--freeze-layers` (如 6)

### Q: GPU 内存不足怎么办?
A: 减小 `--batch-size` (如 4) 或增加 `--freeze-layers`
## 266类工厂目录独立实验

`factory_experiment.py` 为离线实验入口，复用 `incremental_finetune.py`，支持显式验证集和保护测试集。输入为外部证据目录中的 `authored-families.json`（每标签依次为训练、验证、测试表达数组），以及上一轮本机基线及语料。先运行 `prepare --evidence <目录>` 冻结数据与哈希，再运行 `train`、`local`；不要覆盖已存在的实验目录。新266类头只按相同标签名转移旧分类权重，新类另行初始化，原模型目录不写入。

`factory_cloud_shadow.py --evidence <目录>` 只把合成文本和无真值的候选提交给三个已指定的OpenAI模型；使用本机已有 `.codex/secrets/cretas-openai-eval.env`，不打印密钥。最多500次计划请求，最多3并发，不自动重试，未决请求先检查回执；不能据此确认账户剩余额度。TF-IDF候选是离线替代实验，不代表线上BM25/向量召回效果。

35条原始审核和所有已评测文本只用于保护诊断；训练只能接收独立新样本。禁止按测试成绩修改阈值、补真值候选、重标测试标签，或用教师一致作为自动训练批准。生成的权重/业务语料/请求回执保存在Git以外；具体结果与局限见 `docs/dispatch/archive/2026-11-05-intent266-local-experiment.md`。本入口不部署、不替换生产模型。

## 全目录均衡训练与冻结盲测

`factory_accuracy_data.py` 从当前Java Tool/Skill目录和 `data/factory_semantic_boundaries.json` 读取业务边界；同一单Tool的历史入口只在离线训练语义中归一，266个物理head ID不改，不代替租户启用/权限校验。`initialize` 冻结定义和生成计划；独立生成长/短训练、开发与盲测候选，`audit` 的混合候选复核不提供原标签，`curate` 优先保护测试再筛开发/训练。生成与请求原始回执留在 `--evidence` Git外目录。复用本机已有API Key文件，不打印或复制密钥。训练运行环境需torch、transformers、numpy、scikit-learn；CI契约不要求下载模型或执行真实云调用。

`factory_accuracy_train.py --evidence <目录>` 复用真实增量Trainer，按验证宏F1选择最多三组预登记配置，默认标签平滑、冻结前层、权重衰减与早停。`factory_accuracy_eval.py calibrate --evidence <目录>` 只用独立校准集选择全局分流阈值，并测量可选的同Mini分歧复核；没有净增益就关闭。`blind` 锁定数据、权重和路由实现哈希后才打开盲测，之后训练和校准入口拒绝再次拟合。候选融合是离线代理，不是线上完整召回验收。`factory_accuracy_regression.py` 只允许在主盲测冻结后执行旧保护集诊断，不能用它反向选模型。

本轮数值达到本地81.80%／混合96.46%，但验证到正向盲测差10.09点、35边界难例仅26/35，完整泛化门禁未通过；严禁把它表述为无过拟合或直接生产可用。完整证据与剩余质量缺口见 `docs/dispatch/archive/2026-11-05-intent266-accuracy.md`。权重仅本地保存，不部署、不替换线上。

## 独立泛化确认与额度中断恢复

`factory_generalization.py --evidence <新目录>` 依次执行 `initialize`、`generate-train`、`generate-development`、`generate-confirmation`、`audit`、`negatives`、`curate`、`train`、`calibrate`、`confirm`。独立训练与确认只输入能力定义，不输入旧错题；均衡旧训练回放，验证集选模，新确认集打开后拒绝再次训练/校准。沿用266个输出ID与已声明的260个语义目标。`budget-previous-roots.json` 将前轮回执纳入同日已知用量，不代表组织余额可见。未知请求不能通过新目录重发或忽略其预留额度。

额度中断后先停止原进程，再用 `factory_generalization_recovery.py freeze --evidence <原目录>` 保存原始ledger、锁定本地预测并隔离无完整HTTP回执的请求。它们永不重发、按预留上界计额度，最终保守计错（即使真值是澄清也不算答对），所有题保留在分母。额度允许后才执行 `recover`：仅发送原先未发送的请求，完成请求按原payload哈希缓存复用，保持同一权重、策略、候选、提示词与确认集。恢复入口不提高每日额度、不授予额外付费许可。完整确认后才允许 `regression` 打开旧保护集；诊断结果不得反向拟合。第二轮状态见 `docs/dispatch/archive/2026-11-05-intent266-generalization.md`。

第二轮最终796条本地87.94%／混合97.11%，预设泛化检查通过；旧1637条本地81.80%→86.07%。仍有明确缺口：旧35条本地16/35、混合27/35，澄清26/40；不能用总体达标代替这些质量指标或生产验收。全部评测数据保持保护，不替换线上模型。此前$1追加授权先用于已锁定的恢复/旧35诊断，新增请求未缓存价格保守折算约$0.277；后续用户允许的教师指导仍受同一总额约束，默认每日源码上限不变。

配额核算按UTC日期和官方共享模型组进行（北京时间08:00重置）；组内多个模型合计，两个组独立。分组快照在 `factory_quota_accounting.py`，不代表模型当前可调用或项目已加入免费计划。旧回执缺日期时，需要与原文件SHA绑定的 `.utc-days.json` 证据，不能按目录名猜日期；未知分组/日期明确停止。跨UTC日需等待在途请求完成后重建client，已完成请求按原payload缓存复用。停止时排空在途请求，避免仅因提前关闭HTTP连接造成未知状态。

GPT6.1 Terra→GPT6 Luna的源码规则教师影子试验：12条独立探针前后均12/12，未证明净增益，未接入当前模型/路由；12条全部入保护名单。GPT6 Luna按当前帮助表保守纳入100万组；Terra免费归属未列明，单列付费预留。具体额度、缓存写入计价更正和main冻结事项见 `docs/dispatch/archive/2026-11-05-intent-quota-teacher.md`。


## 带上下文的蒸馏数据管线（INTENT-DISTILL-20261111，P3）

学生要学的是「状态 + 当前句 → 对话动作 + 意图」，不是「单句 → 标签」。管线只生成与标注数据，不训练、不换线上模型、不碰 `backend/` 与 prod 数据。代码：`distill_common.py`（样本 schema 校验、Tool 读写表、带美元上限的可续跑 API 客户端）、`distill_leak_check.py`（防泄漏）、`distill_pipeline.py`（generate / label / assemble / split / report / validate）、`test_distill_pipeline.py`（离线测试，`python -m pytest scripts/finetune/test_distill_pipeline.py`）。

样本字段：`utterance` 当前句；`state` 上轮任务状态（`prev_intent`、`confirmed_params[{name,value,source}]`，source 为 explicit_turn / confirmed / default，`pending_fields`、`last_action`；全新会话为 null）；`candidates` 候选意图 ID；`dialogue_act` 取 NEW_TASK / CHANGE_PARAM / PAGE / CANCEL / NEED_CLARIFY；`intent`（CANCEL 与 NEED_CLARIFY 必须为空，CHANGE_PARAM 与 PAGE 必须等于 `state.prev_intent`）；`teacher_probs` Luna Choice 对候选（加 `__NONE__`）的归一化概率；`teachers` 两位老师各自的结论；`agreement`；`family`（意图编码的业务域前缀，切分按它，同族不跨集合）；`read_only`；`generator_plan`（生成时的计划，仅作诊断，不是标签）。`validate_sample` 对 raw / labeled / review 三个阶段分别校验。

`read_only` 取自 Tool 源码里声明的 `getAccessMode()`（CI 强制全量覆盖的二值声明，未声明按 WRITE）；SKILL 意图要求绑定的全部 Tool 都是 READ 才算只读。源码由 `tool_access_modes.py` 读取（去注释、沿父类查找、常量工具名；方法体不是单一 `return AccessMode.X;` 记 DYNAMIC，整条继承链都没声明记 UNDECLARED，二者都不当 READ）；任何训练标签意图解析不出读写，`build_intent_table` 直接报错拒绝建表，不猜（F-pre 之前 `GATE_DIAGNOSE_BATCH`、`PAGINATION_PREVIOUS` 因注释和继承被误读为未解析，Java 里二者都声明 READ）。闸：Java `ToolAccessModeDeclarationTest`（PR fast-contracts 选中）+ `scripts/tests/test-tool-access-mode-scanner.sh`。没有意图的样本按 `state.prev_intent` 对应任务取值，二者都没有则为 null，报告里记为 `no_target`（不适用），与 `unresolved` 分开。

澄清判定标准：`clarify_rubric.json` 由 `build_clarify_rubric.py` 从源码生成（`--check` 检查是否过期）：8 条判定规则（引用 `backend/python/ai/matcher/decision.py` 与本管线动作定义的行号）；每个只读意图的 `must_ask`（必填且无默认值）/ `defaults` / `optional` 取自绑定 Tool 的 `getParametersSchema()`、`getRequiredParameters()` 并带 文件:行号；18 组易混口径，只能引用真实标签意图。`label --rubric clarify_rubric.json` 把同一份标准给 Luna 和 Terra，二者仍各自独立、互不看对方结论和生成计划。`--rubric-mode brief` 是压缩措辞，实测在难例上一致率更低（见下），扩充用 full。

生成：`gpt-5.4`（强模型，独立于两位老师）按语义族逐组写 6 条一组的多轮样本（续句改参数、翻页或取消、切换目标、含糊需澄清、一对「查/改只差一两个字」的全新会话对照），生成时只看到本族意图，不看任何受保护集合；偏离计划的条目直接丢弃。标注：Luna Choice（Decisions API，两道题：对话动作、意图，带概率）与 Terra（Responses，结构化输出）各自独立调用，互相看不到对方，也看不到生成计划。两者的对话动作和意图都一致、且都满足标签规则 ⇒ 银标训练集；不一致、或任一方违反标签规则 ⇒ `review-queue.jsonl`，只给人工审核，不进训练集，label 字段置空。老师一致不是人工真值，验收集另建。第一批 100 条标注后一致率低于 70% 会自动停止（退出码 3），不靠放宽判定凑数。

防泄漏：`python distill_leak_check.py --in raw.jsonl --out clean.jsonl --dropped dropped.jsonl --report r.json` 对 35 / 796 / 1637 评测集、冻结的 holdout 登记、教师探针和已打开的多轮开发对话（`Cretas意图架构只读审计-20261105` 下全部文件）做精确匹配加近邻匹配（字符比率 ≥ .85，沿用 `OverlapIndex`；再加字符二元组 Jaccard ≥ .6 兜底），命中即剔除并计数；任一受保护来源缺失直接报错而不是静默缩小范围。`--self-test` 与报告里的 `self_test` 主动构造精确命中与近邻命中并断言被拦。注意：这是字面相似度，不是向量语义近邻；真正的语义近邻需要另行用嵌入复核。

预算：`DollarClient` 每次调用前按保守上界预留，超过 `--cap`（本线 10 美元）立即停；账本 `api-ledger.json` 与回执 `api-results.jsonl`（不含密钥）在 `--work` 目录，已有回执同 payload 命中缓存、失败回执和未决预留永不自动重发。价格是上界（Terra 按缓存写入价、生成模型按最高输出价、Luna Decisions 只计输入），不是发票。

第一批试点（500 条，数据在仓外 `C:\Users\Steve\Documents\Cretas意图蒸馏-20261111\`，Git 不收）：

| 文件 | SHA256 |
|---|---|
| silver.jsonl（380 条银标） | 35bb760f25def54a028ac6f1eb0fec661ab031e6f79216317a3bc9743bbbc69e |
| silver-train.jsonl | d7c7b89e946a19c0e169f5c589e359599bf2fa930784c4301b6d886b2bb3ff4c |
| silver-validation.jsonl | b9fd5b313245fdc73c554f1eb83a6c651b452aaef1461d7fecb904e3dc8554cf |
| silver-calibration.jsonl | b10883eea52fd86675bbc444c27a1021d5ea9d855f0ce17168cd3132d90a04fc |
| review-queue.jsonl（120 条人工审核） | 989dad436bf79a2b61fcc8a87c90a4145ef70bc26075c1446b5388395f1fb818 |
| labeled-raw.jsonl（两位老师原始结论） | 7e1e30ed98733c41bfeb52ba8a840cb4b6b2aef94c74f4039c5463b5c01ba3e9 |
| raw.jsonl / clean.jsonl | 4e903c2796e80a28b75ee9cc6a89f2f0edb57bb5851641d0fc1f5e90d3cc3f69 / 2ac0acdd11a6ce4b157f8553ea228610dbd7acb8ca65df338fa8b8c208a1ebc7 |
| pilot-report.json | e51d919f686acd39902daa6cc2fbb4bd4587e3bfbf5164f931575388f23e135b |

独立验收集：`distill_leak_check.py` 另用 `--sealed-fingerprints`（默认 `Cretas意图独立验收-20261111/sealed/fingerprints.jsonl`，只读其中的 `hash` 字段，算法 sha256(NFKC→lower→去掉空白与非字母数字字符 的 UTF-8)，不打开 sealed 下任何其他文件）；文件不存在时照常检查并打印「独立验收集指纹未提供」，不静默跳过。试点补跑命中 26 条并已剔除。

读数见 `pilot-report.json`。要点：老师一致率 76.0%；NEED_CLARIFY 只有 47.2% 一致（Luna 倾向直接选一个意图、Terra 倾向要求澄清），所以银标集里「该澄清」的样本偏少，这是学生训练时必须单独补审的部分，不能把银标集当作澄清判定的代表。

### v2（F-前置：读写来源 + 澄清标准 + 扩充，数据在仓外 `Cretas意图蒸馏-20261111\v2\`，路径与 SHA256 见该目录 README.md）

- 澄清标准后，试点那 89 条计划为澄清的样本一致率 47.2% → 83.2%（样本内，标准按这批调过）；新生成、未参与调整的 238 条计划为澄清的样本 81.5%（样本外）。`agreed()` 未改。
- 已标注 1790 条（试点 clean 551 + 新生成去重后 1318，再经防泄漏剔除 79），银标 1479、审核队列 311，总一致率 82.6%；只读 83.3% / 写 81.0% / 无目标意图 85.4%。按语义族切 train 1044 / validation 218 / calibration 217。
- 花费上界 14.79 美元（本线上限 15）。目标约 3000 条未达到：带标准后标注上界约 0.0058 美元/条，可用 `generate --start 345` 续跑。
