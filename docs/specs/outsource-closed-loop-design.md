# 原生委外闭环设计（委外单 + 任务行）

更新：2026-11-14。属于「原型统一」批次的 M7 补范围，口径依据 DECISIONS 第十九节。
模型来源：Steve 11-14 定「按这个模型做」，经 organizer 转达。
只读盘点笔记：side-session-notes/local_7a0a8e1c.../outsource-readonly-inventory-20261114.md。

## 1. 依据：一笔真实的委外业务

数据来自六膳门黑湖只读快照（2026-11-14 08:01 采集），共 6 个委外任务、3 张委外订单、12 条发退料、5 条收货。

- 单据层级分三层：委外订单（每张对应一个委外厂）→ 任务行（每张 1–3 行）→ 发料、退料、收货记录。每条记录只挂一个任务行。
- 一张委外单可以挂多个工单：工序委外的那张订单有 2 行，分别挂在 2 个工单的同一道工序上。
- 整单委外占多数：6 行里有 4 行不挂工单，也不挂工序。
- 一次发料是多物料：自制半成品 N、外购包材 N，再加约 5% 的外购辅料。收货按包回来，数量与半成品 1:1。退料只是少量辅料。
- 加工费每包 0.6–0.64，按实收合格数计。有超发（计划 20000、已发 30720），需要靠退料或损耗对平。

由此确定模型：**一张独立的委外单（按委外厂开）加若干任务行**。行上的工单和工序可以为空：挂了就是工序委外，没挂就是整单委外。发料、退料、收货、退货都挂在单个任务行上。不照搬黑湖的十个菜单，也不另建结算系统。

## 2. 数据模型（迁移 `V20261114_91__outsource_orders.sql`）

| 表 | 关键列 | 说明 |
|---|---|---|
| `outsource_orders` | `order_no`（同工厂唯一）、`supplier_id`、`outsource_warehouse_id`、`status`、`expected_return_date`、`version` | 状态：DRAFT 草稿 → RELEASED 已下达 → CLOSED 已结单；另有 CANCELLED 已取消（只有草稿能取消） |
| `outsource_order_lines` | `outsource_type`（PROCESS 或 WHOLE）、`production_plan_id`、`production_batch_id`、`work_process_task_id`、`output_kind`（FINISHED 或 SEMI）、`product_type_id`、`unit`、`planned_quantity`、`unit_fee`、`tax_rate`、`received_good_quantity`、`received_defect_quantity`、`returned_goods_quantity`、`rework_pending_quantity`、`fee_amount`、`status` | 各累计量都由服务端维护，前端不写 |
| `outsource_movements` | `movement_type`（取值见下）、`line_id`、`source_batch_id`、`target_batch_id`、`target_batch_kind`、`material_type_id` 或 `product_type_id`、`quantity`、`defect_quantity`、`unit`、`unit_cost`、`amount`、`ap_transaction_id`、`quality_return_order_id`、`parent_movement_id`、`reason`、`client_request_id`（同工厂唯一，用于防重复提交） | 收发明细。库存真值在 `material_batches` / `finished_goods_batches` 里，这张表只做单据和审计 |

`movement_type` 的取值：

- ISSUE：发料
- MATERIAL_RETURN：退料
- RECEIVE：收货
- CONSUME：收货时的耗用
- GOODS_RETURN：退货
- LOSS：损耗

既有表加列：

- `work_process_tasks`：加 `is_outsourced`、`outsource_line_id`、`outsourcer_supplier_id`、`outsource_unit_fee`、`outsource_fee_amount`。
- `factory_warehouses`：加 `outsourcer_supplier_id`。每个委外厂对应一个 OUTSOURCE 仓，一一对应，部分唯一索引。
- `quality_return_orders`：加 `outsource_line_id`、`source_batch_id`。

## 3. 库存与物权

**发料（ISSUE）**：按任务行选我方仓库里的物料批次，可以是原料、包材、辅料，也可以是 `product_type_id` 不为空的已入库半成品。流程与领料划出（`FactoryMaterialRequisitionServiceImpl.relocatePickedMaterialToWorkshop`）一样：

1. 源批次 `used_quantity += q`；
2. 在该委外厂的 OUTSOURCE 仓新建一个批次（`source_doc_type=OUTSOURCE_ISSUE`），单价、单位、效期、所有权、客户归属都照抄源批次；
3. 写血缘边 MATERIAL_TRANSFER。

不走 `TransferServiceImpl` 建调拨单。领料代码第 1028 行的注释已经写明原因：调拨单在「调拨管理」里是一张可以继续走完的动作单，走完会二次扣库存。

**物权不变**：委外仓的批次仍是我方或原客户的库存，所以照常计入库存余额和估值。但它不能被领料、FEFO、销售可用量或投产分配选中。

做法是委外仓批次的状态写成 `RESERVED`（已预留）。实际核对过：`MaterialBatchRepository` 里所有可用量和 FEFO 查询都只取 `status='AVAILABLE'`，其中有 8 处不带仓库过滤，比如 `findAvailableBatchesFEFO`、销售可用量、投产分配。如果状态写成 AVAILABLE，委外厂手里的料就会被这些查询算成可用。质检扣留后放行、仓库页或 AI 工具把它改成「可用」时，`MaterialBatchHoldService.releaseTarget` 一律改回 RESERVED（无结存则已耗尽），料回我方只走委外退料。

委外仓批次的数量只由本模块改动。

**OUTSOURCE 不加入结单合法仓集合（刻意不改，与 organizer 原提法不同）**。核对了用到 `SETTLEMENT_ELIGIBLE_TYPES` 的地方：

1. `MaterialBatchRepository.sumAvailableRawStockQuantityByMaterialType` 统计结单时可用的原料库存；
2. `ProductionPlanServiceImpl` 约第 7420 行判断哪些批次可以由结单扣减。

把 OUTSOURCE 加进去，等于允许生产结单直接扣委外厂手里的料，而这批料又会在委外收货时被扣一次，结果就是重复耗用。所以「OUTSOURCE 接入业务」的意思是：只由本模块的发料、退料、收货、损耗来动这些批次。另有一条测试钉住 `SETTLEMENT_ELIGIBLE_TYPES` 里不含 OUTSOURCE，改了它测试就会变红。

**退料（MATERIAL_RETURN）**：发料的精确逆操作。委外仓批次 `used += q`，原批次 `used -= q` 回到原批次。这和 `ProductionMaterialReturn.restoreBatchUsedQuantity` 的口径一致：退回原批次，不能另选目标仓。

**收货（RECEIVE）**：一次收货包含合格数 g 和不良数 d。

1. 耗用：把委外仓批次按「本次 g+d 占剩余应收的比例」给出建议耗用量，人可以改。服务端校验每个批次的耗用量不超过它的结存。每个批次写一条 MaterialConsumption（sourceType `OUTSOURCE_RECEIVE`），另写一条 CONSUME 明细。
2. 产出：
   - FINISHED 行：新建成品批次。状态按 `QcInspectionPolicy.finishedGoodsStatusForOutput` 判定，需检产品为待检，其余可用。入成品仓。
   - SEMI 行：新建半成品物料批次（`product_type_id` 为行上的产品），入车间仓，供下一道工序领料或投料。
   - 不良数 d 另建一个批次，状态为不良（DEFECTIVE），不计加工费。
3. 血缘：每个被耗用的委外仓批次写一条 OUTSOURCE_PROCESS 边，指向产出批次。目标类型是 MATERIAL_BATCH 或 FINISHED_BATCH。
4. 返工回货：如果之前有退货，行上的 `rework_pending_quantity` 会大于 0。这部分重新收货时**不再耗料**，因为料已经在第一次收货时耗过。只建产出批次，**不再计加工费**（#1723 第 4 段）。血缘：SEMI 行指向那次退货的源批次；FINISHED 行（成品批次不能做 OUTSOURCE_PROCESS 边的源）改为按收发明细追到被退批次当初耗用的委外仓批次，逐个写 OUTSOURCE_PROCESS 边到返工批次，返工再返工也追到首次收货。

**损耗（LOSS）**：结单时委外厂手里还有剩余，可以登记为损耗，原因必填。委外仓批次 `used += q`，写一条 MaterialConsumption（sourceType `OUTSOURCE_LOSS`）。

**结单**：委外厂结存（该单所有委外仓批次的当前数量之和）必须等于 0，否则返回 409，并逐批列出剩余。系统不会静默把差额吞掉。

## 4. 工序委外接进生产主链

- 下达时，对挂了 `work_process_task_id` 的行，把对应的 WorkProcessTask 标上 `is_outsourced`、委外厂和单价。工单详情和工序列表就能显示「委外：某厂」。
- 收货时累加 `task.actualQuantity`，同步 `outsource_fee_amount`。如果任务是 PENDING，自动转为 IN_PROGRESS。
- 行完成后（合格数达到计划数，或结单），任务转为 COMPLETED。
- 下一道工序的投料来自半成品批次，走的是现有领料或投料路径，后续工序不需要任何改动。
- **为什么不走 `WipInventoryServiceImpl.postApprovedOutput`**：
  1. 它消耗的是线上 WIP（`semi_finished_inventory`），而这张表没有仓位列，表达不了「这批在委外厂」；
  2. 它还会再算一遍报工成本，与委外加工费重复。

  所以工序委外沿用工序任务这条状态线，产出另落半成品批次。线上 WIP 外发不在 v1 范围内（见第 9 节）。

## 5. 加工费与账

- **金额**：本次**新收**合格数 × 行单价（返工回货抵扣的部分不计）。本次新收（合格新收 + 不良）不能超过剩余应收，超了 409 OUTSOURCE_RECEIVE_EXCEEDS_REMAINING。单价按含税价，行上的税率只作展示。不良不计费。行上的 `fee_amount` 是累计净额。
- **应付**：收货确认的同一事务内调用 `ArApService.recordPayableFromSource`，参数为 sourceType `OUTSOURCE_RECEIVE`、sourceId（收货明细的 id），挂 AP_INVOICE，到期日 30 天（同送货单口径）。按 sourceType+sourceId 幂等。收发写接口（发料、退料、收货、退货、损耗）的 `clientRequestId` 必填，缺了 400 OUTSOURCE_REQUEST_ID_REQUIRED；重试沿用同一个编号即命中幂等。
- **退货（GOODS_RETURN）**：合格品退回委外厂返工，流程如下：
  1. 扣减收货批次；
  2. 行上合格数减 q，`rework_pending_quantity` 加 q；
  3. 用 `ArApService.recordAdjustment` 挂一条负数的 AP_ADJUSTMENT，sourceType 为 `OUTSOURCE_GOODS_RETURN`。它是待审批状态（四眼），审批前不改余额。返工回货不再计费，所以：财务批准 = 返工那部分不付费；驳回 = 照原收货付费，都只付一次。
- **QualityReturnOrder SUBCONTRACT**：
  - 关联了任务行的 SUBCONTRACT 单，在 `ship` 时调用同一个退货实现，并回填 movement。
  - 没有关联任务行的 SUBCONTRACT 单，`ship` **保持原行为**（只记单发出，不动委外库存）。关联入口（接口/界面）接上之前不收紧，否则存量「退回委外」单全部发不出（#1723 终审 高1）。
- **成本**：
  - 产出批次单价 =（本次耗用物料成本 + 本次加工费）/ 合格数。
  - 半成品被下道工序投料后，加工费已经随批次单价进了 `OrderCostBreakdown` 的投料成本，所以不在计划合计里再加一次（防重复计算）。
  - `OrderCostBreakdownDTO` 和 `FinanceCostBreakdown` 新增或接上 `processingFee` 字段，值为销售订单名下计划所挂委外行的加工费合计，作为结算依据展示。没有委外行时仍为 null（不伪造 0）。

## 6. 权限

- 后端：`@RequireModule("production_plan")`；读用 `production:read_write` 或 `production:read`，写用 `production:read_write`。数据都按 factoryId 隔离。
- 前端：菜单放在「生产管理 › 生产过程」下的「委外单」，`/production/outsource`；详情页 `/production/outsource/:id`。权限键沿用生产模块，不新增委外菜单组。

## 7. 页面动线

页面沿用 P1 设计系统：`components/business`、`styles/theme` 的 tokens。

- **委外单列表**：可按委外厂、状态、单号筛选（服务端过滤）。列表列出委外厂、行数、计划数、已收合格数、在委外厂结存、加工费、期望回货日期、状态。每一行只给一个主操作：草稿行是「下达」，已下达行是「收货」。
- **详情页**：头部是委外厂、状态，以及在委外厂结存（按批次）。
  - 「任务行」页签：每行显示类型、所挂工单和工序、产出、计划数、已收、待收、返工待收、加工费，以及该行的动作（发料、退料、收货、退货、损耗）。
  - 「收发明细」页签：列出全部 movement，含源批次、目标批次、数量、金额和关联的应付。
- **收货对话框**：系统带出剩余应收、单位、建议耗用（按批次），人填合格数和不良数，提交后回读。失败时保留已填内容；重复提交由 `client_request_id` 拦下，返回原记录。
- **工单详情**：工序列表里委外工序显示委外厂、状态和委外单号，可以点进去跳到委外单。

## 8. 验收清单

真 PG 测试 `OutsourceClosedLoopPostgresIntegrationTest`，所有断言都在真实 PostgreSQL 上读数。

1. 守恒：发料 → 部分退料 → 收货耗用 → 损耗 → 结单。每种物料满足「源批次减少量 = 委外仓净增 + 耗用 + 损耗」；结单时委外仓结存为 0。
2. 血缘：每个被耗用的委外仓批次都有一条到产出批次的 OUTSOURCE_PROCESS 边，在闭包表里能从产出批次追到原始源批次（深度 ≥ 2）。
3. 超发对平（阴性对照）：超发后直接结单必须是 409，并列出剩余批次；退料或登记损耗之后才能结单。
4. 加工费：合格数 × 单价 = AP_INVOICE 金额 = 行累计；不良不计费；退货产生一条负数的待审 AP_ADJUSTMENT，余额在审批前不变；重复提交同一个 `client_request_id` 不会重复挂账。
5. 阴性对照：
   - 发料量超过源批次结存 → 409，且源批次数量不变；
   - 耗用量超过委外仓结存 → 409；
   - 未关联任务行的 SUBCONTRACT 单 ship → 409；
   - 其它工厂的委外单 → 404。
6. 工序委外：行下达后 WorkProcessTask 的 `is_outsourced` 为 true；收货后 actualQuantity 累加；行完成后任务为 COMPLETED。
7. 界面截图（放在 `Documents/proto-migrate-evidence/outsource/`）：列表、详情（任务行页签和收发明细页签）、收货对话框、结单被拦的提示（阴性对照）、工单里的委外工序。

## 9. 不在本切片（v1）

1. 线上 WIP（没有入库的 `semi_finished_inventory`）外发。目前要求先入库成半成品批次再发料。
2. 委外价目表：单价写在行上。
3. 按月汇总的对账单：应付台账已按委外厂分账，可以直接作为对账依据。
4. 委外预付和发票匹配。
5. 移动端（RN）入口。
6. 委外厂在途数据：黑湖的 vendorOnWayAmount 在系统里没有来源。

## 10. 口径决定（按推荐项自定，逐条写进 PR）

| # | 问题 | 选择 | 原因 |
|---|---|---|---|
| D1 | 委外厂主体 | 复用 Supplier | 应付和余额已经按 Supplier 记，不再造一个主体 |
| D2 | 物权放在哪里 | 每个委外厂一个 OUTSOURCE 仓，首次下达时自动建 | 结存可以按委外厂查；与既有仓库类型一致 |
| D3 | 能发出哪些料 | 仓库里的物料批次（原料、包材、辅料、半成品） | 六膳门实际发出的都是有库存的料；线上 WIP 没有仓位 |
| D4 | 什么时候确认耗用 | 收货时，系统按比例建议，人可以改 | 实收是唯一可靠的事实；超发由结单时的对平兜住 |
| D5 | 产出形态 | 行上指定成品（FINISHED）或半成品（SEMI） | 整单委外回成品，工序委外回半成品，接着走下道工序 |
| D6 | 工序委外怎么接生产 | 同步工序任务状态，不走报工过账 | 见第 4 节 |
| D7 | 加工费口径 | 合格数 × 含税单价，收货即挂应付 | 黑湖金额按实收；与送货单挂账一致 |
| D8 | 差额怎么处理 | 结单时结存必须为 0，用退料或损耗对平，损耗必须写原因 | Steve 定「不许静默吞差额」 |
| D9 | 退货 | 退回返工，加工费按待审调整冲减，返工回货不再耗料 | 财务调整保持四眼审批；料不会被重复耗用 |
| D10 | 订单上的加工费 | 只作展示，不重复计入计划合计 | 半成品单价里已经含了加工费 |
| D11 | 菜单与权限 | 生产管理下放「委外单」，权限沿用生产模块 | organizer 定不新增菜单组 |
| D13 | OUTSOURCE 仓是否加入结单合法仓集合 | 不加入，委外仓批次状态写成已预留（RESERVED） | 加入会让生产结单重复扣委外厂手里的料；状态写成可用会被 8 处不带仓库过滤的可用量查询算进去 |
| D12 | 编号 | 委外单 `OS` + yyyyMMdd + 4 位序号；行号从 1 递增 | 与既有单号风格一致 |
