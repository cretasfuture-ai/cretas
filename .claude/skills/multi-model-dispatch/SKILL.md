---
name: multi-model-dispatch
description: 多模型分发路由规范(in-harness 全 Opus + Fable organizer / Codex / Composer)。触发场景: 计划含 ≥2 个可并行块或用户要派活时产「分发卡」; 决定某个任务派给哪个模型、什么 effort、什么 orchestration(三轴); 写自包含 brief 卡给 Codex/Composer; 审查分层(例行 vs 🔒 risky 终审); 🔒 红线任务的收尾约束。凡涉及"派活给哪个模型 / 多 chat 并行分工 / brief 卡"必读。
---

# 多模型分发路由 (Multi-Model Dispatch)

**in-harness 只用 Opus 5。** organizer 会话跑 Fable 5.1，执行/取证/侦察三条车道一律 Opus。

⇒ **成本旋钮是 effort 轴，不是模型轴。** effort 要 earned-not-predicted：⛔ 不许为了保险默认蹲高 effort。`cost = agents × effort` 是乘。

⚠️ 为什么可以放弃 Sonnet 一档：本仓实测「机械小改」几乎都带陷阱（2026-09-05 一天内：抄 `toDateString` 抄成 `toISOString`、抄 `authStore.user?.role` 抄了个不存在的字段、合并冲突选错会把刚做的权限文档退回去、份额 30/70 的样本对真 bug 恒真）—— 这些在便宜模型手里**全会一路绿灯过去**，症状要么在真机上、要么在几周后。

⚠️ 但同一天的证据也划出了 effort 的边界：那天所有失误**没有一个是「想得不够深」**，全是「量错了东西」（判据撞上自己的注释 ×4、grep 大小写、退出码被 `echo` 替换、读了落后 600 commit 的 worktree、grep 到孤儿 chunk）。**提高 effort 抓不到这一类** —— 那要靠判据和对照，不靠算力。⇒ effort 只买「要不要多想一步」。

Opus 卡住换手段不换模型：对抗式 fan-out、换仪器重新量、把问题拆小 —— 本仓实测卡住基本都是量错了东西。

## 执行层

| 执行者 | 擅长 | 通道 | 车道 / effort |
|---|---|---|---|
| Opus 5 | in-harness 全部：改代码 / 取证 / 侦察 / 需求框架 / 难架构 / 🔒 终审 / 出货闸 / 最硬红线代码（成本财务口径 · prod 迁移/Flyway · 权限/RLS/多租户/业态 · 撤回 · 资金） | organizer spawn，或本体 | `executor` high · `probe` high · `recon` medium |
| Fable 5.1 | organizer 本身：统筹 / 台账 / 追 PR / 汇总读数 / 审计 —— 判断轻、吞吐重 | 会话级（用户 `/model` 选） | 会话 effort |
| Codex（GPT） | 可 brief 的纯后端 / CLI / E2E / 构建 / TDD / 第二审查 | out-of-harness：organizer 出卡 → Steve courier | brief 里建议，设不了 |
| Composer（Cursor） | 独立 UI / 样式 / lint / 补测试 | out-of-harness | brief 里建议，设不了 |

Out-of-harness 看不到 `.claude/rules`，brief 卡必须内联相关规则摘要；Java Tool-Skill / Python parity（12 条硬规则）这类规则重任务优先 in-harness (Opus, 规则自动可见)。预算：Claude Max 20x + GPT 10x（小桶）+ Cursor 三个都是 flat，铺开用、别撑爆 GPT。

## 三条铁律

1. 路由按任务性质，不按固定比例。
2. 隔离是硬约束：每个分发任务独立 worktree off `origin/main`；commit 锁 scope；⛔ 不 `mklink /J` 共享 node_modules（见 `.claude/rules/worktree-and-main-only-deploy.md`、`concurrent-edit-safety.md`）。
3. 🔒 红线不许执行者自己收尾：只做到「实现 + 自测 + PR off origin/main」，回 main 由 Opus 终审 + 部署。

## 🔒 红线四类（brief 卡必标）

| 类别 | 事故证据 |
|---|---|
| prod 部署 / DB migration / Flyway schema | 5/30 RBAC ¥0（feature 分支并发部署覆盖固定共享 jar 路径）；Flyway 跨 session 撞号阻断所有人启动 |
| 权限 / RLS / 多租户 / 业态（餐饮↔工厂）隔离 | 餐饮路由撞制造业工具瞎编；营收脱敏漏配 |
| 架构 / 跨模块重构 / 新实体 / 新服务 | 执行者无全局视角，易造 schema drift / 循环依赖 / BaseEntity 遗漏 |
| 上线前 diff 终审 | merge/deploy 前的最终审查由 Opus 做（可配对抗性多-agent），不交执行者自审自过 |

## 路由启发式

| 任务性质 | 推荐 |
|---|---|
| 改页面 / 组件 / 样式 · 普通 bug · 补 lint/类型/测试 · 照文档批量小改 | Composer |
| 跨模块 bug · 查日志 · headed E2E · 构建排查 · 仓库级检查 | Codex（构建 / CI / 部署脚本问题：Codex 执行 + Opus 审） |
| 规则重 in-harness（新 Java Tool / Flyway 编号 / rule-aware review / parity 机械修复）· 判断密集 / 一般 🔒（DB 事务 / Hibernate 语义 / 报工模型 / 常规写库） | `executor` (Opus/high) |
| Java Tool-Skill 意图路由 / parity port 首次判断 | Opus 本体，或写死 brief 后给 `executor`/Codex |
| 🔒 四类 · 🔒🔒 最硬红线代码 | Opus 把关 / 自做 |
| 某模型修 2 轮没好 / 改乱了 | 停，切 Opus root-cause，别盲改 |

## Effort × Orchestration

| 情况 | effort |
|---|---|
| 日常 Opus | `high`（coding/agentic 可起步 `xhigh` 再往下扫；`low`/`medium` 强得反常，例行活别默认蹲 high） |
| 单个难 turn | 该 prompt 加 `ultrathink`（只点一轮） |
| 长自主 session / 真模糊架构 | `xhigh`；`max` ⛔ 破玻璃 |
| 子代理 | effort **只能**在 `.claude/agents/<name>.md` frontmatter 预设 —— 官方文档明写 Agent 调用**没有 effort 参数**（2026-09-06 复核 code.claude.com/docs/en/sub-agents）。要不同档位就**多定义一个 agent 变体**。`model` 反过来**可以**按次传（优先级：调用参数 > frontmatter > 环境变量 > 会话） |
| Codex / Composer | brief 里建议，设不了 |

降 effort 不缩短输出长度，嫌啰嗦在 prompt 里明说。Organizer 是 effort 分配者不是满载消费者：常驻 max 做廉价分诊 = 烧周额度最快。

Orchestration 从便宜到贵：`inline`（默认）→ 单 subagent（隔离 / 污染性输出）→ workflow（10+ agent 交叉核验）→ ultracode。fan-out 时 workers 压低 effort（`medium`/`low`），只编排者 `high` —— 全 Opus 之后这是**唯一**的成本旋钮 —— `cost = agents × effort` 是乘；编排者蹲 xhigh 时 workers 也继承 xhigh，要在 agents frontmatter 压住。`ultrathink`（深化一个脑）≠ `ultracode`（开很多脑）；概念咨询 / 单点问题 → inline。派 subagent 前先过 organizer skill 的「并发形状」表：并发度 = 1 且 context 在手就 inline，不单派。

| 任务 | 模型 | effort | orchestration |
|---|---|---|---|
| Codex / Composer 执行（有 spec） | Codex / Composer | default | inline |
| in-harness 执行（有计划） | `executor` (Opus) | high | 单 subagent（worktree 隔离） |
| 需求 framing（低歧义） | Opus | high | inline |
| 架构决策（真有疑问） | Opus | xhigh | inline |
| 高风险门控 review | Opus | xhigh | 单 subagent（read-only 隔离） |
| 广覆盖审计 / 迁移 | Opus 编排 + Opus workers（workers 压 `medium`） | xhigh / medium | workflow |

## 分发卡（计划含 ≥2 个可并行块或用户要派活时产）

```markdown
## 🚦 分发总览
| # | 任务 | 推荐模型 | 可否并行 | worktree 分支 | 🔒红线 |

## 卡N → 贴给 {Composer | Codex | executor/probe/recon subagent | Opus 自留}
**目标**: 一句话 + 推荐这个模型的理由
**worktree**: git worktree add -b feat/<task> ../cretas-<task> origin/main
**允许改**: <越窄越好>   **禁改**: <锁死区>
**验收**: <测试命令> + <证据: 截图/日志/headed E2E>
**并行**: ✅ 与卡X独立 / ❌ 依赖卡Y(冲突文件)
**交接**: PR off origin/main → `git diff origin/main...HEAD --stat` 确认 scope 干净
[🔒] **收尾约束**: 只做到实现+自测+PR, 不许自部署 / 自 merge
```

brief 卡自包含：别的 chat 看不到本 chat 上下文。交接靠 brief 卡 + git diff + 测试结果，不靠口头。台账 `docs/dispatch/ACTIVE.md` 条目带 `model` / `effort` / `orchestration` 列。

## 审查分层

例行 review（样式 / lint / 补测试 / 无红线依赖）→ `recon`/`executor` subagent；🔒 risky review（权限 / RLS / 迁移 / 业态 / 架构 / 上线前 diff）→ Opus 本体，不可外包。

## 反 pattern

- 三个模型同时改同一文件 → 互相覆盖
- 执行者从 feature 分支直接部署 prod → 覆盖别人的 jar（5/30）
- brief 卡假设别的 chat 知道「我们刚才聊的」
- 红线任务交 Composer/Codex 自审自部署
- 按额度比例硬塞 Opus 做低价值执行 / 硬塞 Composer 做高风险决策
- Opus 常驻 max effort 做路由分诊
- Java Tool-Skill / parity port 直接派 Codex（无规则上下文）
- fan-out 时 workers 也蹲 `high`/`xhigh`（模型不是旋钮，effort 是；`cost = agents × effort`）
