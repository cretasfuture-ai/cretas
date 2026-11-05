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

第二轮最终796条本地87.94%／混合97.11%，预设泛化检查通过；旧1637条本地81.80%→86.07%。仍有明确缺口：旧35条本地16/35、混合27/35，澄清26/40；不能用总体达标代替这些质量指标或生产验收。全部评测数据保持保护，不替换线上模型。用户本轮额外付费$1授权仅覆盖已锁定的恢复/旧35诊断，新增请求未缓存价格保守折算约$0.277，默认每日源码上限不变。
