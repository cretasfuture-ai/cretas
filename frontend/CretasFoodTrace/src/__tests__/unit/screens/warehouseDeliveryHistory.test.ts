import fs from 'fs';
import path from 'path';

const source = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/warehouse/outbound/WHDeliveryConfirmListScreen.tsx'),
  'utf8',
);
const detailSource = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx'),
  'utf8',
);

describe('发货确认与历史同屏契约', () => {
  it('并行读取待确认与只读历史，来源失败时保留另一来源并可重试', () => {
    expect(source).toContain('Promise.allSettled');
    expect(source).toContain('getPendingDeliveries({ page: nextPendingPage, size: PAGE_SIZE })');
    expect(source).toContain('getDeliveryHistory({ page: nextHistoryPage, size: PAGE_SIZE })');
    expect(source).toContain('setPendingDeliveries((previous) => append');
    expect(source).toContain('setHistoryDeliveries((previous) => append');
    expect(source).toContain('testID="wh-outbound-partial-error"');
    expect(source).toContain('testID="wh-outbound-retry-partial"');
    expect(source).toContain('testID="wh-outbound-load-error"');
    expect(source).toContain('testID="wh-outbound-load-more"');
    expect(source).toContain('pendingPageRef.current + 1');
    expect(source).toContain('historyPageRef.current + 1');
  });

  it('终态卡片可打开核账，但明细页保持只读且展示责任与实际批次', () => {
    expect(source).toContain("isCompletedDeliveryStatus(row.status) ? '查看发货记录'");
    expect(source).toContain('row.shippedBy');
    expect(source).toContain('row.updatedAt');
    expect(source).toContain("['completed', '已完成/历史'");
    expect(detailSource).toContain('const readOnlyHistory = Boolean(record && !isConfirmableDeliveryStatus(record.status))');
    expect(detailSource).toContain('salesDeliveryBatchAllocationApiClient.list(row.id)');
    expect(detailSource).toContain("readOnlyHistory ? '实际出库批次：'");
    expect(detailSource).toContain('{!readOnlyHistory && <Surface style={styles.footer}');
  });
});
