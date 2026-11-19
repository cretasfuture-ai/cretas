import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { materialReceiptDateText, NO_RECEIPT_DATE_TEXT } from '../materialBatchReceipt';

/**
 * 进销存总览「入库记录」入库时间列恒空：页面读 receivedDate，
 * 后端 MaterialBatchDTO 只输出 receiptDate / inboundDate（LocalDate）。
 */
describe('入库时间取后端真实字段', () => {
  it('后端形状（receiptDate + inboundDate）显示入库日期', () => {
    expect(materialReceiptDateText({ receiptDate: '2026-09-09', inboundDate: '2026-09-09' })).toBe('2026-09-09');
  });

  it('只有 inboundDate 也能显示', () => {
    expect(materialReceiptDateText({ inboundDate: '2026-10-01' })).toBe('2026-10-01');
  });

  it('后端没给日期如实显示「无入库时间」，不造数据', () => {
    expect(materialReceiptDateText({})).toBe(NO_RECEIPT_DATE_TEXT);
    expect(materialReceiptDateText({ receiptDate: null, inboundDate: null })).toBe('无入库时间');
    expect(materialReceiptDateText({ receiptDate: '' })).toBe('无入库时间');
  });

  it('页面接上了该取值函数，且不再读不存在的 receivedDate', () => {
    const source = readFileSync(
      resolve(process.cwd(), 'src/views/analytics/SupplyChainOverview.vue'),
      'utf-8',
    );
    expect(source).toContain('materialReceiptDateText(row)');
    expect(source).not.toContain('receivedDate');
  });
});
