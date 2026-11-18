import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const src = readFileSync(resolve(__dirname, '../list.vue'), 'utf-8');

describe('质检记录页计数口径与批次号列 (PROTO-DEMO-LAST)', () => {
  it('标题标明是当前列表条数, 底部汇总标明全部记录且单位为条而非项', () => {
    expect(src).toContain('当前列表共 {{ pagination.total }} 条记录');
    expect(src).toContain("'共': '全部记录'");
    expect(src).toContain("st.unit === '项' ? '条' : st.unit");
    expect(src).toContain(':stats="footerStats"');
    expect(src).not.toContain(':stats="footerSummary?.stats ?? []"');
  });
  it('批次号列不再 truncate, 有 min-width 且不换行', () => {
    const line = src.split('\n').find((l) => l.includes("accessorKey: 'batchNumber'")) ?? '';
    expect(line).toContain('min-w-56');
    expect(line).toContain('whitespace-nowrap');
    expect(line).not.toContain('truncate');
  });
});
