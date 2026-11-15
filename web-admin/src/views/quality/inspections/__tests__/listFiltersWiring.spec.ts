import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import ElementPlus from 'element-plus';

const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const route = reactive<{ query: Record<string, string> }>({ query: {} });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/api/request', () => ({ get: (...a: unknown[]) => state.get(...a), post: (...a: unknown[]) => state.post(...a) }));
vi.mock('@/composables/useListSummary', () => ({ useListSummary: () => ({ summary: null, loading: false }) }));
vi.mock('@/composables/useCreateAndReturn', () => ({ useCreateAndReturn: () => ({ goCreate: vi.fn() }) }));
import Page from '../list.vue';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

const ok = (data: unknown) => ({ success: true, data });
function mountPage() {
  return mount(Page, {
    attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: {
      ...nuxtUiStubs, CanvasAwareWrapper: { template: '<div><slot /></div>' }, CanvasDynamicFields: true,
      TableFooter: true, UpstreamMissingHint: true,
      // Nuxt UI 在 vitest 里没有安装插件, 用最小桩保留 data-testid / 点击 / 输入契约
      UButton: { template: '<button type="button"><slot /></button>' },
      UPopover: {
        props: ['open'], emits: ['update:open'],
        template: `<div><span @click="$emit('update:open', !open)"><slot /></span><div v-if="open"><slot name="content" /></div></div>`,
      },
      UFormField: { template: '<label><slot /></label>' },
      UTextarea: {
        props: ['modelValue'], emits: ['update:modelValue'],
        template: `<textarea :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
      },
    } },
  });
}
function batchTextarea(): HTMLTextAreaElement {
  return document.body.querySelector('[data-testid="batch-no-text"]') as HTMLTextAreaElement;
}
const listCalls = () => state.get.mock.calls.filter(c => String(c[0]).endsWith('/quality/inspections'));

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  route.query = {};
  state.get.mockImplementation((url: string) => Promise.resolve(
    url.endsWith('/batch-no-check') ? ok({ found: ['PB-1'], missing: ['PB-404'] }) : ok({ content: [], totalElements: 2 }),
  ));
});

describe('质检记录列表: 批量批次号 + 相对日期走服务端', () => {
  it('批量批次号: 先核对 found/missing, 再带 batchNos 查列表, 弹层显示找到条数与未找到单号', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(listCalls().at(-1)![1].params.batchNos).toBeUndefined();

    await wrapper.find('[data-testid="batch-no-open"]').trigger('click');
    await flushPromises();
    const textarea = batchTextarea();
    textarea.value = 'PB-1\nPB-404，PB-1';
    textarea.dispatchEvent(new Event('input'));
    await flushPromises();
    (document.body.querySelector('[data-testid="batch-no-run"]') as HTMLButtonElement).click();
    await flushPromises();

    const check = state.get.mock.calls.find(c => String(c[0]).endsWith('/batch-no-check'))!;
    expect(check[0]).toBe('/F006/processing/quality/inspections/batch-no-check');
    expect(check[1].params.batchNos).toBe('PB-1,PB-404'); // 去重
    expect(listCalls().at(-1)![1].params).toMatchObject({ batchNos: 'PB-1,PB-404', page: 1 });
    expect(document.body.querySelector('[data-testid="batch-no-result"]')!.textContent).toContain('找到 2 条；未找到 1 个');
    expect(document.body.querySelector('[data-testid="batch-no-missing"]')!.textContent).toContain('PB-404');
    wrapper.unmount();
  });

  it('相对日期: 选「今天」带 dateFrom=dateTo=今天(北京), 清除后不再带; 重置清掉全部条件', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const click = async (id: string) => { await wrapper.find(`[data-testid="${id}"]`).trigger('click'); await flushPromises(); };
    await click('rel-date-today');
    const params = listCalls().at(-1)![1].params;
    expect(params.dateFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(params.dateTo).toBe(params.dateFrom);
    expect(wrapper.find('[data-testid="rel-date-today"]').attributes('aria-pressed')).toBe('true');

    await click('rel-date-all');
    expect(listCalls().at(-1)![1].params.dateFrom).toBeUndefined();
    expect(listCalls().at(-1)![1].params.dateTo).toBeUndefined();

    await click('rel-date-week');
    const week = listCalls().at(-1)![1].params;
    expect(new Date(week.dateTo).getTime() - new Date(week.dateFrom).getTime()).toBe(6 * 86400000); // 周一至周日
    await click('rel-date-week'); // 再点已选中的 = 取消
    expect(listCalls().at(-1)![1].params.dateFrom).toBeUndefined();
    await click('rel-date-month');
    const reset = wrapper.findAll('button').find(b => b.text() === '重置')!;
    await reset.trigger('click');
    await flushPromises();
    expect(listCalls().at(-1)![1].params.dateFrom).toBeUndefined();
    wrapper.unmount();
  });

  it('批量核对失败: 弹层显示后端原文, 不改列表条件', async () => {
    state.get.mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/batch-no-check') ? { success: false, message: '一次最多查询 200 个批次号' } : ok({ content: [], totalElements: 0 }),
    ));
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="batch-no-open"]').trigger('click');
    await flushPromises();
    const textarea = batchTextarea();
    textarea.value = 'PB-1';
    textarea.dispatchEvent(new Event('input'));
    await flushPromises();
    (document.body.querySelector('[data-testid="batch-no-run"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(document.body.querySelector('[data-testid="batch-no-error"]')!.textContent).toContain('一次最多查询 200 个批次号');
    expect(listCalls().at(-1)![1].params.batchNos).toBeUndefined();
    wrapper.unmount();
  });
});
