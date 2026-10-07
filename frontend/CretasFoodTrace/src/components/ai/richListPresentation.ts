/** Keep stock quantities visible even when DTO metadata comes first. */
export function getListDisplayKeys(row: Record<string, unknown>): string[] {
  if ('batchNumber' in row && 'materialName' in row) {
    const quantityKey = 'currentQuantity' in row ? 'currentQuantity' : 'quantity';
    const unitKey = typeof row.quantityUnit === 'string' && row.quantityUnit.trim() ? 'quantityUnit' : 'unit';
    const statusKey = row.statusDisplayName != null ? 'statusDisplayName' : 'status';
    return ['batchNumber', 'materialName', quantityKey, unitKey, statusKey];
  }
  return Object.keys(row)
    .filter(key => !key.endsWith('Id') && key !== 'id' && key !== 'factoryId' &&
      typeof row[key] !== 'object')
    .slice(0, 5);
}

const MATERIAL_STATUS_NAMES: Record<string, string> = {
  IN_STOCK: '库存中', AVAILABLE: '可用', FRESH: '鲜品', FROZEN: '冻品',
  DEPLETED: '已耗尽', USED_UP: '已用完', EXPIRED: '已过期', INSPECTING: '质检中',
  SCRAPPED: '已报废', RESERVED: '已预留', PRODUCING_RESERVED: '生产预留', DEFECTIVE: '不良品',
};

/**
 * 结果卡「状态」栏认得的状态码 → 中文。批次状态与 MaterialBatchStatus 一致; 其余只收各领域含义不会打架的码
 * (⛔ PENDING 不收: 工序任务里是「待开始」、质检里是「待检验」, 猜一个就是错一个 —— 认不出的码卡片不显示这一行)。
 */
const STATUS_NAMES: Record<string, string> = {
  ...MATERIAL_STATUS_NAMES,
  IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消', SKIPPED: '已跳过',
  INSPECTED: '已检验', PASS: '合格', FAIL: '不合格', CONDITIONAL: '有条件放行',
};

export function getListCellValue(row: Record<string, unknown>, key: string): unknown {
  const value = row[key];
  if (key === 'status' && 'batchNumber' in row && 'materialName' in row && typeof value === 'string') {
    return MATERIAL_STATUS_NAMES[value.toUpperCase()] ?? value;
  }
  return value;
}

// ==================== 结果卡 (RichContentRenderer 的 DETAIL / LIST / STATS) ====================

/**
 * 键 → 中文标签。结果卡只显示这张表里有的键 (见 buildDetailRows)。
 * ⛔ 不靠猜去翻译没见过的键: 键名取自写工具真实返回 (MaterialBatchUseTool / QualityResultSubmitTool /
 * QualityBatchMarkInspectedTool / QualityCheckCreateTool / QualityDefectRecordTool / WorkProcessTaskTransitionPreview)。
 */
export const COLUMN_NAMES: Record<string, string> = {
  orderNumber: '订单编号', planNumber: '计划编号', batchNumber: '批次号',
  transferNumber: '调拨编号', returnNumber: '退货编号',
  status: '状态', productName: '产品', productTypeName: '产品类型',
  quantity: '数量', plannedQuantity: '计划数量', actualQuantity: '实际数量',
  totalAmount: '总金额', unitPrice: '单价', price: '价格',
  supplierId: '供应商', customerName: '客户', supplierName: '供应商',
  createdAt: '创建时间', updatedAt: '更新时间', plannedDate: '计划日期',
  message: '消息', remark: '备注', notes: '备注',
  totalOrders: '总订单', totalElements: '总记录数',
  currentPage: '当前页', hasMore: '有更多',
  statusDisplayName: '状态',
  materialName: '物料名称', materialCode: '物料编码',
  materialCategory: '物料类别', materialMainCategory: '物料大类',
  currentQuantity: '账面数量', unit: '单位', quantityUnit: '单位',
  // 写操作结果卡 (2026-10-07): 领用 / 质检 / 工序
  remainingQuantity: '剩余数量', consumedQuantity: '已领用数量',
  checkType: '质检类型', inspectionDate: '质检日期', result: '质检结果',
  passRate: '合格率(%)', qualityGrade: '质量等级', markedAt: '标记时间',
  pendingInspections: '待检验项目数', defectTypeDisplay: '不良类型',
  processName: '工序', assignedToName: '负责人',
  currentStatusLabel: '当前状态', newStatusLabel: '变更后状态',
};

/** 带数量含义的键: 单位并进数值里 (「0.90 kg」), 不再单列一行「单位」。 */
const QUANTITY_KEYS = new Set([
  'quantity', 'plannedQuantity', 'actualQuantity', 'currentQuantity',
  'remainingQuantity', 'consumedQuantity',
]);
const UNIT_KEYS = ['unit', 'quantityUnit', 'plannedUnit'];

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 没有中文标签时的兜底 (列表表头 / 统计卡仍在用; 详情卡不走这里, 见 buildDetailRows)。 */
export function formatColumnName(key: string): string {
  return COLUMN_NAMES[key] || key
    .replace(/([A-Z])/g, ' $1')
    .replace(/^./, s => s.toUpperCase())
    .trim();
}

export function formatDisplayValue(value: unknown): string {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'number') {
    if (Number.isInteger(value)) return value.toLocaleString();
    return value.toFixed(2);
  }
  const str = String(value);
  // Truncate long values
  return str.length > 50 ? str.substring(0, 47) + '...' : str;
}

export interface DetailRow {
  key: string;
  label: string;
  value: string;
}

function isInternalId(key: string, value: unknown): boolean {
  return key === 'id' || /Id$/.test(key) || (typeof value === 'string' && UUID_RE.test(value));
}

/** 值是状态码 / 结果码的键: 必须翻成中文才显示。 */
function isCodeKey(key: string): boolean {
  return key === 'status' || key === 'result';
}

/**
 * 详情结果卡的行 (给一线看的):
 *  - 内部编号 (xxxId / id / UUID 值) 不显示;
 *  - 只显示有中文标签的键, 没有标签的键不显示 (⛔ 不把 Remaining Quantity 这种英文标签露出来);
 *  - 状态码翻成中文, 翻不出来这一行不显示 (不回退成英文码); 行里有 statusDisplayName 时不再重复显示 status;
 *  - 数量后面带上单位, 单位那一行并进去。
 */
export function buildDetailRows(detail: Record<string, unknown>): DetailRow[] {
  const entries = Object.entries(detail).filter(([key, value]) =>
    value !== null && value !== undefined && typeof value !== 'object'
    && key !== 'factoryId' && !isInternalId(key, value) && COLUMN_NAMES[key] !== undefined);

  const hasDisplayName = detail.statusDisplayName != null && String(detail.statusDisplayName).trim() !== '';
  const unitKey = UNIT_KEYS.find(k => typeof detail[k] === 'string' && String(detail[k]).trim() !== '');
  const unitText = unitKey ? String(detail[unitKey]).trim() : undefined;
  const hasQuantityRow = entries.some(([key]) => QUANTITY_KEYS.has(key));

  const rows: DetailRow[] = [];
  for (const [key, value] of entries) {
    if (hasQuantityRow && UNIT_KEYS.includes(key)) continue;
    if (key === 'status' && hasDisplayName) continue;
    let text: string;
    if (isCodeKey(key) && typeof value === 'string') {
      const zh = STATUS_NAMES[value.trim().toUpperCase()];
      if (zh !== undefined) {
        text = zh;
      } else if (/[一-鿿]/.test(value)) {
        text = value; // 本来就是中文: 原样给
      } else {
        continue; // 英文码翻不出来: 不显示, 不回退成英文
      }
    } else if (QUANTITY_KEYS.has(key) && unitText) {
      text = `${formatDisplayValue(value)} ${unitText}`;
    } else {
      text = formatDisplayValue(value);
    }
    // entries 已按「有中文标签」筛过, ?? key 只为满足类型, 不会走到
    rows.push({ key, label: COLUMN_NAMES[key] ?? key, value: text });
    if (rows.length >= 12) break;
  }
  return rows;
}
