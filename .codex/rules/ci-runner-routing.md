# CI runner 路由：全部 GitHub 托管（仓库已 public）

## 🔴 现行（2026-10-25，owner 决定）

- 仓库 `cretasfuture-ai/cretas` 已转 **public**。全部工作流 `runs-on: ubuntu-latest`（公开仓库托管 runner 免费、不限分钟、4 vCPU / 16 GB，免费计划最多并发 20 个 job）。
- ⛔ **任何 job 都不许再指向自建 runner**：公开仓库谁都能提 PR，自建路由会让陌生人的代码在我们的机器上执行。闸 `scripts/tests/test-public-repo-hosted-only.sh`（全部托管 + 原自建 job 带超时 + Java 显式堆）；`switch_ci_runner.py self-hosted` 直接拒绝。
- 外部 PR：仓库设置 `fork-pr-contributor-approval = all_external_contributors`，外部贡献者的 PR 必须我们点批准才运行；外部贡献者没有写权限，合并只能由组织成员做。
- 托管机上的差异已在工作流里处理：测试库用 PGDG 装 PostgreSQL 17（`run-with-test-postgres.sh`，托管镜像自带 16）；Java Gate `CRETAS_JAVA_FORK_XMX=6g`、nightly `CRETAS_JAVA_NIGHTLY_XMX=12g` 写在 job env；Fresh DB 预检在托管机上跳过 Aurora 身份检查、保留资源/Docker/JDK/Maven 检查。
- Aurora、本机轻任务槽、小检查槽全部退役（`tools/ci/windows-*` 只作历史）。要恢复自建 runner，必须先把仓库改回 private。
- 判据仍是实际 job 的 `runner_name`（托管机形如 `GitHub Actions N`），不看配置。

以下为退役前的历史记录。

## 历史：Aurora 重任务 + Windows 受限轻任务

**当前运行说明：2026-10-24。** 下面的旧机型、加拿大主备与历史内存数字属于迁移前记录，不作为现行启停入口。
**最后更新**：2026-10-24（新增本机小检查槽 `windows-quick-cretas`）。

- Windows 轻任务入口为既有 `Ubuntu-22.04` 中的独立 `windows-light-cretas`（Linux x64），仓库标签只有 `cretas,windows-light` 加系统默认标签，**禁止添加 `java`**。现有 `[self-hosted, cretas]` 队列可直接分流；Java 工作流、选择器、基线和 Aurora 配置保持不变。用户在本轮明确授权 Aurora + Windows 高效分工。
- 本机仅启用新 `cretas-ci-light.service`：独立用户 `cretas-ci-light`，单任务、CPUQuota=300%、MemoryHigh=5G、MemoryMax=6G、MemorySwapMax=0、Nice=10。旧 `cretas-ci.service` 保持 inactive/disabled；不重启 WSL/Docker，不修改全局内存配置、不恢复旧重任务或加拿大 runner。
- 安装/预检源在 `tools/ci/windows-light/`。应用 `/opt/cretas-ci-light`、工具 `/opt/cretas-ci-light-tools`、home/work/cache `/var/lib/cretas-ci-light`、hook `/etc/cretas-ci-light/job-started.sh`；Windows 用户盘、旧 runner、Docker socket和GPU对服务不可访问。只允许官方校验和验证的 runner/Node 包；注册令牌经 stdin 传入，不落 tracked 文件。
- 轻任务验收看实际 job 的 `runner_name`、步骤和结论，不能用 online 代替通过。仅在 idle/busy=false 的安全点维护新服务；回滚只停止/禁用这个新服务并移除它自己的注册，不动 Aurora、旧服务、业务进程和其他容器。已启用 Linux 启动自启；整机重启恢复未实测时须单列未知。
- 小检查槽（owner 2026-10-24，不花钱方案）：同一个 `Ubuntu-22.04` 里再起一个 `windows-quick-cretas`，服务 `cretas-ci-quick.service`，独立用户 `cretas-ci-quick`，CPUQuota=100%、MemoryHigh=768M、MemoryMax=1G、MemorySwapMax=0、TasksMax=128。自定义标签**只有 `cretas-quick`**（⛔ 不加 `cretas` / `java`，否则 vitest、pytest 会落进 1G）。只接 `tracked-secret-scan`、`no-literal-nul-bytes`、`scan-surface-in-sync`、`reap` 四个任务，对应工作流 `runs-on: [self-hosted, cretas-quick]`，由 `switch_ci_runner.py` 的 `QUICK_WORKFLOWS` 管；job-start hook 白名单之外一律退出 75。`aurora-cretas` 与 `windows-light-cretas` 也带 `cretas-quick` 标签兜底。安装/预检/回滚见 `tools/ci/windows-quick/README.md`，闸 `scripts/tests/test-quick-runner-routing.sh`。验收看真实 job 的 `runner_name=windows-quick-cretas`。

- 重任务使用 Aurora 既有 Colima `ci` Linux ARM64 VM（4 CPU / 8 GiB）。主仓 runner 为 `aurora-cretas`；Assembly 为独立注册的 `aurora-assembly`。默认 `colima` profile 继续服务 PomeloX，不切换 Docker context 或停止它。
- SSH 使用 `morrowward-us-mini`，每次连接先确认 `Auroras-Mac-mini.local / aurora`。本机 LaunchAgent `com.cretas.ci-primary` 每 60 秒检查本机 VM 和两个白名单 runner，不查询加拿大。旧 `~/cretas-ci-failover.sh` 只转发到本机 primary，原 failover plist 已退役。
- 监督脚本 `~/.local/lib/cretas-ci-primary/cretas_ci_primary.py`；配置 `~/.config/cretas/ci-primary.json`；状态 `~/.local/state/cretas-ci-primary/latest.json`；本次回执 `~/.local/state/ci-build-migration-20261018/`。VM/服务启动前检查 110 GiB；每个新 job 的只读 hook 要求宿主机至少 125 GiB，以保护 100 GiB 保留空间。
- 两个 runner 共用 Maven 串行锁，等锁最多 5 分钟（退出码 75）；单次 Maven 生命周期最多 90 分钟（退出码 124），为 120 分钟 job 的报告与棘轮步骤留出时间。Maven 默认 1 GiB、独立 javac 上限 2 GiB、测试 JVM 默认 3 GiB；工作流显式堆参数保持优先。用 `reuseForks=false` 在测试类间启动独立 JVM，避免跨类累积 Spring 上下文。测试选择器、断言、PostgreSQL 真实验证与既有棘轮基线保持原样。
- 独立类 JVM 配合负向选择器可能重复执行 Nested 测试并覆盖先前失败 XML；Maven 包装器流式保留输出并记住失败摘要。若最终 XML 不能由现有棘轮可靠判红，则返回 86，避免 `maven.test.failure.ignore` 造成误绿。选择器、断言、基线和真实样本量阈值不改。独立真实 JUnit 阳性样本已验证该保护；完整 nightly 39148327770 在 Aurora 用时 65分04秒通过，最终 15,880 条测试记录、117 跳过、零失败错误，基线哈希未变；3,215 条原日志摘要经最终保护回放退出 0。
- **runner 不全局注入 `spring.test.context.cache.maxSize`。** 历史说明记录过压缓存使共享 H2 关闭的风险；当前 `java-full-nightly.yml` 自身已有显式缓存参数，保留该受版本管理的行为，不扩散到其他门禁。运行参数改变后必须以实际重门禁/全量棘轮读数确认容量，不能只凭 runner online 或轻量 canary 宣称全量可用。
- 加拿大四个 runner 在停止接单、GitHub busy=false、两次确认无 Runner.Worker 后停用，宿主机 CI 自启也已禁用。数据、VM 磁盘、原注册和配置保留；回滚先排空目标、核对最新记录与路由，不能直接恢复旧 watchdog。
- Assembly 路由与六道实际门禁见 [PR #232](https://github.com/cretasfuture-ai/cretas-factory-assembly/pull/232)。完整验收记录由上述回执目录保存，区分配置、离线测试、实际 CI、全量棘轮、合并和应用发布。当前任务不进行应用部署或生产业务写入。

## 迁移前的历史说明（原记录保留）

以下保留原始事故、容量与故障切换证据；其中加拿大启停及旧备用入口已退役。


**最后更新**: 2026-10-04（owner：本机 WSL 内存要炸，停用。自建 runner 全部迁到加拿大 Mac mini 的 colima Linux arm64 VM；美国 aurora 做自动故障切换的备用。Mac mini 时期和 WSL2 时期的接入实测、组织变量排除记录、时区 bug、nightly 开放集合案例都存档在 `docs/rules-casebook/ci-runner-routing-cases.md`）
**触发事故**: 2026-08-26 组织 Actions 免费额度 2000 分钟用尽，所有 job **2~4 秒、0 个 step、无日志**地失败。2026-10-03 本机 WSL（24G + 8G swap）同时跑 2–3 个 Java Gate，swap 打满（8119/8192 MB）、15 分钟负载 106，job 卡在 Spring 启动阶段超时，或者报 runner lost communication。

⚠️ 本文件在 `.claude/rules/` 与 `.codex/rules/` 各有一份，**必须逐字相同**（见文末闸）。

---

## 一句话

**`[self-hosted, cretas]` / `[self-hosted, java]` / `[self-hosted, cretas, java]` 的 job 由加拿大 VM 的 3 个 runner 接；加拿大不健康时，aurora 在 3 分钟内自动顶上，加拿大恢复后自动撤下。⛔ 本机 WSL 不再跑 CI。**

路由靠 **runner 端标签**，workflow 文件一行不用改：
- 加拿大的 3 个实例和 aurora 都带 `cretas` 与 `java` 两个标签。
- 本机 WSL 的 runner 服务已停用并禁用。

⛔ `python scripts/ci/switch_ci_runner.py hosted` 已经**过时**，不要再拿它当"切托管"的开关。2026-10-04 实测（PR #879，已关闭）切到托管后：
- Python Gate 报 `Could not find database tool: initdb`（测试需要本机 PG 二进制）。
- ops 测试新增 3 条红：`test-java-heavy-jobs-need-8g-runner.sh`、`test-selfhosted-jobs-have-timeouts.sh`、`test-fresh-db-baseline-replays.sh`（rc=2）。
- 另外 `source-hygiene` / `crosslang-contract-gate` / `reap-orphan-testenvs` 根本不受这个脚本管。

脚本仍然负责"各 workflow 的 `runs-on` 写法彼此一致"这件事（⛔ 不要手工改单个 `runs-on`）。要真正切到托管，得先把上面这几处修好。

写死 `ubuntu-latest` 的 workflow（`ci.yml` 等）照旧走托管，不受本文件的自建路由影响。

---

## 自建 runner 是什么

### 主力：加拿大 Mac mini 上的 colima `ci` VM

| | |
|---|---|
| 机器 | 加拿大 Mac mini（`ssh canada-mini`，用户 rex，Tailscale `100.85.234.108`，M4 Pro 14 核 / 64 GB）上的 colima `ci` VM：Ubuntu 24.04 **arm64**，**12 核 / 44 GB**，数据盘 120 GB（挂在 `/mnt/lima-colima-ci`，root fs 只有 19 GB） |
| VM 保活 | launchd 代理 `~/Library/LaunchAgents/com.cretas.colima-ci.plist`（`colima start ci --foreground`，KeepAlive）。⚠️ `colima stop ci` 会停掉 VM，但不会让这个前台进程退出，所以 KeepAlive 不会重启它。改 VM 规格（`~/.colima/ci/colima.yaml`）之后要用 `launchctl kickstart -k gui/$(id -u)/com.cretas.colima-ci` 才能按新配置拉起 |
| runner | **仓级**：`canada-mini-cretas`、`canada-mini-cretas-2`、`canada-mini-cretas-3`，目录 `/mnt/lima-colima-ci/actions-runner-cretas{,-2,-3}`。同一个 VM 里还有 assembly 仓的 `canada-mini-ci` |
| 标签 | `self-hosted, Linux, ARM64, cretas, java, canada-mini` |
| systemd | 每个 unit 都带 drop-in `restart.conf`：`Restart=always`、`RestartSec=30`、`StartLimitIntervalSec=0`、`RequiresMountsFor=/mnt/lima-colima-ci`。2026-10-03 assembly runner 因为 DNS 抖动退出后没有重启，掉线 3 小时；2026-10-04 VM 重启时服务比数据盘先起来（203/EXEC），30 秒后由 Restart 拉起 |
| 工具 | JDK 21（apt）、Maven 3.9.16、PG 17 二进制在 PATH（`initdb`/`pg_ctl` 在 `/usr/local/bin`）、python3.11（deadsnakes，与 prod 同小版本）、python3.12-venv、zip、jq、docker |
| r10 常驻 PG | 集群 `17/r10`，**端口 5433**，开机自启。5432 留给同一个 VM 里 assembly `java-build-test` 的服务容器。runner `.env` 设 `R10_IT_PGPORT=5433`，`application-r10-it.properties` 和 nightly 引导步骤读这个变量；⛔ 不要在 `.env` 里全局设 `PGPORT`，那会让别的 psql 调用被静默改道 |
| 测试堆 | PR 闸读 `.env` 的 `CRETAS_JAVA_FORK_XMX=6g`（这台的执行顺序下 fast-contracts 用 3g / 4g 会 OOM，6g / 8g 能过）。nightly 全量读单独的 `CRETAS_JAVA_NIGHTLY_XMX=12g`：Spring 测试上下文缓存随全量累积，实测 3g 只跑到 2879/12012，6g 跑满但尾段 OOM 出 10 红，8g 跑满 12999 仍在最后 OOM 出 11 红。⛔ 不用 `spring.test.context.cache.maxSize` 压缓存：被淘汰的上下文会关掉共享的内存 H2 库，下游测试连带变红 |
| Maven 本地仓库 | VM 里 4 个 runner 共用一个本地仓库。并发下载时曾把 `poi-ooxml-4.1.2.jar` 读到写一半的状态（编译报 `zip END header not found`，事后文件完好）。每个 runner 的 `.env` 都设了 `MAVEN_ARGS=-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav`（Maven 3.9 跨进程文件锁） |
| 时区 | `America/Vancouver`（西七区），继承自宿主 |
| Mac 重启后自恢复 | ⚠️ 配置就绪、**未实测**：已开 rex 自动登录（`autoLoginUser=rex`、`/etc/kcpassword`、FileVault 关、`autorestart=1`）。链路 = 开机 → 自动登录 → launchd 拉起 VM → VM 内 systemd 拉起 runner |

### 备用：美国 aurora，自动故障切换

| | |
|---|---|
| 机器 | aurora（`ssh -i ~/.ssh/canada_mini_ed25519 aurora@100.92.5.62`，macOS 26，10 核 / 24 GB，常年不关机）。上面原本就有 colima `default` VM（4 核 / 6 GB，跑 pomelox-test 容器）、openclaw-gateway 和 iOS 模拟器，内存偏紧，swap 常年在用 |
| CI VM | 单独的 colima `ci` VM（6 核 / 12 GB / 100 GB），工具和 r10 集群与加拿大一致。**平时关着**。`CRETAS_JAVA_NIGHTLY_XMX=7g`：nightly 如果碰上故障切换落到 aurora，**会跑不满**（加拿大要 12g），棘轮会判 rc=1 或 rc=2，这是已知限制 |
| runner | `aurora-cretas`，仓级，标签 `self-hosted, Linux, ARM64, cretas, java, aurora`。systemd unit **disabled**，由看门狗控制启停 |
| 为什么不常驻 | GitHub 派 job **没有优先级**：带同样标签的空闲 runner 都会分到活。aurora 常驻就会和加拿大平分 job，内存会被压进 swap。所以它只在加拿大不健康时才开 |
| 看门狗 | `~/cretas-ci-failover.sh`，由 launchd `~/Library/LaunchAgents/com.cretas.ci-failover.plist` 每 60 秒运行一次，日志写到 `~/Library/Logs/cretas-ci-failover.log` |
| 健康检查 | aurora 用专用密钥 `~/.ssh/id_ed25519_cretas_ci_health` SSH 到加拿大。加拿大的 `authorized_keys` 里把这把钥匙限定为 `restrict,command="/Users/rex/ci-health.sh"`，只能拿到一行 `CRETAS_RUNNERS_ACTIVE=<n>`（VM 里活着的 cretas runner 服务数）。连不上或读数不对按 0 算。阴性对照：带别的命令（`ls /; whoami`）去调，拿回的仍然只是这一行 |
| 切换规则 | 连续 **3** 次 n=0 → 启动 aurora 的 VM 和 runner。n≥1 时，aurora 手上没有 `Runner.Worker` 才停 runner 并关 VM；有就等。每次 `colima start` 之后都把 docker context 切回 `colima`，不影响 aurora 原有的容器 |
| 没覆盖到的 | VM 和 runner 服务都活着、但 runner 卡死不接活，这种情况健康检查看不出来（服务是 active） |

#### 2026-10-04 故障切换实测（判据：真实 job 的 `runner_name`）

| 时刻（UTC） | 事件 |
|---|---|
| 16:57:48 | 停掉加拿大 3 个 cretas runner 服务（VM 和 Mac 不动），健康读数变成 `CRETAS_RUNNERS_ACTIVE=0` |
| 17:00:00 | 看门狗第 3 次读到 0：`FAILOVER ... -> start aurora ci VM` |
| 17:00:13 | `FAILOVER start aurora-cretas.service`，runner 上线后立刻接到排队的 job |
| 17:00:17–17:01:33 | aurora 跑完 `field-names`（run 37138722767），之后又跑了 `no-literal-nul-bytes`、`tracked-secret-scan`，三个都绿，`runner_name=aurora-cretas` |
| 17:01:50 | 恢复加拿大，健康读数 3 |
| 17:02:18 | `FAILBACK ... -> stop aurora-cretas.service and aurora ci VM`，aurora 变 offline，VM 已关，docker context 仍是 `colima` |

⚠️ 这次测试时我停加拿大之前读到 3 个实例都在忙，却没拿这个读数当闸门，打断了 3 个 job（另一条线 PR 的 Java Gate 两个 job 和一个 script-tests），之后用 `gh run rerun --failed` 重跑了。**下次演练前先确认 `busy=false`。** 要摘掉单个实例时，先等它手上的 job 跑完。

### 已停用：本机 WSL2

`cretas-ci.service`（`windows-wsl-ci`）、`cretas-ci-extra@2` / `@3` 都已 `systemctl disable --now`。要恢复：在 WSL 里以 root 执行 `systemctl enable --now cretas-ci.service`（extra 实例是 `cretas-ci-extra@N.service`）。它们是**组织级** runner，仓级 token 列不出来，也改不了它们的标签。

加拿大 Mac mini 时期的 macOS runner（`canada-mac-mini` / `-2`）早已退役，那个时期的接入方式在案例库，⛔ 不要再照着去连。

---

## 新接一个工作流：要过六关，⛔ 光加文件名不算接进来

| # | 关卡 | 长什么样 |
|---|---|---|
| 1 | **缩进** | 脚本按**字面量**匹配 `    runs-on: ubuntu-latest`（4 空格）。缩进不同 → 静默不命中 |
| 2 | **`actions/setup-python`** | 自建 macOS 上**必失败**（安装脚本把 `/Users/runner` 写死）⇒ 条件化 + 建 venv。Linux VM 上用系统 python3.11 |
| 3 | **Linux 专有命令** | `nproc` / `free` / `apt-get` / `stat -c` 在 macOS 上不存在（现役 VM 是 Linux，但 workflow 要能在两边都跑） |
| 4 | **缺工具** | `setup-java` 只装 JDK **不装 Maven**；homebrew 只给 `pip3` / `python3`，没有裸 `pip`、也没有裸 `python` ⇒ 探测 + 兜底。⚠️ 扫的时候**不能只看 `uses:`**，run 步骤里的裸解释器同样要查 |
| 5 | **平台专用 wheel** | `torch==2.4.1+cpu` 的 `+cpu` 只有 x64 Linux 才有，macOS 和 **ARM64 Linux** 都没有这个变体。Python Gate 在 `runner.os == macOS` 或 `runner.arch == ARM64` 时剥掉 `+cpu`（#883）；ARM64 上 PyPI 的 plain `torch==2.4.1` 本身就是 CPU build |
| 6 | 🔴 **`services:` / 容器** | 加拿大和 aurora 的 VM 有 Docker，但加拿大 VM 的宿主 5432 被 assembly 的服务容器占着。A2 闸（`scripts/tests/test-java-heavy-jobs-need-8g-runner.sh`）钉着：可溢出的 Java job 形态不许带 `services:` |

⛔ 六关不逐条过就把文件名加进来，换来的是「从跑不起来」变成「跑起来就红」。

### `python-gate` 在两种 runner 上跑的不是同一个解释器

托管钉 `python-version: '3.10'`；自建用系统 Python（加拿大 / aurora VM 是 **3.11.17**，prod 是 3.11.13）。该步骤把 `PYTHON_USED=<解释器> (<版本>)` 打进日志，并只断言下限 `>=3.10`（⛔ 不允许静默用更老的版本跑；⛔ 也不要改成等值断言）。读它的绿时要连版本一起读。

---

## ⛔ 别用组织变量做路由

`runs-on: ${{ vars.CI_RUNNER && fromJSON(vars.CI_RUNNER) || 'ubuntu-latest' }}` 不工作：`runs-on` 在 job 编排阶段求值，那时 `vars` 够不着，job 实际落成 `labels=ubuntu-latest`。这是能力边界，不是配置问题（见案例库）。

▎判据：`runs-on` 里只放字面量。主备切换靠 runner 端标签和启停，不靠 workflow。

---

## ⛔ 别再试的路

| 载体 | 状态 |
|---|---|
| 阿里云 139 / 47 | ⛔ 堵死：git 长连接连不动（`git clone --depth 1` 连 10MB 小仓都超时） |
| 本机 WSL2 | ⛔ 停用（owner 2026-10-04：内存要炸）。恢复方法见上 |
| 本机 Windows 直跑 | ⛔ 堵死：工作流全是 bash 步骤 |
| aurora 常驻接活 | ⛔ 不做：没有优先级，常驻就是平分 job；它的内存撑不住 |

---

## 工作流里按 runner 类型分支

`setup-python` 那一步按 runner 类型分支，自建用系统 Python 并断言版本 `>=3.10`：

```yaml
- uses: actions/setup-python@v5
  if: runner.environment == 'github-hosted'
- name: Use system Python (self-hosted)
  if: runner.environment != 'github-hosted'
```

改了 runner 的 `.path` / `.env` 都要**重启 runner 服务**才生效。

---

## 时区覆盖：自建 runner 又回到西半球了

Mac mini 时期 runner 在西半球，首次接入当天就照出 3 个东八区永远测不出来的 bug（见案例库）。共同形状：**`Date.now() + 8h` 这种「平移 UTC 轴假装某时区」的写法，必须用 UTC getter 读回**，换成本地 getter 等于又叠一次设备偏移。

WSL2 时期 runner 和开发机同在东八区，这层免费覆盖没有了。现在加拿大 VM 是 `America/Vancouver`（aurora 在美国），**覆盖又回来了**：同一个断言在开发机（东八区）和 CI（西七区）上各跑一遍。

⛔ **别在 Windows 原生 shell 里用 `TZ=x cmd`**：那个前缀根本不传进 node（`process.env.TZ` 是 `undefined`，偏移恒为 `-480`）。`TZ=` 前缀只在 Linux / Mac 上生效。

---

## 判据

- **不看配置说了什么，看 job 实际落在哪**：
  `gh api repos/cretasfuture-ai/cretas/actions/jobs/<id> --jq .runner_name`
- runner 状态（仓级）：`gh api repos/cretasfuture-ai/cretas/actions/runners --jq '.runners[]|.name+" "+.status+" busy="+(.busy|tostring)'`
- 加拿大健康读数：`ssh canada-mini '~/ci-health.sh'`
- aurora 切换记录：`ssh -i ~/.ssh/canada_mini_ed25519 aurora@100.92.5.62 'grep -E "FAILOVER|FAILBACK" ~/Library/Logs/cretas-ci-failover.log | tail'`
- ⚠️ `status=offline` 但 `busy=true` 是**正常的中间态**（正在重连），不要据此判定它挂了；aurora 撤回后 GitHub 上还会显示一会儿 online

关联：`worktree-and-main-only-deploy.md`（部署只从 main）、`concurrent-edit-safety.md`（commit 范围保护）。

## 跑批判定：退出码是开放集合

▎**不是 0 也不是 1，一律当成「没量到」。⛔ 不要枚举失败码。** 枚举 `rc == 1` / `rc == 2` 时，第四种值（如 `python: command not found` 的 127）两条都不命中、静默落进绿（`java-full-nightly` 首次真跑就是这样：job success，artifact 146 字节）。抓住它的是「artifact 大小」这个设计上必然会变的读数——硬约束 9。
