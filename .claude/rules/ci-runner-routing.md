# CI runner 路由：默认托管，额度耗尽切自建

**最后更新**: 2026-08-27
**触发事故**: 2026-08-26 组织 Actions 免费额度 2000 分钟用尽，所有 job **2~4 秒、0 个 step、无日志**地失败，横跨 `main` 与各 feature 分支。

⚠️ 本文件在 `.claude/rules/` 与 `.codex/rules/` 各有一份，**必须逐字相同**（见文末闸）。

---

## 一句话

**默认走托管 runner。额度耗尽时，设一个组织变量切到加拿大 Mac mini；额度恢复，删掉它。**
⛔ 任何时候都**不要**去改 workflow 的 `runs-on`。

```bash
# 额度耗尽 → 切到自建
gh variable set CI_RUNNER --org cretasfuture-ai --body '["self-hosted","cretas"]'

# 额度恢复(每月 1 号) → 切回托管
gh variable delete CI_RUNNER --org cretasfuture-ai

# 当前走哪条
gh variable list --org cretasfuture-ai | grep CI_RUNNER || echo "未设置 = 走托管"
```

工作流里长这样，三个闸一致：

```yaml
runs-on: ${{ vars.CI_RUNNER && fromJSON(vars.CI_RUNNER) || 'ubuntu-latest' }}
```

已接入：`secret-regression.yml` / `web-admin-gate.yml` / `rn-gate.yml`。
其余 12 个工作流仍写死 `ubuntu-latest`，要接就照抄这一行。

---

## 为什么默认是托管，而不是自建

实测耗时（托管，51 个成功样本的中位数）：

| 闸 | 托管 | 自建 Mac mini |
|---|---|---|
| Web Admin Gate | **12.3 分** | ~1.5 分（缓存热时）|
| RN Gate | **4.0 分** | ~1 分 |
| Secret gate | **1.0 分** | 21 秒 |

⚠️ 自建**冷启动**要另算：checkout 工作树 1.7G ≈ 1.5 分钟。
更重要的是托管提供**干净机器 + 第三方执行**的独立性，自建给不了。
⇒ 有额度就用托管，自建是兜底。

---

## 自建 runner 是什么

| | |
|---|---|
| 机器 | 加拿大 `Mac-mini-01`（M4 Pro，14 核 / 64GB / 1.7TB）|
| 连接 | Tailscale `100.85.234.108`；SSH 别名 `canada-mini`（用户 `rex`）|
| ⚠️ 密钥 | 密码在 **MacBook 的 macOS 钥匙串**里，本机没有 ⇒ **必须经 `codex-mac` 中转** |
| 服务 | `launchd`，开机自启，7×24 |
| 标签 | `self-hosted, cretas, macOS, ARM64` |

⛔ **ProxyJump 直连会卡死**（钥匙串要交互解锁，非交互式 SSH 过不去，实测挂满 10 分钟）。
正确姿势：`ssh codex-mac "ssh canada-mini '...'"`，且**脚本落盘再执行**，不要多层引号嵌套。

---

## ⛔ 别再试的三条路（都实测堵死）

| 载体 | 死因 |
|---|---|
| 阿里云 139 / 47 | **git 长连接连不动** —— 同机 `curl codeload` 5MB/s，而 `git clone --depth 1` 连 10MB 小仓都超时（`fetch-pack: unexpected disconnect`）。`HTTP/1.1` / `postBuffer` / `--filter=blob:none` 三种绕法全败 |
| 本机 WSL2 | **启动后 46 秒自动关机**（Windows 侧无进程持有该发行版时，WSL 就关；systemd 后台服务**不算**持有）。且 `nodejs.org` / `python.org` **HTTP=000** |
| 本机 Windows 直跑 | 18 个工作流全是 `ubuntu-latest` + bash 步骤 |

⚠️ `vmIdleTimeout=-1` 在 WSL **2.5.9 上无效**（那个参数它不认）。

---

## 自建 runner 的两个已知特例

**① `setup-python` 在 macOS 自建 runner 上必失败**

它的 macOS 安装脚本把 `/Users/runner` 写死（托管 runner 的用户名就是 `runner`），而本机用户是 `rex` 且 `/Users` 不可写 → `mkdir: /Users/runner: Permission denied`。

⚠️ 试过两条绕法**都不管用**：改 `AGENT_TOOLSDIRECTORY`；预置工具缓存（结构完整、可执行，它仍走安装脚本）。

⇒ 现在的写法是**按 runner 类型分支**，自建用系统 Python 并**断言版本 ≥ 3.11**：

```yaml
- uses: actions/setup-python@v5
  if: runner.environment == 'github-hosted'
- name: Use system Python (self-hosted)
  if: runner.environment != 'github-hosted'
```

**② runner 的 PATH 不含 homebrew**

`~/actions-runner/.path` 原本只有 `/usr/bin:/bin:/usr/sbin:/sbin`，而 `python3.11` 装在 `/opt/homebrew/bin`。已前置该目录。改了 `.path` 或 `.env` 都要**重启服务**才生效。

---

## 🔴 自建 runner 最大的价值：它在**另一个时区**

2026-08-27 首次接入当天，它照出 **3 个在东八区永远测不出来的 bug**，全部是当天引入的：

| # | 位置 | 性质 |
|---|---|---|
| 1 | `analysisDefaults.spec.ts` | 阴性对照在**西半球恒绿** |
| 2 | `aiOrderNormalization.ts` | 西半球日期差一天 |
| 3 | `AIChatScreen.tsx` | **东八区每天 16:00 后返回明天** —— 影响真实用户 |

共同形状：**`Date.now() + 8h` 这种「平移 UTC 轴假装某时区」的写法，必须用 UTC getter 读回**。
换成本地 getter 等于又叠一次设备偏移。

⛔ **别用 `TZ=x cmd` 在 Windows 上验时区** —— 实测那个前缀**根本不传进 node**，
`process.env.TZ` 是 `undefined`，偏移恒为 `-480`。写「四时区全绿」而实际跑了四遍东八区。
⇒ 时区相关的验证**只在 Mac/Linux 上做**。

---

## 判据

- 切换后**不看 `gh variable list`，看 job 实际落在哪**：
  `gh api repos/cretasfuture-ai/cretas/actions/jobs/<id> --jq .runner_name`
- 自建 runner 在线：`gh api orgs/cretasfuture-ai/actions/runners --jq '.runners[]|.name+" "+.status'`
- ⚠️ `status=offline` 但 `busy=true` 是**正常的中间态**（正在重连），不要据此判定它挂了

关联：`worktree-and-main-only-deploy.md`（部署只从 main）、`concurrent-edit-safety.md`（commit 范围保护）。
