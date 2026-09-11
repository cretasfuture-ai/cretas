# CI runner 路由：默认托管，额度耗尽切自建

**最后更新**: 2026-09-11（压缩：本文件只留路由规则、六关清单与判据；接入实测、组织变量排除记录、WSL2 前后对照、时区 bug 与 nightly 开放集合案例存档在 `docs/rules-casebook/ci-runner-routing-cases.md`，章节名一致）
**触发事故**: 2026-08-26 组织 Actions 免费额度 2000 分钟用尽，所有 job **2~4 秒、0 个 step、无日志**地失败，横跨 `main` 与各 feature 分支。

⚠️ 本文件在 `.claude/rules/` 与 `.codex/rules/` 各有一份，**必须逐字相同**（见文末闸）。

---

## 一句话

**默认走托管 runner。额度耗尽时，一条命令切到加拿大 Mac mini；额度恢复，切回来。**

```bash
python scripts/ci/switch_ci_runner.py --check        # 当前走哪条
python scripts/ci/switch_ci_runner.py self-hosted    # 额度耗尽 -> 自建
python scripts/ci/switch_ci_runner.py hosted         # 额度恢复(每月 1 号) -> 托管
python scripts/ci/switch_ci_runner.py <目标> --adopt # 只在**新接入**一个工作流时用, 见下
```

⚠️ 它改的是**工作流文件**，所以要 `git commit && git push` 才生效。
脚本一次改全部已接入的闸，并断言各处一致 —— ⛔ 不要手工改单个 `runs-on`
（「共享结构只改一处」是本仓反复踩的坑）。

已接入（9 个）：`secret-regression` / `web-admin-gate` / `rn-gate` / `python-gate`
/ `jpa-gate-main` / `java-artifact` / `java-full-nightly` / `tool-inventory-gate` / `web-dist`。

其余 6 个写死 `ubuntu-latest` —— 在自建模式下它们**完全跑不起来**
（`2~4 秒 / 0 个 step / 无 runner`），不是「暂时没覆盖」而是「没有信号」：

- `ci.yml` —— 卡在第 6 关（服务容器），见下。
- `e2e-post-deploy` / `e2e-pr` / `tool-isolation-audit` —— 在 GitHub 上仍是
  `disabled_manually`。⛔ 前两个会碰部署环境，**一 dispatch 就有副作用，验不了就不该接**。
- `daily-integration` / `kb-drift-check` / `threshold-parity-check` —— 手动触发，
  同样是「dispatch 一次就有副作用（开 PR 等）」，未接。

---

## 新接一个工作流：要过六关，⛔ 光加文件名不算接进来

| # | 关卡 | 长什么样 |
|---|---|---|
| 1 | **缩进** | 脚本按**字面量**匹配 `    runs-on: ubuntu-latest`（4 空格）。缩进不同 → 静默不命中 |
| 2 | **`actions/setup-python`** | 自建 macOS 上**必失败**（安装脚本把 `/Users/runner` 写死）⇒ 条件化 + 建 venv（homebrew 的 3.11+ 带 PEP 668，直接装会被拒） |
| 3 | **Linux 专有命令** | `nproc` / `free` / `apt-get` / `stat -c` 在 macOS 上不存在。`jpa-gate` 就有一步 `nproc` + `free -h`，**只负责打印却能弄红整个 job** |
| 4 | **缺工具** | `setup-java` 只装 JDK **不装 Maven** → `mvn: command not found`；homebrew 只给 `pip3` / `python3`，**没有裸 `pip`、也没有裸 `python`** ⇒ 探测 + 兜底（`./mvnw` / `python3` / venv 里的 `python`）。⚠️ 扫的时候**不能只看 `uses:`** —— run 步骤里的裸解释器同样要查 |
| 5 | **平台专用 wheel** | `torch==2.4.1+cpu` 的 `+cpu` 是 **Linux 专用**本地版本标识，macOS arm64 没有这个变体 |
| 6 | 🔴 **`services:` / 容器** | 这台自建 runner **没有 Docker**。实测 `ci.yml` 一 dispatch 就 `##[error]docker: command not found`，job 在 **Set up job 阶段**死，**一个 step 都执行不到**（run 33037689686，9 秒） |

⛔ 六关不逐条过就把文件名加进来，换来的是「从跑不起来」变成「跑起来就红」。

`ci.yml` 保持写死 `ubuntu-latest`：它的 `java-build-test` 依赖 `pgvector` 服务容器，自建 runner 没有 Docker，这是机器侧的事（给 runner 装 Docker 或换 Linux self-hosted），脚本解决不了。

`--adopt` 是新接工作流时的显式收敛口（新文件状态必然与现有不一致，一致性闸会拒切）：它只做「全部拉齐到 target」并逐行打印每个文件「改了 / 已经是、跳过」，⛔ 不允许静默拉齐，也不是 `--force`。

### `python-gate` 在两种 runner 上跑的不是同一个解释器

托管钉 `python-version: '3.10'`；自建用系统 Python（当前 `windows-wsl-ci` 是 **3.11.13**，与 prod 同版本）。该步骤把 `PYTHON_USED=<解释器> (<版本>)` 打进日志，并只断言下限 `>=3.10`（⛔ 不允许静默用更老的版本跑；⛔ 也不要改成等值断言——下限写法对两种 runner 都成立）。读它的绿时要连版本一起读。

📌 「自建与 prod 同版本」只是「Java 要不要上托管」选型的一个输入，⛔ 不据此改任何 workflow；它依赖自建系统 Python 与 prod 都保持 3.11.13，任一侧升级就要重新量两边。

---

## ⛔ 别用组织变量做路由

`runs-on: ${{ vars.CI_RUNNER && fromJSON(vars.CI_RUNNER) || 'ubuntu-latest' }}` 不工作：`runs-on` 在 job 编排阶段求值，那时 `vars` 够不着，job 实际落成 `labels=ubuntu-latest`。这是能力边界，不是配置问题（fork / 可见性 / 授权 / 时序都排除过，见案例库）。

▎判据：`runs-on` 里只放字面量。要动态就改文件。

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
## ⛔ 别再试的三条路

| 载体 | 状态 |
|---|---|
| 阿里云 139 / 47 | ⛔ 堵死 —— git 长连接连不动（`git clone --depth 1` 连 10MB 小仓都超时；HTTP/1.1、postBuffer、`--filter=blob:none` 全败） |
| 本机 WSL2 | ⚠️ 有条件可用 —— 见下表三态 |
| 本机 Windows 直跑 | ⛔ 堵死 —— 18 个工作流全是 `ubuntu-latest` + bash 步骤 |

### WSL2 的三态（⛔ 不许把「尚未验证」并进「已验证」）

| 项 | 状态 | 成立前提 |
|---|---|---|
| 保活 | ✅ runner 持续在线并完成真实任务 | Windows 侧登录保活任务处于 **Running**；停掉 / 注销就回到「启动后 46 秒自动关机」（`vmIdleTimeout=-1` 在 WSL 2.5.9 上无效） |
| 外网 | ✅ runner 内 `nodejs.org` / `python.org` HTTP 200 | 同上保活前提；这只说明那个组件在那一刻能出网，不是「这台机器网络通」的结论 |
| Windows 重启后自恢复 | ⚠️ 尚未验证 | — |
| 未登录状态下恢复 | ⚠️ 尚未验证 | — |

⇒ 现在成立的说法只有：**「在有人登录、且保活任务在跑的前提下，它能干活。」** 前提没了，结论跟着作废。

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
## 🔴 自建 runner 最大的价值：它在另一个时区

首次接入当天照出 3 个东八区永远测不出来的 bug（阴性对照西半球恒绿 / 西半球日期差一天 / 东八区 16:00 后返回明天，见案例库）。共同形状：**`Date.now() + 8h` 这种「平移 UTC 轴假装某时区」的写法，必须用 UTC getter 读回**，换成本地 getter 等于又叠一次设备偏移。

⛔ **别用 `TZ=x cmd` 在 Windows 上验时区** —— 那个前缀根本不传进 node（`process.env.TZ` 是 `undefined`，偏移恒为 `-480`）。时区相关的验证**只在 Mac/Linux 上做**。

---

## 判据

- 切换后**不看脚本说了什么，看 job 实际落在哪**：
  `gh api repos/cretasfuture-ai/cretas/actions/jobs/<id> --jq .runner_name`
- 自建 runner 在线：`gh api orgs/cretasfuture-ai/actions/runners --jq '.runners[]|.name+" "+.status'`
- ⚠️ `status=offline` 但 `busy=true` 是**正常的中间态**（正在重连），不要据此判定它挂了

关联：`worktree-and-main-only-deploy.md`（部署只从 main）、`concurrent-edit-safety.md`（commit 范围保护）。

## 跑批判定：退出码是开放集合

▎**不是 0 也不是 1，一律当成「没量到」。⛔ 不要枚举失败码。** 枚举 `rc == 1` / `rc == 2` 时，第四种值（如 `python: command not found` 的 127）两条都不命中、静默落进绿（`java-full-nightly` 首次真跑就是这样：job success，artifact 146 字节）。抓住它的是「artifact 大小」这个设计上必然会变的读数——硬约束 9。

