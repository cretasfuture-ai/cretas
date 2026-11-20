# Aurora 图表规划网关

工厂问数默认使用 Rex 的 `local_canada / qwen3:8b`。后台调用可通过 `conversation.plan(..., background=True)` 选择专用 Aurora 网关的 `aurora_chart / qwen3:14b`。专用后台进程也可以设置 `SMARTBI_CHART_PLANNER_BACKGROUND=1`，让其未显式指定通道的规划调用选用 Aurora。两档分别缓存规划结果，模型超时或无法返回合格目录 ID 时使用 #2001 的确定性规则选图。图表规划只向本地通道发请求。

## 预算与启用

用六膳门黑湖 12 道验收问题和 40 项图表目录进行串行基准。目录包含 28 个由产品图表引擎生成的黑湖候选和 12 个合成的业务候选。直接从 `conversation.plan()` 捕获提示词与真实 `content_validator`。Aurora 使用 Ollama 0.34.4，模型逐题释放，延迟量自 Aurora 的本机请求。Rex 使用现有模型驻留策略，延迟量自 47 的 11437 代理请求，包含现有隧道开销。两侧使用相同输入、关思考与 JSON 约束。首 token 以第一段可见正文为准，p95 按最近秩计算。校验通过表示选择了合格目录中的 ID，实际题意和答案准确率需要另测。

| Ollama tag | 样本 | 总耗时 p50 / p95 | 首 token p50 / p95 | 校验通过 | 6 秒内有效 / 12 秒内有效 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora `qwen3:8b` | 12 | 23.804 / 26.550 秒 | 18.690 / 20.527 秒 | 12/12 | 0/12 / 0/12 |
| Aurora `qwen3:14b` | 12 | 44.748 / 46.300 秒 | 35.409 / 36.175 秒 | 12/12 | 0/12 / 0/12 |
| Aurora 26.9B（缩小上下文和批次） | 12 | 81.098 / 188.902 秒 | 67.814 / 72.296 秒 | 11/12 | 0/12 / 0/12 |
| Rex `qwen3:8b` | 12 | 11.434 / 54.907 秒 | 8.995 / 37.462 秒 | 12/12 | 0/12 / 9/12 |

| 规划通道 | 单次模型请求 | 路由整链 | 规划截止 |
| --- | ---: | ---: | ---: |
| Rex 8B 主通道 | 15 秒 | 18 秒 | 20 秒 |
| Aurora 14B 后台通道 | 52 秒 | 56 秒 | 60 秒 |

Rex 预算采用第三轮任务卡指定的 12.2 秒 p95 参考口径。上表第一轮 Rex 全样本也观测到 54.907 秒的尾延迟。Aurora 14B 第一轮 p95 为 46.30 秒。主通道到时直接采用规则选图。规划器及路由均应用对应预算，部署前还需实测独立隧道的端到端延迟。

14B 裸 Ollama 基准曾测到系统空闲内存 34%，低于 F006 的 50% 准入线。图表路由在发送前检查 F006 的主机 SQLite 租约，等待期间每 200 毫秒复查。图表网关也检查 F006 套接字与网关状态。轮询和释放模型均有时间窗，启用前需在启动、预填充和生成三个阶段实测 F006 优先行为与图表完成率。环境变量缺省时，工厂问数使用规则选图，缺少 Aurora 配置每进程告警一次。

复测入口为 `python backend/python/tests/benchmark_aurora_chart.py --model qwen3:14b`。`--rex-list` 只读取 47 代理已公布的 tag，`--rex` 测现有 Rex 隧道，`--rex-direct` 测 Rex 本机原生 Ollama。Rex 试验必须在 CI 空闲且没有其他模型驻留时执行。Aurora 每题发出前核对 47 的 F006 SQLite 锁和 11435 连接，再在 Aurora 检查网关状态、内存、Ollama 驻留状态，并从首字节前监测 11438 连接。出现占用或压力异常时停止。第二轮 27B 仅安排在 Rex。

表中的 Aurora 26.9B 是第一轮 Ollama 对照 `pomelox-eval-qwen38-gsq-iq3s:20260910`（IQ3_S），当时使用 `num_ctx=4608` 和 `num_batch=32` 压低内存。逐题状态和完整统计以任务回执为准。

## 通道

47 Python → `127.0.0.1:11440/v1` → 独立 SSH 反向转发 → Aurora `127.0.0.1:11501` → Aurora Ollama `127.0.0.1:11434/api/chat`。

- 47 已占用的回环端口为 11435（F006）、11437（Canada 模型）、11438（Canada SOCKS）与 11439（Aurora SOCKS）。勘察时 11440 未监听，Aurora 11501 启用前再检查。两端均使用 IPv4 回环。
- F006 使用 47:11435 → Aurora:11438 和独立原生 27B。图表网关仅在 F006 空闲、11438 无已建立连接、系统内存压力正常且空闲内存至少 65% 时发起推理。单并发，无预热，完成后释放 Ollama 驻留。请求期间如 F006 开始连接、状态变化、内存压力异常、空闲内存低于 15% 或图表客户端断开，关闭图表推理连接。图表路由只读取生产进程所在 Linux 命名空间的 `/proc/locks` 和 F006 租约文件元数据。
- 图表网关只提供短 JSON 完整响应，使用专用 bearer token。上游只允许固定模型和文本消息。F006 租约导致的让路和图表网关 429 均不计入图表路由熔断。请求正文、响应正文和凭据不进入网关日志。

## 后续配置步骤

1. 在 47 为 `cretas-aurora-chart` 创建专用 nologin 账户与独立授权文件。仅授权 Aurora 本机生成的公钥，前缀用 `restrict,port-forwarding,permitlisten="127.0.0.1:11440"`。用生产 sshd 实际生效的位置放置以下约束，先通过 `sshd -t` 和带用户上下文的 `sshd -T -C`，再 reload。

   ```text
   Match User cretas-aurora-chart
       AllowTcpForwarding remote
       AllowStreamLocalForwarding no
       GatewayPorts no
       PermitListen 127.0.0.1:11440
       PermitOpen none
       PermitTTY no
       AllowAgentForwarding no
       X11Forwarding no
       PermitTunnel no
       ForceCommand /usr/sbin/nologin
   ```

2. 在 Aurora 为 `aurora` 用户准备 `~/.config/cretas/aurora-chart/`，目录权限 0700。用已核验的 47 SSH 主机公钥生成此目录的 `known_hosts`，将专用私钥保存在 `tunnel_ed25519`，权限 0600。另生成 32 字节随机 token 的 64 位十六进制表示，保存在权限 0600 的 `token` 文件，通过现有凭证渠道同步同一 token 给 47 Python。
3. 先用专用进程运行 `/opt/homebrew/bin/python3 /path/to/aurora_chart_gateway.py --token-file /Users/aurora/.config/cretas/aurora-chart/token`，然后用 `/bin/bash /path/to/aurora_chart_tunnel.sh` 建立反向转发。验证通过后可由两个独立 LaunchAgent 以原参数保持运行。
4. 在 47 运行 `python3 scripts/ops/aurora_chart_check.py --token-file /path/to/private-token`，核对独立回环监听、健康、访问凭证及允许的模型。分别核对 SSH 专用账号的 shell、其它监听端口及本地转发均被拒绝。推理验收必须走带 F006 租约检查的产品路由。使用合成请求核对完整 JSON 和 usage，并覆盖 F006 租约已占用、图表推理中到来的 F006 请求、图表超时后规则选图。
5. 通过验证与预算验收后，在 47 的受管 Python 环境设置 `LLM_AURORA_CHART_BASE_URL=http://127.0.0.1:11440/v1` 和 `LLM_AURORA_CHART_TOKEN`。路由经共享脱敏出口发送请求。若 Python 进程配置了通用 HTTP 代理，启动时的 `NO_PROXY` 必须包含 `127.0.0.1`，否则路由跳过 Aurora。通用模型登记探针不向此图表网关发推理。使用现有 Python 发布入口启用。

停用时先撤掉 Python 的 Aurora 配置并验证规则选图，然后停止专用图表隧道与网关，撤销此账户的公钥和 sshd 约束。回读 47 上 11440 消失、原有 11435/11437/11438/11439 回环监听和 F006 健康。
