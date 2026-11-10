# 工厂纪律口径改为按工厂配置（纪律排查 A3 / A7 / A11 / A13 / A14）

来源：`工厂纪律与顺序排查-20261110/报告.md`；Steve 2026-11-10 总原则「是否拦、怎么拦都由工厂自己配置，不写死」。
配置全部放在阈值中心 `factory_thresholds`（工厂行 > 全局 `'*'` 行 > 代码兜底），唯一读取处
`service/discipline/FactoryDisciplinePolicy`，迁移 `V20261110_81__factory_discipline_configs.sql`。
工厂在 web「系统设置 → 业务阈值配置」里按类别（质量 / 生产）修改。

| 条目 | 配置键 | 默认 | 可选值 |
|---|---|---|---|
| A3 批次放行 | `quality.batch_release.mode` | `QC_ONLY` | `QC_ONLY` / `QC_OR_MANUAL` |
| | `quality.batch_release.manual_roles` | 空（不另限角色） | 角色代码逗号分隔 |
| | `quality.batch_release.require_reason` | `1` | `1` / `0` |
| A7 完工必须先结单 | `production.completion.require_settlement` | `1`（所有工厂含存量，Steve 2026-11-10） | `1` / `0` |
| A11 仓库多收 | `production.receipt.over_receive_mode` | `BLOCK` | `BLOCK` / `WITHIN_RATE` / `ALWAYS_WITH_REASON` |
| | `production.receipt.over_receive_rate` | `0.05` | 小数比率 |

> A11 放行的多收与少收同口径：差异在称重容差内（kg 为 10 公斤）不要原因、记为称重误差；超出容差必须填差异原因并选责任方。`ALWAYS_WITH_REASON` 的「WITH_REASON」指超出容差的部分。
| A13 解冻补登 | `production.time_limit.thaw_backfill_max_minutes` | `480` | 0 = 不许补登 |
| A14 补录窗口 | `production.backdate.max_days` | `2` | 天数 |

字符串配置写错一律按最严处理（A3 → 只能质检放行，A11 → 硬拦），拦截文案说出配置值。

## 出口清单（硬约束 8：改前 / 改后计数）

- A3 放行（不可投产 → 可投产）出口 4 = 改后 4：仓库 PUT status、AI `batch_update`、AI `release_decision`、质检处置。
  前两条过闸 + 台账；后两条是质检放行，只写台账（`release_decision` 另补 `quality:read_write` 鉴权）。
  故意不改 11 处系统内部 `setStatus(AVAILABLE)`：新建批次 / 退料、撤回、盘点把「已用完」恢复为可用，都不是从不可投产状态出发。
- A7 `requiresProductionSettlement` 调用 2：`ProductionPlanServiceImpl.completeProduction` 改；
  `SupplyChainOrchestrator`（结单模式不自动建成品）故意不改 —— 它管的是「两点报工模式不自动建成品」，不是完工纪律。
  直接完工调用方 2（REST、AI `PlanUpdateTool`）都带上超产原因。
- A11 多收拦截 2：普通入库改；workflow 多产出分次入库故意不改（逐行无差异原因 / 责任侧 / 中转挂账模型，后续单列）。
- A13 解冻登记入口 1（`startMark`）改；冷藏暂停故意仍只收当前时刻（往前补暂停能把超时料救回来）。
- A14 报工类入口收客户端日期 6 = 改后 6 全部过同一校验：报工、领料、班组报工、原料入库（原已接）+ 报工单提交、报工单修改（原未接）。
  故意不改：AI 计件效率记录（`/wage/efficiency/record`，人效数据非报工）、物流导入（非工厂报工域）。

## 前端输入

- web 入库确认弹窗：本厂允许多收时，实收输入不再封顶报产量；多收与少收一样要选差异原因 + 责任侧（复用原有控件）。
- web 逐道录入时限面板：「开始解冻」旁加「补登实际时间」，填时间 + 补登原因。
- RN 计划管理「完成生产」弹窗：实际产量超过计划时出现「超产原因」输入（下方 UX Flow Analysis）。
- RN 两个完工入口（计划管理、调度计划详情）收到 409 `PRODUCTION_SETTLEMENT_REQUIRED` 时，不弹原始错误，改为专门提示「本厂要求先结单，请到网页端结单」并给出网页端路径「生产管理 → 生产计划」(`/production/plans`)。
- RN 没有批次放行、生产入库、解冻登记入口，无需改；web 不调直接完工、没有改批次状态的页面，无需改。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像
车间主任 / 生产主管 — 会用手机 App 但不熟悉系统概念 — 一批做完后在 App 上点「完成生产」并录实际产量，偶尔因为客户追加或投料多而超产。

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 计划卡片上的「完成」按钮 | 点完成 | 弹窗显示计划单号、产品、计划产量 |
| 2 | 实际产量输入框（默认填计划量） | 改成实际产量 | 下方显示完成率 |
| 3 | 实际 > 计划时，完成率下方出现黄色提示 + 「超产原因」输入框 | 填原因 | 确认按钮可点 |
| 4 | 确认完成 | 点确认 | 成功提示；若本厂要求先结单，提示去 web 核对结单 |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 超产要原因，但用户点确认后才被服务端 409 拒绝 | HIGH | fool-proof Rule 1（预先显示边界） |
| F2 | 原因框一直显示会干扰不超产的大多数情况 | MED | 内联 UX 规则：信息密度 |
| F3 | 被拒后表单被清空要重填 | MED | 内联 UX 规则：错误恢复 |
| F4 | 本厂要求先结单时用户在手机上卡住 | MED | fool-proof Rule 5（Dead-end 改导航） |

### 每个摩擦点的设计回应
- F1 → 前端实时比较实际与计划，超产时立即显示原因框，未填原因时「确认完成」置灰，并在框下写明「实际比计划多 X，必须写原因」。
- F2 → 原因框只在实际 > 计划时出现，不超产时不渲染。
- F3 → 服务端拒绝时保留弹窗与已填数量 / 原因，只弹出服务端文案（发生了什么 + 怎么办）。
- F4 → 服务端 `PRODUCTION_SETTLEMENT_REQUIRED` 文案直接说明「去核对结单」，弹窗保留可取消；手机端不提供结单入口（结单在 web）。
