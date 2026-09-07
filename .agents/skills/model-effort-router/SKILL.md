---
name: model-effort-router
description: Provide model or reasoning-effort advice only when the user asks for model selection, comparison, or adjustment. Not a prerequisite for repository work.
---

# Model and Effort Advice

Use the current agent to complete authorized work. This skill gives optional advice when requested; it does not determine whether a model is allowed to perform a task.

## When advice is requested

1. Identify the user's objective and any explicit cost, latency, or quality constraint from available context.
2. Verify current official guidance and the host's actual available models and effort options. Do not infer availability from historical model names or a stale matrix.
3. Give a concise recommendation with its task-specific reason. If the current picker is unavailable, state that limitation without requiring switch confirmation.
4. Change a setting only when the user requested it and a supported tool is available. Do not imply prose changes the running model. External AI handoffs require the user's explicit request.

## Execution boundaries

- No mandatory model preflight, default-family table, task eligibility by model name, or pause when scope changes.
- Complexity changes the investigation and verification needed; it does not automatically require another model, higher effort, or subagents.
- Judge progress through real tool results and applicable project checks. Preserve authorization, dispatch ownership, production, migration, and release requirements.
- Existing independent-work delegation rules remain applicable; do not delegate solely to satisfy a model hierarchy.

## Current evidence

When the user asks for current model advice, consult official documentation and the current host model list. Do not restore historical model rankings, task eligibility tables, or effort approval gates from Git history.
