# 意图验收集 v3（按工厂完整工作流）与多指标上线门槛

批次 `INTENT-DISTILL-20261111` 第②条线。依据：`docs/dispatch/archive/2026-11-14-intent-flywheel-architecture.md` 第 7 节「验收集小、口径偏」。

## 1. 现状（做之前）

| 集 | 题量 | 覆盖 | 问题 |
|---|---|---|---|
| v1 `tests/semantic-read/HOLDOUT.md`（已降为开发集） | 多轮 118 段 / 285 轮，单轮决策 708 条 | 意图限库存 + 订单只读 Tool，另有写意图对照 | 范围窄；11-12 起不再当验收 |
| v2 `tests/semantic-read/HOLDOUT-v2.md`（封存） | 多轮 152 段 / 507 轮，单轮 292，合计 799 轮 | 4 角色（操作员/仓管/质检/管理），109 个意图；改参数、翻页、挂起恢复、同名等 | 出题和标注全是 Claude 一家；没有调度角色；没按「采购→…→对账」流程铺；每次是里程碑手跑，不是门槛 |
| 开发集 `scripts/finetune/*`、E 线开发集 | 数万条蒸馏语料 | 偏已有意图的说法 | 训练用，不能验收 |

门槛现状：**没有多指标门槛**。验收靠 `strict_scoring.py` 等脚本只在里程碑手跑，出读数不判过/不过。#1711 先问口径的上线依据是「校准集 21/38 → 28/38、验收集决策层 149/734 → 430/734」，同时意图判错 9 → 116，当时没有任何一道闸能因为后者拦住。

## 2. v3 是什么

- **覆盖**：业务域 7 个（采购 → 入库仓储 → 生产报工 → 质检放行 → 出库发货 → 销售对账 → 经营管理）× 角色 5 个（操作员 / 仓管 / 质检 / 调度 / 老板）× 说法 3 种（一线说法 / 间接表达 / 易曲解）= 105 格，每格两个模型家族各出两轮（常规一轮、偏「先问」和「不支持」一轮）。
- **姿态标注**：DIRECT 直接办 / ASK 先问 / WRITE 写操作 / REFUSE 不支持 / OOD 域外。先问口径用 Steve 11-14 的默认「激进档」：能办就办，只有写操作、查还是录只差一字、动词含糊、指代不明才先问。
- **多模型**：出题 = 千问 `qwen-max`（阿里云）+ OpenAI `gpt-5.4-mini`（评测 key，不是线上 Luna 的 key）；每题由**另一家**不看答案独立盲标（千问出的 OpenAI 标，反之亦然）。姿态一致且意图相同（或在任一方的替代意图里）= 确定题；否则 `ambiguous`，**单列、不计分**。
- **去重**：集内整句 + 近似（≥0.85）；对全部已知语料（蒸馏数据、E 线、v1/v2 验收集、仓库内意图用例，共 8.9 万条字符串）整句 / 近似 / 包含，命中即删。
- **隔离**：每格按指纹排序交替分成「开发半边」（`dev/items.jsonl`，在仓库里，可调参也可训练）和「封存半边」（只在本机，仓库里只有指纹）。

### 题量

| | 题数 | 其中确定题（计分） | 歧义（单列） |
|---|---|---|---|
| 封存半边 | 2256 | 1232 | 1024 |
| 开发半边（2026-11-15 裁定后，见第 8 节；裁定前 1228 / 980） | 2208 | 1957 | 251 |

封存半边按姿态：DIRECT 860、ASK 671、WRITE 495、OOD 139、REFUSE 91（其中确定题：DIRECT 596、ASK 155、WRITE 370、OOD 101、REFUSE 10）。覆盖 200 个意图，确定题覆盖 168 个意图。完整覆盖表见 [COVERAGE.md](COVERAGE.md)（由 `coverage_report.py` 生成，只有统计）。

**已知局限（不要当成 v3 已经够用）**

1. 标注是两个模型交叉，不是人。歧义率 45%，ASK 题歧义最多（确定题只有 155 条）、REFUSE 确定题只有 10 条，这两类的读数方差大，门槛里只对分母足够的项设线。
2. 全是单句，没有任务状态和历史。多轮 / 挂起恢复 / 翻页仍靠 v2。
3. 识别层读数，不是端到端：看的是「识别成哪个意图、先问还是直接办、是不是写意图」，不含参数绑定和真实查询结果。
4. 物料、人员、单号都是虚构的「青禾食品」设定。
5. 真人说法没有进来（合作工厂的说法槽位仍待 Steve）。

### 封存与泄漏

- 本机封存：`C:\Users\Steve\Documents\Cretas意图验收v3-20261115\sealed\`，`sealed_items.jsonl` 的 SHA256 = `9cc84003be0a3655b24d69ef61e6fecfa8eedaece5225b521a022faf2b5c85a9`（`MANIFEST.json` 里也有）。出题原始输出在本机工作目录，不进 git。
- 仓库里只有 `sealed_v3_fingerprints.jsonl`（每题 sha256(NFKC→小写→去掉空白和非字母数字) + 业务域/角色/风格/姿态标签，**无原文**；算法与 v1/v2 相同）。
- 使用规则（沿用 v1/v2）：只在里程碑 / 上线门槛处跑；跑完只报汇总，不把逐题原文、期望、模型输出贴进训练数据、开发讨论或 PR；排错被打开的题要登记并从下一版剔除。

## 3. 隔离闸 `leak_gate.py`

封存题不得出现在任何训练 / 开发语料里。扫描默认语料根：`scripts/finetune`、`tests/semantic-read`、`tests/intent-acceptance/dev`、`backend/python/ai`、`backend/python/tests/fixtures`、Java 测试资源与意图目录资源；`--root` 可加本机的蒸馏数据目录；本机有封存原文时用 `--sealed-items` 额外做近似重复判定（仓库内 CI 只有指纹，只能整句精确命中）。

退出码三态：0 无泄漏且确实扫到了语料 / 1 有泄漏（只打印来源文件和指纹前缀）/ 2 没量到（指纹空、没扫到文件）。CI：`scripts/tests/test-intent-acceptance-leak-gate.sh`（挂在 ops-script-tests，`tests/intent-acceptance/**` 触发）。

阴阳性对照（PR 里有实测）：

| 对照 | 构造 | 期望 | 实测 |
|---|---|---|---|
| 阴性（干净语料） | 临时目录一条无关句 | rc=0 | rc=0 |
| 阳性（单测） | 把封存题换全角 / 空格 / 标点 / 大小写写法塞进语料 | rc=1 | rc=1 |
| 阳性（真实指纹） | 把封存集第一题改成大写加空格加句号写进临时「训练」目录，用真实 2256 条指纹扫 | rc=1 | rc=1 |
| 近似（单测，带封存原文） | 封存题改一个字（「看一下」→「看下」）进语料 | rc=1（`near_hits`） | rc=1 |
| 没量到 | 空目录 / 缺指纹文件 | rc=2 | rc=2 |
| 仓库当前 | 真实指纹 × 默认语料根（36 个文件、4.7 万字符串） | rc=0 | rc=0 |
| 变异 | 把命中判断取反 / 去掉 NFKC 归一 | 单测必红 | 3 failed / 2 failed |

## 4. 多指标计分 `score_recognize.py` 与门槛 `gate.py`

全部只在确定题上算，另按 角色 / 业务域 / 说法风格 切片；`ambiguous` 单列，没读数（超时/非 200）单列、不当通过。

| 指标 | 含义 |
|---|---|
| `intent_acc` | DIRECT/WRITE 题选中标准或替代意图 |
| `ask_direct_agree` | DIRECT/ASK 题「问不问」与标准一致；拆成 `false_ask_rate`（该办却问）和 `missed_ask_rate`（该问却办） |
| `direct_exact` | DIRECT 题：没先问且意图对（用户真拿到答案） |
| `write_safe_rate` / `write_as_read_rate` | WRITE 题：识别成写意图或先问 / 被当只读直接答 |
| `wrong_write_exec_rate` | 全部题里：识别成写意图、没先问、且不是标准/替代意图 |
| `refuse_ood_safe_rate` | REFUSE/OOD 题没有直接执行任何业务意图 |

门槛（`gate_config.json`，退出码 0 放行 / 1 有指标不过 / 2 没量到或不可比）：同一份验收集（`items_sha256` 相同）；绝对线（越高越好的有下限，越低越好的有上限；写误执行上限最紧）；总体相对基线不退步超过 2 个百分点；**单项**（角色/业务域/风格切片，n≥25）往坏方向超过 5 个百分点且候选 Wilson 95% 区间不再含基线才算「明显退步」；没量到比例 ≤2%；隔离闸必须 rc=0。绝对线按 11-15 线上基线往下留约 3 点，只许往紧收。`gate_config_anchor.json` 给 Steve 300 题锚点用，只看相对基线。

对照：单测里有「总分涨 8 点、QC 单项跌 25 点」必须被拦；真实基线上构造「总体意图准确率 +2.6、QC 意图准确率 −18.7」→ rc=1（点名 `role:QC / intent_acc`）；构造写误执行 5% → rc=1；基线对自己 → rc=0；变异（去掉切片检查 / 去掉上限检查）单测必红（上限那条第一次变异存活，已补「零退步仍越线」用例）。

## 5. 基线读数（线上，2026-11-15，识别接口）

被测：prod Python `/api/ai/intent/match`（Java 识别层调用的同一个接口，经 ssh 在服务器本机调；只识别，不碰 `/execute`、`/execute/stream`；租户 F006；角色按五类映射；先问档位 AGGRESSIVE）。1232 条确定题全部有读数（unmeasured 0）。线上决策链实际调用约 1530 次（含 Steve 300 题）。

| 指标 | 读数 | 95% 区间 |
|---|---|---|
| intent_acc | 78.4%（757/966） | [75.7, 80.9] |
| ask_direct_agree | 76.0%（571/751） | [72.9, 79.0] |
| direct_exact | 58.1%（346/596） | [54.1, 62.0] |
| false_ask_rate | 25.0%（149/596） | [21.7, 28.6] |
| missed_ask_rate | 20.0%（31/155） | [14.5, 27.0] |
| write_safe_rate | 98.7%（365/370） | [96.9, 99.4] |
| write_as_read_rate | 1.4%（5/370） | [0.6, 3.1] |
| wrong_write_exec_rate | 1.8%（22/1232） | [1.2, 2.7] |
| refuse_ood_safe_rate | 94.6%（105/111） | [88.7, 97.5] |

切片读数见 `baseline/prod-20261115-v3-sealed.score.json`。最值得注意的单项：**出库发货** 误问率 47.4%、**销售对账** 36.7%、**入库仓储** 39.5%（该直接办的在问）；入库仓储意图准确率只有 67.8%；经营管理的写误执行率 3.2% 最高；质检员的写误执行率 2.4%、仓管 2.6%。

**Steve 300 题锚点**（`baseline/prod-20261115-steve300-anchor.score.json`；Steve 自答 296 条有效，直接办 155 / 先展示再确认 78 / 先问 63；题面原文不进仓库）：单句、无任务状态、老板角色识别，其中 62 题原本带任务状态，所以绝对值偏低且不设绝对线，只看相对不退步。读数：先问/直接办一致率 48.8%（117/240），误问率 64.4%（114/177），漏问率 14.3%（9/63），写误执行 1.0%（3/296）。这与大验收集的 25% 误问差异大，原因是 300 题本来就是老师分歧题 + 疑似与口径冲突的题；它说明对抗性题上「该办却问」仍是最大缺口。

## 6. 用法

```bash
cd tests/intent-acceptance
python -I -X utf8 scan_catalog.py --out <catalog_rw.json>            # 意图 → 读写属性
python -X utf8 gen_workflow_set.py gen   --work <dir> --catalog <catalog_rw.json> --gen qwen-max,gpt-5.4-mini --per-cell 12
python -X utf8 gen_workflow_set.py label --work <dir> --catalog <catalog_rw.json> --gen qwen-max,gpt-5.4-mini
python -I -X utf8 assemble_v3.py --work <dir> --catalog <catalog_rw.json> --sealed-dir <本机封存目录> --models qwen-max,gpt-5.4-mini
python -I -X utf8 run_recognize.py --items <本机 sealed_items.jsonl 的确定题> --out <results.jsonl>
python -I -X utf8 score_recognize.py --items <sealed_items.jsonl> --results <results.jsonl> --catalog <catalog_rw.json> --out cand.json
python -I -X utf8 gate.py --candidate cand.json --baseline baseline/prod-20261115-v3-sealed.score.json
python -I -X utf8 leak_gate.py [--self-test]
python -m pytest tests/intent-acceptance -q
```

凭证（`creds.py`）只进内存：千问 key 经 ssh 读服务器 `.env.prod`，OpenAI 用本机 `~/.codex/secrets/cretas-openai-eval.env`；`INTERNAL_API_SECRET` 只在服务器上读和用。⛔ 不进 git，不打印。

## 7. 还没做（交给后续）

- 门槛还没接进「自动上线」流程（需要自动优化器那条线调用 `run_recognize → score → gate`）；现在是手跑 + 离线单测 + 隔离闸进 CI。
- 题量里 ASK / REFUSE 的确定题偏少，等真人说法或人工复核后补。
- 多轮（任务状态、挂起恢复、翻页）仍用 v2。

## 8. 开发半边歧义题裁定（2026-11-15，`adjudicate_v3.py`）

只动 `dev/items.jsonl` 里原来 `ambiguous=true` 的 980 题；确认题（1228 条）一行不改，封存半边和 `sealed_v3_fingerprints.jsonl` 不读不改（封存半边仍是 1232 确定 / 1024 歧义，**没有裁定**；基线 `baseline/*.score.json` 仍对应封存半边，与本次裁定无关、不可与开发半边比）。

口径（Steve 偏执行）：能按合理默认执行就判 DIRECT（读）或 WRITE（写，先展示再确认）；只有真歧义才判 ASK：写操作同名对象有多个、写操作缺必填参数且无合理默认、两个意图结果差别大且都说得通；只读题的同名不先问（两个都列出来，沿用第 2 节口径）。REFUSE 只用于超出权限或不该做的事，OOD 只用于与工厂业务无关的闲聊。

做法：出题方、盲标方的意见保留，加第三票 `qwen3.5-plus`（关思考，千问通道，盲判，看不到前两方答案，目录取自 `scan_catalog.py` 当前扫出的 270 个意图含说明）；姿态按三票多数；意图不在出题方候选里的再加第二票 `qwen-plus`。判定逻辑在 `decide()`，规则编号见脚本头注释，写进每题的 `adjudication.rule_id` 和 `adjudication.votes`。仍无多数的保持 `ambiguous=true`、`adjudication.status=pending_review`，原因写在 `pending_cause`，清单 CSV 在本机不进仓库。

回滚：原姿态 / 意图 / 替代意图 / 询问选项 / 读写属性 / 歧义标记改名为 `orig_*`，`adjudicate_v3.base_row()` 可把整行还原（实测 2208 行全部与 `main` 上原文件逐字段相同）。脚本幂等，重跑结果字节相同。

```bash
cd tests/intent-acceptance
python -X utf8 adjudicate_v3.py vote --work <dir>        # 第三票 + 第二票，断点续跑
python -X utf8 adjudicate_v3.py calibrate --work <dir>   # 阳性对照：抽确认题让第三票盲判，看一致率
python -X utf8 adjudicate_v3.py apply --work <dir> --csv <待复核.csv> --summary-out <stats.json>
```

局限：第三票也是模型，不是人；第三票对已确认题的姿态一致率约 81%（抽样 167 条，WRITE 题有三分之一被它判成先问，说明它比标注更爱问，所以 ASK 多数必须带三类真歧义理由，否则按默认执行）。裁定后的 ASK 和 REFUSE 仍是多数票结果，读数要标注口径，不要和只有确认题的旧读数混着比。确认题里另有 43 行 `intent_alternates`（47 行含 `ask_options`）是字符串（assemble 的既有问题，`score_recognize` 会按字符展开），第二轮已改成列表（见 8.1）；封存半边大概率同样，**未动**。

### 8.1 第二轮（对第一轮仍待复核的 251 题，`adjudicate_v3_r2.py`）

口径同上，再深一层，详细规则编号见脚本头注释（`adjudication.rule_id` = 第一轮规则 + `R2` + 第二轮规则；第一轮结果整份留在 `adjudication.round1`，`orig_*` 仍是最初标注，`base_row()` 仍能整行回滚）：

1. 意图无多数：给强模型候选意图的完整定义（代码 + 中文名 + 类别 + 说明全文 + 读写属性 + 必填实体，取自 Python 侧 `backend/python/ai/data/factory-intent-catalog.json`），`qwen-max` 与 `qwen3.5-plus`（关思考）各自独立选或判「都不对」并写理由；两次一致才定（J1）；不一致时再让两个模型各自独立判上下位，两个都认定同一个是「更具体者」且读写相同才取它、另一个进 `intent_alternates`（J2）；其余保持待复核（J0）。
2. 姿态三方各异：先套规则（S4：去掉无三类真歧义理由的 ASK 票和意图是真意图时的 OOD 票，剩下的票里有与意图读写属性一致的就取它），规则判不了再一次强模型复核（S6；ASK 仍须带理由，只读意图的同名 / 缺必填仍按默认执行；REFUSE 须另有一票支持）。OOD 一律过复核，且话里点名工厂实体（人 / 物料 / 产品 / 客户 / 设备 / 单子 / 货……）的不判 OOD（S7）。
3. 读写冲突：以意图目录里该意图的读写属性为准修姿态（R3）。
4. 第二轮票（判官意图、理由、上下位、复核）写在 `adjudication.round2.j`，`apply2` 没有缓存也能复跑，结果字节相同。

```bash
cd tests/intent-acceptance
python -X utf8 adjudicate_v3_r2.py deepen --work <dir>                              # 判官 A/B -> 上下位 -> 姿态复核，断点续跑
python -X utf8 adjudicate_v3_r2.py apply2 --work <dir> --csv <剩余待复核.csv> --summary-out <stats.json>
```

仍定不下的题 `ambiguous=true`、`adjudication.status=pending_review`，原因在 `pending_cause`，清单在本机不进仓库。

