# 并发编辑安全规范

**最后更新**: 2026-09-05（压缩；事故叙事存档在 `docs/rules-casebook/concurrent-edit-safety-cases.md`）

多个 Claude Code session / Cursor / VSCode 同时开同一仓库时会互相覆盖编辑，**commit 阶段也会被并发 session 的文件串到一起**（husky / lint-staged auto-stage）。两次原型事故：4/8 重做 deploy 脚本写了 150 行被并发 session 覆盖只剩 29 行；4/11 一个只想提交 2 个脚本的 commit 带进了别人的 5 个无关文件并推上 main。

## 规则

1. **里程碑 commit**：长任务按阶段 WIP commit，不要「全部做完再 commit」；关键 edit 后 `git diff --stat <file>` 对比预期，diff 远小于预期就是被覆盖了（`git reflog` 找回）。
2. **长任务用 worktree 物理隔离**（见 `worktree-and-main-only-deploy.md`）。
3. **改共享文件前 `git status --short <file>`**（`scripts/deploy/*.sh`、`.claude/rules|skills/*`、`pom.xml`、`package.json`）：有你不记得的 unstaged 改动 → 停手先确认。
4. **commit 只带列出的路径**（默认习惯，不只是怀疑并发时）：
   ```bash
   git commit -m "feat: my change" -- backend/foo.py backend/bar.py     # --only 模式, 别人 staged 的文件不进来
   ./scripts/safe-commit.sh "feat: my change" backend/foo.py backend/bar.py   # 前后各 verify 一次, 且按 PR 状态拒绝在已 squash-merge 的分支上提交
   ```
   ⚠️ `git commit -- <paths>` **不包含未列出的删除**：删了文件却没列出，HEAD 里旧文件还在而工作树没有，症状是本机绿 / CI 红。commit 前再 `git status --short` 看一眼 staged 区；已推上去的 scope creep 只能补 follow-up commit，未推的 `git reset --soft HEAD~1` 拆开。
5. **不要同时打开同一文件**：IDE auto-save 会覆盖外部修改；两个 chat 不并行 edit 同一文件，必须并行就 worktree 隔离或明确分工。
6. ⛔ **Subagent worktree 禁止 `mklink /J` 共享 `node_modules`**（也别用于 `target/`、`.next/`）：Windows 下 `git worktree remove --force` 会穿透 junction 把主仓 `node_modules` 掏空（2026-05-18 事故，阻断 prod deploy）。各自 `cd web-admin && npm install --prefer-offline --legacy-peer-deps`（cache 命中，接近 junction 的速度）。扫描 / 摘除：`pwsh -File scripts/check-worktree-junctions.ps1 [-Fix]`；移除 worktree 用 `./scripts/safe-worktree-remove.sh <path>` 或 `--all-merged`，摘链接只能非递归删除。maven `.m2` 天然跨 worktree 共享，不受影响。
7. ⛔ **干净工作树上 `git stash` 什么都不存，随后的 `pop` 弹出的是别人的 stash**（栈不是抽屉）。量基线用 worktree 或 `git checkout -q <sha> -- <文件>`；一定要 stash 就用 `git stash push -u -m "<tag>"`、记 SHA、`git stash apply <sha>`、事后按 tag 找到再 drop，并先 `git stash list | wc -l` 确认真的新建了一条。

## 速查

| 场景 | 方案 |
|---|---|
| 单 session 改多个文件 | 里程碑 commit（1） |
| 2+ chat 要改同一文件 | worktree 隔离（2） |
| 不确定是否并发 | `git status` 防御（3）+ 关闭其他 editor（5） |
| commit 阶段保护 scope | `git commit -- F1 F2` / `safe-commit.sh`（4） |
| subagent worktree 的 node_modules | `npm install --prefer-offline --legacy-peer-deps`（6） |
| 量基线 | worktree / checkout 指定文件，不用 stash（7） |
