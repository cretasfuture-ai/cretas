/**
 * LSM-COSTBI 第 10 项: 模板卡片上曾露出英文字段名 troughValue。
 * 守的性质: Python 物化模板产出的每一个 KPI 键, 界面都有中文标签 (不靠手抄清单)。
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { KPI_LABEL_MAP, KPI_META_KEYS, localizeKpiKey } from '../templateKpis';

const TEMPLATE_DIR = resolve(
  __dirname, '..', '..', '..', '..', '..', '..',
  'backend', 'python', 'smartbi', 'services', 'materialized_analytics', 'templates',
);

function pythonTemplateKpiKeys(): string[] {
  const keys = new Set<string>();
  for (const name of readdirSync(TEMPLATE_DIR)) {
    if (!name.endsWith('.py')) continue;
    const src = readFileSync(resolve(TEMPLATE_DIR, name), 'utf-8');
    for (const block of src.matchAll(/kpis\s*=\s*\{([\s\S]*?)\n\s*\}/g)) {
      for (const m of block[1].matchAll(/"([a-z_0-9]+)"\s*:/g)) keys.add(m[1]);
    }
    for (const m of src.matchAll(/kpis\[\s*"([a-z_0-9]+)"\s*\]/g)) keys.add(m[1]);
  }
  return [...keys].sort();
}

describe('template KPI labels', () => {
  it('reads a non-trivial key set from the Python templates (instrument check)', () => {
    const keys = pythonTemplateKpiKeys();
    expect(keys).toContain('trough_value');
    expect(keys).toContain('peak_value');
    expect(keys.length).toBeGreaterThan(15);
  });

  it('every KPI key a template emits has a Chinese label, in snake_case and camelCase', () => {
    const missing: string[] = [];
    for (const key of pythonTemplateKpiKeys()) {
      if (KPI_META_KEYS.has(key)) continue; // unit / aggregation never render as KPIs
      const camel = key.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase());
      for (const k of [key, camel]) {
        const label = localizeKpiKey(k);
        if (label === k || /[A-Za-z]{3,}/.test(label.replace(/Top/g, ''))) missing.push(k);
      }
    }
    expect(missing).toEqual([]);
  });

  it('troughValue / peakValue read as 谷值 / 峰值', () => {
    expect(localizeKpiKey('troughValue')).toBe('谷值');
    expect(localizeKpiKey('trough_value')).toBe('谷值');
    expect(localizeKpiKey('peakValue')).toBe('峰值');
  });

  it('unknown keys still fall through unchanged (no silent rename)', () => {
    expect(localizeKpiKey('someNewKey')).toBe('someNewKey');
    expect(KPI_LABEL_MAP.someNewKey).toBeUndefined();
  });
});
