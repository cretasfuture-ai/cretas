# jev-oss-eval（评测存档：只收被生产代码引用的那部分）

这里是 2026-09-25 ~ 10-06 工厂意图识别离线评测的**出处存档**。生产代码和迁移里写着
「评测仓 sim/xxx.py」的地方，指的就是这个目录。文件按原样从本地分支
`codex/claude-jev-oss-eval`（35 个提交，未合并）拷出，**没有改过一行**，
所以它们记录的是当时产出那些结论的真实代码。

## 谁引用了它

| 文件 | 引用方 |
|---|---|
| `sim/mine_eval.py` | `backend/python/ai/matcher/decision.py`（粗选 / 细选提示词的起点） |
| `sim/train_reranker.py` | `backend/python/ai/matcher/rerank.py`、`scripts/rerank-service/app.py`、`scripts/rerank-service/README.md` |
| `sim/gen_intent_cleanup.py`、`sim/intent_cleanup_plan.py` | 迁移 `V20261101_49`、`V20261101_50` 首行 |
| `results/sim/full_replay_r2_20261006.md` | `DynamicToolSelectionService.java`（3519 题线上回放） |
| `REPORT.md` | 10-02 开源 Jev 类决策模型评测结论 |

其余 `.py` 是上面几个脚本 import 的依赖（`bailian` / `normalize` / `task_catalog` /
`phrase_baseline` / `run_backend` / `common` / `remote_teacher` / `embedding_bench` / `retrieval_eval`）。

## ⚠️ 不能直接复跑

这是出处，不是可复跑的套件。缺的输入：

- `D:/Temp/IKB.java`、`D:/Temp/IRP.java`：当时 `IntentKnowledgeBase.java` /
  `IntentRecognitionPipelineServiceImpl.java` 的本地副本（`phrase_baseline.py`、`mine_eval.py`）。
- `C:/Users/Steve/cretas-intent-v2/backend/python`：当时的 Python 后端 worktree（`train_reranker.py`、`retrieval_eval.py`）。
- 百炼 key 从 `backend/python/.env` 的 `LLM_API_KEY` 运行时读取，仓库里不含任何密钥。
- 考题 / 回放明细（`testset.jsonl`、`results/sim/*.jsonl`，含 F006 主数据）和 r3、10-09 三条说话意图实验
  **没有收进来**，仍在本地分支上。

线上提示词此后已演进（#875 最近对话、#880「以上都不是 / 需要先问清楚」），
改 `decision.py` 后要复测，请以线上代码为准重建评测输入，而不是照搬这里的路径。
