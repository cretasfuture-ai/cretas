import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  SECRECY_GATED_PATHS,
  SECRECY_ITEMS,
  isSecrecyOn,
  secrecyFromFeatureConfig,
  secrecyHiddenMenuPaths,
} from '../factorySecrecy';

const featureRows = (secrecy: unknown) => [
  { moduleId: 'production', enabled: true, config: {} },
  { moduleId: 'secrecy', enabled: true, readOnly: true, config: { secrecy }, secrecy },
];

describe('工厂级保密配置 (feature-config 只读下发)', () => {
  it('only an explicit true counts as on', () => {
    const flags = secrecyFromFeatureConfig(featureRows({ PROCESS_SECRET: true, EGRESS_REDACTION: false, TRAINING_FILTER: 'true' }));
    expect(isSecrecyOn(flags, 'PROCESS_SECRET')).toBe(true);
    expect(isSecrecyOn(flags, 'EGRESS_REDACTION')).toBe(false);
    expect(isSecrecyOn(flags, 'TRAINING_FILTER')).toBe(false);   // 字符串不算
    expect(isSecrecyOn(flags, 'CUSTOMER_HELD')).toBe(false);     // 缺字段不算
    expect(isSecrecyOn(secrecyFromFeatureConfig([]), 'PROCESS_SECRET')).toBe(false);   // 没有那一行
    expect(isSecrecyOn(secrecyFromFeatureConfig(null), 'PROCESS_SECRET')).toBe(false);
    expect(isSecrecyOn(null, 'PROCESS_SECRET')).toBe(false);
  });

  it('hides the factory-side process-secret menu unless process secret is on', () => {
    expect(secrecyHiddenMenuPaths({})).toContain('/system/process-secret');
    expect(secrecyHiddenMenuPaths({ PROCESS_SECRET: false })).toContain('/system/process-secret');
    expect(secrecyHiddenMenuPaths({ PROCESS_SECRET: true })).not.toContain('/system/process-secret');
    expect(SECRECY_GATED_PATHS['/system/process-secret']).toBe('PROCESS_SECRET');
  });

  it('reads the generated items file (the Java enum is the single source)', () => {
    const file = JSON.parse(readFileSync(resolve(process.cwd(), 'src/config/secrecy-items.json'), 'utf8'));
    expect(file._generated).toContain('SecrecyItem');
    expect(SECRECY_ITEMS.map((i) => i.code)).toEqual([
      'PROCESS_SECRET', 'PLATFORM_RECIPE_BLOCK', 'EGRESS_REDACTION', 'TRAINING_FILTER', 'CUSTOMER_HELD', 'AI_SECRECY_AUDIT',
    ]);
    const customerHeld = SECRECY_ITEMS.find((i) => i.code === 'CUSTOMER_HELD');
    expect(customerHeld?.offRequiresConfirmation).toBe(true);
    expect(customerHeld?.offConfirmWarning).toContain('这会撤销与客户合同中约定的云端不保管工艺');
  });

  it('the factory-side route and sidebar are gated on the same item', () => {
    const router = readFileSync(resolve(process.cwd(), 'src/router/index.ts'), 'utf8');
    const guards = readFileSync(resolve(process.cwd(), 'src/router/guards.ts'), 'utf8');
    const sidebar = readFileSync(resolve(process.cwd(), 'src/components/layout/AppSidebar.vue'), 'utf8');
    expect(router).toMatch(/path: 'process-secret'[\s\S]{0,600}secrecyFeature: 'PROCESS_SECRET'/);
    expect(guards).toContain('isSecrecyOn(await getFactorySecrecyFlags(factoryId), secrecyFeature)');
    expect(sidebar).toContain('secrecyHiddenMenuPaths(secrecyFlags)');
    // 读到 === true 之前就是隐藏的
    expect(sidebar).toContain('hiddenFactoryMenuPaths.value = secrecyHiddenMenuPaths({})');
  });
});
