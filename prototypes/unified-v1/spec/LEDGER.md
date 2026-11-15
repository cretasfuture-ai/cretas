# 统一原型 · 分派台账（单一写者：organizer 会话；其他线只回传回执，不写本文件）

建立：2026-11-11。正式登记在仓库 `docs/dispatch/ACTIVE.md` 的 `PROTO-UNIFIED-20261111`，详情在 `docs/dispatch/archive/2026-11-11-proto-unified.md`；本文件只是放在原型目录里方便各线查看的副本，冲突时以仓库登记为准。

## 当前阶段
阶段 0：规格。融合在飞时，四条线只读现有原型，只写 `unified-spec/` 下各自的规格文件。
阶段 1：实现。融合落地后，由 organizer 按各线规格分配代码 scope 锁，再放行实现。放行前任何线不得改原型源码或数据。

## 在飞
| ID | 任务 | 写入范围（scope 锁） | 状态 |
|---|---|---|---|
| UNI-FUSE | 融合味美 + 六膳门 + 黑湖成本为统一原型（后台子代理，唯一源码写者） | `samples-v5/accounts/` 下味美、六膳门及其新增统一目录；`evidence/unified/`；融合交付记录 | in-progress |
| UNI-SPEC-A | 生产链规格：计划/领料/批次/报工 + 工序画布 + BOM | 仅 `unified-spec/A-production-chain.md` | queued |
| UNI-SPEC-B | 成本分析规格：产品→批次→工序→成本依据、人工补充、缺口 | 仅 `unified-spec/B-cost-analysis.md` | queued |
| UNI-SPEC-C | 采购/销售/库存/仓储规格 | 仅 `unified-spec/C-supply.md` | queued |
| UNI-SPEC-D | 首页/岗位工作台/OA/数据分析/质量规格 + 跨页 AI 协议 | 仅 `unified-spec/D-home-analytics.md` | queued |

## 共用规则
- 规格模板 `TEMPLATE.md`，章节名与顺序不许改。
- 原版（19061）、账号源数据、Steve 有意关闭的模块、受保护文件不动；生产写入 0；不回写黑湖。
- 看页面可以，不点会保存的按钮；要演示写入必须用带独立会话参数的地址（`?acceptanceSession=spec-<线>`）。
- 终版验收由 organizer 另派对抗审计，不由各线自审。
