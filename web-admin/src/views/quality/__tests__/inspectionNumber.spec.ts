import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { hasInspectionNumber, inspectionNumberLabel, UNNUMBERED_INSPECTION_LABEL } from '../inspectionNumber';

/**
 * PROTO-QC-NUMBER: 质检编号显示后端业务编号 QI-YYYYMMDD-NNNN, 不再把内部 UUID 截前 8 位当编号。
 */
describe('质检编号显示', () => {
  it('有编号就原样显示', () => {
    expect(inspectionNumberLabel({ inspectionNumber: 'QI-20261119-0003' })).toBe('QI-20261119-0003');
    expect(hasInspectionNumber({ inspectionNumber: 'QI-20261119-0003' })).toBe(true);
  });

  it('没编号显示「未编号」, 不拿 id 顶替', () => {
    const legacy = { id: '3f2a9c1b-0000-4000-8000-000000000000', inspectionNumber: null };
    expect(inspectionNumberLabel(legacy)).toBe(UNNUMBERED_INSPECTION_LABEL);
    expect(inspectionNumberLabel(legacy)).not.toContain('3F2A9C1B');
    expect(inspectionNumberLabel({ inspectionNumber: '  ' })).toBe(UNNUMBERED_INSPECTION_LABEL);
    expect(inspectionNumberLabel(undefined)).toBe(UNNUMBERED_INSPECTION_LABEL);
    expect(hasInspectionNumber(legacy)).toBe(false);
  });

  const read = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf-8');

  it('列表列与详情都用业务编号, 不再截 UUID', () => {
    const list = read('src/views/quality/inspections/list.vue');
    expect(list).toContain('inspectionNumberLabel(row.original)');
    expect(list).toContain('inspectionNumberLabel(detailData)');
    expect(list).not.toMatch(/\.id\)\.substring\(0, 8\)/);
  });

  it('登记不良的质检来源也不截 UUID', () => {
    const defects = read('src/views/quality/defects/list.vue');
    expect(defects).toContain('inspectionNumberLabel(source)');
    expect(defects).not.toContain('source.id.slice(0, 8)');
  });
});
