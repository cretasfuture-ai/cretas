import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { getVisiblePageCodes } from '../../composables/useTemplateMap';

const getAnalysisResults = vi.fn();
vi.mock('@/api/smartbi/analysisResults', () => ({
  getAnalysisResults: (...a: unknown[]) => getAnalysisResults(...a),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

let domain = 'FACTORY';
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ get businessDomain() { return domain; } }),
}));

import TemplateGrid from '../TemplateGrid.vue';

const RESTAURANT_ONLY = [
  'revenue_management_report',
  'stored_value_card_consumption',
  'groupon_channel_breakdown',
];

async function mountGrid(pageKey = 'finance') {
  setActivePinia(createPinia());
  const w = mount(TemplateGrid, {
    props: { pageKey, factoryId: 'LSMDEMO' },
    global: {
      stubs: {
        TemplateCard: { props: ['code'], template: '<div class="card-stub" :data-code="code" />' },
        'el-button': true,
        'el-icon': true,
        Refresh: true,
      },
    },
  });
  await flushPromises();
  return w;
}

const renderedCodes = (w: Awaited<ReturnType<typeof mountGrid>>) =>
  w.findAll('.card-stub').map((c) => c.attributes('data-code'));

describe('getVisiblePageCodes', () => {
  it('FACTORY 不含餐饮专属，保留利润表', () => {
    const codes = getVisiblePageCodes('finance', 'FACTORY');
    for (const c of RESTAURANT_ONLY) expect(codes).not.toContain(c);
    expect(codes).toContain('profit_loss_statement');
  });
  it('RESTAURANT 阴性对照：全部保留', () => {
    expect(getVisiblePageCodes('finance', 'RESTAURANT')).toEqual([
      'profit_loss_statement', ...RESTAURANT_ONLY,
    ]);
  });
  it('FACTORY 下 restaurantv2 页全空', () => {
    expect(getVisiblePageCodes('restaurantv2', 'FACTORY')).toEqual([]);
  });
});

describe('TemplateGrid 真实组件按业务域过滤', () => {
  beforeEach(() => {
    getAnalysisResults.mockReset();
    getAnalysisResults.mockResolvedValue({ items: [], missingCodes: [], neverMaterializedCodes: [] });
  });

  it('食品工厂：看不到餐饮模板，请求也不带它们', async () => {
    domain = 'FACTORY';
    const w = await mountGrid('finance');
    expect(renderedCodes(w)).toEqual(['profit_loss_statement']);
    expect(getAnalysisResults.mock.calls[0][1]).toEqual(['profit_loss_statement']);
  });

  it('餐饮工厂：仍显示 4 张', async () => {
    domain = 'RESTAURANT';
    const w = await mountGrid('finance');
    expect(renderedCodes(w)).toEqual(['profit_loss_statement', ...RESTAURANT_ONLY]);
  });

  it('食品工厂过滤后一张不剩：整块不渲染且不请求', async () => {
    domain = 'FACTORY';
    const w = await mountGrid('restaurantv2');
    expect(w.find('.tpl-grid-section').exists()).toBe(false);
    expect(getAnalysisResults).not.toHaveBeenCalled();
  });
});
