import { describe, expect, it } from 'vitest';
import {
  externalHiddenMenuPaths,
  integrationsFromFeatureConfig,
  isExternalAuthorized,
} from '../externalIntegrations';

const row = (integrations: unknown) => ({ moduleId: 'integrations', readOnly: true, integrations, config: { integrations } });

describe('外部软件接口授权（P10，feature-config 只读行）', () => {
  it('授权工厂：authorized === true 才算，外部软件对照菜单不隐藏', () => {
    const auth = integrationsFromFeatureConfig([
      { moduleId: 'production', config: {} },
      row({ blacklake: { authorized: true, label: '黑湖', lastCollectedAt: '2026-11-15 08:01', stale: false } }),
    ]);
    expect(isExternalAuthorized(auth, 'blacklake')).toBe(true);
    expect(auth.blacklake?.lastCollectedAt).toBe('2026-11-15 08:01');
    expect(externalHiddenMenuPaths(auth)).toEqual([]);
  });

  it('阴性：没有这一行 / 读不到 / 空对象 / 非布尔真值，都按没授权并隐藏菜单', () => {
    for (const rows of [
      [],
      null,
      'oops',
      [row({})],
      [row({ blacklake: { authorized: 'true' } })],
      [row({ blacklake: { authorized: 1 } })],
      // 别的模块里塞的同名键不算
      [{ moduleId: 'production', integrations: { blacklake: { authorized: true } } }],
    ]) {
      const auth = integrationsFromFeatureConfig(rows);
      expect(isExternalAuthorized(auth, 'blacklake'), JSON.stringify(rows)).toBe(false);
      expect(externalHiddenMenuPaths(auth)).toEqual(['/external-software']);
    }
  });

  it('RN 只保留 config 时也读得到', () => {
    const auth = integrationsFromFeatureConfig([{ moduleId: 'integrations', config: { integrations: { blacklake: { authorized: true } } } }]);
    expect(isExternalAuthorized(auth, 'blacklake')).toBe(true);
  });
});
