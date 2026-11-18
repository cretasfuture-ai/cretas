import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ListEmptyExplain from '../ListEmptyExplain.vue';
import { loadErrorText, resolveListEmpty } from '@/utils/listEmptyState';

const base = { emptyTitle: '还没有销售退货单', emptyHint: '客户退货时在销售订单里登记' };

describe('resolveListEmpty 判定顺序', () => {
  it('本来就没有数据：说明为什么为空与下一步', () => {
    expect(resolveListEmpty(base)).toEqual({ kind: 'empty', title: '还没有销售退货单', hint: '客户退货时在销售订单里登记' });
  });
  it('筛选后为空：提示调整筛选条件，不说「还没有」', () => {
    const r = resolveListEmpty({ ...base, filtered: true });
    expect(r.kind).toBe('filtered');
    expect(r.title).not.toContain('还没有');
  });
  it('接口报错优先于筛选与空：显示错误，不说没数据', () => {
    const r = resolveListEmpty({ ...base, filtered: true, loadError: '500 服务异常' });
    expect(r.kind).toBe('error');
    expect(r.title).toContain('500 服务异常');
    expect(r.hint).toContain('不代表没有数据');
  });
  it('loadErrorText 取 Error / 带 message 对象 / 兜底', () => {
    expect(loadErrorText(new Error('boom'))).toBe('boom');
    expect(loadErrorText({ message: 'x' })).toBe('x');
    expect(loadErrorText(undefined, '兜底')).toBe('兜底');
  });
});

describe('ListEmptyExplain 组件三态', () => {
  it('无数据说明', () => {
    const w = mount(ListEmptyExplain, { props: base });
    expect(w.attributes('data-kind')).toBe('empty');
    expect(w.text()).toContain('还没有销售退货单');
    expect(w.text()).toContain('客户退货时在销售订单里登记');
    expect(w.find('[data-testid="list-empty-retry"]').exists()).toBe(false);
    expect(w.find('[data-testid="list-empty-clear"]').exists()).toBe(false);
  });
  it('筛选后为空：可清空筛选', async () => {
    const w = mount(ListEmptyExplain, { props: { ...base, filtered: true } });
    expect(w.attributes('data-kind')).toBe('filtered');
    expect(w.text()).toContain('调整或清空筛选条件');
    await w.get('[data-testid="list-empty-clear"]').trigger('click');
    expect(w.emitted('clear-filter')).toHaveLength(1);
  });
  it('接口报错：显示错误并可重试，不出现「还没有」', async () => {
    const w = mount(ListEmptyExplain, { props: { ...base, loadError: '网络异常' } });
    expect(w.attributes('data-kind')).toBe('error');
    expect(w.text()).toContain('加载失败：网络异常');
    expect(w.text()).not.toContain('还没有销售退货单');
    await w.get('[data-testid="list-empty-retry"]').trigger('click');
    expect(w.emitted('retry')).toHaveLength(1);
  });
});
