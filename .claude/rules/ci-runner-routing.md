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
- **唯一例外（2026-11-18，Steve 撤销「公开仓库禁自建」，前提实测 mini 更快）**：Java Gate 的 PG 这条线（判定作业 `changes`、4 片 `postgres-reversal-concurrency-shard`、汇总作业 `postgres-reversal-concurrency`；首尾不挪的话分片仍要等托管机排队）在本仓库分支 PR 和合并队列上跑 canada-mini，fork PR 落托管。runner 是一次性 JIT runner（标签 `cretas-mini-pg`，2 个槽位，名字形如 `canada-mini-gate-N-<时间戳>`）：rex 名下的 launchd `com.cretas.gh-gate-N` 跑 `~/gh-gate/refill.sh`，用 rex 的 gh 令牌（mini 账号 `canadaremote001-coder`，Steve 2026-11-18 授权为仓库 admin，只为注册 runner）换 JIT 配置，交给无密码、非管理员、不能登录的 `ghgate` 账户跑，每个任务一个全新目录、跑完删除；`/Users/rex`（部署密钥、令牌）对 ghgate 不可读，任务里的读数步骤读到可读就红。表达式逐字登记在闸的 `SELF_HOSTED_ALLOWED`，必须带「`merge_group` 或 `head.repo.full_name == github.repository`」守卫，阴性对照钉住去掉守卫即红。mini 离线时 job 会一直排队不回落：给 PR 打 `ci-hosted` 标签回托管，合并队列要回托管只能改表达式。发布构建优先：补位脚本在 `~/cretas-build/.build.lock` 被持有时不领新任务。⛔ mini 上不开 Claude 会话，runner 只经 ssh 配置。新增例外先在闸里登记，⛔ 不手改别的 job。 脚本原件在仓库 `scripts/ci/canada-mini-gate/`（`setup.sh` 建账户和 launchd，`refill.sh` 补位循环，`job-started.sh` 任务开始钩子），改动先改仓库再 scp 上去。任务里的 git 走本机完整镜像 `~ghgate/mirror/cretas.git`：钩子在任务开始前从 GitHub 增量更新，`.env` 用 `url.<镜像>.insteadOf` 把任务内拉取改到本机（2026-11-18 实测 actions/checkout 不报本地已有对象，mini 每次整份下载历史，`changes` 5 分钟拉不完）。
- 外部 PR：仓库设置 `fork-pr-contributor-approval = all_external_contributors`，外部贡献者的 PR 必须我们点批准才运行；外部贡献者没有写权限，合并只能由组织成员做。
- 托管机上的差异已在工作流里处理：测试库用 PGDG 装 PostgreSQL 17（`run-with-test-postgres.sh`，托管镜像自带 16）；Java Gate `CRETAS_JAVA_FORK_XMX=6g`、nightly `CRETAS_JAVA_NIGHTLY_XMX=12g` 写在 job env；Fresh DB 预检在托管机上跳过 Aurora 身份检查、保留资源/Docker/JDK/Maven 检查。
- Aurora、本机轻任务槽、小检查槽全部退役（`tools/ci/windows-*` 只作历史）。「要恢复自建 runner 必须先改回 private」已由 Steve 2026-11-18 撤销（托管并发只有 20 个 job，CI 长期排队；mini 实测更快）：仓库保持 public，自建只走上面登记的例外，fork PR 永远托管。
- **慢 job 必须拆分片，跑满托管并发**（2026-11-10）：托管并发上限 20 个 job，单个 job 不许独占 30–40 分钟。Java Gate 的 `fast-contracts`、`postgres-reversal-concurrency` 已用 `strategy.matrix` 拆成 4 片（`*-shard` job，`runs-on: ubuntu-latest` 字面量），类名哈希取模分片（`scripts/ci/shard-tests.py`），同名汇总 job 保留原 job 名聚合各片结论（`needs` + `if: always()`，任一片非 success 即红），分支保护不变。目标单片 ≤10 分钟，超了就加片数。新增 PG 测试类写进 `scripts/ci/java-gate-pg-tests.txt`（一行一类），新增 fast 闸命名模式写进 `scripts/ci/java-gate-fast-patterns.txt`；⛔ 不要往 workflow 的 `-Dtest` 行追加（并行 PR 会在同一行冲突）。闸 `scripts/tests/test-java-gate-shards-cover-selector.sh` 钉住「所有分片并集 == 选择器全集、无重叠、matrix 不漏片」。
- 判据仍是实际 job 的 `runner_name`（托管机形如 `GitHub Actions N`），不看配置。

## 仍然有效的通用规则（2026-11-10 从退役历史里挑出）

- **`runs-on` 只放字面量**（唯一例外是上面登记的、只读 `github` 上下文的自建路由表达式）：⛔ 不用组织变量做路由（`runs-on` 在 job 编排阶段求值，`vars` 读到空，job 落成 `labels=ubuntu-latest`；能力边界，不是配置问题）。各工作流 `runs-on` 写法由 `scripts/ci/switch_ci_runner.py --check` 统一核对，⛔ 不手工改单个。
- **跑批退出码是开放集合**：不是 0 也不是 1，一律当成「没量到」，⛔ 不枚举失败码（`python: command not found` 的 127 曾静默落进绿）。抓它要靠设计上必然会变的读数（如 artifact 大小），见 `measurement-and-wiring.md` 硬约束 9。
- **时区覆盖**：托管 runner 是 UTC，开发机是东八区，同一断言两边各跑一遍。`Date.now() + 8h` 这类「平移 UTC 轴假装某时区」的写法必须用 UTC getter 读回。⛔ 别在 Windows 原生 shell 里用 `TZ=x cmd`（不传进 node）。
- **判据看 job 实际落在哪**：`gh api repos/cretasfuture-ai/cretas/actions/jobs/<id> --jq .runner_name`，不看配置。

自建 runner 时期（Aurora / 加拿大 VM / 本机 WSL）的完整原文、六关接入、故障切换实测，全部移到 `docs/rules-casebook/ci-runner-routing-cases.md` 末尾「退役前规则原文」一节，新增自建例外前必读（public 下的防线是本仓库守卫 + 闸登记，见上面「唯一例外」）。
