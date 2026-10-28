import { describe, expect, it } from 'vitest';
import { describeAuditReason, type AuditGrantInfo, type AuditLabelContext } from '../auditLabels';

const USERS: Record<number, string> = { 1309: '张六膳', 1558: '六膳门质检员', 1600: '李主管' };
const GRANTS: Record<number, AuditGrantInfo> = {
  1: { granteeId: 1558, canViewFull: true, canExport: false, canEdit: false, purpose: '质量审核', expiresAt: '2026-10-26T09:58:00' },
};
const ctx: AuditLabelContext = {
  userName: (id) => USERS[id] || `#${id}`,
  modeLabel: (m) => ({ CLOUD_RESTRICTED: '工艺保密访问', CUSTOMER_HELD: '核心工艺不入云' } as Record<string, string>)[m] || m,
  grantById: (id) => GRANTS[id],
  formatTime: (s) => (s ? s.replace('T', ' ').substring(0, 16) : '—'),
};

describe('describeAuditReason', () => {
  it('ENABLE names the mode and the owner', () => {
    expect(describeAuditReason('ENABLE CLOUD_RESTRICTED owner=1309', ctx)).toEqual({
      action: '开启保密', text: '设为「工艺保密访问」，负责人：张六膳',
    });
  });

  it('UPDATE lists only what changed', () => {
    expect(describeAuditReason('UPDATE CLOUD_RESTRICTED->CUSTOMER_HELD owner 1309->1309', ctx).text)
      .toBe('保密方式「工艺保密访问」改为「核心工艺不入云」');
    expect(describeAuditReason('UPDATE CLOUD_RESTRICTED->CLOUD_RESTRICTED owner 1309->1600', ctx).text)
      .toBe('负责人 张六膳 改为 李主管');
    expect(describeAuditReason('UPDATE CLOUD_RESTRICTED->CLOUD_RESTRICTED owner 1309->1309', ctx).text)
      .toBe('保存设置（无变化）');
  });

  it('DISABLE says the grants go with it', () => {
    expect(describeAuditReason('DISABLE CLOUD_RESTRICTED', ctx)).toEqual({
      action: '关闭保密', text: '关闭「工艺保密访问」，该产品的全部授权同时撤销',
    });
  });

  it('GRANT resolves the grantee and, when loaded, the permission, purpose and expiry', () => {
    expect(describeAuditReason('GRANT #1 to FACTORY_USER:1558', ctx)).toEqual({
      action: '新增授权', text: '授权给 六膳门质检员（可以查看；用途：质量审核；到期：2026-10-26 09:58）',
    });
    expect(describeAuditReason('GRANT #9 to FACTORY_USER:1600', ctx).text).toBe('授权给 李主管');
  });

  it('REVOKE resolves the grantee through the grant id', () => {
    expect(describeAuditReason('REVOKE #1', ctx)).toEqual({ action: '撤销授权', text: '撤销 六膳门质检员 的授权' });
    expect(describeAuditReason('REVOKE #9', ctx).text).toBe('撤销一条授权');
  });

  it('never shows the raw machine format, and keeps already-readable reasons', () => {
    for (const raw of ['ENABLE CLOUD_RESTRICTED owner=1309', 'GRANT #1 to FACTORY_USER:1558', 'REVOKE #1',
      'DISABLE CUSTOMER_HELD', 'UPDATE CUSTOMER_HELD->CLOUD_RESTRICTED owner 1309->1600']) {
      expect(describeAuditReason(raw, ctx).text).not.toMatch(/ENABLE|GRANT|REVOKE|DISABLE|UPDATE|FACTORY_USER|#\d|owner|_/);
    }
    expect(describeAuditReason('有效授权', ctx)).toEqual({ action: null, text: '有效授权' });
    expect(describeAuditReason(null, ctx).text).toBe('—');
  });
});
