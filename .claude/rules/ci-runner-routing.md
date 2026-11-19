---
paths:
  - ".github/workflows/**"
  - "tools/ci/**"
  - "scripts/ci/**"
---

# CI runner 路由：全部 GitHub 托管（仓库已 public）

## 🔴 现行（2026-10-25，owner 决定）

- 仓库 `cretasfuture-ai/cretas` 已转 **public**。全部工作流 `runs-on: ubuntu-latest`（公开仓库托管 runner 免费、不限分钟、4 vCPU / 16 GB，免费计划最多并发 20 个 job）。
- ⛔ **除登记的例外，job 不许指向自建 runner**：公开仓库谁都能提 PR，自建路由会让陌生人的代码在我们的机器上执行。闸 `scripts/tests/test-public-repo-hosted-only.sh`（全部托管 + 原自建 job 带超时 + Java 显式堆）；`switch_ci_runner.py self-hosted` 直接拒绝。
- **登记的自建例外**：Java Gate 的 fast、PG 分片与汇总，RN Gate `jest`、Web Admin Gate `vitest` 可走 canada-mini。共用池最多 4 槽，当前标签 `cretas-mini`，过渡期同时消费旧标签 `cretas-mini-pg` / `cretas-mini-fast`。GitHub 托管的 `changes` 从受保护的 main 基线读取路由脚本，先于候选代码运行。直接 PR 的 head 仓库须是本仓库。合并组按事件 SHA 的 first-parent 链与 GitHub merge queue 逐项匹配，组内每个 PR 的 head 仓库须可读且等于本仓库。缺项、fork、任一 PR 带 `ci-hosted` 均走托管。白名单 `SELF_HOSTED_ALLOWED` 逐项登记六条表达式，并要求首次 attempt、可信策略版本和合并组验证输出。组织层 runner 隔离是待办（见 security backlog），不阻塞开放。
- **派发与恢复**：`refill.sh` 每分钟发布 `MINI_POOL_CAPACITY=Unix秒:可接单槽数:空闲槽数`。仅已启用且 launchd 在跑的槽计入，发布锁、过热或半醒时发布零容量。路由用派发当下的时间校验心跳，缺失、超过 180 秒或零空闲槽则走托管。两端共用 `queue-snapshot.sh`，分页读取 queued / in_progress run 和 jobs，校验 API 总数，数三个 mini 标签的 queued / in_progress 作业。每份快照最多 12 个去重的活跃 run、16 个 REST 请求。超限或读数不完整时路由走托管，refill 此轮不接单。实时忙槽数已达容量也走托管。值班观察到作业在 mini 等待满 5 分钟就按 runbook 发起恢复：三条 workflow 只对 `ci-hosted` 标签触发恢复作业，Java 另保留 `java-full-ready` 的全量门禁；无关标签的 run 使用独立并发组且跳过作业。完整重跑和仅重跑失败作业的 attempt > 1 均走托管，合并组过期时打标签后重新入队。发布步骤与核验读数见 [共用槽池切换与恢复](../../scripts/ci/canada-mini-gate/rollout.md)。开放前须完成双标签消费者核验、槽 5 排空与清理。在 `MINI_POOL_V2_ENABLED=0` 的托管探针确认 `GITHUB_TOKEN` 可读 merge queue，且 `headCommit` 对应合并组提交后才设 `MINI_POOL_V2_ENABLED=1`。
- **时段与缓存**：北京时间 22:00–08:00 启用槽 1–4（阈值 3），08:00–22:00 启用槽 1–3（阈值 2）。白天 PG 片 2、3 和 fast 片 3 可上 mini，夜间 PG 全部和 fast 片 2、3 可上 mini。时段唯一实现为 `refill.sh --window`，读取不到按白天处理。可用 `~/gh-gate/enabled-slots` 覆盖启用槽，超出上限无效。白天全开需同时设置仓库变量 `MINI_DAY_FULL=1` 和 mini 的 `~/gh-gate/day-full`。mini 上只用 npm 和 Maven 的常驻缓存，禁止动作往共享目录恢复归档。继续使用 `job-started.sh` 更新本地完整镜像。
- **mini 运维**：`setup.sh [槽数]` 默认及上限 4，卸载前先排空超出请求槽数的旧 LaunchAgent。JIT runner 由 rex 启动并交给受限的 ghgate 账户，作业后清理本槽临时进程与 runner 目录。空闲 Runner.Listener 等 180 秒无任务即回收。用 `caffeinate` 使机器完全唤醒并避免任务执行时入睡，触发热警告暂停接单，保留原有发布锁优先策略。系统电源设置由 Steve 管理。失效合并组继续由 mini 的 `reap-stale-merge-groups.sh` 和托管的 `merge-queue-reaper.yml` 清理，读不到队列时不取消。mini 上不开 Claude 会话。
- 外部 PR：仓库设置 `fork-pr-contributor-approval = all_external_contributors`，外部贡献者的 PR 必须我们点批准才运行；外部贡献者没有写权限，合并只能由组织成员做。
- 托管机上的差异已在工作流里处理：测试库用 PGDG 装 PostgreSQL 17（`run-with-test-postgres.sh`，托管镜像自带 16）；Java Gate `CRETAS_JAVA_FORK_XMX=6g`、nightly `CRETAS_JAVA_NIGHTLY_XMX=12g` 写在 job env；Fresh DB 预检在托管机上跳过 Aurora 身份检查、保留资源/Docker/JDK/Maven 检查。
- Aurora、本机轻任务槽、小检查槽全部退役（`tools/ci/windows-*` 只作历史）。「要恢复自建 runner 必须先改回 private」已由 Steve 2026-11-18 撤销（托管并发只有 20 个 job，CI 长期排队；mini 实测更快）：仓库保持 public，自建只走上面登记的例外，fork PR 永远托管。
- **慢 job 必须拆分片，跑满托管并发**（2026-11-10）：托管并发上限 20 个 job，单个 job 不许独占 30–40 分钟。Java Gate 的 `fast-contracts`、`postgres-reversal-concurrency` 已用 `strategy.matrix` 拆成 4 片（`*-shard` job，`runs-on: ubuntu-latest` 字面量），类名哈希取模分片（`scripts/ci/shard-tests.py`），同名汇总 job 保留原 job 名聚合各片结论（`needs` + `if: always()`，任一片非 success 即红），分支保护不变。目标单片 ≤10 分钟，超了就加片数。新增 PG 测试类写进 `scripts/ci/java-gate-pg-tests.txt`（一行一类），新增 fast 闸命名模式写进 `scripts/ci/java-gate-fast-patterns.txt`；⛔ 不要往 workflow 的 `-Dtest` 行追加（并行 PR 会在同一行冲突）。闸 `scripts/tests/test-java-gate-shards-cover-selector.sh` 钉住「所有分片并集 == 选择器全集、无重叠、matrix 不漏片」。
- 判据仍是实际 job 的 `runner_name`（托管机形如 `GitHub Actions N`），不看配置。

## 仍然有效的通用规则（2026-11-10 从退役历史里挑出）

- **`runs-on` 只放字面量**（唯一例外是上面登记的、只读 `github` / `matrix` 上下文和 `needs.changes.outputs.runner|mini_window` 的自建路由表达式）：⛔ 不用组织变量做路由（`runs-on` 在 job 编排阶段求值，`vars` 读到空，job 落成 `labels=ubuntu-latest`；能力边界，不是配置问题）。各工作流 `runs-on` 写法由 `scripts/ci/switch_ci_runner.py --check` 统一核对，⛔ 不手工改单个。
- **跑批退出码是开放集合**：不是 0 也不是 1，一律当成「没量到」，⛔ 不枚举失败码（`python: command not found` 的 127 曾静默落进绿）。抓它要靠设计上必然会变的读数（如 artifact 大小），见 `measurement-and-wiring.md` 硬约束 9。
- **时区覆盖**：托管 runner 是 UTC，开发机是东八区，同一断言两边各跑一遍。`Date.now() + 8h` 这类「平移 UTC 轴假装某时区」的写法必须用 UTC getter 读回。⛔ 别在 Windows 原生 shell 里用 `TZ=x cmd`（不传进 node）。
- **判据看 job 实际落在哪**：`gh api repos/cretasfuture-ai/cretas/actions/jobs/<id> --jq .runner_name`，不看配置。

自建 runner 时期（Aurora / 加拿大 VM / 本机 WSL）的完整原文、六关接入、故障切换实测，全部移到 `docs/rules-casebook/ci-runner-routing-cases.md` 末尾「退役前规则原文」一节，新增自建例外前必读（public 下的防线是本仓库守卫 + 闸登记，见上面「唯一例外」）。
