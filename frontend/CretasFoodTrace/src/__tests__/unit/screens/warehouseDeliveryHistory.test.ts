import fs from 'fs';
import path from 'path';

const source = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/warehouse/outbound/WHDeliveryConfirmListScreen.tsx'),
  'utf8',
);

describe('发货确认与历史同屏契约', () => {
  it('并行读取待确认与只读历史，来源失败时保留另一来源并可重试', () => {
    expect(source).toContain('Promise.allSettled');
    expect(source).toContain('getPendingDeliveries({ page: 1, size: 50 })');
    expect(source).toContain('getDeliveryHistory()');
    expect(source).toContain('setPendingDeliveries(page.content ?? [])');
    expect(source).toContain('setHistoryDeliveries(page.content ?? [])');
    expect(source).toContain('testID="wh-outbound-partial-error"');
    expect(source).toContain('testID="wh-outbound-retry-partial"');
    expect(source).toContain('testID="wh-outbound-load-error"');
  });

  it('终态卡片不进入确认写流程并明确标为只读历史', () => {
    expect(source).toContain('disabled={!isConfirmableDeliveryStatus(row.status)}');
    expect(source).toContain("if (!isConfirmableDeliveryStatus(row.status)) return;");
    expect(source).toContain('只读历史 · 不可确认');
    expect(source).toContain("['completed', '已完成/历史'");
  });
});
