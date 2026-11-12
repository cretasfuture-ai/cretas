# 字段映射与指标口径

权威来源：[官方动态接口文档](https://blacklake.feishu.cn/docs/doccn4ngN8QPClafs1duryGaLed)、[官方接入说明](https://blacklake.feishu.cn/docx/ZSlidy6YlooVAAxY0yxcpa5hnMe)，并用 2026-11-03 本工厂的真实只读响应核对。未使用第三方旧文档。

## 已验证的传输结构

| 对象 | 列表位置 | 总数位置 | 主键 | 更新时间 |
|---|---|---|---|---|
| 工单 | data | page.total | id | updateTime |
| 任务 | data | page.total | id | updatedAt |
| 报工 | data | page.total | id，另保留 reportingId 于 raw | updatedAt |
| 出入库单 | data.data | data.total | id，明细 orderDetails[].id | updatedAt |

分页均为请求 `page.pageNum/page.pageSize`，页码从 1 起，每页最多 100。生产响应结构和更新时间字段不可统一猜为同一路径。

| 标准字段 | 工单 | 任务 | 报工 |
|---|---|---|---|
| project_id | 本身 id | projectId | projectId |
| project_code | projectCode | projectCode | projectCode |
| task_id | — | 本身 id | taskId |
| product_id | productCode | productCode | productCode |
| process_id | — | processId | processId |
| planned | planAmount | planAmount | 不映射 |
| actual | actualAmount | 不映射 | 不映射 |
| good | numberofGoodProducts | fineAmount | numberofgoodProducts |
| defective | numberofDefectives | defectsAmount | numberofDefectives |
| approved_good | — | approvedFineAmount | 按 approvalStatus 筛选 |
| duration_seconds | — | outputDurationForSecond | 不推算/不叠加 |
| labor_cost | — | sumOutputSalary | estimatedSalary 不参与叠加 |
| unit | projectUnit | 源响应无明确单位，保留缺失 | stockUnit |
| status | status | processStatus | — |
| approval_status | — | 不猜任务整体审批状态 | approvalStatus |

source_factory_id 取成功 HTTPS 登录签发 Token 中的 orgId；任务另核对返回的 orgId。其他对象没有行级厂 ID 时，要求已经验证的连接身份，不从业务名称推断归属。Token 中的身份元数据仅用于来源标记，不用于 Cretas 认证。

日期显式按 Asia/Shanghai 解读黑湖的本地秒格式，存为带时区时间；新厂或跨时区复用前须重新核对时区约定。所有数量、金额使用 Decimal 计算并以十进制字符串进入标准 JSON，缺失为 null，源数据显式零值保留零值。

## 库存与材料成本

每个出入库单的标准化 details 仅保留所需产品、单位、数量、变动数、关联工单身份和成本字段。`costUnitPrice` → cost_unit_price，`changedCostAmountSigned` → material_cost_signed，`associatedBizOrderCode/Id/Type` 保留来源关联；源明细 ID 做稳定键，不使用数组序号代替身份。

材料成本视图仅接受已审批单据、bizType 32（生产领料）或 30（生产退料）、associatedBizOrderType 10（工单）且存在工单编号的明细。出库成本为负，因此消耗成本方向为库存有符号成本的相反数，退料抵减。其他库存变化不当作生产耗料。

成本覆盖率是“有源成本字段的合格明细数 / 合格明细总数”；它衡量字段存在，不代表成本价格已经业务核定。源零成本单独计数，不能声称价格可靠或完整制造成本已经覆盖。

## 第一版确定性 BI

- 工单队列：按计划开始时间落在期间内选取工单。状态 0 新建、10 执行中、20 已结束、30 已取消；完成率为已结束 / 非取消且已知状态工单。未知状态另计，不静默归为未开始。
- 延期率：已结束取实际结束时间，未结束取期间截止点，与计划结束时间比较；缺计划时间或已结束却缺实际结束时间的记录排除分母，并报告缺失数量。此口径是本次截止点快照，不重建历史状态。
- 计划/实际产量：仅按工单层汇总，同产品、同单位分组；分别报告缺失数，不叠加任务或报工产量。
- 工序进度：按任务状态和任务自身计划数、良品数计算；达到率可超过 100%，不裁剪。工时按工序展示累计快照，不能叫“本周实际工时”，也不能仅凭累计总量认定瓶颈原因。
- 质量：仅选报工结束时间在期间内、approvalStatus 为 10 的记录，按产品、工序、单位分组，良品率为 good/(good+defective)，零分母为 null。不能跨工序把这些数量汇总为全厂最终产量。
- 工资：任务累计工资与报工预计工资互斥。当前没有已验证的期间分摊策略，因此不生成期间人工成本或单位产品完整成本。
- 原始缺失、未审批、无法关联、未知单位分别披露；不让 AI 补数。完整制造成本和毛利固定缺失，P2 AI 尚未接入。

默认 CLI 指标不打印工资或外部 ID。完整 PoC 指标保存在本机 DPAPI 报告中；tracked 联调报告只留计数和覆盖率。
# 历史归档补充

`full_contract.json` 保存历史归档的逐对象路径，不替代七天 BI 口径。产品/工序以 id、路线以 processRoutingCode、仓库以 simpleVO.id、单位及 BOM 以 id、余额以 productId+warehouseId 建键。工单用料保留请求父上下文且缺少返回父 ID 时标记未验证；当前工序按请求工单包装原始数组，不制造来源工单标识。详情和数量见 FULL-REPORT.md。
