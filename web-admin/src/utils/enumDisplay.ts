/**
 * 枚举码 → 中文展示。
 *
 * 客户 2026-07-30 (Sheet Row 6): 单据追踪抽屉里出现「未知状态（IN_PROGRESS）」。
 * 根因不是漏了一个码, 而是 `enumLabel` 的兜底会把**任何**没登记的码原样吐给用户,
 * 而 COMMON_ENUM_LABELS 当时只有 18 条。
 *
 * 📐 分层规则 (加新码前先想清楚放哪一层):
 *
 * 1. `COMMON_ENUM_LABELS` —— 只放**在所有单据里含义都一致**的码 (草稿 / 已取消 / 现金…)。
 * 2. `<DOMAIN>_STATUS_LABELS` —— 同一个码在不同单据里含义不同的, 必须分域放。
 *    实例: `FINANCE_APPROVED` 销售单是「财务已批准」采购单是「财务已审核」;
 *          `REJECTED` 采购收货是「已退回」别处是「已驳回」。
 *    塞进全局表就必然有一半场景显示错的中文 —— 那比显示英文码更糟 (用户不会怀疑它)。
 * 3. `ROLE_LABELS` —— 角色码 (小写下划线), 与状态码 (大写下划线) 不可能撞, 所以能同链兜底。
 *
 * 🔴 ROLE_LABELS 是**角色中文的单一来源**。此前仓库里有 4 份互相矛盾的角色表
 * (`warehouse_worker` 被写成「仓库工人」/「仓库员」/「仓管员」三种)。本表采用
 * `approval-workflow-editor/lib/approvalDirectory.ts` 的措辞并由它反向引用, 所以那边
 * 渲染不变。剩下 `hr/employees/list.vue` / `system/employees/BadgeGenerator.vue` /
 * `error/mobile-only.vue` 仍各持一份, 收敛它们会改动客户已习惯的文案, 留待单独确认。
 *
 * 取值来源均为**实测**而非推断: Java 枚举的 displayName (措辞直接沿用), String 型状态
 * 则取自后端 setter 全量 grep + prod 实际分布。`EnumDisplayCoverage` 测试对着 Java 源
 * 文件断言覆盖, 后端加枚举值忘了补中文会红。
 */

/** 在任何单据里含义都一致的码。含义随单据变化的**不要**放这里, 放分域表。 */
export const COMMON_ENUM_LABELS: Record<string, string> = {
  PENDING: '待处理',
  FINANCE_APPROVED: '财务已审核',
  DRAFT: '草稿',
  COMPLETED: '已完成',
  CONFIRMED: '已确认',
  CANCELLED: '已取消',
  BANK_TRANSFER: '银行转账',
  CASH: '现金',
  WECHAT: '微信',
  ALIPAY: '支付宝',
  CHECK: '支票',
  CREDIT: '赊账',
  POS: 'POS',
  OTHER: '其他',
  PURCHASE_ORDER: '采购订单',
  SALES_ORDER: '销售订单',
  // ↓ 2026-07-30 补: 以下码在各单据含义一致, 之前全部会渲染成「未知状态（…）」
  CLOSED: '已关闭',
  SUBMITTED: '已提交',
  APPROVED: '已审批',
  REJECTED: '已驳回',
  PENDING_FINANCE_REVIEW: '待财务审核',
  FINANCE_REJECTED: '财务已驳回',
  WORKFLOW_RUNNING: '审批中',
  IN_PROGRESS: '进行中',
  PROCESSING: '处理中',
  PAUSED: '已暂停',
  PLANNED: '计划中',
  PLANNING: '计划中',
};

/** 角色码 → 中文。**角色中文的单一来源**, 见文件头注释。 */
export const ROLE_LABELS: Record<string, string> = {
  platform_admin: '平台管理员',
  factory_super_admin: '工厂总管理员',
  factory_admin: '工厂管理员',
  permission_admin: '权限管理员',
  department_admin: '部门管理员',
  procurement_manager: '采购主管',
  sales_manager: '销售主管',
  operations_coordinator: '运营协调员',
  production_manager: '生产经理',
  production_clerk: '生产文员',
  dispatcher: '生产调度员',
  workshop_supervisor: '车间主管',
  quality_manager: '质量主管',
  quality_inspector: '质检员',
  quality_controller: '质量管控员',
  warehouse_manager: '仓储主管',
  warehouse_worker: '仓管员',
  finance_manager: '财务主管',
  cashier: '出纳',
  hr_admin: '人事管理员',
  equipment_admin: '设备管理员',
  // 🔴 2026-09-01 owner 裁定改名: 「出成率录入员」→「报工操作员」。
  //
  // 本表其余措辞刻意与 Java 枚举的 displayName 不同(采用 approvalDirectory 那一套,
  // 见文件头) —— `factory_super_admin` 这边是「工厂总管理员」而枚举是「工厂总监」,
  // `warehouse_worker` 是「仓管员」而枚举是「仓库员」。那些是**措辞**差异, 保留。
  //
  // 这一条不是措辞差异, 是**名字指错了事**: 枚举职责写着「报工写入: 产量/工时/工序报告」,
  // 与「出成率」无关; LIUSHANMEN 挂这个角色的 9 个账号也叫 baogong00X(报工)。
  // 缺陷 2 修完之后这些账号第一次在列表里显示中文, 显示一个指错事的中文比显示英文码
  // 更糟 —— 用户不会怀疑它。⇒ 与枚举对齐。
  yield_operator: '报工操作员',
  restaurant_manager: '餐厅经理',
  restaurant_owner: '餐厅老板',
  restaurant_chef: '餐厅厨师',
  restaurant_purchaser: '餐厅采购',
  team_leader: '班组长',
  group_leader: '组长',
  operator: '操作员',
  viewer: '只读人员',
  unactivated: '未激活',
};

// ─── 分域状态表 (措辞沿用后端枚举的 displayName) ────────────────────────────

/** `SalesOrderStatus` */
export const SALES_ORDER_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  PENDING_FINANCE_REVIEW: '待财务审核',
  FINANCE_APPROVED: '财务已批准',
  FINANCE_REJECTED: '财务已驳回',
  PROCESSING: '处理中',
  PARTIAL_DELIVERED: '部分发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

/** `PurchaseOrderStatus` */
export const PURCHASE_ORDER_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  WORKFLOW_RUNNING: '审批中',
  APPROVED: '已审批',
  PENDING_FINANCE_REVIEW: '待财务审核',
  FINANCE_APPROVED: '财务已审核',
  FINANCE_REJECTED: '财务驳回',
  PARTIAL_RECEIVED: '部分到货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  CLOSED: '已关闭',
};

/** `PurchaseReceiveStatus` —— 注意 REJECTED 在收货语境是「已退回」不是「已驳回」。 */
export const PURCHASE_RECEIVE_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_QC: '待质检',
  CONFIRMED: '已确认',
  REJECTED: '已退回',
  // M-15 (2026-08-20): 后端为「分批收货」加了作废草稿的能力, 新增了这个枚举值。
  // ⚠️ 措辞取自 Java 枚举自己的注释 —— 它明确写着与 REJECTED 是两件事:
  //   CANCELLED = 仓储主动作废、**从未生成入库批次**;
  //   REJECTED  = 质检不合格**退回供应商**。
  // ⛔ 不要为了看起来一致而把两者写成同一个词。
  CANCELLED: '已作废',
};

/** `FactoryMaterialRequisition.Status` (措辞取自该枚举的行内注释) */
export const MATERIAL_REQUISITION_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '已生成待备料',
  PICKING: '备料中',
  TRANSFERRED: '已调拨',
  ISSUED: '已签收',
  IN_USE: '生产中',
  CLOSED: '已退料关单',
  CANCELLED: '已取消',
};

/** `ProductionBatchStatus` —— IN_PROGRESS 在生产批次语境是「生产中」(客户撞到的就是这个)。 */
export const PRODUCTION_BATCH_STATUS_LABELS: Record<string, string> = {
  PLANNED: '计划中',
  PLANNING: '计划中',
  IN_PROGRESS: '生产中',
  PRODUCING: '生产中',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

/** `SalesDeliveryStatus` (母子发运单 11 态) */
export const SALES_DELIVERY_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_WAREHOUSE_CONFIRM: '待仓库确认',
  PICKED: '已拣货',
  PENDING_SPLIT: '待分批',
  PARTIALLY_SCHEDULED: '部分已安排',
  FULLY_SCHEDULED: '已全部安排',
  PARTIALLY_SHIPPED: '部分已发货',
  SHIPPED: '已发货',
  DELIVERED: '已签收',
  CANCELLED: '已取消',
  RETURNED: '已退回',
};

/**
 * `ProductionSettlement.postingStatus` —— String 列不是枚举, 取值来自后端
 * `setPostingStatus("…")` 全量 grep + 实体默认值。
 */
export const PRODUCTION_SETTLEMENT_POSTING_STATUS_LABELS: Record<string, string> = {
  PENDING_POSTING: '待过账',
  PENDING_CLEARING: '待清账',
  PENDING_WAREHOUSE_RECEIPT: '待入库确认',
  POSTED: '已过账',
  POSTED_WITH_TOLERANCE: '已过账（含容差）',
};

/** `FinishedGoodsBatch.status` —— 同为 String 列, 取值来自后端 setter grep。 */
export const FINISHED_GOODS_BATCH_STATUS_LABELS: Record<string, string> = {
  AVAILABLE: '可用',
  DEFECTIVE: '不良品',
  DEPLETED: '已耗尽',
};

/**
 * `MaterialBatchStatus` (原材料批次 12 态)。
 *
 * 2026-08-20: 客户在「入库任务与批次」列表里看到状态显示成 `INSPECTING` ——
 * 那个页面自带一份只有 4 个码 (AVAILABLE/RESERVED/DEPLETED/EXPIRED) 的私有表,
 * 剩下 8 个码原样吐英文。中文取后端枚举的 displayName, 一字不改。
 */
export const MATERIAL_BATCH_STATUS_LABELS: Record<string, string> = {
  IN_STOCK: '库存中',
  AVAILABLE: '可用',
  FRESH: '鲜品',
  FROZEN: '冻品',
  DEPLETED: '已耗尽',
  USED_UP: '已用完',
  EXPIRED: '已过期',
  INSPECTING: '质检中',
  SCRAPPED: '已报废',
  RESERVED: '已预留',
  PRODUCING_RESERVED: '生产预留',
  DEFECTIVE: '不良品',
};

/** `ProductionPlanStatus` —— 注意 PREPARED 在生产计划语境是「草稿」不是「已准备」。 */
export const PRODUCTION_PLAN_STATUS_LABELS: Record<string, string> = {
  PLANNED: '已计划',
  PREPARED: '草稿',
  PENDING: '待处理',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  PAUSED: '暂停',
  PENDING_APPROVAL: '待审批',
};

/** `TransferStatus` —— 调拨语境下 REJECTED 是「已驳回」(申请被拒), RECEIVED 是「已签收」。 */
export const INTERNAL_TRANSFER_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  REQUESTED: '已申请',
  APPROVED: '已审批',
  REJECTED: '已驳回',
  SHIPPED: '已发货',
  RECEIVED: '已签收',
  CONFIRMED: '已确认',
  CANCELLED: '已取消',
  REVERSED: '已冲销',
};

/**
 * `TransferDiffRecord.status` —— String 列不是枚举, 取值来自后端
 * `TransferDiffServiceImpl.setStatus("…")` 全量 grep + 实体默认值。
 */
export const TRANSFER_DIFF_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处理',
  RESOLVED: '已处理',
};

/**
 * `PurchaseInvoice.reconcileStatus` —— String 列, 取值来自实体注释 + 后端 setter grep。
 * 注意这是**对账**状态不是审批状态, PENDING 是「待对账」不是「待处理」。
 */
export const PURCHASE_INVOICE_RECONCILE_STATUS_LABELS: Record<string, string> = {
  PENDING: '待对账',
  MATCHED: '对账一致',
  MISMATCHED: '对账不符',
};

/** `PaymentRequestStatus` —— PENDING 在付款申请语境是「待财务初审」。 */
export const PAYMENT_REQUEST_STATUS_LABELS: Record<string, string> = {
  PENDING: '待财务初审',
  FINANCE_REVIEW: '财务审核中',
  APPROVED: '已批准（待付款）',
  PAID: '已付款',
  REJECTED: '已拒绝',
  CANCELLED: '已撤回',
};

/**
 * `ApprovalWorkflowInstance.InstanceStatus` (嵌套 enum, 不是独立文件)。
 *
 * owner 2026-08-20: 采购订单详情页「OA 状态」显示成「未知状态（RUNNING）」——
 * 这个码此前一张表都没登记过。措辞取自该枚举自己的 javadoc 状态机说明:
 *   RUNNING = 至少 1 个节点 active 等待人工/超时; CANCELLED = 业务发起方主动 cancel;
 *   TIMEOUT = 自动 escalate 多次失败后的终态。
 *
 * ⛔ 不要把 RUNNING 塞进 COMMON_ENUM_LABELS —— 它在审批语境是「审批中」, 在生产/任务
 * 语境会是「运行中」, 压进全局表就必然有一半场景显示错的中文。
 */
export const APPROVAL_INSTANCE_STATUS_LABELS: Record<string, string> = {
  RUNNING: '审批中',
  APPROVED: '审批通过',
  REJECTED: '已驳回',
  CANCELLED: '已撤销',
  TIMEOUT: '审批超时',
};

/**
 * `PurchaseInvoiceStatus` —— 挂在 `PurchaseOrder.invoiceStatus` 上的**收票**进度。
 *
 * ⚠️ 与上面的 `PURCHASE_INVOICE_RECONCILE_STATUS_LABELS` 是两件事, 别混:
 *   本表   = 采购订单收没收到票 (purchase_orders.purchase_invoice_status);
 *   那一张 = 某张发票对没对上账 (purchase_invoices.reconcile_status)。
 * owner 2026-08-20 撞到的「未知状态（NOT_RECEIVED）」是本表这一路。
 */
export const PURCHASE_ORDER_INVOICE_STATUS_LABELS: Record<string, string> = {
  NOT_RECEIVED: '未收票',
  RECEIVED: '已收票',
  RECONCILED: '已对账',
};

/**
 * `SettlementType` —— 措辞逐字取自该枚举的 displayName。
 *
 * 2026-08-20 收编: 这张表原来以行内字面量的形式写死在采购订单详情页的 template 里,
 * 属于「同一个东西有两份」—— 后端加第 7 个结算方式时那份不会跟着变, 且没有任何闸
 * 会红。收进本文件后由 EnumDisplayCoverage 对着 Java 源文件钉住。
 */
export const SETTLEMENT_TYPE_LABELS: Record<string, string> = {
  PREPAID: '预付',
  CREDIT_FIRST: '赊销先入库',
  NO_INVOICE: '未到票',
  MONTHLY: '月结',
  CREDIT_PERIOD: '账期',
  IMMEDIATE: '现结',
};

/** `ReturnOrderStatus` —— FINANCE_APPROVED 在退货语境是「财务已审」。 */
export const RETURN_ORDER_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  APPROVED: '已审批',
  FINANCE_APPROVED: '财务已审',
  REJECTED: '已驳回',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
};

/**
 * 单据追踪抽屉: documentType → 该单据的状态表。
 *
 * 抽屉本来就知道自己在渲染哪种单据, 所以能精确选表, 不必忍受全局表的同码歧义。
 * key 与后端 `ProductionDocumentTraceService.document(type, …)` 与
 * `BusinessDocumentTraceService.document(type, …)` 传入的字面量一一对应。
 */
export const TRACE_STATUS_LABELS_BY_DOCUMENT_TYPE: Record<string, Record<string, string>> = {
  SALES_ORDER: SALES_ORDER_STATUS_LABELS,
  PURCHASE_ORDER: PURCHASE_ORDER_STATUS_LABELS,
  PURCHASE_RECEIPT: PURCHASE_RECEIVE_STATUS_LABELS,
  MATERIAL_REQUISITION: MATERIAL_REQUISITION_STATUS_LABELS,
  PRODUCTION_PLAN: PRODUCTION_PLAN_STATUS_LABELS,
  PRODUCTION_BATCH: PRODUCTION_BATCH_STATUS_LABELS,
  PRODUCTION_SETTLEMENT: PRODUCTION_SETTLEMENT_POSTING_STATUS_LABELS,
  FINISHED_GOODS_BATCH: FINISHED_GOODS_BATCH_STATUS_LABELS,
  SALES_DELIVERY: SALES_DELIVERY_STATUS_LABELS,
  SALES_RETURN: RETURN_ORDER_STATUS_LABELS,
  PURCHASE_RETURN: RETURN_ORDER_STATUS_LABELS,
  PURCHASE_INVOICE: PURCHASE_INVOICE_RECONCILE_STATUS_LABELS,
  PAYMENT_REQUEST: PAYMENT_REQUEST_STATUS_LABELS,
  INTERNAL_TRANSFER: INTERNAL_TRANSFER_STATUS_LABELS,
  TRANSFER_DIFF: TRANSFER_DIFF_STATUS_LABELS,
};

/** 按单据类型取状态中文; 未知单据类型退回全局表 (不臆造)。 */
export function traceStatusLabel(
  documentType: unknown,
  status: unknown,
  empty = '—',
): string {
  const type = String(documentType ?? '').trim();
  return enumLabel(status, TRACE_STATUS_LABELS_BY_DOCUMENT_TYPE[type] ?? {}, empty);
}

export function enumLabel(
  code: unknown,
  localLabels: Record<string, string> = {},
  empty = '—',
): string {
  const normalized = String(code ?? '').trim();
  if (!normalized) return empty;
  return localLabels[normalized]
    || COMMON_ENUM_LABELS[normalized]
    || ROLE_LABELS[normalized]
    || `未知状态（${normalized}）`;
}
