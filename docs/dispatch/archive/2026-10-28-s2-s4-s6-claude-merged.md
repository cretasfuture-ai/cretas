# 2026-10-28 归档：S2 / S4 / S6 Claude 线已合并条目（业务验收未做）

由 Organizer 于 2026-10-28 从 ACTIVE.md 移出，scope 锁一并释放。依据：GitHub PR 状态实读（同日），
以及 S4 会话（local_f6b118ce）全阶段剩余工作审计 `Documents/cretas-claude-handoff-20261021/全阶段剩余工作审计-20261028.md`。

⚠️ 「已合并」只说明代码在 main / prod，**不代表业务验收通过**；各场景验收状态以场景表为准。

| 任务 ID | 对应 PR | 状态 |
|---|---|---|
| `ERP-S2-08A-CLAUDE-20261024` | #1144（2026-10-25 MERGED） | 已合并；业务验收未做 |
| `ERP-S4-03A-CLAUDE-20261024` | #1141（2026-10-24 MERGED） | 已合并；业务验收未做 |
| `ERP-S2-READINESS-ENTRY-CLAUDE-20261022` | #1129（2026-10-25 MERGED） | 已合并；业务验收未做 |
| `ERP-S6-B3C-CLAUDE-20261022` | #1073 + #1219（2026-10-26 MERGED） | 已合并；业务验收未做 |
| `ERP-S6-B3B-GRANT-CLAUDE-20261022` | 原 #1095 已 CLOSED，由 #1073 + #1219 取代 | 已合并；业务验收未做 |
| `ERP-S6-V72-PASSIVE-CLAUDE-20261022` | 原 #1093 / #1105 已 CLOSED，由 #1073 + #1219 取代 | 已合并；业务验收未做 |
| `ERP-S6-B2-CLAUDE-20261021` | 原 #1075 已 CLOSED，由 #1073 + #1219 取代 | 已合并；业务验收未做 |
| `ERP-S6-EXECUTION-ARCH-CLAUDE-20261021` | #1073（2026-10-26 MERGED）；#1086 / #1099 已 CLOSED | 已合并；业务验收未做 |

## 移出前的原文

- `ERP-S2-08A-CLAUDE-20261024` — `claimed` — Owner local_f6b118ce-f036-4ece-9a0b-322073b20c74 — Base 315ab97addfd9f1295a4855a7ea8ece430034e4f — 精确6文件/无迁移；待办恢复及真实JPA，见archive/2026-10-24-s245-next-slices.md。
- `ERP-S4-03A-CLAUDE-20261024` — `claimed` — Owner local_f6b118ce-f036-4ece-9a0b-322073b20c74 — Base 315ab97addfd9f1295a4855a7ea8ece430034e4f — 精确5文件/无迁移；可行性拒绝及真实JPA，见archive/2026-10-24-s245-next-slices.md。
- `ERP-S2-READINESS-ENTRY-CLAUDE-20261022` — `in-progress` — Owner local_f6b118ce-f036-4ece-9a0b-322073b20c74 — Base 4a48c5b49c3a5f5214c68a3add5081ee409d88b2 — 10月24日实际ACK并实施；精确11文件见archive/2026-10-23-s2-entry-scope.md；原QA验收。
- `ERP-S6-B3C-CLAUDE-20261022` — `claimed` — Owner local_f6b118ce-f036-4ece-9a0b-322073b20c74 — Base 13874247c5b47897e86511cd095dc3bebe6447e1 — C2/C3整改7可写+5冻结，见archive/2026-10-23-s6-c2c3-atomic-scope.md；原HEAD/生产仍HOLD。
- `ERP-S6-B3B-GRANT-CLAUDE-20261022` — `review` — Owner local_f6b118ce-f036-4ece-9a0b-322073b20c74 — Base 41eda6951b56790a4d3989c35e234e4b0f54b204 — PR1095/a844845精确3+1仅审阅；重放等V72→V73；新共享scope未释放，见archive/2026-10-22-s6-b3b-review.md。
- `ERP-S6-V72-PASSIVE-CLAUDE-20261022` — `review` — Owner local_f6b118ce-f036-4ece-9a0b-322073b20c74；Base e4896543a76a15c8808cb02767b3b021fad53462；1105/66a845精确5文件V78、1093/5c4bc2精确9文件V79仅改号接收；合并HOLD，见archive/2026-10-23-claude-v78-v79-adjudication.md。
- `ERP-S6-B2-CLAUDE-20261021` — `blocked` — Owner local_f6b118ce-f036-4ece-9a0b-322073b20c74 — Base 405d01f1f7e03f6e57cb06f9dbbc3e5a97e0bdce — PR1075/41eda实际28文件整体HOLD；未来runtime writeScope=[]；V72另列被动5文件队列。
- `ERP-S6-EXECUTION-ARCH-CLAUDE-20261021` — `review` — Owner local_f6b118ce-f036-4ece-9a0b-322073b20c74 — Base 405d01f1f7e03f6e57cb06f9dbbc3e5a97e0bdce — B1/1073/3029151精确8已接收待集成，非部署或业务验收；正式裁决已交原Owner。
- `ERP-S2-08A-CLAUDE-20261024`：仅archive/2026-10-24-s245-next-slices.md列6文件；原Claude唯一写者，不接管设备/浏览器/CSV或权限入口。
- `ERP-S4-03A-CLAUDE-20261024`：同归档精确5文件，含InsertSlotRepositoryQueryValidationTest；无迁移，不放开S4-05/Orchestrator/采购链。
- `ERP-S2-READINESS-ENTRY-CLAUDE-20261022`：精确11文件锁见archive/2026-10-23-s2-entry-scope.md；原Claude唯一写者，平台权限/采购/S6不释放。
- `ERP-S6-B3C-CLAUDE-20261022`：仅archive/2026-10-23-s6-c2c3-atomic-scope.md的7文件修改，5依赖冻结；config写锁与同事务扣减，禁止回写B3A/B2和生产调度。
- `ERP-S6-V72-PASSIVE-CLAUDE-20261022`：原Owner精确5文件V78/9文件V79续验；B3C仅独立登记7可写+5冻结（含V80），原HEAD/生产HOLD；S2已登记11文件待Owner回读；Gateway/采购/Orchestrator不释放。
- `ERP-S6-B2-CLAUDE-20261021`：实际28文件整体review/HOLD；runtime writeScope=[]；V72被动5另列队列待独立WT确认；不放开采购/供应商/Gateway/共享DataScope/runtime policy。
- `ERP-S6-EXECUTION-ARCH-CLAUDE-20261021`：仅archive/2026-10-21-claude-s6-actual-intake.md列B1精确8文件修订；Owner不变，不回溯批准先写后登记。
