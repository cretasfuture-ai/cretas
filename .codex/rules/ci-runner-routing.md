# CI runner 路由：默认托管，额度耗尽切自建

**最后更新**: 2026-09-11（自建 runner 换成本机 WSL2 `windows-wsl-ci`，Mac mini 时期的接入方式与特例移入案例库；同日压缩：本文件只留路由规则、六关清单与判据；接入实测、组织变量排除记录、WSL2 前后对照、时区 bug 与 nightly 开放集合案例存档在 `docs/rules-casebook/ci-runner-routing-cases.md`，章节名一致）
**触发事故**: 2026-08-26 组织 Actions 免费额度 2000 分钟用尽，所有 job **2~4 秒、0 个 step、无日志**地失败，横跨 `main` 与各 feature 分支。

⚠️ 本文件在 `.claude/rules/` 与 `.codex/rules/` 各有一份，**必须逐字相同**（见文末闸）。

---

## 一句话

**默认走托管 runner。额度耗尽时，一条命令切到本机 WSL2 自建 runner；额度恢复，切回来。**

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

⚠️ 六关是在 Mac mini（macOS）上实测的。现役自建是 WSL2（Linux）：第 1 关（缩进）和第 6 关（Docker）与平台无关，仍然成立；第 2–5 关是 macOS 特有（`/Users/runner`、缺 `nproc`/`free`、homebrew、无 `+cpu` wheel），Linux 上不适用——但 WSL 上有没有 Docker、`mvn`/`python3` 在不在 PATH **没重测过**，接新工作流前逐关跑一遍。

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

| 闸 | 托管 | 自建（Mac mini 时期实测；WSL2 未测） |
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
| 机器 | 本机 Windows 上的 WSL2，三个 runner 实例：`windows-wsl-ci`（标签另带 `ci-migration-candidate`）、`windows-wsl-ci-2` / `-3`（`ci-extra`） |
| 标签 | `self-hosted, cretas, Linux, X64` —— 脚本写的 `runs-on: [self-hosted, cretas]` 只会派到它们 |
| 连接 | 本机，不需要 SSH |
| Python | 系统 Python **3.11.13**（与 prod 同版本，见上） |
| 前提 | Windows 侧登录保活任务 **Running**（三态表见下）；停掉 / 注销就掉线 |

### 溢出 runner：加拿大 Mac mini 上的 Linux VM（2026-10-03 起）

本机三个 8G runner 满了时，**可溢出的 Java job** 会被 GitHub 派到 `canada-mini-cretas`：

| | |
|---|---|
| 机器 | 加拿大 Mac mini（`ssh canada-mini`）上 colima 的 `ci` VM，Ubuntu 24.04 **arm64**，10 核 / 24 GB，数据盘 120 GB |
| 注册 | **仓级** runner（cretas 仓），systemd 自启；同一 VM 里另有 assembly 仓的 `canada-mini-ci` |
| 标签 | `self-hosted, Linux, ARM64, java, canada-mini` —— **没有 `cretas`**，所以只接 `runs-on: [self-hosted, java]` 的 job |
| 可溢出的 job | `java-gate-pr`（fast-contracts、postgres-reversal-concurrency）、`jpa-gate-main`、`tool-inventory-gate` |
| 不溢出 | `java-full-nightly`（要 runner 本机常驻 PG + r10 库，加拿大没有）；`java-artifact`（09-15 起只手动派发、不抢 runner，没必要溢出）——仍是 `[self-hosted, cretas, java]` |
| 工具 | Maven 3.9.16、PG 17 服务端二进制（无常驻集群，`run-with-test-postgres.sh` 自起临时实例）、python3 |
| 测试堆 | runner `.env` 设 `CRETAS_JAVA_FORK_XMX=6g`；`java-gate-pr` 用 `-Xmx${CRETAS_JAVA_FORK_XMX:-3g}`（本机不设 = 3g）。实测这台的执行顺序下 fast-contracts 3g / 4g 都在第 324 个类 OOM，6g / 8g 全过；本机同一测试集 3g 能过只是顺序碰巧有利 |
| 三态 | 在线 ✅；**Mac 重启后自恢复 ⚠️ 未验证**（VM 由 rex 的 GUI 会话下 launchd 代理拉起，机器未开自动登录） |

⚠️ 非 Java 的闸（`[self-hosted, cretas]`）**不会**溢出：它们要 `cretas` 标签，而 arm64 上装不了 `torch==2.4.1+cpu`（第 5 关）。⛔ 别给这台 runner 加 `cretas` 标签。
A2 闸（`scripts/tests/test-java-heavy-jobs-need-8g-runner.sh`）钉着「可溢出形态不许带 `services:`、不许是 nightly」：
同一 VM 里 assembly 的 job 用 `services:` 映射 5432，可溢出的 job 再声明服务容器就会撞端口。

加拿大 Mac mini（`canada-mac-mini` / `-2`，标签 `self-hosted, macOS, ARM64`，**没有 `cretas`**）已退役为本仓 CI 载体：离线，且 `AGENTS.md` 已把它留给其他任务。它时期的接入方式、ProxyJump/钥匙串坑、`setup-python`/homebrew 两个特例都在案例库「Mac mini 时期」一节，⛔ 不要再照着那些去连。

---
## ⛔ 别再试的三条路

| 载体 | 状态 |
|---|---|
| 阿里云 139 / 47 | ⛔ 堵死 —— git 长连接连不动（`git clone --depth 1` 连 10MB 小仓都超时；HTTP/1.1、postBuffer、`--filter=blob:none` 全败） |
| 本机 WSL2 | ✅ 现役自建 runner —— 成立前提见下表三态 |
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

## 工作流里按 runner 类型分支

`setup-python` 那一步按 runner 类型分支，自建用系统 Python 并断言版本 `>=3.10`（写法保留，不管自建是 macOS 还是 Linux 都成立）：

```yaml
- uses: actions/setup-python@v5
  if: runner.environment == 'github-hosted'
- name: Use system Python (self-hosted)
  if: runner.environment != 'github-hosted'
```

改了 runner 的 `.path` / `.env` 都要**重启 runner 服务**才生效。

---
## 🔴 时区覆盖：自建 runner 回到东八区之后要显式跑

Mac mini 时期它在西半球，首次接入当天照出 3 个东八区永远测不出来的 bug（阴性对照西半球恒绿 / 西半球日期差一天 / 东八区 16:00 后返回明天，见案例库）。共同形状：**`Date.now() + 8h` 这种「平移 UTC 轴假装某时区」的写法，必须用 UTC getter 读回**，换成本地 getter 等于又叠一次设备偏移。

现役 WSL2 runner 和开发机同在东八区，**那层免费覆盖没有了**：时区相关的断言要显式用西半球 `TZ` 再跑一遍。⛔ **别在 Windows 原生 shell 里用 `TZ=x cmd`** —— 那个前缀根本不传进 node（`process.env.TZ` 是 `undefined`，偏移恒为 `-480`），写「四时区全绿」而实际跑了四遍东八区。`TZ=` 前缀只在 Linux（含 WSL）/ Mac 上生效。

---

## 判据

- 切换后**不看脚本说了什么，看 job 实际落在哪**：
  `gh api repos/cretasfuture-ai/cretas/actions/jobs/<id> --jq .runner_name`
- 自建 runner 在线：`gh api orgs/cretasfuture-ai/actions/runners --jq '.runners[]|.name+" "+.status'`
- ⚠️ `status=offline` 但 `busy=true` 是**正常的中间态**（正在重连），不要据此判定它挂了

关联：`worktree-and-main-only-deploy.md`（部署只从 main）、`concurrent-edit-safety.md`（commit 范围保护）。

## 跑批判定：退出码是开放集合

▎**不是 0 也不是 1，一律当成「没量到」。⛔ 不要枚举失败码。** 枚举 `rc == 1` / `rc == 2` 时，第四种值（如 `python: command not found` 的 127）两条都不命中、静默落进绿（`java-full-nightly` 首次真跑就是这样：job success，artifact 146 字节）。抓住它的是「artifact 大小」这个设计上必然会变的读数——硬约束 9。

