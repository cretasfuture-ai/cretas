import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { policySaveWarningText, policySaveWarnings } from '../policyWarnings';

/**
 * Steve 2026-11-03 选 B: 产品全名是通用品类词时训练过滤不按名字拦, 保存策略的响应带 warnings (仍是 200),
 * 页面必须显示给负责人。
 */
const WARNING = '该名称为通用品类词，训练过滤不按名字拦截，仅按编码/工艺数值保护';

describe('policySaveWarnings', () => {
  it('returns the server warnings and drops blanks', () => {
    expect(policySaveWarnings({ warnings: [WARNING, '', '  '] })).toEqual([WARNING]);
  });

  it('no warnings field / null data -> nothing to show', () => {
    expect(policySaveWarnings({})).toEqual([]);
    expect(policySaveWarnings({ warnings: null })).toEqual([]);
    expect(policySaveWarnings(null)).toEqual([]);
    expect(policySaveWarnings(undefined)).toEqual([]);
  });

  it('names the product in the text shown', () => {
    expect(policySaveWarningText('牛腱', [WARNING])).toBe(`「牛腱」：${WARNING}`);
  });
});

describe('process-secret page shows the save warnings', () => {
  const src = readFileSync(resolve(__dirname, '../index.vue'), 'utf-8');

  it('reads warnings from the upsert response and shows them as a warning message', () => {
    const save = src.slice(src.indexOf('async function savePolicy'), src.indexOf('async function confirmDisable'));
    expect(save).toContain('policySaveWarnings(res.data)');
    expect(save).toContain("type: 'warning'");
    expect(save).toContain('policySaveWarningText(row.name, warnings)');
  });
});
