---
name: rules-maintenance
description: 检查 Codex 侧规则与 skill 的时效与触发面（.codex/rules、.agents/skills、AGENTS.md），并与 .claude 侧真值对齐。用户要审查或清理规则、skill 时用。
---

# Rules / Skills 维护（Codex 侧）

规则正文的真值在 `.claude/rules/`。`.codex/rules/` 下同名文件是指针（`POINTER: <目标>`），只有 `ci-runner-routing.md`（由 `scripts/ci/check_shared_agent_rules.py` 钉住与 `.claude/rules` 逐字相同）和 `*.example.md` 保留正文。改规则改 `.claude/rules/`；新增规则时在 `.codex/rules/` 加一个指针文件。

先出清单，改动走单独 commit。逐份检查：

1. **时效**：引用的路径、脚本、端口、服务、URL 是否还存在（`ls`、`git grep`），已下线的环境或已作废的流程（如已停用的步骤、旧端口、旧地址）是否还留在正文。以 `.claude/skills/` 下同名 skill 和 `.claude/rules/` 为现状参照。
2. **漂移**：`.agents/skills/` 与 `.claude/skills/` 的同名 skill 是否说法冲突；能做成指针（像 `aliyun-operations`、`.codex/rules/*`）就不要维护两份。
3. **触发面**：skill `description` 是否过宽、是否与别的 skill 抢活。
4. **重复**：同一段规则在 rule / skill / AGENTS.md 出现几份，留一份其余指向。
5. **写法**：skill 只写团队特有的观点和坑；模型本来就会的通用知识、大段逐步清单和强硬语气（⛔ / CRITICAL / 必须 堆叠）删掉或改成「做法 + 原因」。

Claude 侧的 organizer 编排（`.claude/skills/organizer/`）只由 Claude 使用，Codex 侧不建镜像。模型选型只在用户明确询问时用 `model-effort-router`。
