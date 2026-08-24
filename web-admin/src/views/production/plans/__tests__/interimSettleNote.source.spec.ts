import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');
const apiSource = readFileSync(
  resolve(__dirname, '..', '..', '..', '..', 'api', 'productionPlan.ts'),
  'utf8',
);

function fnBody(source: string, name: string): string {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `找不到 function ${name}`).toBeGreaterThan(-1);
  const end = source.indexOf('\n}', start);
  expect(end, `function ${name} 没有顶格收尾`).toBeGreaterThan(start);
  return source.slice(start, end);
}

describe('存货生产小结备注契约', () => {
  it('计划列表必须在提交前采集备注并传给 API', () => {
    const body = fnBody(listSource, 'handleInterimSettle');

    expect(body).toContain('ElMessageBox.prompt');
    expect(body).toContain('填写本次小结备注');
    expect(body).toContain('note = promptResult.value');
    expect(body).toContain('interimSettle(factoryId.value, planId, note)');
  });

  it('API 必须把 trim 后的备注放进请求体', () => {
    const body = fnBody(apiSource, 'interimSettle');

    expect(body).toContain('note?: string');
    expect(body).toContain('{ note: note?.trim() || null }');
  });
});
