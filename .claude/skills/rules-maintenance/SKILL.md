---
name: rules-maintenance
description: 检查 .claude/rules 与 .claude/skills 的时效与触发面。用 /rules-cleanup 或 /rules-update 调用，或用户要审查 / 清理规则与 skill 时触发。
---

# Rules / Skills 维护

只出清单，不直接改；改动走单独 commit。

对 `.claude/rules/*.md` 与 `.claude/skills/*/SKILL.md` 逐份检查：

1. **触发面**：`paths:` 是否过宽（会不会被无关改动拉进上下文）；skill `description` 是否与别的 skill 重叠。
2. **常驻成本**：无 `paths:` 的 rule 每 session 都加载，合计多少 KB；超过 20 KB 的单文件考虑拆「判据 + 案例库」。
3. **时效**：`最后更新` 超过 90 天的、引用的路径 / 脚本 / 端口 / 服务是否还存在（`ls`、`git grep`），已下线的环境或已作废的裁定是否还留在正文。
4. **停顿点**：有没有「向用户确认 / AskUserQuestion / 先问」这类不必要的请示，与 `standing-authorization-and-visual-acceptance.md` 冲突的删掉。
5. **重复**：同一段规则在 rule / skill / AGENTS.md 出现几份，留一份其余指向。
6. **载体**：新规则是否登记进 `CLAUDE.md` 常驻清单；`.codex/rules` 与 `.claude/rules` 同名文件是否漂移。

上一次全面审查与方案：`docs/plans/2026-09-05-skills-docs-cleanup-plan.md`。
