# Luna 出境出口：三层探针、告警、第二条出口（2026-11-12，INTENT-DISTILL-20261111）

Luna（OpenAI Decisions，线上意图决策）只经 `LLM_EGRESS_PROXY` 出境。这条出口原来是单点；本文记录探针、告警和备用出口。
这条出口只给决策调用用：`_EGRESS_PROXY_ACCOUNTS` 仍然只有付费两家，生成链默认不含它们，本次没有让任何别的流量经过它。

## 1. 两条出口

| 名字 | 47 上的端口 | 路径 | 依赖 |
|---|---|---|---|
| `primary` | `127.0.0.1:11438` | 加拿大 mini `com.cretas.llm-egress` 反向隧道 → mini 上 Clash Verge `7897` → 代理节点 → OpenAI | 加拿大 mini、Clash、节点 |
| `fallback1` | `127.0.0.1:11439` | 美国 Mac mini aurora `com.cretas.llm-egress-us` 反向隧道 → aurora `127.0.0.1:17897`（`egress-socks-allowlist.py`，只放行 `api.openai.com:443`）→ 直连 OpenAI | aurora（美国 IP，直连 OpenAI 不被拒，不用 Clash） |

两条只共用 47 自己。aurora 上的东西都是本线新建的（`~/cretas-llm-egress/`、两个 LaunchAgent），没有用它以前做 CI runner 时的任何配置。

为什么备用出口前面要加一个白名单 SOCKS，而不是直接 `ssh -R 11439` 动态转发：动态转发不能限制目的地，47 上任何本机进程都能借它打到 aurora 的 `127.0.0.1` 和局域网。现在只放行 `api.openai.com:443`，其它目的地一律拒绝（实测见第 4 节）。要放行 Anthropic，在 LaunchAgent 里加 `EGRESS_SOCKS_ALLOW=api.openai.com:443,api.anthropic.com:443`。

## 2. 三层探针（`backend/python/common/llm_egress_health.py`）

每条出口每轮量三层，不花 token：

| 层 | 怎么量 | 断了说明 |
|---|---|---|
| `tunnel` | 47 本机 TCP 连代理端口 | 反向隧道没了（mini/aurora 掉线、重启、launchd 没拉起） |
| `proxy` | SOCKS5 握手 `05 01 00` → `05 00` | 隧道在，远端代理没在跑（Clash 停了 / 白名单 SOCKS 停了）。SSH `-R` 的监听由 47 上的 sshd 持有，远端死了 TCP 照样连得上，所以这一层必须单独量 |
| `upstream` | 经代理 `GET https://api.openai.com/v1/models`（带 key，列模型不计费） | 节点坏了 / 地区被封（403）/ key 失效（带 key 的 401） |

服务内：每个 uvicorn worker 起一个循环（`main.py` lifespan），用 flock 选一个 worker 探，状态写共享文件，其它 worker 只读 ⇒ 所有 worker 用同一条出口。默认每 15 秒一轮。

## 3. 切换

- 当前出口连续 `LLM_EGRESS_SWITCH_AFTER`（默认 2）轮不通，且别的出口这一轮通 → 切过去。
- 主出口恢复后连续 `LLM_EGRESS_FAILBACK_AFTER`（默认 3）轮通 → 切回主。
- 手动开关：`LLM_EGRESS_FORCE=primary` / `fallback1`（改 `.env.prod` 后重启 cretas-python）。
- 两条都不通时不切；各自有 DOWN 告警。
- 切换时代理客户端按新 URL 重建，旧连接池异步关闭。

## 4. 实测（2026-11-12，47 上，本分支代码以 overlay 方式运行，临时目录事后删除）

**探针阳性/阴性**（`python -m common.llm_egress_health`）：

| 场景 | tunnel | proxy | upstream | rc |
|---|---|---|---|---|
| 主出口（线上） | ok | ok 0.044s | ok 200 1.61s | 0 |
| 没有监听的端口 11499 | refused | skipped | skipped | 1 |
| 端口在但不是 SOCKS（11437 本地模型隧道） | ok | timeout | skipped | 1 |
| aurora 隧道已起、白名单 SOCKS 未起（= Clash 停了的形状） | ok | conn_error | skipped | 1 |
| 备用出口（aurora） | ok | ok 0.374s | ok 200 5.79s（首次） | 0 |
| `LLM_EGRESS_PROXY` 为空 | — | — | — | 2 |

**白名单阴性**：经 11439 打 `127.0.0.1:22`、`api.anthropic.com:443`、`example.com:443` 均被拒（aurora 日志各一行 `DENY`），`api.openai.com` 返回 401（未带 key）。

**`/v1/models` 延迟（curl，各 5 次，带 key，200）**：主出口 total 1.51–2.76s；备用出口 3.17–5.20s（connect 0.6–1.2s）。

**真实 Luna 请求延迟**（`call_decision_choice`，线上同一入口；合成内容；租户上下文 F006；每条出口 10 次，共 20 次，20/20 成功）：

| 出口 | 首次（冷连接） | 之后 9 次 p50 | 最小 / 最大 |
|---|---|---|---|
| 主出口（mini Clash） | **5.84s** | 0.65s | 0.46 / 0.75s |
| 备用出口（aurora） | 3.15s | 0.78s | 0.56 / 1.44s |

⚠️ 冷连接的第一次接近 `call_decision_choice` 的默认超时 6 秒。进程重启后、连接池空闲 30 秒过期后的第一次 Luna 请求有超时风险。这一条只记读数，本线没有改超时或保活（决策逻辑不在本线范围）。

**切换演练**（47 上起一个转发口 11498 → 真主出口 11438；「坏」= 转发口接了就关，与 Clash 停了同形；跑的是服务里同一个后台循环；探针间隔 10 秒）：

- 坏掉 → 当前出口变成备用：**31.5 秒**（2 轮失败 + 轮询粒度）
- 恢复 → 切回主出口：**45.1 秒**（3 轮成功）
- 告警文件 4 行：`DOWN`（first_down=proxy）、`SWITCH primary→fallback1`、`RECOVERED`、`SWITCH fallback1→primary`
- `/metrics`：`llm_egress_switch_total` 两条、`llm_egress_active{egress="primary"} 1`、`llm_egress_probe_last_run_timestamp_seconds` 在前进

按默认间隔 15 秒推算，切到备用约 30–45 秒，切回约 45–60 秒。

## 5. 告警到哪里（缺口照实写）

仓库里**没有推送渠道**：没有 webhook（2026-09-06 owner 裁定等企微/钉钉机器人）。2026-11-12 起 `scripts/cron/alert-digest.sh` 已进 47 root crontab（每日 09:00，`ALERT_SINK=stdout` → `logs/alert-digest.cron.log`），`--check-state` 每 10 分钟跑（→ `logs/llm-egress-check.cron.log`）。所以现在能保证的是：

1. `/metrics`：`llm_egress_probe_up{egress,layer}`、`llm_egress_consecutive_failures`、`llm_egress_active`、`llm_egress_switch_total`、`llm_egress_probe_instrument_dead_total`、`llm_egress_probe_last_run_timestamp_seconds`。
2. 状态文件 `/www/wwwroot/cretas/logs/llm-egress-state.json`：`last_run` 每轮必更新。
3. 告警文件 `/www/wwwroot/cretas/logs/llm-egress-alerts.log`：只在状态变化时写一行。文件名含 `alert`，`alert-digest.sh` 自动发现。实测：把演练产生的告警文件交给 `alert-digest.sh`，rc=1，4 行全部进了 digest。
4. journal：固定前缀 `LLM_EGRESS_ALERT {json}`（WARNING）。
5. 服务外的「探针还活着吗」：`python -m common.llm_egress_health --check-state`，状态文件不在或超过 `max(120s, 5×间隔)` 没更新 ⇒ rc=2，并往告警文件写一行 `LLM EGRESS STALE`。

要做到「有人一定知道」，还差一个推送渠道（webhook）：给了 URL 后把 crontab 那行改成 `ALERT_SINK=webhook ALERT_WEBHOOK_URL=…`。

## 6. 服务器侧改动（2026-11-12）

47：
- 新增系统用户 `cretas-llm-aurora`（nologin，home `/var/empty`）。
- 新增 `/etc/ssh/cretas-llm-aurora/authorized_keys`：`restrict,port-forwarding,permitlisten="127.0.0.1:11439"` + aurora 的专用公钥。
- `/etc/ssh/sshd_config` 末尾追加 `BEGIN/END CRETAS LLM AURORA EGRESS 20261112` 块（`Match User cretas-llm-aurora`，`AllowTcpForwarding remote`，`PermitListen 127.0.0.1:11439`，`PermitOpen none`，`PermitTTY no`）。备份 `/etc/ssh/sshd_config.bak.20261112210111`；`sshd -t` 通过后 `systemctl reload sshd`。原 `cretas-llm-canada` 块与 11435/11437/11438 未动（reload 后三个端口仍在监听）。

aurora（`aurora@100.92.5.62`）：
- `~/cretas-llm-egress/`（700）：`tunnel_ed25519`（新生成，专用）、`known_hosts`（47 的 ED25519 指纹 `SHA256:SkznXpeGz/96C73XW27BrzN01w2P/jjDiCjl4NuC8To`，与 47 本机读数一致）、`egress-tunnel-us.sh`、`egress-socks-allowlist.py`、两个 `.err` 日志。
- `~/Library/LaunchAgents/com.cretas.llm-egress-us.plist`、`com.cretas.llm-egress-socks.plist`（KeepAlive）。
- 没有改 aurora 上已有的 `com.cretas.f006-aurora-*`、`com.cretas.ci-primary`。

回滚：aurora 上 `launchctl bootout gui/$(id -u)/com.cretas.llm-egress-us` 和 `…-socks`；47 上删 sshd 块（或 `cp -p /etc/ssh/sshd_config.bak.20261112210111 /etc/ssh/sshd_config`，前提是此后没人再改过它）、`sshd -t`、`systemctl reload sshd`、`userdel cretas-llm-aurora`、删 `/etc/ssh/cretas-llm-aurora/`。

## 7. 未做 / 已知限制

- aurora 重启后自恢复：LaunchAgent 需要 aurora 用户登录会话；未实测整机重启。
- 备用出口比主出口慢（`/v1/models` 约慢 1.5–2.5 秒，Luna 热连接 p50 慢 0.14 秒，冷连接反而快）。
- 探针只探 OpenAI；Anthropic 不在决策路径上，没探。
- 推送渠道未接（见第 5 节）。
