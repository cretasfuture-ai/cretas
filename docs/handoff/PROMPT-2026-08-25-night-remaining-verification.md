# 新会话可直接粘贴的 Prompt

```text
接手并完整执行：

docs/handoff/HANDOFF-2026-08-25-night-f006-cost-closed-remaining-verification.md

（已在 origin/main 上，仓库相对路径可直接读）
```

不要只输出方案。先完整读取最新 `AGENTS.md`、适用 `CLAUDE.md`、最新 `docs/dispatch/ACTIVE.md`
和上述交接件，**尤其是交接件第 4 章「已排除的错误路径」** —— 那一章能省下一整轮弯路。

## 开工前

1. `git fetch origin`，核对 `origin/main`、`git worktree list --porcelain`、`git status --porcelain`、ACTIVE。
2. 主工作区 `C:\Users\Steve\my-prototype-logistics` 有大量其他 Owner 改动。
   **禁止** reset / stash / clean / 覆盖 / 直接改。从最新 `origin/main` 开独立 worktree。
3. `gh` 命令**必须带 `-R cretasfuture-ai/cretas`** —— 否则会解析到错误的仓库并报
   "No commits between"。
4. 实时核验生产：真实 upstream（蓝绿会交替）、active systemd、进程启动时间、JAR mtime、直连健康。

## 本轮目标：剩余 7 项的实现与验证

`ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`、
`ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001`、
`BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001`、
`ENH-F006-SUPPLIER-IMPORT-001`、
`ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001`、
`BUG-F006-R2-SKU-UNIT-001`、
`FEATURE-F006-R2-WORKPROCESS-DEDUP-001` / `FEATURE-F006-R2-BOM-SUBSTITUTE-001`

⚠️ **成本链路本轮已闭环，不要重做**（详见交接件第 2 章）。剩余阻断已定性为数据问题，不是代码缺陷。

## 硬性纪律（上一轮踩过的，逐条写在这里）

1. **台账描述可能过期** —— 每一项先跑一次/查一次真实数据再下结论。已有先例：
   「客户必填规则冲突」实际是过期描述，四个面本就一致。
2. **不要在第一个自洽的解释处停下**。成本那条链上每一层都像根因，实测否掉了 5 个判断。
   判据是「拿真实数据逐行对账」，不是「解释得通」。
3. **变异不红时，先证明变异生效**。`mv .bak` 会带回旧 mtime 让 Maven 跳过重编 ——
   每次变异/恢复后 `touch` 源文件。
4. **含转义的内容全程不经过 shell**（heredoc 会吃掉一层反斜杠）。用 Write 落盘。
5. **squash 合并后立刻从新 `origin/main` 开新分支 cherry-pick**，不要在原分支追加。
6. **任何「0」读数都要阳性对照**（搜一个必然存在的串），否则可能是仪器坏了。
7. **遇到部署锁禁止 `rm`** —— 先 `Get-Process` 确认持有者是否存活，活着就等。
8. 提交用 `./scripts/safe-commit.sh "<msg>" <files...>`；新文件先 `git add`。

## 不可越的边界

- **F006 以外租户生产业务写入必须为 0**。LIUSHANMEN 有 2 行 `item_cost` 不一致，
  **不要顺手修** —— 即使被要求「全部做完」，也不含其他租户的生产数据。
- `totalCost` 的 COGS 口径：缺**单价**类必须继续阻断，已由阴性断言钉住，不得为了数字好看放宽。
- 迁移 / 权限 / 全局配置 / 真实付款 / 批量破坏性操作仍需各自明确授权。

## 门禁

- Java：目标测试；触及 Entity/Repository/JPQL/Flyway 必须真实 JPA Context 测试。
- 闸命名必须落在 CI 选择器里：`*ContractTest` / `*StartupGuardTest` / `*RepositoryQueryValidationTest`。
- Web：目标 Vitest + `npx vue-tsc -b`（**权威**，`--noEmit` 在本仓检查 0 个文件）。
- 发布判据只看：`DEPLOY_EXIT=0` **且** `RELEASE_FINAL_STATUS` **恰好 1 次**。
- 部署后**真跑一次那条路径**并核对运行制品含本次改动的标记；脚本 SUCCESS 不算证据。

## 交付

每项分别报告：实现、目标测试（含变异对照）、commit、PR、main 合入、迁移、部署、生产验收、
未闭环项及原因。缺产品裁决/凭证/设备时**只阻塞该项**并继续其他项，不要扩大成整体停止。

现在开始执行，不要只给计划。
