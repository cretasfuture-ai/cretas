import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// 原型统一 DECISIONS 十九: 销售/采购列表的关键词与日期筛选必须走服务端 (分页前由数据库过滤)，
// 不能再只对当前页做 includes —— 否则翻页后的单号搜不到。
const read = (p: string) => readFileSync(resolve(process.cwd(), p), 'utf8');

function loadDataBody(source: string): string {
  const start = source.indexOf('async function loadData()');
  expect(start).toBeGreaterThan(-1);
  const end = source.indexOf('\nasync function ', start + 10);
  return source.slice(start, end > start ? end : start + 4000);
}

describe('order list server-side search (keyword / date)', () => {
  it('sales orders list sends keyword to the server and does not filter the page locally', () => {
    const body = loadDataBody(read('src/views/sales/orders/list.vue'));
    expect(body).toContain('params.keyword = kw');
    expect(body).not.toMatch(/\.filter\(\(r: TableRow\) =>[\s\S]{0,120}includes\(lower\)/);
    expect(body).not.toContain('toLowerCase().includes');
  });

  it('purchase orders list sends keyword and order-date range to the server and does not filter locally', () => {
    const body = loadDataBody(read('src/views/procurement/orders/list.vue'));
    expect(body).toContain('params.keyword = kw');
    expect(body).toContain('params.startDate = dateRange.value[0]');
    expect(body).toContain('params.endDate = dateRange.value[1]');
    expect(body).not.toContain('toLowerCase().includes');
    expect(body).not.toContain('d >= from && d <= to');
  });
});
