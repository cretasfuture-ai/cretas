# Cretas 执行规则精简 — 2026-10-16

- 任务 ID：RULES-EXECUTION-SLIM-20261016
- Base SHA：394a3183c0d2761c2668ecd5d52b89ad0e23eeae
- Owner：当前 Codex 协调者 /root；DIRECT 实施，独立只读审查按下述范围进行。
- Worktree：C:/Users/Steve/cretas-execution-rules-slim-20261016
- 分支：codex/execution-rules-slim-20261016
- 目标：完成本机 Cretas 的编排、验证、交接精简，Claude/Codex 共用原则，agent 专属边界分开。
- 允许范围：下列文件及本回执；ACTIVE 仅临时登记/释放本任务，不整理其他任务。
- `.claude/rules/execution-and-verification.md`
- `.codex/rules/execution-and-verification.md`
- `.claude/skills/organizer/SKILL.md`
- `.claude/skills/organizer/references/dispatch.md`
- `CLAUDE.md`
- `AGENTS.md`
- `.claude/agents/executor.md`
- `.codex/agents/cretas-executor.toml`
- `.codex/agents/cretas-reviewer.toml`
- `.codex/agents/cretas-risk-reviewer.toml`
- `.codex/agents/cretas-test-runner.toml`
- `.claude/rules/worktree-and-main-only-deploy.md`
- `.claude/rules/standing-authorization-and-visual-acceptance.md`
- `.claude/rules/measurement-and-wiring.md`
- `.claude/skills/deploy-backend/SKILL.md`
- `.agents/skills/deploy-backend/SKILL.md`
- 禁止范围：产品源码、测试断言、依赖、CI/发布脚本、数据库/生产状态、全局技能/配置、MacBook 配置；保留主工作区已有 AGENTS/ACTIVE 改动。
- 验收：共享规则/指针检查、ACTIVE 体量检查、技能 YAML 与 Codex TOML 解析、差异/路径/门禁保留检查、规则语义复核。只改规则与说明，不运行无关产品全量测试。
- 状态：done（2026-10-20 由 Claude 复核后合入，分支 `codex/claude-execution-rules-slim-20261020`；原分支停在 review 未提交，内容在 worktree 清理时快照为 3cd56f91ec）。

## 已实施

- 最新主线已于 2026-10-05 合并旧 dispatch skill；本批修改 organizer/references/dispatch.md，不恢复被移除技能。
- DIRECT 为双方默认；委派须有实际收益，普通修改不固定追加审查，风险场景保留独立终审。
- 统一冻结后最终验证和有前提的证据复用；不以文档/回执或只读审查触发产品重测。
- 简短任务包和事件通知，保留 scope、Owner、Base、测试及交接回执。
- Codex 执行/测试/审查代理实际接入共用规则；Claude-only organizer 不镜像给 Codex。
- 复用已核验的隔离 worktree；完整发布回执不再额外重复同类服务检查。

## 验证与边界

2026-10-20 复核（Claude）：

- 合并面：`git merge-tree --write-tree origin/main 3cd56f91ec` rc=0；基点 394a3183c0 之后 main 未改过这 17 个文件。
- `python scripts/ci/check_shared_agent_rules.py` rc=0（1 条共享规则逐字相同，16 个 `.codex` 指针目标有效）；阴性对照：把新指针改成不存在的目标 → rc=1，还原 → rc=0。
- `python scripts/ci/check_dispatch_active.py` rc=0（在飞 61 条、全部 ≤ 300 字）。
- 5 个 `.codex/agents/*.toml` 用 `tomllib` 解析通过；4 个 skill/agent 的 frontmatter 含 name + description。

复核时补回三处被精简掉的 Owner 裁定 / 硬约束（不是新规则）：

1. organizer：开 sister chat 时任务包与派卡消息写明「执行车道 ⇒ Opus + effort high」（Steve 2026-09-07 裁定；独立会话没有 agents frontmatter）。
2. worktree 铁律 1：精简版允许「纯文档按现有范围安全编辑」，等于放开在主目录改文档；改回「主目录不混入任何改动，含文档/规则/台账」。
3. CLAUDE.md 常驻清单：standing-authorization 的摘要补回「⛔ 不要再问『要不要 / 可不可以』，不可逆动作宣告而非请示」。

规则文件改动不代表任何正在运行的会话已重新加载。

独立复核发现部署技能仍有重复 grep/真跑路径要求，已仅修改 Claude/Codex 对应部署技能的验收段，不改发布脚本/命令/授权。
