/**
 * 已入库批次 —— 按收货单(source_doc_id)分组 (2026-09-01)。
 *
 * ## owner 原话
 *
 * 「像我们 3251厂牛前 跟 941厂牛后, 它是一次一个单入库的, 那它应该是放在一个组里边 ——
 * 一个入库批次里面有三个批次号。要不然每一条平铺在一起比较混乱, 你都不知道上一批
 * 这整个收货一共有哪几个批次号入库。」
 *
 * ## 数据从哪来
 *
 * `material_batches.source_doc_id` 早就存在: 同一次采购收货写入的批次共享同一个
 * `source_doc_id`(= `purchase_receive_records.id`), 不同收货之间的值不同 ——
 * prod 实测过(见 PR 说明)。⛔ 这里不新增列、不回填、不迁移, 只是把已经存在的关系
 * 用起来。人能读的「收货单号」由后端 `MaterialBatchDTO.sourceDocNumber` 提供
 * (对应 `MaterialBatch#sourceDocNumber` 的 `@Formula` 只读关联查询), 本文件只做
 * 纯前端聚合, 不发任何新请求。
 *
 * ## 非采购收货来源 / 无来源单据 怎么办
 *
 * `sourceDocId` 为空(null/undefined/'') 的批次 —— 落入固定的
 * {@link NO_SOURCE_DOC_KEY} 桶, 显式标注「无关联单据」, ⛔ 不静默从列表消失,
 * ⛔ 也不和别的批次拼成一个假装"一起到货"的组。
 *
 * `sourceDocId` 非空但不是采购收货(`sourceDocNumber` 为 null, 例如调拨入库/
 * 期初库存/客供来料)—— 仍然按 `sourceDocId` 正常分组(它们确实共享同一张来源单据),
 * 只是表头退化成「单据类型中文名 + 单据 ID 前 8 位」而不是收货单号。
 *
 * ## 「本次总量」为什么按单位拆开
 *
 * 一次收货可能同时收多种物料、多种单位(kg/箱/个…)。⛔ 把不同单位的数量直接相加
 * 会得到一个没有意义的数字(这正是本次改动明确要求避免的第 6 个缺陷形状)。
 * 这里按 `quantityUnit`(退化到 `unit`)分桶求和, 拿不到有效数字的批次单独计数,
 * 不悄悄按 0 处理掉。
 */
import type { TableRow } from '@/types/api';

/** 落不到任何真实来源单据的批次的分组键 —— 固定值, 不与任何真实 UUID 冲突。 */
export const NO_SOURCE_DOC_KEY = '__NO_SOURCE_DOC__';

/** `sourceDocType` → 中文名。仅用于「有 sourceDocId 但不是采购收货」时的表头 fallback。 */
const SOURCE_DOC_TYPE_LABELS: Record<string, string> = {
  PURCHASE_RECEIVE: '采购收货',
  TRANSFER_IN: '调拨入库',
  OPENING: '期初库存',
  CUSTOMER_MATERIAL_RECEIPT: '客供来料',
  CUSTOMER_MATERIAL_ARRIVAL: '客供到货',
  LEGACY_IMPORT: '历史迁移',
  BYPRODUCT: '副产品',
  PRODUCTION_BATCH: '生产在制',
  MATERIAL_REQUISITION_RETURN: '领料退回',
  SALES_RETURN: '销售退回',
  MANUAL_ADJUST: '人工调整',
};

/** 一个单位下的小计。`unknownCount` > 0 时前端要把它显示出来, 不能悄悄吞掉。 */
export interface UnitSubtotal {
  unit: string;
  total: number;
  /** 该单位下有多少批次读不出有效数量(算不出来, 不是"0") —— 与本仓 A¹⁰ 判据同源。 */
  unknownCount: number;
}

export interface DocumentBatchGroup {
  /** 分组键: sourceDocId, 或 NO_SOURCE_DOC_KEY。 */
  key: string;
  /** true = 落在「无关联单据」桶。 */
  isUnlinked: boolean;
  /** 表头「收货单号」展示文本。 */
  docLabel: string;
  /** sourceDocType 的中文名(无来源单据时为 '') —— 供表头次要信息展示。 */
  docTypeLabel: string;
  /** 组内最早的入库日期(YYYY-MM-DD), 用作「收货时间」; 组内没有任何日期时为 null。 */
  receivedAt: string | null;
  batchCount: number;
  totalsByUnit: UnitSubtotal[];
  batches: TableRow[];
}

function pickReceiptDate(row: TableRow): string | null {
  const raw = row.receiptDate ?? row.inboundDate ?? row.createdAt ?? null;
  if (raw == null) return null;
  const s = String(raw);
  // createdAt 可能带时间戳, 只取日期部分用于排序/展示比较的稳定性; 展示时前端仍可用原值。
  return s.slice(0, 10);
}

/** 「本次总量」用的数量字段 —— 优先 receiptQuantity(本次入库量), 不是会随消耗变化的 currentQuantity。 */
function pickReceiptQuantity(row: TableRow): number | null {
  const raw = row.receiptQuantity ?? row.inboundQuantity ?? row.currentQuantity ?? row.quantity;
  if (raw == null || raw === '') return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

function pickUnit(row: TableRow): string {
  const raw = row.quantityUnit || row.unit;
  return raw == null || raw === '' ? '-' : String(raw);
}

function docTypeLabel(sourceDocType: unknown): string {
  const key = String(sourceDocType || '').toUpperCase();
  return SOURCE_DOC_TYPE_LABELS[key] || (key ? '其它来源' : '');
}

function resolveDocLabel(key: string, members: TableRow[]): { docLabel: string; docTypeLabel: string } {
  if (key === NO_SOURCE_DOC_KEY) {
    return { docLabel: '无关联单据', docTypeLabel: '' };
  }
  const withNumber = members.find((m) => m.sourceDocNumber);
  if (withNumber) {
    return { docLabel: String(withNumber.sourceDocNumber), docTypeLabel: '采购收货' };
  }
  const sample = members[0];
  const type = docTypeLabel(sample?.sourceDocType);
  const shortId = key.length > 8 ? `${key.slice(0, 8)}…` : key;
  return { docLabel: type ? `${type} · ${shortId}` : shortId, docTypeLabel: type };
}

function computeTotalsByUnit(members: TableRow[]): UnitSubtotal[] {
  const byUnit = new Map<string, UnitSubtotal>();
  for (const row of members) {
    const unit = pickUnit(row);
    const qty = pickReceiptQuantity(row);
    let bucket = byUnit.get(unit);
    if (!bucket) {
      bucket = { unit, total: 0, unknownCount: 0 };
      byUnit.set(unit, bucket);
    }
    if (qty == null) {
      bucket.unknownCount += 1;
    } else {
      bucket.total += qty;
    }
  }
  return Array.from(byUnit.values());
}

/**
 * 把「已入库批次」的原始行按 source_doc_id 分组。
 *
 * ⛔ 纯函数, 不发请求 —— 调用方负责先把全量(不分页)的批次行准备好
 * (与「按物料汇总」共用同一份 `summaryRows` 拉取逻辑, 见 list.vue)。
 */
export function groupBatchesBySourceDocument(rows: TableRow[]): DocumentBatchGroup[] {
  const order: string[] = [];
  const byKey = new Map<string, TableRow[]>();
  for (const row of rows) {
    const raw = row.sourceDocId;
    const key = raw == null || raw === '' ? NO_SOURCE_DOC_KEY : String(raw);
    let members = byKey.get(key);
    if (!members) {
      members = [];
      byKey.set(key, members);
      order.push(key);
    }
    members.push(row);
  }

  const groups: DocumentBatchGroup[] = order.map((key) => {
    const members = byKey.get(key) as TableRow[];
    const { docLabel, docTypeLabel: typeLabel } = resolveDocLabel(key, members);
    const dates = members.map(pickReceiptDate).filter((d): d is string => d != null).sort();
    return {
      key,
      isUnlinked: key === NO_SOURCE_DOC_KEY,
      docLabel,
      docTypeLabel: typeLabel,
      receivedAt: dates.length > 0 ? dates[0] : null,
      batchCount: members.length,
      totalsByUnit: computeTotalsByUnit(members),
      batches: members,
    };
  });

  // 「无关联单据」桶固定排最后, 其余按收货时间倒序(最近的收货排前面)。
  // receivedAt 缺失的组排在同类的最后, 而不是让 undefined 参与比较产生不稳定顺序。
  const linked = groups.filter((g) => !g.isUnlinked).sort((a, b) => {
    if (a.receivedAt === b.receivedAt) return 0;
    if (a.receivedAt == null) return 1;
    if (b.receivedAt == null) return -1;
    return a.receivedAt < b.receivedAt ? 1 : -1;
  });
  const unlinked = groups.filter((g) => g.isUnlinked);
  return [...linked, ...unlinked];
}
