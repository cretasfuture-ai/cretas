---
name: model-effort-router
description: Silent, non-blocking Cretas model and reasoning router. Use when model choice materially affects cost, latency, ambiguity, or blast radius, or when the user explicitly asks which model or effort to use.
---

# Model Effort Router

Route internally and continue immediately. Do not turn model selection into a user-visible preflight.

## Default routes

| Work | Route |
|---|---|
| Cretas coordinator and ordinary repository work | GPT-5.6 Sol Medium |
| Narrow read-only search, extraction, or log triage | GPT-5.6 Luna Low/Medium |
| Clear reversible implementation with strong tests | GPT-5.6 Luna High |
| Broad bounded exploration where different search behavior helps | GPT-5.6 Terra Medium |
| Ambiguous bug, unfamiliar integration, or cross-module judgment | GPT-5.6 Sol High |
| Security, auth, migration, Entity/Repository, production, destructive, or costly one-shot review | GPT-5.6 Sol Extra High/XHigh |

Prefer the lowest route that safely handles the task. Weight blast radius, reversibility, and verification cost at least as heavily as perceived difficulty.

## Execution rules

1. Skip a visible recommendation unless the user explicitly asks for model or effort advice.
2. Never ask the user to switch or confirm a model before continuing authorized work.
3. Select the model family before effort; do not compensate for a mismatched family by maximizing effort.
4. If the current model or picker is unknown, continue with the current agent and use an appropriate configured subagent when delegation helps.
5. Prefer one coherent agent when it can finish the narrow task and target validation without significant context pollution. Subagents are not a default cost optimization because they repeat model and tool work.
6. Delegate only for material wall-clock parallelism, noisy context isolation, independent evidence, or clear reversible execution on a cheaper model. Do not split a sequential dependency chain into role theater.
7. Re-route silently only when scope, ambiguity, risk, or required autonomy changes materially.
8. Use Luna as a lead only for explicit, reversible, testable scope. Keep architecture and high-blast-radius judgment with Sol.
9. Use Terra only for a concrete exploration or diversity reason; it is not the automatic middle tier.
10. Reserve Max for a failed XHigh attempt or a justified high-stakes one-shot decision. Reserve Ultra for explicitly approved, genuinely independent parallel work.

## Evidence reference

Do not load `references/model-matrix.md` for routine routing. Read it completely only when the user asks for current evidence, a detailed model comparison, an external Claude handoff, or a routing-policy refresh. Treat its dated evidence as provisional and use the live picker as availability truth.

## Explicit advice format

When the user explicitly asks, answer compactly and do not delay execution:

```text
推荐：<model> / <effort>
原因：<one or two task-specific sentences>
重新路由条件：<material change only>
执行：继续进行，无需确认切换
```
