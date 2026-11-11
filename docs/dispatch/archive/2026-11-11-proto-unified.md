# PROTO-UNIFIED-20261111 原型终版（融合 + 规格 + 实现）

Owner：organizer 会话 `local_f2ec35f8`（2026-11-11 起）。Base：`9bfd4ed652`（缺口清单 `docs/progress/2026-11-11-prototype-gaps.md` 已在 main）。

## 目标
把三套原型融合成一个统一原型终版：味美（ydwm_admin）为底座，六膳门（黑湖真实数据）与黑湖成本分析并入，顶部「工作区」切换隔离数据；Steve 的重点是使用逻辑清晰、信息内容清晰，该由 AI 处理的部分接入 AI 能力。

## 位置（仓库外，非 git）
- 原型：`C:\Users\Steve\Documents\Cretas-admin-双角色完整原型-20261028\samples-v5\accounts\{ydwm-v1,liushanmen-v1}`
- 规格：`samples-v5\unified-spec\`（`TEMPLATE.md`、`LEDGER.md`、`A-production-chain.md`、`B-cost-analysis.md`、`C-supply.md`、`D-home-analytics.md`）
- 黑湖成本（只读）：`C:\Users\Steve\cretas-blacklake-20261103`，草稿 PR #1390

## 阶段与执行者
| 阶段 | 执行者 | 模型 / effort | 写入范围 | 状态 |
|---|---|---|---|---|
| 融合骨架 | 后台子代理（general-purpose） | Opus；effort 不能按次设，未核实 | 原型源码/数据、`evidence/unified/`、融合交付记录 | in-progress |
| 规格 A 生产链 | 会话 `local_72a060bd` proto-spec-a-production | Opus 5.5 / medium | 仅 `unified-spec/A-production-chain.md` | in-progress |
| 规格 B 成本分析 | 会话 `local_041b7ac4` proto-spec-b-cost | Opus 5.5 / medium | 仅 `unified-spec/B-cost-analysis.md` | in-progress |
| 规格 C 采购销售库存 | 会话 `local_8c7ef4dd` proto-spec-c-supply | Opus 5.5 / medium | 仅 `unified-spec/C-supply.md` | in-progress |
| 规格 D 首页与分析 + 跨页 AI 协议 | 会话 `local_da26b083` proto-spec-d-home | Opus 5.5 / medium | 仅 `unified-spec/D-home-analytics.md` | in-progress |
| 实现 | 待派：按规格与融合交付的「模块 → 文件」对照表分配 scope 锁 | Opus / medium 起步 | 待定 | queued |
| 终版验收 | 待派：独立对抗审计，不由各线自审 | Opus | 只读 | queued |

## 共用约束
原版（19061）、账号源数据、Steve 有意关闭的模块、受保护文件不动；生产写入 0；不回写黑湖；不提交、不推送 blacklake 仓库；不发布远程 ydwm-prototype 域名，除非静态发布门禁全过。

## 记录
- 2026-11-11 规格线起初继承 Sonnet / medium，先改 Opus high，再按现行 organizer 规则（独立会话 Opus + medium 起步）改为 medium，均停掉旧一轮重起。融合代理第一次 Sonnet 起，停后换 Opus 接续。
