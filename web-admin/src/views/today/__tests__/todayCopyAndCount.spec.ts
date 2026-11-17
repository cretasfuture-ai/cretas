import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import SourceBlock from '../SourceBlock.vue';
import type { TSrc } from '../todayData';

const read = (...p: string[]) => readFileSync(resolve(__dirname, '..', ...p), 'utf8');

/** 只取用户看得到的文字：模板部分 + 剥掉注释的脚本字符串。 */
function visibleText(file: string): string {
  const src = read(file);
  const noComments = src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:'"`])\/\/.*$/gm, '$1');
  return noComments;
}

// 内部开发口径：P5 / P4 / P10 / M7 这类编号、「本批」「迁移范围」
const INTERNAL = /(?<![A-Za-z0-9])[PM]\d{1,2}(?![A-Za-z0-9])|本批|迁移范围|补接口/;

describe('「今天」页文案不带内部开发用语', () => {
  for (const f of ['TodayPage.vue', 'SourceBlock.vue', 'todayData.ts', 'TodoPage.vue', 'todoTabs.ts']) {
    it(`${f} 面向用户的文字里没有内部编号`, () => {
      const hit = visibleText(f).match(INTERNAL);
      expect(hit, `命中：${hit?.[0]}`).toBeNull();
    });
  }

  it('阴性对照：闸认得出 P5 / M7 / 本批', () => {
    for (const bad of ['由 P5（数据疑点）补', '不在迁移范围（M7）', '本批不显示', 'P10']) {
      expect(INTERNAL.test(bad), bad).toBe(true);
    }
  });
});

function src(over: Partial<TSrc>): TSrc {
  return {
    id: 'salesOrders', title: '待处理销售订单', hint: 'h', state: 'ok', count: 0, items: [],
    note: '服务端按我的数据范围计数', link: '/sales/orders', linkLabel: '去销售订单', ...over,
  };
}
const mountBlock = (s: TSrc) => mount(SourceBlock, {
  props: { s },
  global: {
    mocks: { $router: { push: () => undefined } },
    stubs: { 'el-button': { template: '<button><slot /></button>' } },
  },
});

describe('「今天」来源卡：数字与说明同源', () => {
  it('数字 26 且没有明细行 ⇒ 不许写「现在没有」，写共 26 项', () => {
    const w = mountBlock(src({ count: 26 }));
    expect(w.get('[data-testid="src-salesOrders-count"]').text()).toBe('26');
    expect(w.text()).not.toContain('现在没有');
    expect(w.get('[data-testid="src-detail-hint"]').text()).toContain('共 26 项');
  });

  it('数字 0 ⇒ 现在没有', () => {
    const w = mountBlock(src({ count: 0 }));
    expect(w.get('[data-testid="src-empty"]').text()).toBe('现在没有。');
  });

  it('读取失败 ⇒ 不显示数字，也不写「现在没有」', () => {
    const w = mountBlock(src({ state: 'error', note: '读取失败：x' }));
    expect(w.text()).not.toContain('现在没有');
    expect(w.find('[data-testid="src-salesOrders-count"]').exists()).toBe(false);
  });
});
