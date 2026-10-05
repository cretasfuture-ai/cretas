---
name: rules-maintenance
description: 检查 Codex 侧规则载体（AGENTS.md、.codex/rules 指针、.agents/skills）的时效、重复与漂移。用户要审查 / 清理规则或 skill 时用。
---

# Rules / Skills 维护（Codex 侧）

规则正文只有一份，在 `.claude/rules/`。`.codex/rules/` 下同名文件是指针（`POINTER: <目标>`），只有 `ci-runner-routing.md`（与 `.claude` 逐字相同，`scripts/ci/check_shared_agent_rules.py` 钉着）和 `*.example.md` 有正文。改规则改 `.claude/rules/`；新规则登记进 AGENTS.md「代码质量原则」清单，Codex 从那里读。

只出清单，不直接改；改动走单独 commit。逐份检查 AGENTS.md、`.codex/rules/*.md`、`.agents/skills/*/SKILL.md`：

1. **时效**：引用的路径、脚本、端口、服务是否还存在（`ls`、`git grep`）；已下线的环境（test 环境与 10011/8084 于 2026-07-13 下线）、已作废的裁定（如发布前 prewarm）是否还在正文。事实以 `.claude/skills` 与 `.claude/rules` 当前版本为准。
2. **指针**：每个 `POINTER:` 目标是否存在；`.agents` 里声称「canonical」的文件（如 `e2e-web-admin`）与对侧指针是否互相指对。
3. **重复**：同一段规则在 AGENTS.md / skill / `.claude` 出现几份，留一份其余指向。发布口径三处（AGENTS.md + 两份 deploy skill）由 `scripts/tests/test-release-pipeline-acceleration.sh` 钉着，改一处要三处一起改。
4. **停顿点**：与 `.claude/rules/standing-authorization-and-visual-acceptance.md` 冲突的「先问 / 需另行授权」逐条列出，交 owner 裁定，不自行删。
5. **记忆**：`.codex/memory/MEMORY.md` 不超过 200 行，过时条目移入主题文件或删除。

Claude-only 边界：`.claude/skills/organizer/`（含 `references/dispatch.md`）只由 Claude 使用，Codex 侧不建 `.agents/skills/organizer/` 镜像。`.agents/skills/model-effort-router/SKILL.md` 只在用户明确问模型 / effort 时用，不恢复型号资格表或切换确认门禁。

上一次全面审查与方案：`docs/plans/2026-09-05-skills-docs-cleanup-plan.md`。
