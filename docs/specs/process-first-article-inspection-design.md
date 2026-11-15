# 工序首检（E 线 BL17 承接）设计

日期：2026-11-16　迁移：`V20261115_62__process_first_article_inspection.sql`　来源：`prototypes/unified-v1/spec/impl-E.md` ①、`BL-borrow.md` 第 17 条、`DECISIONS.md` 十九

## 目标

一个工序开始生产后先做首件检验：产线送检，质检逐项录实测值、系统按标准算单项结论，质检判定合格 / 不合格。按工厂开关决定首检没合格时只提示还是拦完工。机制归系统、策略按厂配；默认关，现有工厂行为不变。

## 数据模型

| 表 | 作用 | 关键约束 |
|---|---|---|
| `first_article_policies` | 工厂开关 `mode` = `OFF` / `WARN` / `BLOCK` | 主键 `factory_id`；**没有行 = OFF** |
| `first_article_standards` | 哪道工序（`work_processes.id`）要首检、检哪些项（`items_json`） | 部分唯一索引：每厂每道工序最多一条启用标准；改标准 = 同行 `revision+1`；停用写原因 |
| `first_article_inspections` | 挂在工序任务（`work_process_tasks.id`）上的首检单，冗余 `production_batch_id`、`work_process_id` | 存送检时的标准快照（`standard_items_json` + revision），后改标准不影响已送检的单；部分唯一索引：一个任务同时只有一张 PENDING；CHECK：判定过的必须有判定时间与系统结论，作废必须有原因 |

和计划、任务的关系：标准挂在**工序定义**上（与原型「标准勾用作首检并选工序」一致，覆盖该工序的所有产品）；首检单挂在**工序任务**上，经任务找到批次和计划。不新建通用检验规范模块（N7 已裁定），也不改现有 `quality_inspections`。

检验项（唯一一份规则在 `FirstArticleItemRules`）：
- 数值项：上限 / 下限至少一个，下限 ≤ 上限，标准值在上下限之间；实测值在 [下限, 上限] 内为合格。
- 选项项：至少 2 个选项；合格选项是非空真子集。
- 必检 / 选检；必检项缺值不能判定。

## 状态机

```
送检 → PENDING ──判定──→ PASSED / FAILED
          └────作废(写原因)────┴──→ VOID（作废后可重新送检）
FAILED 之后返工再送检，开一张新单（旧单保留）。
```
任务的首检状态取最近一张非 VOID 的单：还没送检 / 待判定 / 首检合格 / 首检不合格。

- 送检：任务没结束（待开始或进行中）、不是委外工序、工序有启用标准、没有待判定的单、还没合格（合格后要重检先作废）。送检只关联任务，**不改任务状态**。待开始也能送检，是因为 web 逐道录入（Workflow 线）的任务在录入前一直是待开始，web 和 App 上都没有开工按钮（独立审查 M2）。工序首检表默认列「未完成」（待开始 + 进行中）。
- 判定：服务端按快照逐项算结论；系统算出不合格时**不能判合格**；各项都合格却判不合格必须写原因。

## 谁能做（服务端 `@RequirePermission`）

| 动作 | 权限 |
|---|---|
| 读开关、标准、工序首检表、首检单 | `quality:read(_write)` 或 `production:read(_write)` |
| 送检 | `production:read_write` 或 `quality:read_write` |
| **判定、判定预览、作废、维护标准** | **只认 `quality:read_write`** |
| 改工厂开关 | `factory_super_admin` / `permission_admin`（与工厂总设置一致） |

所有查询带 `factory_id`；别厂的任务、标准、首检单一律 404。

## 开关打开后的效果（`FirstArticleGate`）

| 开关 | 开工 | 报完工（工序任务完工 + AI 完工工具、Web 逐道录入正数产出的正式行、App 产出报工且完工） |
|---|---|---|
| OFF（默认） | 照旧；只查一次开关 | 照旧；返回 JSON 与之前一样（`firstArticleNotice` 为 null，不序列化） |
| WARN | 照旧，返回里带 `firstArticleNotice` | 照旧，返回里带 `firstArticleNotice` |
| BLOCK | 照旧（首件要开工后才做得出来）+ 提示 | 首检没合格 → 409 `FIRST_ARTICLE_NOT_PASSED`；AI 完工预览同一判据，不出确认卡 |

## 完工入口与判据

全仓库把工序任务写成完成的地方有 4 处：3 处接了同一判据，委外结单故意不接。

| 入口 | 接在哪 | 拦什么 | 不拦 |
|---|---|---|---|
| 工序任务完工（HTTP、AI 工具） | `WorkProcessTaskServiceImpl#complete`，AI 预览经 `assertFirstArticleAllowsComplete` | 完工 | 开工 |
| Web 逐道录入 | `ProcessSheetServiceImpl#saveRow / #submitRow` 的前置校验（与上游解冻闸同位置，写入前）；改行、多产出、重新物化等路径在回写「记成完成」前再判一次（同一事务，抛出即整次保存回滚） | 有正数产出的正式行 | 产出为 0 的行、草稿、早已完成的任务 |
| App 产量报工 | `YieldReportServiceImpl#submitReport`，落库前 | 产出 / 旧式整合报工且会把任务记成完成 | 部分产出续报（`markComplete=false`）、领料、工时段 |

| 委外结单 | `OutsourceService#close` | **不接**：委外工序不要求我方首检 | — |

委外不接的理由：委外工序的首件在委外厂做，我方拿不到首件；回厂走委外收货和来料质检。所以闸对委外任务按「不要求首检」处理（`FirstArticleGate#requirementOf`），工序首检表不列委外任务，送检接口拒绝委外任务（409 + hint），不会出现永远消费不掉的待办。

开关关或提示时，每个入口只多查一次开关，不读 Workflow 快照，也不查标准和首检单。

开关读取失败（连接抖动、锁超时、表权限）一律按「关」处理并记 warn，读取放在独立只读事务里（同 `FactorySelfApprovalGate.isEnabled`）。只加 try/catch 不够：在调用方事务里查询报错，PG 会把外层事务标成 aborted，后面的开工或报工照样失败（独立审查 M1；真 PG 测试里有「不隔离会废掉外层事务」的阳性对照）。

### 已知且接受的缺口（独立审查低危项）

- L2 跳过工序（`skip`，限主管、必填原因）不算完工，拦截档下也不拦，下游会照常解锁。这是故意的：跳过本身已经要主管写原因。
- L3 web 线的判定发生在请求事务里，真正写「完成」是在提交后另开的事务（`WorkflowTaskProgressEventListener`）。两者之间如果有人作废了合格单，任务照样会被写成完成；判定、作废和完工之间也没有共同的锁。窗口很小，不加锁。
- L5 首检接口挂在 `quality_inspection` 模块下。如果工厂设成拦截后又关掉质检模块，首检页和送检接口都进不去，但闸仍然生效。目前靠运维约定：关质检模块前先把首检开关改回「关」；后续可以让闸在模块关闭时按关处理。

## 口径选择（二选一时选了保守的一边）

1. 默认关，没有开关行的工厂一律按关处理，不回填。
2. 开关三档，「提示」与「拦截」由工厂选；拦截只拦报完工，不拦开工。
3. 标准挂在工序定义上，不按产品分别配置。
4. 判定遵守工厂「允许本人审批」开关（organizer 按保守口径定，走唯一判定处 `FactorySelfApprovalGate`）：开关关（默认，或读不到）时，送检人不能判定自己送的样，服务端返回 403 `FIRST_ARTICLE_SELF_JUDGE_FORBIDDEN` 并写明原因；开关开时允许，但必须写本人判定原因（没写返回 422），记录上留痕 `self_judged` + `self_judge_reason`。
5. 系统判出不合格项时不允许人工改判合格（不设让步放行）。

## 未做（本切片范围外）

- 对话建厂（FactoryBuildInterview）里还没有首检开关这一题；目前只能在 web-admin「工序首检」页改。
- 规则提醒（首检未做提醒）是 E 线另一项，不在本切片。
- RN App 没有改界面；`firstArticleNotice` 字段已在开工和完工的返回里，App 还没有显示它。
