# 锅次模型（S3-16）设计说明

2026-10-28，Steve 拍板：开发锅次模型，不豁免。验收场景见 `验收场景表.csv` 中的 S3-16 行：锅次时间和人工工时分开记；有更正就保留更正前后的值；关联不明、摄像头给出的候选时间不能自动当成精确时间；锅批对应关系明确时能算时长，不明确时标「待确认」。

## 一锅是什么

在卤、煮、熟制、腌制这类按锅做的工序里，一锅就是一个生产批次里的一次下锅到出锅。逐工序报工时，每道工序都会生成一个生产批次（`production_batches`）。一锅不是独立的批次，它只是对这个生产批次已有投入和产出的进一步细分。

## 字段（`production_pots`，V20261103_119）

| 字段 | 含义 |
|---|---|
| `production_batch_id`（必填）、`production_plan_id`、`process_sheet_row_id`、`process_code/name` | 属于哪个生产批次、哪道工序、哪一行报工 |
| `pot_number` | 锅号。同一批次、同一工序内唯一；作废的锅会让出它的锅号 |
| `equipment_label` | 用的是哪口锅或哪台设备（自由文本） |
| `started_at` / `ended_at` | 下锅、出锅时间，都可以为空 |
| `time_source` | `MANUAL`（人工录入）、`DEVICE`（设备）、`CAMERA_CANDIDATE`（摄像候选）、`UNKNOWN`（未知） |
| `time_confirmed` | 人工录入或设备来源、且两个时间都填了，才算已确认。摄像候选永远是未确认（有数据库 CHECK 约束钉住），要主管确认后才变成人工确认的时间 |
| `binding_status` | `CONFIRMED`（锅批对应明确）或 `PENDING_CONFIRMATION`（关联不明，待确认） |
| `operator_id/name` | 下锅的操作员 |
| `output_quantity/unit` | 出锅量 |
| `process_params`（jsonb） | 关键工艺参数，比如 `{"temperatureC":98,"durationMin":45}`，工序有就记 |
| `status` / `void_reason` | 状态为 `RECORDED` 或 `VOIDED`。锅从不物理删除，作废时必须填原因 |

表里**没有人工工时字段**。人工工时继续记在报工的 `laborSegments` 里，和锅次时间是两件独立的事。锅次时长只有在时间已确认时才计算，否则返回 null。

## 和其他数据怎么关联（`production_pot_inputs` / `_outputs`，V20261103_120）

- **投入**：用 `(source_type, source_id, quantity)` 表示这一锅从生产批次的某个投入节点（原料批或在制批）里用了多少。
- **产出**：用 `(target_type, target_id, quantity)` 表示这一锅出到了生产批次的哪个产出节点（成品批或在制批）。
- 这两类记录都只是把批次级的投入产出分摊到各锅，**不会再扣一次库存或入一次库**。服务端会校验三件事：节点必须真的是这个生产批次的投入或产出；同一节点上各锅的量加起来不能超过批次量；单位要一致。
- 批次的节点从哪里读：一是 `batch_lineage_edges`，二是同一事务里已经写入的权威记录（投入读 `material_consumptions`；在制产出读 `material_batches.source_doc_type=PRODUCTION_BATCH`，产出量用生产批次的实际产量）。原因是血缘边要在业务事务提交之后才写（`LineageEdgeRecorder` 的 afterCommit），和报工同一事务记录锅次时还读不到它。
- 更正（V20261103_121 `production_pot_corrections`）：每改一次、每确认一次、每作废一次都追加一行，记下字段、原值、新值、原因、操作人和时间，只增不改。

## 召回和追溯怎么用到锅次

召回漏掉一个成品就是事故，所以只在**能证明**的时候才缩小到具体某几锅：

1. 某个原料批在这个生产批次里的用量**已经全部分到各锅上**（各锅之和 ≥ 批次级用量），这时受影响的锅就是分到了这个原料批的那几锅。没有分完，或者批次用量未知时，**全部锅都算受影响**，并给出原因。
2. 产出也一样：某个产出节点的量已经全部分到各锅上，而且其中没有一锅是受影响的锅，这个产出节点才能排除。
3. 生产批次没有锅次记录时，按整批召回。
4. 待确认的锅（时间未确认或关联不明）照样算进召回范围，并标上 pending。

`/traceability/impact` 返回的批次级三组列表**一行不减**，只是多返回一个 `potScopes`。专用接口：`/production-pots/trace/consumers/{type}/{id}` 查一个原料批进了哪几锅、出到了哪里、下游有哪些成品；`/trace/producers/{type}/{id}` 查一个产出来自哪几锅；`/{potId}/trace` 查这一锅出到了哪里、最终变成了哪些成品。

## 入口

- 逐工序报工时，`ProcessSheetRowRequest.pots` 跟着这一行一起提交（只写入，不进 `row_payload`），由 `CcpReportFacade` 在报工完成后**在同一事务里**记录。只要有一锅不合法，整笔报工回滚；草稿里带锅次直接拒绝（不悄悄丢掉）；多产出的行暂时不支持随报工一起录锅次。
- web-admin：「生产批次详情 → 锅次」可以录入、更正、确认、作废，也能看分摊进度；「溯源查询 → 按锅追溯」可以查。
