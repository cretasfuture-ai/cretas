# CI runner 路由：默认托管，额度耗尽切自建

**最后更新**: 2026-08-27
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

2026-08-27 接 `python-gate` / `jpa-gate-main`（以及试过又撤回的 `ci.yml`）时逐条**实测**踩出来的：

| # | 关卡 | 长什么样 |
|---|---|---|
| 1 | **缩进** | 脚本按**字面量**匹配 `    runs-on: ubuntu-latest`（4 空格）。缩进不同 → 静默不命中 |
| 2 | **`actions/setup-python`** | 自建 macOS 上**必失败**（安装脚本把 `/Users/runner` 写死）⇒ 条件化 + 建 venv（homebrew 的 3.11+ 带 PEP 668，直接装会被拒） |
| 3 | **Linux 专有命令** | `nproc` / `free` / `apt-get` / `stat -c` 在 macOS 上不存在。`jpa-gate` 就有一步 `nproc` + `free -h`，**只负责打印却能弄红整个 job** |
| 4 | **缺工具** | `setup-java` 只装 JDK **不装 Maven** → `mvn: command not found`；homebrew 只给 `pip3` / `python3`，**没有裸 `pip`、也没有裸 `python`** ⇒ 探测 + 兜底（`./mvnw` / `python3` / venv 里的 `python`）。⚠️ 扫的时候**不能只看 `uses:`** —— run 步骤里的裸解释器同样要查 |
| 5 | **平台专用 wheel** | `torch==2.4.1+cpu` 的 `+cpu` 是 **Linux 专用**本地版本标识，macOS arm64 没有这个变体 |
| 6 | 🔴 **`services:` / 容器** | 这台自建 runner **没有 Docker**。实测 `ci.yml` 一 dispatch 就 `##[error]docker: command not found`，job 在 **Set up job 阶段**死，**一个 step 都执行不到**（run 33037689686，9 秒） |

⛔ 六关不逐条过就把文件名加进来，换来的是「从跑不起来」变成「跑起来就红」。

### 📌 `ci.yml` 试过并撤回了 —— 卡在第 6 关

它是**发布链 `--prefer-ci-artifact` 那个制品的产出方**，额度耗尽期间它死掉是有生产影响的，
所以值得试。它有 **4 个 job**（脚本因此从「恰好 1 处」改成「N 处全换」），
但 `java-build-test` 依赖 `pgvector/pgvector:pg17` 服务容器 + `psql` ——
**没有 Docker 就无解，与 YAML 怎么写无关。**

⇒ 这是**机器侧**的事（给 runner 装 Docker，或换一台 Linux self-hosted），
⛔ 不是这个脚本能解决的。在那之前 `ci.yml` 保持写死 `ubuntu-latest`。

### `--adopt`：一致性闸曾经把接入路径堵死

加一个新工作流的那一刻，状态必然不一致（新的还是 `hosted`）⇒ 脚本拒绝切；
而唯一的出路「手改单个 `runs-on`」正是上面明令禁止的。**这道闸把自己设计的
接入路径堵死了。** `--adopt` 是那个显式的收敛口：

⚠️ 它**不是** `--force` —— 它只做「全部拉齐到 target」，并把每个文件是
「改了」还是「已经是、跳过」**逐行打出来**，⛔ 不允许静默拉齐。

### ⚠️ `python-gate` 落到自建时，跑的**不是 3.10**

托管上钉的是 `python-version: '3.10'`；自建上只能用系统 Python（3.11+）。
该文件顶部那句「本机 py3.11 跑绿 ≠ CI py3.10 跑绿」在这里反向成立 ——
**自建模式下这道闸测的是另一个小版本**。所以那一步会把
`PYTHON_USED=<解释器> (<版本>)` 打进日志，并只断言下限 `>=3.10`
（⛔ 不允许静默用更老的版本跑）。读它的绿时要连版本一起读。

---

## ⛔ 别用组织变量做路由（实测不生效，别再试第二遍）

第一版写的是这个，看起来最优雅 —— **切换不用碰任何工作流文件**：

```yaml
runs-on: ${{ vars.CI_RUNNER && fromJSON(vars.CI_RUNNER) || 'ubuntu-latest' }}
```

**它不工作。** job 实际被派成 `labels=ubuntu-latest`，即 `vars.CI_RUNNER`
在 `runs-on` 求值时读到的是**空**。

逐条排除过，配置侧没有任何问题：

| 怀疑 | 实测 |
|---|---|
| PR 来自 fork（fork 读不到组织变量）| `isCrossRepository=false` |
| 变量可见性没覆盖本仓 | `visibility=all` |
| 组织变量没授权给这个仓 | `repos/.../actions/organization-variables` 列得到 `CI_RUNNER` |
| 建变量晚于 run（时序竞争）| 变量 `01:43:43Z` < run `01:48:34Z` |

⇒ 成因是 **`runs-on` 在 job 编排阶段求值**，那时可用的上下文比步骤里窄，
`vars` 够不着。这不是配错了，是**能力边界**。

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

- 切换后**不看脚本说了什么，看 job 实际落在哪**：
  `gh api repos/cretasfuture-ai/cretas/actions/jobs/<id> --jq .runner_name`
- 自建 runner 在线：`gh api orgs/cretasfuture-ai/actions/runners --jq '.runners[]|.name+" "+.status'`
- ⚠️ `status=offline` 但 `busy=true` 是**正常的中间态**（正在重连），不要据此判定它挂了

关联：`worktree-and-main-only-deploy.md`（部署只从 main）、`concurrent-edit-safety.md`（commit 范围保护）。

---

## 🔴 「跑批报 success 却什么都没产出」—— 三态判定的开放集合陷阱

2026-08-27 实测（run 33041333971，`java-full-nightly` 第一次真跑）：

```
Run the full Java suite = success      ← 全量套件确实跑了（34 万行日志）
Ratchet                 = success      ← 但它里面是 python: command not found, rc=127
判定 rc=2 / 判定 rc=1    = skipped      ← 127 既不是 1 也不是 2
job                     = success
artifact                = 146 字节，known-failing-tests.txt 压根没产出
```

成因两层，**第二层才是要害**：

1. 棘轮那一步调**裸 `python`**，macOS 上不存在（见第 4 关）。
2. 🔴 判定**枚举**了 `rc == 2` 和 `rc == 1`，而退出码是个**开放集合** ——
   任何第四种值都两条都不命中，静默落进「绿」。
   ⚠️ 这跟三态机制想解决的问题**是同一个**：它把「没量到」从「没问题」里拆出来，
   却给自己留了一条「没量到但看起来像没问题」的缝。

▎**判据反过来写：不是 0 也不是 1，一律当成「没量到」。⛔ 不要枚举失败码。**

⚠️ 抓住它的**不是那个绿**，是「artifact 只有 146 字节」——
一个**设计上必然会变**的读数。这正是硬约束 9：
验证「X 没发生」时必须同时有一个「Y 一定会发生」的读数。
⛔ 只看 job 是不是绿，这次会得出「nightly 跑通了」这个完全相反的结论。
