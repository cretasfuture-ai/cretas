# 本地模型兜底：加拿大 Mac mini（qwen3:8b）

**建立**：2026-11-03（Steve 裁定「换模型，用 aurora 或者加拿大 Mac mini 的模型」，随后选定加拿大那台；aurora 不接本地模型）
**一句话**：文本槽（CHAT / SIMPLE_TEXT / CHART / MAPPER / INSIGHTS / REVIEW / REASONING）链尾追加一跳 `local_canada / qwen3:8b`。前面三个阿里云账号都不可用时才轮到它。

## 1. 数据不出我们自己的机器（保密）

- 请求路径：47 上的 Python 服务 → 47 本机回环 `127.0.0.1:11437`（只有 SSH 反向隧道占着这个端口）→ 加拿大 Mac mini 回环 `127.0.0.1:11500`（鉴权代理）→ 本机 ollama `127.0.0.1:11434`。
- 全程不经过任何第三方推理服务：提示词、上下文、输出只在 47 与加拿大 Mac mini 之间走我们自己的 SSH 隧道。这一点对工艺保密是加分项——云端 qwen3.8-flash 是外部服务，本地模型不是。
- 没有计费面：不调用任何付费端点。机器离线时请求在连接层失败，不会落到别处。
- ⛔ 不向公网开放任何端口，⛔ 没有改 47 的阿里云安全组：
  - 47 侧只新增一个仅限回环的 `PermitListen 127.0.0.1:11437`；
  - 加拿大侧代理只监听 `127.0.0.1`，要 bearer token，只放行 `POST /v1/chat/completions`、`GET /v1/models`、`GET /health`，且只接受模型名 `qwen3:8b`。

## 2. 机器离线 / 隧道断了会怎样

- 链末一跳连接失败 → `call_chain` 抛 `LLMChainExhausted`，错误里逐条列出每一跳原因（本跳形如 `local_canada/qwen3:8b: ConnectError`）。**明确失败，不静默降级，不返回假数据。**
- 阿里云账号有可用的，本地这一跳根本不会被调用，机器离线对它们零影响。
- 没配 `LLM_LOCAL_CANADA_API_KEY` ⇒ 该跳 `empty_api_key`，不发请求，也不会拿别的 key 顶替。
- 恢复：机器开机、登录后 launchd 自动拉起代理和隧道（`KeepAlive`），代理每 60 秒重申模型常驻，无需人工操作。判活：`ssh canada-mini 'launchctl list | grep cretas.llm'` 应有两行；47 上 `curl -s -m5 127.0.0.1:11437/v1/models` 返回 401（有鉴权）说明隧道通，不是 000/拒绝连接。

## 3. 组成

| 位置 | 内容 |
|---|---|
| 加拿大 Mac mini（`ssh canada-mini`，用户 rex） | ollama（Ollama.app 登录项，`127.0.0.1:11434`），模型 `qwen3:8b`（Q4_K_M，5.2 GB）；`~/cretas-llm/`：`proxy.py`、`token`（0600）、`tunnel_ed25519`（专用密钥）、`known_hosts` |
| launchd | `com.cretas.llm-proxy`（代理）、`com.cretas.llm-tunnel`（反向隧道），均 `RunAtLoad` + `KeepAlive`；参考副本见本目录 `*.plist.example`、`tunnel.sh`、`proxy.py` |
| 47 | 用户 `cretas-llm-canada`（nologin，家目录 `/var/empty`）；sshd `Match User` 段（`PermitListen 127.0.0.1:11437`、`PermitOpen none`、不给 shell）；公钥文件 `/etc/ssh/cretas-llm-canada/authorized_keys`（`restrict,port-forwarding,permitlisten=…`）；`.env.prod` 里 `LLM_LOCAL_CANADA_BASE_URL` / `LLM_LOCAL_CANADA_API_KEY` |
| 代码 | `backend/python/common/llm_router.py`：`_SAFE_MODELS` / `_MINIMAL_SAFE_SET` / `_TEXT_TAIL` / `_ABSOLUTE_LAST` / `_provider_config` 里的 `local_canada` |

备份：改 47 前存过 `/etc/ssh/sshd_config.bak-llm-20261103`、`/www/wwwroot/cretas/.env.prod.bak-llm-20261103`。

## 3b. 两条通路（推理 / 管理）与 tailscale 断了怎么办

- **推理通路（47 → 本地模型）不依赖 tailscale**：加拿大 mini 主动向 47 的公网 SSH（22 端口）发起出站连接建反向隧道，47 只在回环上收。tailscale 断了推理照常，只要 mini 能出网。
- **管理通路（人 → mini）有两条**，指向同一台 `Mac-mini-01.local`：
  - `ssh canada-mini`：走 tailscale（`100.85.234.108`）。
  - `ssh canada-mini-backup`：tailscale 用不了时用。`~/.ssh/config` 里是 `HostName 127.0.0.1 / Port 22026 / ProxyJump aliyun`；`aliyun` 别名是 **139 网关**（`139.196.165.140`，不是 47），22026 是 139 回环上的反向隧道，由 mini 上 root 的 launchd 进程（`/var/db/canada-ssh-fallback/` 密钥）发起。
  - 切换：`canada-mini` 超时（`Connection timed out`）就换 `canada-mini-backup`；两条都不通才是 mini 本机断网/关机，需要人到场。
- ollama 不在 PATH：它是 **Ollama.app**（登录项），命令行在 `/Applications/Ollama.app/Contents/Resources/ollama`（`ollama pull` 用这个全路径）。

## 4. 为什么是 qwen3:8b

2026-11-03 合成提示词（10 条意图判定 + 10 条简短分析，不含任何客户数据），同台对照：

| 模型 | 意图 | 分析 | 首 token 中位 | 吞吐 |
|---|---|---|---|---|
| 阿里云 qwen3.8-flash（经 47） | 10/10 | 9/10 | 0.5 s | ~50 tok/s |
| 加拿大 qwen3:8b | 10/10 | 9/10 | 0.1–0.2 s | ~49 tok/s |
| 加拿大 qwen3:14b | 10/10 | 7/10 | 0.3 s | ~28 tok/s |
| 加拿大 qwen3.8:27b Q4 | 10/10 | 9/10 | 0.7–0.9 s | ~22 tok/s |

准确率判定粗（标签 / 关键数字子串，每类仅 10 条），只说明「作为兜底够用」，不说明「能当主力」。并发 2：ollama 默认单并行，第二个请求排队（8b：0.26 s / 0.44 s）。

## 5. 以后怎么换模型

1. 在加拿大 mini 上 `ollama pull <新模型>`（先确认内存：64 GB，目前几乎全空）。
2. 改 `~/cretas-llm/proxy.py` 的 `CRETAS_LLM_MODEL`（或在 plist 里加该环境变量），`launchctl kickstart -k gui/$(id -u)/com.cretas.llm-proxy`；代理会把新模型钉住常驻。
3. 改代码里 `local_canada` 的模型名（`_SAFE_MODELS` / `_MINIMAL_SAFE_SET` / `_TEXT_TAIL` / `_ABSOLUTE_LAST` / `_REGISTRY_AUDIT_PROBE` / 冻结表 / golden 链文件）→ 走 PR；用合成提示词重跑 `tests` 里的基准对照后再换。
4. 换 token：`openssl rand -hex 32 > ~/cretas-llm/token`（0600）并同步 47 的 `LLM_LOCAL_CANADA_API_KEY`，两边都重启（代理 kickstart，Python 服务重启）。

## 6. 周期复审

注册表每 21 天复审一次（`_REGISTRY_AUDIT_DATE`）。本条目会被每日探针 `scripts/cron/probe-llm-registry.sh`（47 crontab，每天 03:17 CST）一并探测，经隧道真请求一次；非 ok 即进告警。

## 出境 SOCKS 出口：OpenAI / Anthropic 经 mini 出网（2026-11-12，LLM-PROVIDER-SWITCH）

47 在国内，直连两家都不行：`api.anthropic.com` 返回 403（地区封锁），`api.openai.com` 超时。所以在 mini 上开一个出口，两家的请求从这里出去。

- **47 侧（organizer 已改，已备份）**
  - `Match User cretas-llm-canada` 的 `PermitListen` 改为 `127.0.0.1:11437 127.0.0.1:11438`。
  - `authorized_keys` 加了 `permitlisten="127.0.0.1:11438"`。
  - 其余不变：`AllowTcpForwarding remote`、`PermitOpen none`、`GatewayPorts no`。
- **mini 侧**（2026-11-12 已装好并验证）：独立的 launchd 任务 `com.cretas.llm-egress`，跑 `~/cretas-llm/egress-tunnel.sh`。
  - 它做一次反向转发 `-R 127.0.0.1:11438:127.0.0.1:7897`：47 回环上的 11438 → mini 上 Clash Verge 的混合端口 7897（SOCKS 和 HTTP 都收）。
  - **不用动态转发的原因**：mini 自己的出口 IP 也被这两家挡。实测 mini 直连 `api.openai.com` 超时，`api.anthropic.com` 返回 403 "Request not allowed"。mini 平时是经 Clash 上网的（系统代理就是 127.0.0.1:7897），而 SSH 动态转发不走 Clash，会被同样挡掉。
  - ⇒ **这条出口依赖 mini 上的 Clash Verge 在跑**，出站走的是 Clash 当前的节点。请求是 TLS 端到端加密的，节点看不到内容。
  - 用户和密钥与 11437 的隧道相同。
  - **单独开一个进程的原因**：`tunnel.sh` 带了 `ExitOnForwardFailure`。如果把 11438 合进去，11438 被拒时 11437 会一起断，本地模型也就没了。
  - 两个文件的参考副本：`local-llm-canada-mini/egress-tunnel.sh`、`com.cretas.llm-egress.plist.example`。
- **验证读数**（2026-11-12，在 47 上用 `curl --socks5-hostname 127.0.0.1:11438` 测）：
  - 连通：两家都返回 401（没带密钥），3 轮各 0.47–0.55 s。
  - 断线：停掉 egress 任务后，47 上 11438 没有人监听，curl 立刻失败（rc=7，0.0001 s），明确报错，不会挂死。这时 11437 不受影响（返回 401，0.07 s）。
  - 恢复：重新 bootstrap 后再测，401，0.53 s。
- **应用 / 重装**（在 mini 上，以 rex 执行）：

  ```
  cp egress-tunnel.sh ~/cretas-llm/
  chmod 700 ~/cretas-llm/egress-tunnel.sh
  cp com.cretas.llm-egress.plist.example ~/Library/LaunchAgents/com.cretas.llm-egress.plist
  launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.cretas.llm-egress.plist
  ```

- **服务配置**：`.env.prod` 里设 `LLM_EGRESS_PROXY=socks5h://127.0.0.1:11438`。只有 openai 和 anthropic 两家走它，见 `common/llm_router.py` 的 `_EGRESS_PROXY_ACCOUNTS`。
- **判活**（在 47 上）：

  ```
  curl -s -o /dev/null -w '%{http_code} %{time_total}\n' --socks5-hostname 127.0.0.1:11438 https://api.openai.com/v1/models
  ```

  - 返回 `401`：连通，只是没带密钥。
  - 换成 `https://api.anthropic.com/v1/models` 同样判。
  - 返回 `000` 并且 `curl: (7)`（连接被拒）：隧道断了。这时请求会明确报错，不会挂死。
