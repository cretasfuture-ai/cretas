# 分发细节：执行者、路由、effort、brief 卡

（原 `multi-model-dispatch` skill，2026-10-05 并入 organizer。）

## 执行者

| 执行者 | 擅长 | 通道 | effort |
|---|---|---|---|
| Opus（in-harness） | 改代码、取证、侦察、需求框架、难架构、红线终审、最硬红线代码 | organizer spawn `executor` / `probe` / `recon`，或本体 | 由 `.claude/agents/*.md` frontmatter 决定 |
| Fable | organizer 本身：统筹、台账、追 PR、汇总读数 | 会话级（用户选模型） | 会话 effort |
| Codex | 可 brief 的纯后端 / CLI / E2E / 构建 / TDD / 第二审查 | out-of-harness：organizer 出卡，用户转交 | brief 里建议 |
| Composer（Cursor） | 独立 UI / 样式 / lint / 补测试 | out-of-harness | brief 里建议 |

in-harness 不用 Sonnet 一档：本仓实测「机械小改」几乎都带陷阱（2026-09-05 一天内：`toDateString` 抄成 `toISOString`、抄了不存在的 `authStore.user?.role`、合并冲突选错把权限文档退回去），便宜模型手里会一路绿灯过去。但那天的失误没有一个是「想得不够深」，全是「量错了东西」，提高 effort 也抓不到，要靠判据和对照。

out-of-harness 看不到 `.claude/rules`，brief 卡要内联相关规则摘要；Java Tool-Skill、Python parity 这类规则重的任务优先 in-harness。预算：Claude Max 20x、GPT 10x（小桶）、Cursor 都是包月，铺开用，别撑爆 GPT。

## 路由

| 任务性质 | 推荐 |
|---|---|
| 改页面 / 组件 / 样式、普通 bug、补 lint/类型/测试、照文档批量小改 | Composer |
| 跨模块 bug、查日志、headed E2E、构建排查、仓库级检查 | Codex（构建 / CI / 部署脚本：Codex 执行 + Opus 审） |
| 规则重的 in-harness 任务（新 Java Tool、Flyway 编号、parity 机械修复）、判断密集的一般红线（DB 事务、Hibernate 语义、报工模型、常规写库） | `executor` |
| Java Tool-Skill 意图路由、parity port 首次判断 | Opus 本体，或写死 brief 后给 `executor` / Codex |
| 四类红线、最硬红线代码 | Opus 把关或自做 |
| 某个执行者修两轮没好 / 改乱了 | 停下，Opus 查根因，不盲改 |

红线四类（brief 卡上标 🔒）：prod 部署 / DB 迁移 / Flyway（5/30 并发部署覆盖共享 jar；跨会话撞号阻断所有人启动）；权限 / RLS / 多租户 / 业态隔离（餐饮路由撞上制造业工具、营收脱敏漏配）；架构 / 跨模块重构 / 新实体 / 新服务（执行者没有全局视角）；上线前 diff 终审（不交执行者自审自过）。

## effort 与编排

| 情况 | effort |
|---|---|
| 日常 | `high`；例行活可以更低，`low` / `medium` 在新模型上已经很强 |
| 单个难 turn | 那一轮加 `ultrathink` |
| 长自主会话 / 真模糊的架构 | `xhigh`；`max` 只在破玻璃时用 |
| 子代理 | 只能在 `.claude/agents/<name>.md` frontmatter 预设，Agent 调用没有 effort 参数（2026-09-06 核对官方文档）；要不同档位就多定义一个 agent 变体。`model` 可以按次传 |
| Codex / Composer | brief 里建议，设不了 |

降 effort 不会缩短输出，嫌啰嗦要在 prompt 里明说。编排从便宜到贵：inline → 单 subagent → workflow（10+ agent 交叉核验）→ ultracode。fan out 时 workers 用较低 effort，只编排者 `high`；编排者蹲 `xhigh` 时 workers 会继承，要在 agents frontmatter 压住。`ultrathink` 是深化一个脑，`ultracode` 是开很多脑。

| 任务 | 模型 | effort | 编排 |
|---|---|---|---|
| Codex / Composer 执行（有 spec） | Codex / Composer | 默认 | inline |
| in-harness 执行（有计划） | `executor` | high | 单 subagent（worktree 隔离） |
| 需求 framing（低歧义） | Opus | high | inline |
| 架构决策（真有疑问） | Opus | xhigh | inline |
| 高风险门控 review | Opus | xhigh | 单 subagent（只读隔离） |
| 广覆盖审计 / 迁移 | Opus 编排 + workers | xhigh / medium | workflow |

## 分发卡（计划含 ≥ 2 个可并行块，或用户要派活时）

```markdown
## 分发总览
| # | 任务 | 推荐执行者 | 可否并行 | worktree 分支 | 🔒红线 |

## 卡N → 贴给 {Composer | Codex | executor/probe/recon | Opus 自留}
**目标**: 一句话 + 选这个执行者的理由
**worktree**: git worktree add -b feat/<task> ../cretas-<task> origin/main
**允许改**: <越窄越好>   **禁改**: <锁死区>
**验收**: <测试命令> + <证据: 截图/日志/headed E2E>
**并行**: 与卡X独立 / 依赖卡Y(冲突文件)
**交接**: PR off origin/main → `git diff origin/main...HEAD --stat` 确认范围干净
[🔒] **收尾**: 只做到实现 + 自测 + PR，不自部署、不自 merge
```

brief 卡要自包含：别的会话看不到这里的上下文，交接靠卡、git diff 和测试结果。台账条目带 `model` / `effort` / `orchestration` 列。
