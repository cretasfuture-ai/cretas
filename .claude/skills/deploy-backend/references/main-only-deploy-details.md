# 合入通道、发布命令与离线部署（从常驻规则移入，2026-11-13）

规则本体（prod 只从 main、worktree 隔离）在 `.claude/rules/worktree-and-main-only-deploy.md`；这里放操作细节。

## 合入通道双轨（2026-07-28 Steve 拍板）

| 变更 | 通道 |
|---|---|
| `docs/**.md`、`.claude/**`、根 `*.md`、`.gitignore` | **fastlane**：`./scripts/deploy/publish-main-fastlane.sh --base-sha <完整 40 位 SHA> --confirm YES-DIRECT-MAIN`（先 `--dry-run`）。2026-11-13 起 main 设了必需检查、GitHub 拒收直推，脚本默认改为「推分支 → 开 PR → 等检查全部结束无红 → `--match-head-commit` 合并 → 等 MERGED」，文档类 PR 上多数闸判为跳过，通常几分钟；禁 force push、开始前证明 origin/main 未前进；分支名必须 `codex/*`。`FASTLANE_PUBLISH_MODE=direct` 只给本地夹具/离线 main |
| 其余（含 `docs/dispatch/**`、配置文件）| fastlane 须带 `--task-id <本批任务ID>`（ID 必须以词边界出现在 `docs/dispatch/` 下），否则门禁按「全局零未完成任务」判定而恒拒 |
| backend / web-admin 代码 | **PR**（Python Gate / Web Admin Gate / Secret regression 按 paths 跑；PR 号是台账锚点） |
| AGENTS.md / `.agents/skills/*` / `.codex/rules/*` / workflows / `scripts/deploy/*` / 迁移 / entity / repository / security | **强制 PR**（fastlane 列为高风险自动拒绝；`.claude/` 豁免而 `.agents/`、`.codex/` 高风险，三个目录名很像） |

分档是脚本说了算，拿真实 commit 跑一次 `--dry-run` 看 `FASTLANE_READY` 还是 `FASTLANE REJECTED: <原因>`。底线：**任何通道都必须推上 origin/main 后才可部署**。

看 CI 结论：`gh` 时间戳是 UTC；PR 上的 `failure` 先查是不是 `cancelled`（`concurrency: cancel-in-progress` 让 push 与 pull_request 互相取消）；判「某个闸还在不在」看 yml 文件，不看 `gh workflow list`。

## 发布命令

```bash
# 合并【前】在候选 worktree: 先 build + 预传 (硬规则 2026-09-15; manifest 按 backend tree 复用, 合并后部署 ≈1 min)
./scripts/deploy/release-cretas.sh --phase build --base-sha '<Base SHA>' --tests '<tests>' --stage-backend YES-STAGE
# 合并后:
git fetch origin main && git checkout --detach origin/main      # 或 clean 的 main worktree
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<同上选择器>' --no-prefer-ci-artifact --confirm-prod YES-PROD
# ⛔ 不跑 prewarm-main-artifact.sh: CI 制品车道在本组织不通 (无 attestation + 制品存储配额), 等 420s 纯浪费
# 判据: DEPLOY_EXIT=0 且日志里 RELEASE_FINAL_STATUS 恰好 1 次 (不出现本身就是失败信号)
```

`deploy-backend.sh` 上传到**固定的共享 jar 路径**，多个 session 各自从 feature 分支部署 = last-write-wins 互相覆盖（2026-05-30 青花椒 RBAC 事故原形）。`deploy-backend.sh` 的 idle readiness 三态：`UP:*` 健康 / `TIMEOUT`、`RESTART_LIMIT:*` 真失败 / 空串是 ssh 断线**没量到**（脚本先重试再独立核验）。

## 部署后核对（缺证据时才做；同一轮完整发布回执已覆盖的不重复）

```bash
ssh <host> "ps -o lstart= -p \$(systemctl show -p MainPID --value <svc>); stat -c '%y' <artifact>"   # 进程晚于制品
ssh <host> "unzip -p <jar> '<class>' | strings | grep -c '<ASCII 标记>'"   # strings 读不到中文, 中文标记恒为 0
```

找到标记不证明业务能跑：完成条件含业务修复/验收时，还须按任务授权真跑对应路径并检查必然产出。

必须在 prod 验证 feature 分支时：验完**立即 merge 到 main + 从 main 重部**，再核对运行中的 jar 含你的修复（上面 `unzip | strings | grep -c`）。

## GitHub 不可用时：本地 `main` 是唯一汇合点

1. 汇合点固定用本地 `main`，不新造整合分支；只有占着 `main` 的那个 worktree 能往上合（天然串行）。动它之前单独跑一次 `git status --porcelain`。要看「我要动哪些文件」问自己的 commit（`git show --name-only --format= <分支>`），⛔ 不用 `git diff --name-only main..<分支>`。
2. 部署从 detached worktree：`git worktree add --detach ../cretas-deploy-offline $(git rev-parse main)`，然后 `SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-backend.sh --env prod`（web-admin 同理）。`check_git_sync` 离线时只 WARN。
3. 合并后、部署前跑迁移撞号闸：`mvn -o test -Dtest='FlywayVersionUniquenessTest,*RepositoryQueryValidationTest'`。
4. 每次离线部署记账：部署了哪个本地 commit、含哪些分支、跑了哪些迁移，写进当天 handoff。
5. 恢复后清单**现算**：`bash scripts/offline-recovery-manifest.sh --commands`，各 feature 分支分别 push → PR → CI；全部合完再 `git checkout main && git reset --hard origin/main`。⛔ 不 `git push origin main`；⛔ 不用 `%(ahead-behind:origin/main)` 筛分支，判据是提交时间晚于 origin/main 冻结时刻。

## worktree 自动回收细节

`worktree-hygiene.sh --auto`（SessionStart hook，日志 `<git-common-dir>/worktree-hygiene.log`）只回收**干净且已完成**的 worktree（分支 PR 已 MERGED 且 PR 头 == worktree HEAD，或 HEAD 已是 origin/main 祖先）；有改动、locked、当前所在、`--keep`、会话活动标记（`worktree-touch.sh`）在近 6 小时内的一律不动；无标记的看 HEAD/index mtime，同一 6 小时窗；还会清未登记空壳目录；本会话 `gh pr merge` / `publish-main-fastlane.sh`（非 dry-run）后由 PostToolUse hook（`worktree-after-merge.sh`）立刻触发一次。⛔ 不用提交时间判断是否在用（已合并 worktree 提交时间必然旧）。已知不覆盖：无 PR 但内容已被别的 PR 吸收的 detached worktree、合并后又加了提交的分支。gh 不可用则不回收；分支保留。
