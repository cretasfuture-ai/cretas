# 并发编辑安全规范

**最后更新**: 2026-11-13（瘦身；事故叙事与瘦身前原文在 `docs/rules-casebook/`）

多个 session / IDE 同时开同一仓库会互相覆盖编辑，commit 阶段也会把别人 staged 的文件带进来（4/8 deploy 脚本被覆盖、4/11 commit 夹带 5 个无关文件上了 main）。

1. **里程碑 commit**：长任务按阶段 WIP commit；关键 edit 后 `git diff --stat <file>` 对比预期，远小于预期就是被覆盖（`git reflog` 找回）。
2. **长任务用 worktree 隔离**（见 `worktree-and-main-only-deploy.md`）。
3. **改共享文件前 `git status --short <file>`**（`scripts/deploy/*.sh`、`.claude/rules|skills/*`、`pom.xml`、`package.json`）：有你不记得的 unstaged 改动就停手确认。
4. **commit 只带列出的路径**：`git commit -m "..." -- <paths>` 或 `./scripts/safe-commit.sh "msg" <paths>`（后者前后各 verify 并拒绝在已 squash-merge 的分支提交）。⚠️ `git commit -- <paths>` 不包含未列出的删除（症状：本机绿 / CI 红）；core.fileMode=false 下还会丢暂存的 +x，push 前 `git ls-tree HEAD <path>` 必须 100755。commit 前再 `git status --short` 看 staged 区；已推的 scope creep 补 follow-up，未推的 `git reset --soft HEAD~1` 拆开。
5. **不同时打开同一文件**：IDE auto-save 会覆盖外部修改；并行改同一文件必须 worktree 隔离或明确分工。
6. ⛔ **subagent worktree 禁止 `mklink /J` 共享 `node_modules`**（也别用于 `target/`、`.next/`）：`git worktree remove --force` 会穿透 junction 掏空主仓。各自 `npm install --prefer-offline --legacy-peer-deps`；扫描/摘除 `pwsh -File scripts/check-worktree-junctions.ps1 [-Fix]`；移除用 `./scripts/safe-worktree-remove.sh`。
7. ⛔ **干净树上 `git stash` 什么都不存，随后 `pop` 弹出的是别人的 stash**。量基线用 worktree 或 `git checkout -q <sha> -- <文件>`；必须 stash 用 `git stash push -u -m "<tag>"`、记 SHA、`git stash apply <sha>`、事后按 tag 找到再 drop。
8. ⛔ **后台变异脚本在跑时不要提交**（会把变异态源码提交进分支）；收尾 `git grep MUTANT` 必须为空。
9. ⛔ **命令链里相对 `cd` + `git checkout --`**：前一步失败会在主目录还原别人的未提交文件；还原一律 `git -C <绝对路径> ...`。
