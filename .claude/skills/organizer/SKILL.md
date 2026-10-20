---
name: organizer
description: Claude Code 多会话协调：用户说 /organizer，或任务确需多执行者分工、共享 scope 仲裁、统一合并/发布排程时使用。普通单任务直接执行；分发卡在 references/dispatch.md。Codex 不加载本技能。
---

# Organizer

遵守 `.claude/rules/execution-and-verification.md`：默认 DIRECT，只有委派确有收益时才协调。不是所有需求的必经入口，也不要求为日常任务切换模型。

## 开始协调

1. 创建/复用代码 worktree、派工或合并前核对 `docs/dispatch/ACTIVE.md`、实际 Git 状态和 Owner，只处理本批相关范围。记录唯一任务 ID、Base SHA、scope、Owner、验收命令，重叠范围串行处理。
2. 明确目标、完成条件、依赖和交付范围；已有设计直接引用。单任务能连续完成就自己做，不额外写 brief 或派侦察。
3. 确需委派时才读 [references/dispatch.md](references/dispatch.md)。两个独立块仅表示可以并行，还须有耗时、输出隔离或独立证据收益。

## 责任与审查

- 当前批次只有一个协调者负责范围/接口裁决、台账、diff 与回执核对、合并和发布。普通修改不固定加审查代理；高风险保留独立终审，不能执行者自审自过。
- 执行者在登记 scope 内实现、自测并交付 commit/PR，不自行合并、部署、OTA 或安装服务器。协调者按用户已授权范围完成后续，遵守 clean main、发布互斥、租户、迁移/JPA 和回滚门禁。
- 自己可以连续完成普通实现及验证；高风险设计/实现与独立审查的责任保持分开。卡住先查具体原因，不固定增加代理、换模型或重跑全部测试。
- 代码任务使用已核验的独立 worktree，保留他人改动，commit 只带 scope 内路径；不共享 `node_modules`/构建目录。细节见 `worktree-and-main-only-deploy.md`、`concurrent-edit-safety.md`。

## 任务载体与通知

- 一次性且边界清楚、确有隔离或复核收益的工作可交 subagent。同一链无需拆成侦察、分析、执行、审查四轮。
- 跨天、多轮实现、需要保留环境/调查结果的工作可使用持久独立会话；遵守当前工具、用户授权和唯一写 Owner 交接规则。不要按 PR 数或会话闲置时长机械切换载体，不假定旧工具名或历史消息能力仍可用。
- 开 sister chat（独立会话没有 agents frontmatter，模型只有用户能切）时，任务包开头写明「本线是执行车道 ⇒ Opus + effort high」，派卡消息里再说一次（Steve 2026-09-07 裁定：执行活继承 organizer 的 Fable 是浪费）。
- 每个切片只维护一份任务说明、一条在飞记录和一份证据索引。ACTIVE 的 ID/状态/Owner/Base/scope 与阻塞摘要 ≤ 300 字，详情进 `docs/dispatch/archive/`；不要求模型、effort、编排方式等固定列。
- 只在完成、阻塞、范围/接口变化或需裁决时通知。等待 CI/PR 使用一个有截止条件的监视；不主动逐线重复询问、不叠加固定轮询。
- 已有验证按共享规则复用。合并后归档本批并释放 scope；完整发布回执已覆盖的服务检查不重复做，业务验收缺口单列。
