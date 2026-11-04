import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { REWORK_REASONS, REWORK_REASON_OTHER, errorCodeOf, reworkReasonBlockReason } from '../reworkRatio';

const table = readFileSync(resolve(__dirname, '../ProcessDataTable.vue'), 'utf-8');
const api = readFileSync(resolve(__dirname, '../../../../../api/processSheet.ts'), 'utf-8');
const javaCheck = readFileSync(
  resolve(__dirname, '../../../../../../../backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow/ReworkRatioCheck.java'),
  'utf-8',
);

describe('回料超限原因', () => {
  it('原因选项与后端 ReworkRatioCheck.STANDARD_REASONS 同一份', () => {
    for (const r of REWORK_REASONS) expect(javaCheck).toContain(`"${r}"`);
    expect(REWORK_REASONS.length).toBe(5);
  });
  it('没选原因 / 选「其他」没写说明都不放行', () => {
    expect(reworkReasonBlockReason('', '')).not.toBeNull();
    expect(reworkReasonBlockReason(REWORK_REASON_OTHER, '  ')).not.toBeNull();
    expect(reworkReasonBlockReason(REWORK_REASON_OTHER, '客户要求')).toBeNull();
    expect(reworkReasonBlockReason('主料不足', '')).toBeNull();
  });
  it('取错误码', () => {
    expect(errorCodeOf({ code: 'REWORK_RATIO_REASON_REQUIRED' })).toBe('REWORK_RATIO_REASON_REQUIRED');
    expect(errorCodeOf(new Error('x'))).toBe('');
  });
  it('正式报工被拦后走弹窗重提; 拦截器不再重复弹错', () => {
    expect(table).toContain('submitWithReworkReason(row, req)');
    expect(table).toContain('{ ...req, reworkRatio }');
    expect(api).toContain("'REWORK_RATIO_REASON_REQUIRED'");
    expect(javaCheck).toContain('"REWORK_RATIO_REASON_REQUIRED"');
  });
});
