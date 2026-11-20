# Worktree 隔离 + 只从 main 部署 prod (HARD RULE)

**最后更新**: 2026-11-13（瘦身：命令、合入通道分档表、离线部署、自动回收细节移到 `deploy-backend` skill 的 `references/main-only-deploy-details.md`；事故叙事与瘦身前原文在 `docs/rules-casebook/`）
**触发事故**: 2026-05-30 青花椒 RBAC 修复在 prod 被并发 session 的 Java 部署覆盖，总营收回归 ¥0。

## ⛔ 四条铁律

1. **永远不在主工作目录 `C:\Users\Steve\my-prototype-logistics` 干活**（含文档、规则、台账）。代码任务先核验现有 worktree 的路径、分支/HEAD、scope、唯一 Owner，复用合格的本任务副本；没有才新建，**永远 off `origin/main`**：`git worktree add -b codex/claude-<task> ../cretas-<task> origin/main`。只读/调研不新建。
2. **完成 → 推上 origin/main**，不在 feature 分支累积部署。merge 前 `git diff origin/main...HEAD --stat` 确认只有你的文件，夹带别人的就 cherry-pick 到干净 worktree 重做；squash 合并后本地分支必须重开（measurement-and-wiring 硬约束 7）。通道：`docs/**.md`、`.claude/**`、根 `*.md` 走 fastlane（`publish-main-fastlane.sh`，会自动走 PR 并等检查全绿）；backend / web-admin 代码走 PR；AGENTS.md、`.agents/`、`.codex/`、workflows、`scripts/deploy/*`、迁移、entity、repository、security **强制 PR**。分档以脚本 `--dry-run` 为准，完整分档表与参数见 deploy-backend skill 的 `references/main-only-deploy-details.md`。**任何通道都必须推上 origin/main 后才可部署。**
3. **prod 永远从 main 构建/部署，绝不从 feature 分支**：合并前在候选 worktree `release-cretas.sh --phase build ... --stage-backend YES-STAGE`；合并后在 clean 的 `origin/main` 树 `--phase deploy ... --confirm-prod YES-PROD`；⛔ 不跑 prewarm；判据 `DEPLOY_EXIT=0` 且 `RELEASE_FINAL_STATUS` 恰好 1 次。`deploy-backend.sh` 上传到固定共享 jar 路径，多 session 各自从 feature 分支部署会互相覆盖。完整命令见 deploy-backend skill。必须在 prod 验 feature 分支时，验完立即 merge main + 从 main 重部并核对运行 jar 含修复。
4. **worktree 总数 ≤ 30，开新的之前先体检，做完收自己的**：开新前 `bash .claude/tools/worktree-hygiene.sh --check`（> 30 先 `--apply`，用 `list_sessions` 把 `isRunning` 的 cwd 用 `--keep` 传进去）；任务做完/PR 合并后从主仓库目录执行 `--apply` 收掉自己的（会 WIP commit 脏改动、摘 junction、不 `--force`，分支保留）。SessionStart hook 的 `--auto` 会自动回收「干净且已完成」的 worktree（细节见 skill reference）。⛔ 不对带 junction 的目录 `git worktree remove --force`；⛔ 不替 detached 且有改动的 worktree 擅自建分支。

## GitHub 不可用时

本地 `main` 是唯一汇合点；部署从 detached worktree + `SKIP_GIT_CHECK=1`；恢复后各分支走 PR，⛔ 不 `git push origin main`。完整五步协议见 skill reference。

## 速查

| 场景 | 做法 |
|---|---|
| 开始代码任务 | 复用本任务副本；没有才从 `origin/main` 新建 |
| 任务完成 | PR 或 fastlane → origin/main；先 `git diff origin/main...HEAD --stat` |
| 部署 prod | 合并前 `--phase build --stage-backend` → 合并 → clean origin/main 树 `--phase deploy` |
| 清理 | `scripts/safe-worktree-remove.sh`（⛔ 不 `mklink /J` 共享 node_modules） |

关联: `concurrent-edit-safety.md`、`deploy-backend` skill。
