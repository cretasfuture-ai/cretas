# 意图候选重排服务 (rerank-service)

工厂 AI 意图识别第②层的候选重排：**向量 Top-50 → 本服务 → 前 10 个交给决策模型**，
取代原来每次约 7k token 的 LLM 粗选（编排器接入见 `backend/python/ai/matcher/rerank.py`，PR #901）。

## 现状（2026-10-04）

| 项 | 值 |
|---|---|
| 模型 | 用本仓数据微调的 `bge-reranker-v2-m3`（蒸馏第一步），fp16 推理 |
| 运行位置 | 开发机 RTX 3060（上线前阶段），目录 `C:\Users\Steve\rerank-service\` |
| 自启 | Windows 计划任务 `CretasIntentRerank`（登录时跑 `start-rerank.ps1`，已在跑就跳过） |
| 接到 prod | FRP **STCP** 私密通道：本机 frpc 注册 `cretas-rerank`，47 上 `frpc-rerank-visitor.service` 只绑 `127.0.0.1:18190`，公网不可见 |
| 访问控制 | 请求头 `X-Rerank-Key`（本机 `.env` 的 `RERANK_KEY` = 47 `.env.prod` 的 `AI_RERANK_KEY`） |
| 延迟 | 从 47 调用：50 个候选约 0.1–0.15s，3 并发约 0.35s |

线上实测（理解力考题 514 题，粗选模式 → 重排模式）：办对率 84.8% → 85.6%，误执行 2.9% → 2.1%，
识别耗时中位 1.29s → 0.72s，每次 token 约 8.5k → 1.5k。

## 文件

| 文件 | 作用 |
|---|---|
| `app.py` | FastAPI 服务：`GET /health`、`POST /rerank`（只监听 127.0.0.1） |
| `start-rerank.ps1` | 计划任务入口，起 uvicorn + frpc。⚠️ **必须保持纯 ASCII**：PowerShell 5.1 把无 BOM 的 UTF-8 当 ANSI 读，中文注释会吞掉下一行 |
| `setup_frp_on_47.sh` | 在 47 上生成两端 FRP 配置（`/opt/frp/rerank/`）并装 visitor 的 systemd 单元；幂等，不换密钥 |
| `reset_frps_on_47.sh` | 2026-10-04 重设 47 上 frps 的 token（磁盘配置曾被安装包默认版覆盖，详见脚本头注释）。**只在需要轮换 token 时跑**，跑完要重跑 `setup_frp_on_47.sh` 并把新的 `win-frpc.toml` 拷回本机 |

不进仓库：`model/`（微调权重）、`.env`（`RERANK_KEY` / `MODEL_DIR`）、`frpc.exe`、`frpc.toml`（含 token 与 STCP 密钥）、日志。

## 从零部署到一台新机器

1. 拷微调权重到 `model/`。训练脚本在评测仓 `scripts/jev-oss-eval/sim/train_reranker.py`。
2. `.env` 写 `RERANK_KEY=<随机>`；47 `.env.prod` 写同值的 `AI_RERANK_KEY`，再加 `AI_RERANK_URL=http://127.0.0.1:18190/rerank`、`AI_DECISION_CANDIDATES=rerank`。
3. 47 上跑 `setup_frp_on_47.sh`，把生成的 `/opt/frp/rerank/win-frpc.toml` 拷成本机 `frpc.toml`；frpc 二进制在 47 的 `/opt/frp/win/`（v0.61.0）。
4. 注册计划任务（或换成目标机器的服务管理器），确认从 47 `curl 127.0.0.1:18190/health` 通。

## 失败时会怎样

服务不在线 / 超时（`AI_RERANK_TIMEOUT_MS`，现为 1500）/ 返回异常：编排器退回 LLM 粗选，`timingMs.rerankFallback=1`，识别不中断。
带对话历史的轮次本来就不走重排（`rerankSkippedHistory=1`），因为重排只看这一句。
