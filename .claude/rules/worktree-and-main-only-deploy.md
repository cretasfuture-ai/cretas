# Worktree 隔离 + 只从 main 部署 prod (HARD RULE)

**最后更新**: 2026-10-16（复用已隔离的任务 worktree；离线期与 CI 判读叙事存档在 `docs/rules-casebook/worktree-and-main-only-deploy-cases.md`）
**触发事故**: 2026-05-30 青花椒 RBAC 修复在 prod 被并发 session 的 Java 部署覆盖, 总营收回归 ¥0。

## ⛔ 四条铁律

### 1. 永远不在主工作目录直接干活

主目录 `C:\Users\Steve\my-prototype-logistics` 被多个并发 session 共享，不能在其中混入代码改动。代码任务先核验现有 worktree 的路径、分支/HEAD、scope 与唯一 Owner，复用合格的本任务副本；无可复用副本时才新建，**永远 off `origin/main`**（off 别的 feature 分支会夹带 sister 的 commit）。只读/调研不新建。⛔ 主目录不混入任何改动（含文档、规则、台账）——要改就在本任务的 worktree 里改，已有合格副本就复用，不因为是纯文档而重建。

```bash
git worktree add -b codex/claude-<task> ../cretas-<task> origin/main
```

### 2. 完成 → 推上 origin/main（不在 feature 分支上累积部署）

merge 前 `git diff origin/main...HEAD --stat` 确认只有你的文件；夹带 sister 文件就 cherry-pick 到干净的 origin/main worktree 重做。squash 合并之后本地分支必须重开（measurement-and-wiring 硬约束 7）。

**合入通道双轨**（2026-07-28 Steve 拍板）：

| 变更 | 通道 |
|---|---|
| `docs/**.md`、`.claude/**`、根 `*.md`、`.gitignore` | **fastlane 直推**：`./scripts/deploy/publish-main-fastlane.sh --base-sha <完整 40 位 SHA> --confirm YES-DIRECT-MAIN`（先 `--dry-run`）。fast-forward only、禁 force push、推前 re-fetch 证明 origin/main 未前进；分支名必须 `codex/*` |
| 其余（含 `docs/dispatch/**`、配置文件）| fastlane 须带 `--task-id <本批任务ID>`（ID 必须以词边界出现在 `docs/dispatch/` 下），否则门禁按「全局零未完成任务」判定而恒拒 |
| backend / web-admin 代码 | **PR**（Python Gate / Web Admin Gate / Secret regression 按 paths 跑；PR 号是台账锚点） |
| AGENTS.md / `.agents/skills/*` / `.codex/rules/*` / workflows / `scripts/deploy/*` / 迁移 / entity / repository / security | **强制 PR**（fastlane 列为高风险自动拒绝；`.claude/` 豁免而 `.agents/`、`.codex/` 高风险，三个目录名很像） |

分档是脚本说了算，拿真实 commit 跑一次 `--dry-run` 看 `FASTLANE_READY` 还是 `FASTLANE REJECTED: <原因>`。底线：**任何通道都必须推上 origin/main 后才可部署**，省的是 PR 仪式往返，不是 push 本身。

看 CI 结论：`gh` 时间戳是 UTC；PR 上的 `failure` 先查是不是 `cancelled`（`concurrency: cancel-in-progress` 让 push 与 pull_request 互相取消）；判「某个闸还在不在」看 yml 文件，不看 `gh workflow list`。

### 3. prod 永远从 main 构建 / 部署，绝不从 feature 分支

```bash
# 合并【前】在候选 worktree: 先 build + 预传 (硬规则 2026-09-15; manifest 按 backend tree 复用, 合并后部署 ≈1 min)
./scripts/deploy/release-cretas.sh --phase build --base-sha '<Base SHA>' --tests '<tests>' --stage-backend YES-STAGE
# 合并后:
git fetch origin main && git checkout --detach origin/main      # 或 clean 的 main worktree
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<同上选择器>' --no-prefer-ci-artifact --confirm-prod YES-PROD
# ⛔ 不跑 prewarm-main-artifact.sh: CI 制品车道在本组织不通 (无 attestation + 制品存储配额), 等 420s 纯浪费
# 判据: DEPLOY_EXIT=0 且日志里 RELEASE_FINAL_STATUS 恰好 1 次 (不出现本身就是失败信号)
```

`deploy-backend.sh` 上传到**固定的共享 jar 路径**，多个 session 各自从 feature 分支部署 = last-write-wins 互相覆盖（5/30 事故原形）。根治：prod = main，所有工作 merge 进 main 再部署。`deploy-backend.sh` 的 idle readiness 三态：`UP:*` 健康 / `TIMEOUT`、`RESTART_LIMIT:*` 真失败 / 空串是 ssh 断线**没量到**（脚本先重试再独立核验；2026-09-02 曾把断线判死回滚）。单组件入口与逃生路见 `deploy-backend` skill。

### 4. worktree 总数 ≤ 9，开新的之前先体检，做完顺手收自己的

2026-10-07 清理前仓库挂着 76 个 worktree（大半是已合并的、一批是没合并却没人管的），目录占盘、`node_modules` 删起来按分钟算，还有 junction 穿透的风险。铁律 1 要求「每个任务一个 worktree」，没有对应的收口，数量只会涨。

- **开新 worktree 之前**：`bash .claude/tools/worktree-hygiene.sh --check`。总数 > 9（脚本返回 3）就先 `--apply`，再开。先用 `list_sessions` 把 `isRunning` 的 session 的 cwd 用 `--keep <路径>` 传进去。
- **任务做完 / PR 合并后**：把**自己**这个 worktree 收掉（从主仓库目录执行，不要在要删的目录里执行）：`bash .claude/tools/worktree-hygiene.sh --apply`；它会 WIP commit 脏改动、摘 junction、不加 `--force` 地移除，分支保留。
- 脚本保留：主仓库 / 当前所在 / `.codex/worktrees` / `--keep` / locked / 近 120 分钟有动静的。没合并的分支**不删**，分支本身就是备份，最后汇报「分支名 / 领先几个提交 / 有没有开着的 PR」。
- 兜底：每周二 8 点定时任务 `cretas-worktree-cleanup` 再扫一遍。
- ⛔ 不对带 junction 的目录用 `git worktree remove --force`；⛔ 不替 detached 且有改动的 worktree 擅自建分支（脚本会跳过并报告）。

## 必须在 prod 验证 feature 时

测试环境的当前分配见 `server-operations` skill 的服务器地图（YOLO 只在本机 Windows/RTX）。若必须在 prod 验 feature 分支：验完**立即 merge 到 main + 从 main 重部**，然后核对运行中的 jar 含你的修复：

```bash
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/.../YourClass.class' | strings | grep -c '<ASCII 修复标记>'"
```

## 🔌 GitHub 不可用时：本地 `main` 是唯一汇合点

铁律 3 的意图（prod = 所有人都汇合过的点）照样成立，载体从 `origin/main` 换成本地 `main`。所有 worktree 共享同一个 `.git`，本地 merge 不需要网络。

1. 汇合点固定用本地 `main`，不新造整合分支；只有占着 `main` 的那个 worktree 能往上合（天然串行）。动它之前**单独跑一次** `git status --porcelain` 看结果再决定，别和 merge 压进同一条命令。要看「我要动哪些文件」问自己的 commit（`git show --name-only --format= <分支>`），⛔ 不用 `git diff --name-only main..<分支>`（答案取决于基底，会把本地 main 领先的文件全算成你的）。
2. 部署从 detached worktree：`git worktree add --detach ../cretas-deploy-offline $(git rev-parse main)`，然后 `SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-backend.sh --env prod`（web-admin 同理）。逃生门只在「已合进本地 main + 从 main 部署」前提下用；`check_git_sync` 离线时只 WARN，「部署入口全被闸挡住」是不完整的结论。
3. 合并后、部署前跑迁移撞号闸：`mvn -o test -Dtest='FlywayVersionUniquenessTest,*RepositoryQueryValidationTest'`（git 对 Flyway 撞号一个字不报）。
4. 每次离线部署记账：部署了哪个本地 commit、含哪些分支、跑了哪些迁移，写进当天 handoff。
5. 恢复后清单**现算**：`bash scripts/offline-recovery-manifest.sh --commands`，各 feature 分支分别 push → PR → CI；全部合完再 `git checkout main && git reset --hard origin/main`。⛔ 不 `git push origin main`（绕过 CI 一次推十几个 commit）；⛔ 不用 `%(ahead-behind:origin/main)` 筛分支（squash 让老分支永远 ahead，实测筛出 869 条而真动过的 28 条），判据是提交时间晚于 origin/main 冻结时刻。

## 速查

| 场景 | 做法 |
|---|---|
| 开始代码任务 | 核验并复用本任务隔离副本；没有合格副本才从 `origin/main` 新建 |
| 任务完成 | PR 或 fastlane → origin/main；先 `git diff origin/main...HEAD --stat` 确认 scope |
| 部署 prod | 合并前候选树 `--phase build --stage-backend` → 合并 → clean 的 origin/main 树 `--phase deploy`（绝不从 feature 分支；不跑 prewarm） |
| 必须 prod 验 feature | 验完立即 merge main + 从 main 重部 + 核对运行 jar 含修复 |
| 开新 worktree 前 / 任务做完 | `bash .claude/tools/worktree-hygiene.sh --check`（> 9 先 `--apply`）；做完收掉自己的（铁律 4） |
| 清理 | `scripts/safe-worktree-remove.sh`（⛔ 不 `mklink /J` 共享 node_modules，见 concurrent-edit-safety） |
| GitHub 不可用 | 合进本地 `main` → detached worktree + `SKIP_GIT_CHECK=1` → 恢复后各分支走 PR |

关联: `concurrent-edit-safety.md`、`deploy-backend` skill、memory `feedback_concurrent_deploy_r2_path_collision`。
