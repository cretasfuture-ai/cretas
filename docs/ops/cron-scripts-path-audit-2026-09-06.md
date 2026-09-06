# 47 上定时脚本的 PATH / `2>/dev/null` 审计（2026-09-06）

票：`OPS-CRON-SCRIPTS-PATH-AUDIT`（brief 卡 C 任务 2）。读数全部来自 2026-09-06 对 `root@47.100.235.168` 的只读 ssh。

## 载体事实（先于任何一行脚本）

- root 的 user crontab（`crontab -l`）**没有 `PATH=` 行**，只有 `LANG`/`LC_ALL` ⇒ cronie 默认 `/usr/bin:/bin`。`/etc/crontab` 与 `/etc/cron.d/*` 有 `PATH=/sbin:/bin:/usr/sbin:/usr/bin`，但那些不是我们的脚本。`MAILTO` 0 行。
- 在 `/usr/bin:/bin` 之外的命令（实测 `PATH=/usr/bin:/bin command -v`）：**只有 `ss`（/usr/sbin）、`sendmail`（/usr/sbin）、`nginx`（/usr/sbin）**。`psql`/`python`/`python3`/`jq`/`curl`/`systemctl`/`journalctl`/`flock`/`pgrep`/`git`/`scp`/`rsync`/`unzip`/`pg_dump` 全在 `/usr/bin`。
- 系统 `python` 是 **3.6.8**（`/usr/bin/python3` 有 httpx 0.22）。项目 venv：`backend/python/venv311`，`venv-current -> venv311`（2026-09-04 重指）。**`venv38` 不存在**。
- `/www/wwwroot/cretas/code` 那棵 checkout 停在 `2e90a2016`（2026-05-07，#135），1394 个脏条目；`scripts/cron/` 与 `scripts/deploy/llm-router-*.sh` 在那里是 **untracked**。⇒ 它们不是「从 git checkout 跑、跟着代码走」：
  - `scripts/cron/*.sh` 由 `deploy-smartbi-python.sh` **3d 整目录 rsync**（每次 Python 部署重刷）。当日 md5 与 origin/main 逐个相等（14/14，含 3 个 NOT_SCHEDULED）。
  - `scripts/deploy/llm-router-*.sh` **没有任何同步机制**（3d 只管 cron/，3c 白名单不含），服务器副本 mtime 2026-07-01，手 scp 的。当日 md5 与 origin/main 相等只是因为两边都没再改。⇒ 本次登记进 `server-script-inventory.conf`，让漂移检查/安装器管它们。
  - crontab 直接执行的另有 6 个**不在仓库**的服务器本地脚本（见表末）。

## 表：每个脚本依赖什么、吞了什么、三态吗

「PATH 外」列只看 cron 默认 PATH（`/usr/bin:/bin`）下找不到的命令。「venv」列：`source …/activate` 失败时 `set -u`（无 `-e`）**不中止**，会掉回系统 python 3.6.8。

| # | 脚本 | crontab | 交付 | PATH 外命令 | venv | `2>/dev/null` | rc 三态 | 本次动作 |
|---|---|---|---|---|---|---|---|---|
| 1 | `cron/async-failure-backlog-daily.sh` | NOT_SCHEDULED | 3d | **`ss`** | 无 | 2（`ss`、`eval`）| ✅ | **改**：钉 PATH + require ss/psql + ss stderr 不吞 + `no-listener-on-ports` 单独 reason + 去 `grep -P` |
| 2 | `cron/daily-close-push.sh` | 23:40 | 3d | 无 | venv-current，无守卫 | 3（`.env.prod`/`.env.daily-close`/`systemctl`）| ✅ | 批 2 |
| 3 | `cron/flywheel-promotion-staleness-daily.sh` | 05:15 | 3d | 无 | 可覆盖，无守卫 | 3 | ✅ + 台账必发生 | 批 2 |
| 4 | `cron/forecast-backtest-watch.sh` | 05:30 | 3d | 无 | 无守卫 | 2 | 0/1/2 但 1 混两义（已在脚本内区分）| 批 2 |
| 5 | `cron/llm-provider-health-daily.sh` | 05:25 | 3d | 无 | 无守卫 | 2 | ✅ 三产出文件 | 批 2 |
| 6 | `cron/margin-parity-daily.sh` | 03:50 | 3d | 无 | 无守卫 | 3 | ✅ | 批 2 |
| 7 | `cron/probe-llm-registry.sh` | NOT_SCHEDULED | 3d | 无 | 无守卫 | 0 | 两态（0/1）| 批 3 |
| 8 | `cron/refresh-demo-rest.sh` | 00:05 | 3d | 无 | 无守卫（`set -e` 下 source 失败会中止，反而安全）| 0 | 两态 | 批 3 |
| 9 | `cron/replay-equivalence-daily.sh` | 03:40 | 3d | 无 | 无守卫 | 2 | ✅ + 告警文件存在性 | 批 2 |
| 10 | `cron/restaurant-ai-eval.sh` | 04:40 | 3d | 无 | 无守卫 | 3 | 两态（非 0 一律 FAILED）| 批 3 |
| 11 | `cron/restaurant-flywheel-promotion-backlog-daily.sh` | NOT_SCHEDULED | 3d | 无 | 可覆盖，`&&` 链上失败会落 rc≠0 → rc=2 ✅ | 0 | ✅ | 不动（已合规） |
| 12 | `cron/restaurant-unfreeze-watch.sh` | 05:10 | 3d | 无 | 无守卫 | 4 | ✅ + 台账必发生 | 批 2 |
| 13 | `cron/soft-delete-orphans-watch.sh` | 04:30（装在 `/www/wwwroot/cretas/scripts/`）| installer | `ss`（已自钉 PATH）| 无 | 4（仅 `command -v`/`unzip` 探测）| ✅ | **不动**（#627/#632 今日两轮，organizer 裁定只登记）|
| 14 | `cron/time-phrase-backlog-daily.sh` | 05:45（`bash …`）| 3d | 无 | 可覆盖，`&&` 链 ✅ | 0 | ✅ | 不动（已合规） |
| 15 | `deploy/llm-router-billing-canary.sh` | 周一 09:00 | **无** | 无 | **`venv38` 不存在 → 系统 3.6.8 → SyntaxError** | 0 | 0/1/3，崩溃靠 crontab `\|\| echo FLAGGED` 折叠成 FLAGGED | **改**：venv-current + 守卫 + 三态措辞写进 `$ALERTS` + 登记 inventory |
| 16 | `deploy/llm-router-rules-gap-heatmap.sh` | 周一 09:30 | **无** | 无 | 不用 venv | 0 | 无（psql 失败也 rc 0/非 0 混）| **改**：钉 PATH + require + `psql-failed` rc=2 + 登记 |
| 17 | `deploy/llm-router-rerank-advisor.sh` | 周一 10:00 | **无** | 无 | **`venv38`**，同 #15 | 0 | 两态 | **改**：同 #15 + 登记 |

服务器本地、**不在仓库**的 crontab 脚本（只登记，不属本票）：`/www/wwwroot/cretas/scripts/backup-db.sh`（pg_dump/gzip/find，`2>/dev/null`×5）、`/www/wwwroot/cretas/health-monitor.sh`（curl/flock/**nginx**/ssh/systemctl，×4）、`capability-watch.sh`（×14，仓库 `scripts/monitoring/` 由 3c3 同步）、`capability-soak-report.sh`、`/opt/cretas/scripts/ops/nightly-canvas-e2e.sh`、`/www/wwwroot/ota/prune-cron.sh`。`health-monitor.sh` 用 `nginx`（/usr/sbin）而无 PATH 钉——同款隐患，但它不在仓库，留票。

## 发现（按严重度）

1. 🔴 **#15/#17 从 2026-08-03 起每周崩一次，被记成账单告警。** `llm-canary.log` 里 7-06、7-13 两次真跑（81 条目 / 77 billing-safe / 4 FLAGGED），之后 5 次全是 `venv38/bin/activate: No such file` + `SyntaxError: future feature annotations is not defined`；`llm-canary-alerts.log` 里 9 行 `LLM canary FLAGGED`，**后 5 行是崩溃**。rerank 同样 5 次 SyntaxError。⇒ 注册表账单安全金丝雀已 5 周无读数。
2. 🔴 交付机制被误记：inventory 原文「从服务器 git checkout 跑、跟着代码走」不成立（见载体事实）。改 `scripts/cron/*` 后要跑一次 Python 部署或手动 rsync 同一目录；改 `llm-router-*` 后要走 installer（本次已登记）。
3. 🟡 #1 是 soft-delete 那次 15 天空转的同款（`ss` + `2>/dev/null`），只是它还没接 crontab。
4. 🟡 10 个 venv 脚本共享同一个未守卫的 `source venv-current/bin/activate`：链接坏掉的那天，10 个会同时掉回 3.6.8，其中两态的（#8/#10）会把崩溃写成业务失败。批 2/3 统一换 `cron_activate_venv`。
5. 🟢 PATH 外命令实际只有 `ss`（#1、#13 已钉）。「钉 PATH」对其余 15 个是防御，不是修复——它们今天在 cron 默认 PATH 下都找得到自己要的命令。
6. 🟢 `2>/dev/null` 共 26 处，绝大多数在 `source /www/wwwroot/cretas/.env.prod 2>/dev/null`（文件缺失被吞，随后 python 侧才报凭证缺）。批 2 用「显式存在性检查 + 缺则 rc=2」替换，⛔ 不做成「缺就 WARN 继续」——那正是 `.env.daily-close` 这种**可选**文件与 `.env.prod` 这种**必需**文件要分开的地方。

## 批次

| 批 | 内容 | 状态 |
|---|---|---|
| 1 | `scripts/cron/cron-common.sh`（钉 PATH / 缺命令 rc=2 / venv 守卫）+ #1 + #15/#16/#17 + inventory 登记与订正 + 本文档 + 3 个测试 | 本 PR |
| 2 | #2 #3 #4 #5 #6 #9 #12（六个「systemctl 取 environ」形状 + forecast）：`cron_activate_venv` + `.env.prod` 显式存在性 + 去 `2>/dev/null` | 下一 PR |
| 3 | #7 #8 #10（两态 → 三态：崩溃与业务失败分开）| 再下一 PR |
| — | #11 #14 已合规；#13 不动 | — |

## 装什么 / 装哪 / 怎么验（organizer）

- `scripts/cron/cron-common.sh` + `async-failure-backlog-daily.sh`：跑一次 `deploy-smartbi-python.sh --env prod`（3d 会同步整目录），或 `rsync -az --include='*.sh' --exclude='*' scripts/cron/ root@47:/www/wwwroot/cretas/code/scripts/cron/`。验：`ssh root@47 'bash -n /www/wwwroot/cretas/code/scripts/cron/cron-common.sh && md5sum /www/wwwroot/cretas/code/scripts/cron/{cron-common,async-failure-backlog-daily}.sh'` 与本地 `git show origin/main:<path> | md5sum` 相等。
- 三个 `llm-router-*.sh`：`./scripts/deploy/install-server-scripts.sh --host ecs --only llm-router-billing-canary.sh --confirm YES-INSTALL`（另两个同理；首次会报 DRIFT，因为服务器那份是 7-01 的旧版，要 `--accept-overwrite-drift`，理由：服务器版含已不存在的 venv38）。验：周一之前手跑一次 `bash /www/wwwroot/cretas/code/scripts/deploy/llm-router-billing-canary.sh`，看到 `[canary 2026-09-…] N entries · M billing-safe` 抬头且 rc∈{0,1,3}；`tail -1 /www/wwwroot/cretas/logs/llm-canary-alerts.log` 不再是 `LLM canary FLAGGED <date>` 那种无 rc 的旧格式。
- crontab（host-local，organizer 改）：canary 那行去掉 `|| echo "LLM canary FLAGGED $(date)" >> …llm-canary-alerts.log`——三态措辞现在由脚本自己写。
