/**
 * EntityFieldTree 取实体字段 schema 必须走统一请求封装 (api/request 的 get),
 * 由它带上真实 token (localStorage['cretas_access_token'] / 工厂查看凭证)。
 * 旧实现读不存在的 localStorage['access_token'] 手写 Bearer, 读不到只会静默退回静态 schema。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

const mocks = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/api/request', () => ({ get: mocks.get, default: { get: mocks.get } }));

import EntityFieldTree from '../components/EntityFieldTree.vue';

const elTreeStub = defineComponent({
  name: 'ElTree',
  props: { data: { type: Array, default: () => [] } },
  setup: (p) => () => h('ul', (p.data as Array<{ label: string }>).map((n) => h('li', n.label))),
});

describe('EntityFieldTree 鉴权', () => {
  beforeEach(() => {
    mocks.get.mockReset();
    localStorage.clear();
    vi.stubGlobal('fetch', vi.fn(() => { throw new Error('不应使用裸 fetch'); }));
  });

  it('通过统一请求封装取 schema, 且显示后端返回的字段', async () => {
    mocks.get.mockResolvedValue({ success: true, data: [{ label: '后端字段XYZ', path: 'x.y', type: 'string' }] });
    const w = mount(EntityFieldTree, {
      props: { entityType: 'PRINT_SALES_ORDER', factoryId: 'F006' },
      global: { stubs: { 'el-tree': elTreeStub } },
    });
    await flushPromises();
    expect(mocks.get).toHaveBeenCalledTimes(1);
    expect(String(mocks.get.mock.calls[0][0])).toBe('/F006/entity-metadata/PRINT_SALES_ORDER');
    expect(w.text()).toContain('后端字段XYZ');
    expect(fetch).not.toHaveBeenCalled();
  });

  it('请求失败退回静态 schema, 不抛', async () => {
    mocks.get.mockRejectedValue(new Error('404'));
    const w = mount(EntityFieldTree, {
      props: { entityType: 'PRINT_SALES_ORDER', factoryId: 'F006' },
      global: { stubs: { 'el-tree': elTreeStub } },
    });
    await flushPromises();
    expect(w.text()).toContain('订单号');
  });
});
