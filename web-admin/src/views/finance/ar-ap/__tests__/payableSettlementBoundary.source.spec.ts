import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const arApSource = readFileSync(resolve(__dirname, '../index.vue'), 'utf8');
const legacySource = readFileSync(
  resolve(__dirname, '../../../procurement/payment-requests/list.vue'),
  'utf8',
);

describe('payable settlement UI boundary', () => {
  it('starts payment from an audited open AP item with finance write permission', () => {
    expect(arApSource).toContain("permissionStore.canWrite('finance')");
    expect(arApSource).toContain("row.transactionType === 'AP_INVOICE'");
    expect(arApSource).toContain('payableOutstanding(row) > 0');
    expect(arApSource).toContain('/finance/payables/${row.id}/settlements');
    expect(arApSource).toContain('idempotencyKey: settleForm.value.idempotencyKey');
    expect(arApSource).not.toContain("path: '/procurement/payment-requests'");
  });

  it('keeps legacy payment requests read-only and explains the replacement flow', () => {
    expect(legacySource).toContain('采购付款申请旧入口已停用');
    expect(legacySource).toContain('仓库确认收货生成未清应付');
    expect(legacySource).toContain("import { get } from '@/api/request'");
    expect(legacySource).not.toMatch(/\b(?:post|put|patch|del)\s*\(/);
    expect(legacySource).not.toContain('+ 新建付款申请');
    expect(legacySource).not.toContain('提交审核');
    expect(legacySource).not.toContain('openMarkPaidDialog');
    expect(legacySource).not.toContain('doMarkPaid');
    expect(legacySource).not.toContain("path: '/finance/ar-ap'");
  });
});
