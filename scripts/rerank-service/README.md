# 意图候选重排服务 (rerank-service)

工厂 AI 意图识别第②层的候选重排：**向量 Top-50 → 本服务 → 前 10 个交给决策模型**，
取代原来每次约 7k token 的 LLM 粗选（编排器接入见 `backend/python/ai/matcher/rerank.py`，PR #901）。

## 现状（2026-11-15 起）

两个站点跑同一份微调权重、同一个 HTTP 接口，47 上 `AI_RERANK_URL` 指向哪个就用哪个：

| 项 | **主用：加拿大 Mac mini**（2026-11-15 起） | **备份：开发机 Windows RTX 3060**（2026-10-04 ~ 11-15 主用） |
|---|---|---|
| 47 上的地址 | `http://127.0.0.1:18191/rerank` | `http://127.0.0.1:18190/rerank` |
| 代码 | `mini/app_mlx.py` + `mini/mlx_xlmr.py`（MLX fp16） | `app.py`（torch cuda fp16） |
| 目录 | mini `~/rerank-service/`（用户 rex，700） | `C:\Users\Steve\rerank-service\`（`model` 链到 `B:\AIModels\rerank-models-20261007\model`） |
| 常驻 | launchd `~/Library/LaunchAgents/com.cretas.rerank.plist`（RunAtLoad + KeepAlive） | 计划任务 `CretasIntentRerank`（登录时跑 `start-rerank.ps1`） |
| 接到 47 | `com.cretas.rerank-tunnel` 跑 `mini/rerank-tunnel.sh`：`ssh -R 127.0.0.1:18191:127.0.0.1:18190`，受限账户 `cretas-llm-canada`（与本地模型隧道 11437、出口 11438 同一账户同一把钥匙） | FRP **STCP**：本机 frpc 注册 `cretas-rerank`，47 `frpc-rerank-visitor.service` 绑 `127.0.0.1:18190` |
| 公网 | 不可见：47 sshd `Match User cretas-llm-canada` 的 `PermitListen` + authorized_keys `permitlisten` 只放行 127.0.0.1 上的 11437/11438/18191，`GatewayPorts no` | 不可见：visitor 只绑回环 |
| 日志 | `service.log`（每次请求一行带时间戳 `rerank ok n= ms=`）、`service.err.log`、`tunnel.err` | `service.log`（uvicorn access log，不带时间戳）、`frpc.log` |

访问控制两边相同：请求头 `X-Rerank-Key`（各机 `.env` 的 `RERANK_KEY` = 47 `.env.prod` 的 `AI_RERANK_KEY`）。
⛔ 密钥只在各机 `.env`，不进 git、不打印。线上超时 `AI_RERANK_TIMEOUT_MS=1500`（代码默认 600）。

线上实测（理解力考题 514 题，粗选模式 → 重排模式，2026-10-04 Windows）：办对率 84.8% → 85.6%，误执行 2.9% → 2.1%，
识别耗时中位 1.29s → 0.72s，每次 token 约 8.5k → 1.5k。

## 为什么迁、代价是什么（2026-11-15）

Windows 关机/休眠时线上整段退回 LLM 粗选（贵约 7k token/次、召回更低）；mini 是 7×24 常开的。代价是慢：
M4 Pro 的 GPU 对 50 × ~113 token 过一遍 XLM-R large（约 4 TFLOP）就是 0.5s 量级，3060 fp16 张量核约快 2.5 倍。
Steve 拍板「慢一点无所谓」。

### 延迟对照（1 条 query × 50 个文档；文档取 factory-intent-catalog 补齐到 120 字，与线上 `intent_text[:120]` 同长，token 61–113、均 90）

| 站点 / 引擎 | 口径 | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|
| Windows 3060 cuda fp16 | 本机 HTTP，串行 200 次 | 201 | 227 | 275 |
| Windows 3060 cuda fp16 | 本机 HTTP，3 并发 200 次 | 528 | 629 | 1534 |
| Windows 3060 cuda fp16 | **从 47 经 FRP**，串行 100 次 | 219 | 336 | 458 |
| mini torch 2.9.1 mps fp32 | 进程内 200 次 | 667 | 738 | 755 |
| mini torch mps fp16 | 进程内 200 次 | 547 | 613 | 652 |
| mini torch mps fp16 / bf16（sdpa） | 进程内 60 次 | 534 / 533 | 596 / 608 | — |
| mini torch cpu fp32（12 线程） | 进程内 20 次 | 1461 | 1581 | — |
| mini **MLX 0.29.4 fp16**（本目录 `mini/`） | 进程内 200 次 | 503 | 542 | 588 |
| mini MLX fp16 | 本机 HTTP，串行 200 次 | 487 | 522 | 545 |
| mini MLX fp16 | 本机 HTTP，3 并发 200 次 | 1470 | 1529 | 1588 |
| mini MLX fp16 | **从 47 经 ssh -R**，串行 100 次 | 526 | 560 | 650 |
| mini MLX fp16，只给 30 个文档 | 进程内 100 次 | 295 | 324 | — |

- 推理加锁串行（MLX 非线程安全、只有一块 GPU）⇒ 3 个请求同时到，第 3 个要等约 1.5s，**会略超 1500ms 超时、那一次退回粗选**。线上约 1 次/分钟，并发少见；真成问题再考虑 `AI_RERANK_POOL_K=30`（要先重测召回）或 CoreML/ANE。
- 打分一致性：MLX fp16 vs torch fp32 CrossEncoder 最大差 0.0013；mini 服务 vs Windows 服务同 10 组输入：top-1 10/10 一致、top-10 集合 10/10 一致、分数最大差 0.0024。

### 切换前后各 1 小时（2026-11-15）

切换时刻 **2026-11-15 20:50:44**（47 `.env.prod` 备份 `.env.prod.bak.20261115-205044-rerank-mini`，只重启 `cretas-python`，9s 后 `/health` healthy）。
活进程核对：MainPID 及 3 个子进程的 `/proc/<pid>/environ` 均为 `AI_RERANK_URL=http://127.0.0.1:18191/rerank`、`AI_RERANK_TIMEOUT_MS=1500`。

| 窗口 | 成功（重排服务侧） | 回退（47 `python-prod.log` 的 `rerank failed` / `rerank: HTTP` / `rerank: bad`） |
|---|---|---|
| 切换前 19:50–20:50（Windows） | ≈1（Windows `service.log` 200 行数 19:43=9926 → 20:50=9927；该日志无时间戳，只能按差值） | 0 |
| 切换后 20:50–20:51（mini） | 1（n=50） | 0 |

切换后跑 `ai_live_probe.py --no-write`：两入口 12/12 PASS，rc=0（20 次调用里只有 1 次走重排，其余带历史或精确命中，符合设计）。
线上这个时段流量很低（每小时个位数），计数只能证明「接上了、没回退」，不能说明高峰表现。

## 已知限制

- **3 个请求同时到时 p95 ≈ 1.53s，略超 `AI_RERANK_TIMEOUT_MS=1500`**：MLX 推理串行，第 3 个排队约 1.5s，那一次退回 LLM 粗选（识别不中断，只是贵一点）。Steve 2026-11-15 接受（「慢一点无所谓」）。若回退计数变多：调大超时到 2000、`AI_RERANK_POOL_K=30`（要先重测召回），或切回 Windows。
- 单次（不并发）比 Windows 慢约 0.3s（从 47 测 p50 526 vs 219ms）。

## 文件

| 文件 | 作用 |
|---|---|
| `mini/app_mlx.py` | mini 主用服务：`GET /health`、`POST /rerank`（只监听 127.0.0.1:18190），接口与 `app.py` 逐字相同 |
| `mini/mlx_xlmr.py` | XLMRobertaForSequenceClassification 的最小 MLX 实现，直接读 HF safetensors |
| `mini/com.cretas.rerank.plist` / `mini/com.cretas.rerank-tunnel.plist` | launchd 单元（装到 `~/Library/LaunchAgents/`） |
| `mini/rerank-tunnel.sh` | 反向隧道 47:127.0.0.1:18191 → mini:127.0.0.1:18190 |
| `app.py` | Windows 备份服务（torch cuda） |
| `start-rerank.ps1` | Windows 计划任务入口，起 uvicorn + frpc。⚠️ **必须保持纯 ASCII**：PowerShell 5.1 把无 BOM 的 UTF-8 当 ANSI 读，中文注释会吞掉下一行 |
| `setup_frp_on_47.sh` | 在 47 上生成 Windows 那条 FRP 两端配置（`/opt/frp/rerank/`）并装 visitor 的 systemd 单元；幂等，不换密钥 |
| `reset_frps_on_47.sh` | 2026-10-04 重设 47 上 frps 的 token。**只在需要轮换 token 时跑**，跑完要重跑 `setup_frp_on_47.sh` 并把新的 `win-frpc.toml` 拷回本机 |

不进仓库：`model/`（微调权重，sha256 `9ba4b534…`）、`.env`、`venv/`、`frpc.exe`、`frpc.toml`（含 token 与 STCP 密钥）、日志。

## 切换 / 回滚（47 上，root）

```bash
# 0. 两条通道都应在线 (任意一条不通就别切过去)
curl -s -m 3 127.0.0.1:18191/health   # mini
curl -s -m 3 127.0.0.1:18190/health   # Windows
# 1. 改地址 (先备份); 切回 Windows 就把 18191 换成 18190
cp -p /www/wwwroot/cretas/.env.prod /www/wwwroot/cretas/.env.prod.bak.$(date +%Y%m%d-%H%M%S)
sed -i 's#^AI_RERANK_URL=.*#AI_RERANK_URL=http://127.0.0.1:18190/rerank#' /www/wwwroot/cretas/.env.prod
# 2. 只重启 Python (env 只在启动时读); 避开 Java 发布窗口
systemctl restart cretas-python && sleep 15 && curl -s -m 5 127.0.0.1:8083/health
# 3. 核对: 之后 python-prod.log 里 "rerank failed" 不该持续增长; 被选中的那台服务日志里有新请求
grep -c "rerank failed" /www/wwwroot/cretas/python-prod.log
```

Windows 备份要能接上：开机并登录（计划任务登录时起服务 + frpc）。mini 那条停用：
`launchctl bootout gui/$(id -u)/com.cretas.rerank-tunnel`（及 `com.cretas.rerank`），恢复用 `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/<名>.plist`。

## 从零部署到一台新 Mac

1. `python3.11 -m venv ~/rerank-service/venv && venv/bin/pip install "torch==2.9.*" "sentence-transformers==5.1.*" "transformers==4.57.*" "fastapi==0.115.*" "uvicorn==0.32.*" "mlx==0.29.*"`（zsh 下版本号要加引号）。
2. 拷权重到 `~/rerank-service/model/`（核 sha256），拷 `mini/` 下的 py 与 sh；`.env` 写 `RERANK_KEY=<与 47 AI_RERANK_KEY 同值>`、`MODEL_DIR=`、`MODEL_TAG=`，`chmod 600`。
3. 47：在对应受限账户的 `PermitListen` 与 `permitlisten` 里加回环端口，`sshd -t` 后 `systemctl reload sshd`（已有隧道不断）。
4. 两个 plist 放 `~/Library/LaunchAgents/`，`plutil -lint` 后 `launchctl bootstrap gui/$(id -u) <plist>`；从 47 `curl 127.0.0.1:<端口>/health` 通。

## 失败时会怎样

服务不在线 / 超时（`AI_RERANK_TIMEOUT_MS`，现为 1500）/ 返回异常：编排器退回 LLM 粗选，`timingMs.rerankFallback=1`，
47 `python-prod.log` 记一行 `ai.matcher.rerank - WARNING - rerank failed`，识别不中断。
带对话历史的轮次本来就不走重排（`rerankSkippedHistory=1`），因为重排只看这一句。
