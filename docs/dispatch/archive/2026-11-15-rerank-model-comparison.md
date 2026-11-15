# 重排模型对比：自训 bge-reranker-v2-m3 vs 百炼重排（2026-11-15）

批次 INTENT-DISTILL-20261111。回答三个问题：百炼重排和我们自训的比能力差多少、多少钱；阿里云有没有能微调重排的服务；值不值得换。

## 结论（先看这里）

1. **不换，继续用自训微调的 bge-reranker-v2-m3，迁到 Mac mini 照旧推进。** 召回@10/@20 上百炼模型和我们打平（差异在统计误差内），但首选命中（@1）我们高 5–7 个百分点（95% 区间不含 0）；写操作类问题上优势最大。
2. **百炼不能微调重排模型。** 百炼「模型调优」只支持 Qwen 文本/视觉生成模型，列表里没有 rerank 和 embedding。唯一的自定义路径是专属部署（按模型单元计时），约 10139 元/月起，远贵于现状。
3. **百炼重排作为「机器离线时的第二跳」是可行的**：从 47 调用 p50 160–240ms，p95 250–310ms，比 mini（p95 约 0.54s）还快，且 qwen3-rerank 召回@10 与自训持平。月成本约 90 元（按 1500 次/天 × 50 候选）。但它没有微调带来的 @1 优势，且依赖外部服务与额度，建议只当兜底，不当主路。
4. 注意：线上 `food_kb` 的 gte-rerank 默认**没开**（见第 5 节），gte-rerank-v2 今天仍能调通。

## 1. 评测口径

- **query + 金标**：`tests/intent-acceptance/dev/items.jsonl`（验收集 v3 开发半边，两家模型交叉标注）。取 `stance` 为 DIRECT 或 WRITE 且 `ambiguous=false` 的题，共 981 条（READ 601 / WRITE 380）。不碰封存半边。
- **候选**：在 47 上只读，用线上同一套 `BlendRetriever`（blend=0.35）、`F006` / `FACTORY`、可见目录 270 个意图，取向量 Top-50；文档文本为生产 `intent_text(row)[:120]`，与 `ai/orchestrator.py::_rerank_candidates` 逐字一致。金标在 Top-50 池内的比例 96.5%（池内召回上限）。
- **为什么不是 10-04 的 767 条难题**：那批原始数据在本机和 mini 都找不到（organizer 同意改用此口径）。⚠️ 这份集合比 10-04 的难题**容易得多**：自训模型召回@10 为 94.1%，而 10-04 难题上是 73.5%，量级不可直接对比，只能比模型之间的相对差。
- **模型**
  ① 自训：线上同一份权重（`ft-r2-20261004`，Windows `rerank-service/model`），fp16，max_length=128。
  ② 原版：`BAAI/bge-reranker-v2-m3`，同样设置。
  ③ 百炼 `gte-rerank-v2`；④ 百炼 `qwen3-rerank`、`qwen3.7-text-rerank`。
  ①② 跑全部 981 条；百炼模型按固定随机种子取 600 条（控制费用）。qwen3.7-text-rerank 跑到 398 条时命中 `403 AllocationQuota.FreeTierOnly`（账号 A 的免费额度耗尽，且开着「用完即停」），所以它只有 398 条。表里同一行的数字都在同一个子集上算。
- **延迟**：在 47（阿里云上海）上顺序调用，每条 1 次请求、50 个文档，走 `dashscope.aliyuncs.com` 原生接口，含 TLS 与公网往返。
- **局限**：自训模型的训练集含例句与老师标注的说法，dev 题是新生成的，但不能排除措辞相近；每个子集 400–600 条，@10 差 1–2 点在噪声内。

## 2. 召回结果（金标进入重排后前 k 个的比例，%）

全部 981 条（只有 ①②）：

| 排序 | @1 | @5 | @10 | @20 |
|---|---|---|---|---|
| 向量 Top-50 原顺序 | 57.0 | 85.3 | 91.1 | 93.8 |
| ② 原版 bge-reranker-v2-m3 | 64.4 | 86.7 | 91.8 | 95.4 |
| ① 自训微调 | **70.8** | **90.8** | **94.1** | **95.8** |

百炼子集 600 条（同一批题）：

| 排序 | @1 | @5 | @10 | @20 |
|---|---|---|---|---|
| 向量原顺序 | 55.8 | 85.3 | 90.2 | 93.2 |
| ② 原版 bge | 63.3 | 87.0 | 92.0 | 95.3 |
| ① 自训微调 | **70.7** | 90.5 | 93.7 | **95.7** |
| ③ gte-rerank-v2 | 65.2 | 90.5 | 93.7 | 94.7 |
| ④ qwen3-rerank | 63.3 | 89.7 | 93.0 | **95.7** |

qwen3.7-text-rerank 子集 398 条：

| 排序 | @1 | @5 | @10 | @20 |
|---|---|---|---|---|
| 向量原顺序 | 54.3 | 83.9 | 88.7 | 92.5 |
| ① 自训微调 | **70.4** | 89.9 | 93.2 | 95.7 |
| ② 原版 bge | 65.1 | 88.2 | 92.2 | 95.5 |
| ③ gte-rerank-v2 | 64.8 | 91.0 | 93.7 | 94.7 |
| ④ qwen3-rerank | 64.1 | 89.4 | 93.2 | 96.0 |
| ④ qwen3.7-text-rerank | 58.5 | 88.2 | 94.5 | 96.5 |

配对自助法（600 条，2000 次重采样）自训减各模型：

| 对比 | @1 差 | 95% 区间 | @10 差 | 95% 区间 |
|---|---|---|---|---|
| 自训 − 原版 bge | +7.3 | [+3.3, +11.3] | +1.7 | [−0.5, +3.8] |
| 自训 − qwen3-rerank | +7.3 | [+3.3, +11.3] | +0.7 | [−1.2, +2.5] |
| 自训 − gte-rerank-v2 | +5.5 | [+2.0, +9.0] | +0.0 | [−1.8, +1.8] |

按题型看自训的作用（全部 981 条，@1 / @10）：
- 写操作 380 条：向量 57.9 / 92.9，原版 61.8 / 93.4，**自训 75.8 / 97.4**。
- 查询 601 条：向量 56.4 / 90.0，原版 66.1 / 90.8，自训 67.7 / 92.0。

读法：
- **微调的贡献**（自训对原版）：@1 +6.4，@5 +4.1，@10 +2.3，集中在写操作；查询类提升有限。
- **百炼模型**基本等同于原版 bge：@5/@10/@20 与自训差距落在误差内，@1 低 5–7 点。
- qwen3.7-text-rerank 的 @1 明显偏低（58.5）但 @10/@20 最高，适合只取前 10 个送决策模型的用法，但样本只有 398 条，不单独下结论。
- 线上用的是「前 K=10 给决策模型」，所以 @10 才是对接指标；@1 差距体现在决策层首选更容易对。

## 3. 延迟与成本

从 47（上海）调用百炼，50 候选/次：

| 模型 | 样本 | p50 ms | p95 ms | p99 ms | 平均 token/次 |
|---|---|---|---|---|---|
| gte-rerank-v2 | 600 | 163 | 251 | 384 | 3745 |
| qwen3.7-text-rerank | 398 | 208 | 313 | 537 | 3693 |
| qwen3-rerank | 600 | 242 | 291 | 420 | 4107 |

对照自训模型：Windows RTX 3060 本机 p50 201 / p95 227ms（串行），3 并发 p95 629ms；Mac mini MLX p95 约 0.54s（见 `side-session-notes/rerank-to-mini-latency-20261115.md`）。线上超时 `AI_RERANK_TIMEOUT_MS=1500`。

价格（官方页，**币种为美元**；人民币按约 7.1 折算，仅估算）：

| 模型 | 单价 | 来源 | 月成本（1500 次/天 × 30 天 × ~4100 token ≈ 1.85 亿 token） |
|---|---|---|---|
| qwen3-rerank | 北京 0.069 美元/百万 token（新加坡 0.1） | https://www.alibabacloud.com/help/en/model-studio/qwen3-rerank | 约 12.8 美元 ≈ 90 元 |
| gte-rerank-v2 | 0.115 美元/百万 token | https://www.alibabacloud.com/help/en/model-studio/model-pricing（页面摘要，未逐字核对，下单前以控制台为准） | 约 21 美元 ≈ 150 元 |
| qwen3.7-text-rerank | **价格页未找到**；规格页 https://help.aliyun.com/zh/model-studio/text-rerank-api | — | 无法估算 |
| 自训（mini 或 Windows） | 0 | — | 电费，依赖我们自己的机器 |

计费口径（官方）：请求 token = 查询 token × 文档数 + 文档 token 总和。本次评测实际用量：qwen3-rerank 246 万、qwen3.7 147 万、gte 225 万 token，合计约 620 万，按上表单价不超过 4 元，且 qwen3.7 的部分已落在免费额度里，**远低于 20 元上限**。

模型清单与限制（官方文档 https://help.aliyun.com/zh/model-studio/text-rerank-api ，2026-11-15 读取）：
- `qwen3.7-text-rerank`：单次 500 文档、单条 30,000 token；`qwen3-rerank`：500 文档、4,000 token；`qwen3-vl-rerank`：多模态；`gte-rerank-v2`。
- 文档称 gte-rerank 将于 2026-05-30 下线、推荐 qwen3-rerank；但今天 gte-rerank-v2 实测仍返回 200（600 次全成功）。免费额度页的「已下架免费额度」名单包含 gte-rerank-v2（新用户不再有额度）：https://help.aliyun.com/zh/model-studio/new-free-quota 。

## 4. 阿里云能不能微调重排模型

| 路径 | 结论 | 来源 |
|---|---|---|
| 百炼「模型调优」（SFT/DPO/CPT） | **不支持 rerank / embedding**。支持列表仅 Qwen3.x、Qwen2.5 文本模型和 Qwen-VL 视觉模型；数据格式为 ChatML jsonl；训练按 token 计价（如 Qwen3-8B 0.006 元/千 token） | https://help.aliyun.com/zh/model-studio/model-training-overview 、https://help.aliyun.com/zh/model-studio/model-training-and-deployment-billing |
| 百炼调优后部署 | 能部署成在线 API，但仅限生成类调优模型 | https://help.aliyun.com/zh/model-studio/model-deployment-introduction |
| 百炼专属部署现成重排模型（不能自带数据微调） | 按「模型单元」小时计费：Qwen3-Rerank / Qwen3-Rerank-0.6B / Qwen3-MoE-Rerank-0.6B 约 21 元/小时、约 10139 元/月（MU5×1） | https://help.aliyun.com/zh/model-studio/model-training-and-deployment-billing |
| 百炼「我的模型」导入 | 可导入 safetensors 格式的**千问系列**开源模型再部署，不含 bge | 同上 model-training-overview |
| 阿里云 PAI（DSW/DLC 训练 + EAS 部署） | 通用 GPU 平台，理论上能自己训 bge-reranker 并部署成在线接口；但我没找到 bge-reranker / Qwen3-Reranker 的官方微调文档，EAS 页面也未列出 GPU 按量单价（以控制台为准）。「PAI 模型部署及训练」页面未读，不能排除有现成方案 | https://help.aliyun.com/zh/pai/deploy-an-llm/ 、https://help.aliyun.com/zh/pai/product-overview/billing-of-eas |

要点：百炼里想用自己的数据微调重排，当前**走不通**；自己在 PAI 上训再部署，等于租 GPU 自养，成本和运维都不比我们现有的 Windows/mini 好，且 47 无 GPU 的约束不变。

## 5. food_kb reranker.py（gte-rerank-v2）线上状态

- `backend/python/food_kb/services/reranker.py` 默认模型 `gte-rerank-v2`；`main.py` 启动时用 `FOOD_KB_RERANKER_ENABLED`（默认 false）决定开关，模型取 `FOOD_KB_RERANKER_MODEL`。
- 47 的 `.env.prod`、`.env.aistore`、`.env.ota` 里**都没有这两个变量** ⇒ 线上 food_kb 重排处于关闭状态，不会因 gte 下线而报错；近 7 天 `cretas-python` 日志里没有 "Reranker configured" 或相关错误行（journal 窗口内 0 条）。
- 实测回执：2026-11-15 从 47 对 `gte-rerank-v2` 发 600 次请求（50 文档/次），600 次 HTTP 200、0 次错误。所以文档中的下线公告对 `gte-rerank-v2` 暂未生效；若要打开 food_kb 重排，模型应改用 `qwen3-rerank`，不要押在 gte 上。
- 副作用提醒：`reranker.py` 头部注释写「默认 qwen3-rerank / ¥0.0005/1K」，与代码默认值 `gte-rerank-v2` 不一致，是历史遗留，下次碰这个文件时对齐。

## 6. 建议

1. 主路：自训 bge-reranker-v2-m3，迁 Mac mini（MLX）。已有读数 p95 约 0.54s、在 1500ms 超时内；池子 50 不变，避免召回回退。
2. 兜底：给 `RerankClient` 加第二跳「百炼 qwen3-rerank」（Windows/mini 都不通时用），延迟 p95 约 0.3s，月成本约 90 元，召回@10 与自训持平。先不改线上；落地要加开关、失败返回 None 的现有约定不变，并注意额度与「用完即停」403。
3. 不做：不买百炼专属部署（≥10139 元/月）；不再寻找百炼微调（不支持）；不用 qwen3.7-text-rerank（无价格、@1 偏低、样本少）。
4. 想让自训模型继续提升：优先补写操作之外的查询类训练数据（查询类 @1 只比原版高 1.6 点）。

## 附：复现与数据

- 候选：47 上跑一次性只读脚本（用线上 `BlendRetriever` + `load_snapshot`），输出 981 条 × Top-50（`codes` + `docs`）。
- 自训/原版：`sentence_transformers.CrossEncoder`，`max_length=128`，fp16，Windows RTX 3060（评测期间与线上服务共用显卡，未改服务）。
- 百炼：47 上顺序调用 `POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank`，凭证读 `.env.prod`，未落盘未入库。
- 随机种子：百炼 600 条为 `random.Random(20261115).shuffle` 后的前 600 条。
- 线上服务未改动、未重启；LIUSHANMEN 未触碰。
