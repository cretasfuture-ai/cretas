// @ts-nocheck
import * as fs from 'fs';
import * as path from 'path';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { SECRECY_ITEMS, isSecrecyOnIn } from '../../../config/secrecyItems';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import MockAdapter from 'axios-mock-adapter';

/**
 * 工厂级保密配置 (Steve 2026-11-03): feature-config 里 moduleId = "secrecy" 的只读行。
 * 只有明确 true 才算开 —— 本 App 对「缺行 / 没加载」默认按启用, 保密配置不能沿用。
 */
const FACTORY = 'F006';
const URL = `/api/mobile/${FACTORY}/feature-config`;

let mock: MockAdapter;

beforeEach(() => {
  mock = createApiMock();
  useFactoryFeatureStore.setState({ modules: {}, loaded: false, loading: false });
});

afterEach(() => {
  resetApiMock(mock);
});

const secrecyRow = (secrecy) => ({
  moduleId: 'secrecy', moduleName: '保密配置（平台设置，只读）', enabled: true, readOnly: true,
  config: { secrecy }, secrecy,
});

describe('工厂级保密配置 (App 只读)', () => {
  it('reads the flags from the read-only feature-config row and only trusts true', async () => {
    mock.onGet(URL).reply(200, { success: true, data: [
      { moduleId: 'production', enabled: true, config: {} },
      secrecyRow({ PROCESS_SECRET: true, EGRESS_REDACTION: false, TRAINING_FILTER: 'true' }),
    ] });
    await useFactoryFeatureStore.getState().loadFeatures(FACTORY);
    const store = useFactoryFeatureStore.getState();
    expect(store.isSecrecyOn('PROCESS_SECRET')).toBe(true);
    expect(store.isSecrecyOn('EGRESS_REDACTION')).toBe(false);
    expect(store.isSecrecyOn('TRAINING_FILTER')).toBe(false);
    expect(store.isSecrecyOn('CUSTOMER_HELD')).toBe(false);
    // 伪模块不影响其他模块的「启用」判断
    expect(store.isModuleEnabled('production')).toBe(true);
  });

  it('is off before loading and when the row is missing (unlike modules, which default to enabled)', async () => {
    expect(useFactoryFeatureStore.getState().isSecrecyOn('PROCESS_SECRET')).toBe(false);
    expect(useFactoryFeatureStore.getState().isModuleEnabled('anything')).toBe(true);
    mock.onGet(URL).reply(200, { success: true, data: [{ moduleId: 'production', enabled: true, config: {} }] });
    await useFactoryFeatureStore.getState().loadFeatures(FACTORY);
    expect(useFactoryFeatureStore.getState().isSecrecyOn('PROCESS_SECRET')).toBe(false);
    expect(isSecrecyOnIn(null, 'PROCESS_SECRET')).toBe(false);
    expect(isSecrecyOnIn({ secrecy: { config: { secrecy: { NOT_AN_ITEM: true } } } }, 'NOT_AN_ITEM')).toBe(false);
  });

  it('uses the generated items file (the Java enum is the single source)', () => {
    const file = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../config/secrecy-items.json'), 'utf8'));
    expect(file._generated).toContain('SecrecyItem');
    expect(SECRECY_ITEMS.map((i) => i.code)).toEqual(file.items.map((i) => i.code));
    expect(SECRECY_ITEMS).toHaveLength(6);
  });

  it('ConversionRateScreen shows 已保密 only for a marked hidden rate, — for a plain null', () => {
    const source = fs.readFileSync(path.join(__dirname, '../../../screens/management/ConversionRateScreen.tsx'), 'utf8');
    expect(source).toContain("c.conversionRate == null && isProcessSecretHidden(c, 'conversionRate')");
    expect(source).toContain("item.conversionRateSecret ? PROCESS_SECRET_LABEL : '—'");
    expect(source).not.toMatch(/avgConversionRate == null\s*\n?\s*\? PROCESS_SECRET_LABEL/);
  });
});
