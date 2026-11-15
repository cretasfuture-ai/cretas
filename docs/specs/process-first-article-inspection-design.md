# 工序首检（E 线 BL17 承接）设计

日期：2026-11-16　迁移：`V20261115_51__process_first_article_inspection.sql`　来源：`prototypes/unified-v1/spec/impl-E.md` ①、`BL-borrow.md` 第 17 条、`DECISIONS.md` 十九

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

- 送检：任务必须「进行中」、工序有启用标准、没有待判定的单、还没合格（合格后要重检先作废）。
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

| 开关 | 开工 | 报完工（`PUT /work-process-tasks/{id}/complete`、AI 完工工具） |
|---|---|---|
| OFF（默认） | 照旧；只查一次开关 | 照旧；返回 JSON 与之前一样（`firstArticleNotice` 为 null，不序列化） |
| WARN | 照旧，返回里带 `firstArticleNotice` | 照旧，返回里带 `firstArticleNotice` |
| BLOCK | 照旧（首件要开工后才做得出来）+ 提示 | 首检没合格 → 409 `FIRST_ARTICLE_NOT_PASSED`；AI 完工预览同一判据，不出确认卡 |

## 口径选择（二选一时选了保守的一边）

1. 默认关，没有开关行的工厂一律按关处理，不回填。
2. 开关三档，「提示」与「拦截」由工厂选；拦截只拦报完工，不拦开工。
3. 标准挂在工序定义上，不按产品分别配置。
4. 判定不设四眼：送检人和判定人可以是同一人，只要有质量写权限（小厂一人质检的常见情况）。要不要接工厂的「允许本人审批」开关，需要 Steve 拍板。
5. 系统判出不合格项时不允许人工改判合格（不设让步放行）。

## 未做（本切片范围外）

- BLOCK 只接了工序任务「报完工」入口（HTTP + AI 工具）。Web 逐道录入（`ProcessSheetServiceImpl` → `WorkflowTaskProgressWriter`）和 App 产量报工也会把任务记成完成，这两条路**不拦**。接上前，开「拦截」的工厂只在工序任务完工入口生效。
- 对话建厂（FactoryBuildInterview）里还没有首检开关这一题；目前只能在 web-admin「工序首检」页改。
- 规则提醒（首检未做提醒）是 E 线另一项，不在本切片。
- RN App 没有改界面；`firstArticleNotice` 字段已在开工和完工的返回里，App 还没有显示它。
