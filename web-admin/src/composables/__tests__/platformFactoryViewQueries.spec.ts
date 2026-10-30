import { effectScope, ref } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import { factoryViewContext, resetFactoryViewContext } from '@/store/modules/platformFactoryContext';

const mocks = vi.hoisted(() => ({ summary: vi.fn(), post: vi.fn() }));
vi.mock('@/api/listSummary', () => ({ fetchListSummary: mocks.summary }));
vi.mock('@/api/request', () => ({ post: mocks.post }));
vi.mock('../useFactoryId', () => ({ useFactoryId: () => ref('F001') }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: true, canViewPurchasePrice: true }) }));

import { useListSummary } from '../useListSummary';
import { useLinkChipCounts } from '../useLinkChipCounts';
import TableFooter from '@/components/list/TableFooter.vue';
import LinkChipCell from '@/components/list/LinkChipCell.vue';

describe('optional factory-user queries in platform view', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    factoryViewContext.value = { factoryId: 'F001', factoryName: '厂', accessToken: 'view', readOnly: true, expiresIn: 300, expiresAt: Date.now() + 300000 };
  });
  afterEach(() => resetFactoryViewContext());

  it('does not query factory-user-only totals or attachment counts', async () => {
    const scope = effectScope();
    const summary = scope.run(() => useListSummary('salesOrder', ref({})))!;
    const chips = useLinkChipCounts(ref('F001'), 'SALES_ORDER');
    await summary.refresh();
    await chips.fetchLinkChipCounts(['1']);
    expect(mocks.summary).not.toHaveBeenCalled();
    expect(mocks.post).not.toHaveBeenCalled();
    expect(summary.summary.value).toBeNull();
    expect(summary.error.value?.message).toContain('暂不提供');
    scope.stop();
  });

  it('labels unavailable metadata rather than displaying invented totals', () => {
    const footer = shallowMount(TableFooter, { props: { stats: [{ label: '金额', value: 999, format: 'number' }] } });
    expect(footer.text()).toContain('只读查看暂不提供列表合计');
    expect(footer.text()).not.toContain('999');
    expect(footer.find('.actions').exists()).toBe(false);
    const chips = shallowMount(LinkChipCell, { props: { counts: { fileCount: 999, imageCount: 0, contractCount: 0 } } });
    expect(chips.text()).toBe('未加载');
    footer.unmount(); chips.unmount();
  });
});
