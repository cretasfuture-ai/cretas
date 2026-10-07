/**
 * 写操作确认卡的「预览行」—— web-admin 侧, 与 RN 的
 * `frontend/CretasFoodTrace/src/components/ai/aiWriteConfirm.ts` 的 `previewRows` 同一套规则
 * (AI 对话标准 H3 + V3):
 *
 *   - 能显示的键 = 有中文 label 的英文键, 或键本身含中文 (`isShowableKey`);
 *     ⛔ 不能只认 KEY_LABELS: 那样中文键全被藏掉, 确认卡变成空卡, 用户在看不到改动的情况下点确认。
 *   - 没有 label 的英文键整行不显示, ⛔ 不回落成驼峰拆分 / 原始键。
 *   - 有 newValues → 「当前 → 修改后」对比行; 否则 previewData (剥掉 success/data 外壳) 的键值行。
 *   - 对象值不用 JSON.stringify: 按「键：值」逐项显示, 键同样走上面的规则。
 *
 * 形态 D: 这里的 HIDDEN_KEYS / KEY_LABELS 是 RN 那份的拷贝, 会漂 ——
 * `__tests__/AiOperationCard.rows.spec.ts` 读 RN 源文件逐键对比, 并直接拿 RN 的 previewRows 当数行数的真值。
 */

export interface PreviewRow {
  key: string;
  label: string;
  value: string;
  /** diff 行: 修改前的值 */
  before?: string;
  changed?: boolean;
}

export interface ConfirmablePreviewSource {
  previewData?: unknown;
  currentValues?: unknown;
  newValues?: unknown;
}

/** 预览数据里不给用户看的系统键 */
export const HIDDEN_KEYS: ReadonlySet<string> = new Set([
  'factoryId', 'factory_id', 'userId', 'user_id', 'userRole', 'role', 'intentCode', 'userInput',
  'status', 'canDo', 'message', 'success', 'confirmed', 'permissions', 'scopes', 'principal',
  'tenantId', 'tenant_id',
]);

/** 已确认存在的业务键 → 中文标签 (与 RN 的 KEY_LABELS 逐键相同)。 */
export const KEY_LABELS: Readonly<Record<string, string>> = {
  orderNumber: '单号',
  supplierName: '供应商',
  materialName: '物料',
  productName: '产品',
  specification: '规格',
  unit: '单位',
  ordered: '订购数量',
  alreadyReceived: '已收',
  pending: '未收',
  overReceiveLimit: '超收上限',
  remainingCap: '还可入',
  yourProposedQty: '本次数量',
  quantity: '数量',
  actualQuantity: '实际数量',
  batchNumber: '批次号',
  notes: '备注',
  reason: '原因',
  warningIfOver: '提醒',
  defectType: '缺陷类型',
  defectQuantity: '缺陷数量',
  statusDisplayName: '状态',
};

/** 算不出 / 没填的值, 用文字写状态, 不显示 `—` / 空 / `null` */
export const EMPTY_VALUE_TEXT = '暂无';

const HAS_CJK = /[一-鿿]/;

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

export function isShowableKey(key: string): boolean {
  return Object.prototype.hasOwnProperty.call(KEY_LABELS, key) || HAS_CJK.test(key);
}

export function labelOf(key: string): string {
  return Object.prototype.hasOwnProperty.call(KEY_LABELS, key) ? KEY_LABELS[key] : key;
}

/** 值 → 文字。对象按「键：值」逐项 (键走 isShowableKey / label), 数组逐项用「、」连接。 */
export function formatPreviewValue(value: unknown, depth = 0): string {
  if (value === null || value === undefined || value === '') return EMPTY_VALUE_TEXT;
  if (Array.isArray(value)) {
    const items = value.map((v) => formatPreviewValue(v, depth + 1)).filter((s) => s !== EMPTY_VALUE_TEXT);
    return items.length ? items.join('、') : EMPTY_VALUE_TEXT;
  }
  if (typeof value === 'object') {
    if (depth >= 3) return EMPTY_VALUE_TEXT;
    const parts = Object.entries(value as Record<string, unknown>)
      .filter(([k]) => !HIDDEN_KEYS.has(k) && isShowableKey(k))
      .map(([k, v]) => `${labelOf(k)}：${formatPreviewValue(v, depth + 1)}`);
    return parts.length ? parts.join('；') : EMPTY_VALUE_TEXT;
  }
  return String(value);
}

export function previewRows(action: ConfirmablePreviewSource | null | undefined): PreviewRow[] {
  if (!action) return [];
  const next = asRecord(action.newValues);
  if (next && Object.keys(next).length > 0) {
    const cur = asRecord(action.currentValues) ?? {};
    const keys = Array.from(new Set([...Object.keys(cur), ...Object.keys(next)]));
    return keys
      .filter((k) => !HIDDEN_KEYS.has(k) && isShowableKey(k))
      .map((k) => {
        const before = formatPreviewValue(cur[k]);
        const value = formatPreviewValue(next[k]);
        return { key: k, label: labelOf(k), value, before, changed: before !== value };
      });
  }
  const root = asRecord(action.previewData);
  if (!root) return [];
  const data = asRecord(root.data) ?? root;
  return Object.entries(data)
    .filter(([k]) => !HIDDEN_KEYS.has(k) && k !== 'data' && isShowableKey(k))
    .map(([k, v]) => ({ key: k, label: labelOf(k), value: formatPreviewValue(v) }));
}

/** 本次预览是不是「改前 / 改后」对比 (有非空 newValues) */
export function isDiffPreview(action: ConfirmablePreviewSource | null | undefined): boolean {
  const next = asRecord(action?.newValues);
  return !!next && Object.keys(next).length > 0;
}
