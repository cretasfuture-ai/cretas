import { describe, expect, it } from 'vitest';
import { groupBatchesBySourceDocument, NO_SOURCE_DOC_KEY } from './groupBySourceDocument';
import type { TableRow } from '@/types/api';

/**
 * owner 场景复现: 3251厂牛前 / 941厂牛后 三个批次号, 同一次收货 RCV-20260901-6529。
 * 第二次收货 RCV-20260901-6530(不同 source_doc_id)必须落到独立的组。
 */
const RECEIPT_1_ID = '87e02e01-3bec-47c1-b371-e2bb39e60b45';
const RECEIPT_2_ID = '5c7c71b0-4cc6-4267-8c03-e221c7f12c45';

function batch(overrides: Partial<TableRow>): TableRow {
  return {
    id: 'B-DEFAULT',
    batchNumber: 'MT-DEFAULT',
    materialName: '原料',
    quantityUnit: 'kg',
    receiptQuantity: 10,
    receiptDate: '2026-09-01',
    sourceDocType: 'PURCHASE_RECEIVE',
    sourceDocId: RECEIPT_1_ID,
    sourceDocNumber: 'RCV-20260901-6529',
    ...overrides,
  };
}

describe('groupBatchesBySourceDocument', () => {
  it('groups 3 batches from one receipt into a single group containing all 3', () => {
    const rows = [
      batch({ id: 'B1', batchNumber: 'MT-20260901-4022' }),
      batch({ id: 'B2', batchNumber: 'MT-20260901-4043' }),
      batch({ id: 'B3', batchNumber: 'MT-20260901-4055' }),
    ];

    const groups = groupBatchesBySourceDocument(rows);

    expect(groups).toHaveLength(1);
    expect(groups[0].batchCount).toBe(3);
    expect(groups[0].batches.map((b) => b.batchNumber)).toEqual([
      'MT-20260901-4022',
      'MT-20260901-4043',
      'MT-20260901-4055',
    ]);
    expect(groups[0].docLabel).toBe('RCV-20260901-6529');
  });

  it('renders a second receipt as a separate group, not merged into the first', () => {
    const rows = [
      batch({ id: 'B1', batchNumber: 'MT-20260901-4022' }),
      batch({ id: 'B2', batchNumber: 'MT-20260901-4043' }),
      batch({
        id: 'B4',
        batchNumber: 'MT-20260901-3312',
        sourceDocId: RECEIPT_2_ID,
        sourceDocNumber: 'RCV-20260901-6530',
        receiptDate: '2026-09-01',
      }),
    ];

    const groups = groupBatchesBySourceDocument(rows);

    expect(groups).toHaveLength(2);
    const receiptOne = groups.find((g) => g.key === RECEIPT_1_ID);
    const receiptTwo = groups.find((g) => g.key === RECEIPT_2_ID);
    expect(receiptOne?.batchCount).toBe(2);
    expect(receiptTwo?.batchCount).toBe(1);
    expect(receiptTwo?.batches[0].batchNumber).toBe('MT-20260901-3312');
  });

  it('positive control: a single-batch receipt still renders as its own visible group of one', () => {
    const rows = [batch({ id: 'B1', sourceDocId: RECEIPT_2_ID, sourceDocNumber: 'RCV-20260901-6530' })];

    const groups = groupBatchesBySourceDocument(rows);

    expect(groups).toHaveLength(1);
    expect(groups[0].batchCount).toBe(1);
    expect(groups[0].isUnlinked).toBe(false);
    expect(groups[0].docLabel).toBe('RCV-20260901-6530');
  });

  it('buckets batches with null/empty sourceDocId into an explicit "无关联单据" group instead of dropping them', () => {
    const rows = [
      batch({ id: 'B1' }),
      batch({ id: 'B-NULL', sourceDocId: null, sourceDocNumber: null, sourceDocType: null }),
      batch({ id: 'B-EMPTY', sourceDocId: '', sourceDocNumber: null, sourceDocType: null }),
      batch({ id: 'B-UNDEF', sourceDocId: undefined, sourceDocNumber: undefined, sourceDocType: undefined }),
    ];

    const groups = groupBatchesBySourceDocument(rows);

    const unlinked = groups.find((g) => g.key === NO_SOURCE_DOC_KEY);
    expect(unlinked).toBeDefined();
    expect(unlinked?.isUnlinked).toBe(true);
    expect(unlinked?.docLabel).toBe('无关联单据');
    // 全部 3 条(null/''/undefined)都要在这一组里, 一条都不能从总数里消失。
    expect(unlinked?.batchCount).toBe(3);
    const totalAcrossAllGroups = groups.reduce((sum, g) => sum + g.batchCount, 0);
    expect(totalAcrossAllGroups).toBe(rows.length);
  });

  it('does not fake-group batches that merely lack a doc id together with real receipts', () => {
    // "无关联单据"必须是独立的桶, 不能和某个真实收货单混在一起显得像"一起到货"。
    const rows = [batch({ id: 'B1' }), batch({ id: 'B-NULL', sourceDocId: null, sourceDocNumber: null })];
    const groups = groupBatchesBySourceDocument(rows);
    expect(groups).toHaveLength(2);
    expect(groups.some((g) => g.isUnlinked && g.batches.some((b) => b.id === 'B1'))).toBe(false);
  });

  it('falls back to doc-type label + short id when sourceDocId exists but is not a purchase receive', () => {
    const rows = [
      batch({
        id: 'B-TRANSFER',
        sourceDocId: 'transfer-doc-uuid-not-a-receive',
        sourceDocNumber: null,
        sourceDocType: 'TRANSFER_IN',
      }),
    ];

    const groups = groupBatchesBySourceDocument(rows);

    expect(groups).toHaveLength(1);
    expect(groups[0].isUnlinked).toBe(false);
    expect(groups[0].docLabel).toContain('调拨入库');
    expect(groups[0].docTypeLabel).toBe('调拨入库');
  });

  it('splits 本次总量 by unit instead of summing incompatible units together', () => {
    const rows = [
      batch({ id: 'B1', receiptQuantity: 10, quantityUnit: 'kg' }),
      batch({ id: 'B2', receiptQuantity: 3, quantityUnit: '箱' }),
      batch({ id: 'B3', receiptQuantity: 5, quantityUnit: 'kg' }),
    ];

    const groups = groupBatchesBySourceDocument(rows);

    expect(groups).toHaveLength(1);
    const totals = groups[0].totalsByUnit;
    const kg = totals.find((t) => t.unit === 'kg');
    const box = totals.find((t) => t.unit === '箱');
    expect(kg?.total).toBe(15);
    expect(box?.total).toBe(3);
    // ⛔ 阴性对照: 绝不允许把 15 和 3 加成一个 18 的"总量"
    expect(totals.some((t) => t.total === 18)).toBe(false);
  });

  it('counts batches with an unparseable quantity as unknown instead of silently folding them into 0', () => {
    const rows = [
      batch({ id: 'B1', receiptQuantity: 10, quantityUnit: 'kg' }),
      batch({ id: 'B2', receiptQuantity: null, quantityUnit: 'kg' }),
      batch({ id: 'B3', receiptQuantity: undefined, quantityUnit: 'kg' }),
    ];

    const groups = groupBatchesBySourceDocument(rows);
    const kg = groups[0].totalsByUnit.find((t) => t.unit === 'kg');

    expect(kg?.total).toBe(10);
    expect(kg?.unknownCount).toBe(2);
  });

  it('positive control: distinct receipts keep distinct group keys (grouping key is not a constant)', () => {
    const rows = [
      batch({ id: 'B1', sourceDocId: RECEIPT_1_ID }),
      batch({ id: 'B2', sourceDocId: RECEIPT_2_ID, sourceDocNumber: 'RCV-20260901-6530' }),
    ];

    const groups = groupBatchesBySourceDocument(rows);

    expect(groups.map((g) => g.key).sort()).toEqual([RECEIPT_1_ID, RECEIPT_2_ID].sort());
  });
});
