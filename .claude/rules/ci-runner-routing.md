# CI runner 路由：全部 GitHub 托管（仓库已 public）

## 🔴 现行（2026-10-25，owner 决定）

- 仓库 `cretasfuture-ai/cretas` 已转 **public**。全部工作流 `runs-on: ubuntu-latest`（公开仓库托管 runner 免费、不限分钟、4 vCPU / 16 GB，免费计划最多并发 20 个 job）。
- ⛔ **任何 job 都不许再指向自建 runner**：公开仓库谁都能提 PR，自建路由会让陌生人的代码在我们的机器上执行。闸 `scripts/tests/test-public-repo-hosted-only.sh`（全部托管 + 原自建 job 带超时 + Java 显式堆）；`switch_ci_runner.py self-hosted` 直接拒绝。
- 外部 PR：仓库设置 `fork-pr-contributor-approval = all_external_contributors`，外部贡献者的 PR 必须我们点批准才运行；外部贡献者没有写权限，合并只能由组织成员做。
- 托管机上的差异已在工作流里处理：测试库用 PGDG 装 PostgreSQL 17（`run-with-test-postgres.sh`，托管镜像自带 16）；Java Gate `CRETAS_JAVA_FORK_XMX=6g`、nightly `CRETAS_JAVA_NIGHTLY_XMX=12g` 写在 job env；Fresh DB 预检在托管机上跳过 Aurora 身份检查、保留资源/Docker/JDK/Maven 检查。
- Aurora、本机轻任务槽、小检查槽全部退役（`tools/ci/windows-*` 只作历史）。要恢复自建 runner，必须先把仓库改回 private。
- **慢 job 必须拆分片，跑满托管并发**（2026-11-10）：托管并发上限 20 个 job，单个 job 不许独占 30–40 分钟。Java Gate 的 `fast-contracts`、`postgres-reversal-concurrency` 已用 `strategy.matrix` 拆成 4 片（`*-shard` job，`runs-on: ubuntu-latest` 字面量），类名哈希取模分片（`scripts/ci/shard-tests.py`），同名汇总 job 保留原 job 名聚合各片结论（`needs` + `if: always()`，任一片非 success 即红），分支保护不变。目标单片 ≤10 分钟，超了就加片数。新增 PG 测试类写进 `scripts/ci/java-gate-pg-tests.txt`（一行一类），新增 fast 闸命名模式写进 `scripts/ci/java-gate-fast-patterns.txt`；⛔ 不要往 workflow 的 `-Dtest` 行追加（并行 PR 会在同一行冲突）。闸 `scripts/tests/test-java-gate-shards-cover-selector.sh` 钉住「所有分片并集 == 选择器全集、无重叠、matrix 不漏片」。
- 判据仍是实际 job 的 `runner_name`（托管机形如 `GitHub Actions N`），不看配置。

## 仍然有效的通用规则（2026-11-10 从退役历史里挑出）

- **`runs-on` 只放字面量**：⛔ 不用组织变量做路由（`runs-on` 在 job 编排阶段求值，`vars` 读到空，job 落成 `labels=ubuntu-latest`；能力边界，不是配置问题）。各工作流 `runs-on` 写法由 `scripts/ci/switch_ci_runner.py --check` 统一核对，⛔ 不手工改单个。
- **跑批退出码是开放集合**：不是 0 也不是 1，一律当成「没量到」，⛔ 不枚举失败码（`python: command not found` 的 127 曾静默落进绿）。抓它要靠设计上必然会变的读数（如 artifact 大小），见 `measurement-and-wiring.md` 硬约束 9。
- **时区覆盖**：托管 runner 是 UTC，开发机是东八区，同一断言两边各跑一遍。`Date.now() + 8h` 这类「平移 UTC 轴假装某时区」的写法必须用 UTC getter 读回。⛔ 别在 Windows 原生 shell 里用 `TZ=x cmd`（不传进 node）。
- **判据看 job 实际落在哪**：`gh api repos/cretasfuture-ai/cretas/actions/jobs/<id> --jq .runner_name`，不看配置。

自建 runner 时期（Aurora / 加拿大 VM / 本机 WSL）的完整原文、六关接入、故障切换实测，全部移到 `docs/rules-casebook/ci-runner-routing-cases.md` 末尾「退役前规则原文」一节，恢复自建 runner 前必读（且须先把仓库改回 private）。
