import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowReworkConfigDrawer from '../WorkflowReworkConfigDrawer.vue';
import type { ReworkTargetContext } from '../workflowReworkTargets';

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('element-plus');
  return { ...actual, ElMessageBox: { confirm: vi.fn(() => Promise.resolve('confirm')) } };
});

function context(overrides: Partial<ReworkTargetContext> = {}): ReworkTargetContext {
  return {
    reworkNodeId: 'rework-1',
    sourceProcessNodeId: 'p-a2',
    sourceRouteProductTypeIds: ['SKU-A'],
    groups: [
      {
        routeKey: 'product:SKU-A',
        routeName: '产品甲',
        productTypeId: 'SKU-A',
        isSourceRoute: true,
        isSharedGroup: false,
        options: [
          {
            processNodeId: 'p-a1',
            processName: '腌制A',
            fullLabel: '腌制A · 产品甲',
            routeProductTypeIds: ['SKU-A'],
            selected: true,
            inputRatioPercent: 15,
          },
          {
            processNodeId: 'p-a2',
            processName: '熟制A',
            fullLabel: '熟制A · 产品甲',
            routeProductTypeIds: ['SKU-A'],
            selected: false,
            inputRatioPercent: null,
          },
        ],
      },
      {
        routeKey: 'product:SKU-B',
        routeName: '产品乙',
        productTypeId: 'SKU-B',
        isSourceRoute: false,
        isSharedGroup: false,
        options: [{
          processNodeId: 'p-b1',
          processName: '腌制B',
          fullLabel: '腌制B · 产品乙',
          routeProductTypeIds: ['SKU-B'],
          selected: false,
          inputRatioPercent: null,
        }],
      },
    ],
    selectedCount: 1,
    totalCount: 3,
    ...overrides,
  };
}

function mountDrawer(props: Record<string, unknown> = {}) {
  return mount(WorkflowReworkConfigDrawer, {
    props: {
      visible: true,
      canWrite: true,
      context: context(),
      name: '回料 1',
      skuId: '',
      unit: 'kg',
      semiOptions: [],
      finishedOptions: [],
      unitOptions: ['kg', 'g'],
      lockedTargetId: null,
      ...props,
    },
    global: {
      stubs: {
        'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' },
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<div><slot /></div>' },
        'el-input': { template: '<input />' },
        'el-select': { template: '<select><slot /></select>' },
        'el-option': { template: '<option><slot /></option>' },
        'el-empty': { template: '<div class="empty" />' },
        WorkflowSkuPicker: { template: '<div class="sku-picker" />' },
        'el-checkbox': {
          props: ['modelValue', 'disabled'],
          emits: ['change'],
          template: '<label><input type="checkbox" :checked="modelValue" :disabled="disabled"'
            + ' @change="$emit(\'change\', $event.target.checked)" /><slot /></label>',
        },
        'el-button': {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>',
        },
      },
    },
  });
}

describe('§8 目标工序选择器：分组与计数', () => {
  it('按实际产品线路分组，来源线路排最前，每组显示已选 n / 总数', () => {
    const wrapper = mountDrawer();
    expect(wrapper.text()).toContain('产品甲');
    expect(wrapper.text()).toContain('来源线路');
    expect(wrapper.get('[data-testid="route-count-product:SKU-A"]').text()).toContain('已选 1 / 2');
    expect(wrapper.get('[data-testid="route-count-product:SKU-B"]').text()).toContain('已选 0 / 1');
  });

  it('顶部显示全局已选数', () => {
    expect(mountDrawer().get('[data-testid="rework-total-selected"]').text())
      .toContain('已选 1 / 3');
  });

  it('卡片显示短工序名，完整名称进 tooltip 与 aria-label', () => {
    const card = mountDrawer().get('[data-testid="rework-target-p-a1"]');
    const name = card.get('.target-card__name');
    expect(name.text()).toBe('腌制A');
    expect(name.attributes('title')).toBe('腌制A · 产品甲');
    expect(name.attributes('aria-label')).toBe('腌制A · 产品甲');
  });

  it('⛔ 列表内部没有自己的小滚动盒 —— 由抽屉 body 统一滚动', () => {
    const wrapper = mountDrawer();
    expect(wrapper.html()).not.toMatch(/route-group__grid[^>]*style="[^"]*overflow/);
    expect(wrapper.html()).not.toMatch(/max-height:\s*\d+px/);
  });
});

describe('§8 勾选与定位严格拆开', () => {
  it('🔴 勾 checkbox 只改允许关系，一次都不触发定位', async () => {
    const wrapper = mountDrawer();
    await wrapper.get('[data-testid="rework-check-p-a2"] input').setValue(true);
    // 勾选是抽屉内部草稿状态，不外抛；⛔ 尤其不许顺手 emit 定位。
    expect(wrapper.emitted('toggle-locate')).toBeUndefined();
    expect(wrapper.get('[data-testid="rework-total-selected"]').text()).toContain('已选 2 / 3');
  });

  it('点「定位」抛 toggle-locate，且**不**改变已选关系', async () => {
    const wrapper = mountDrawer();
    await wrapper.get('[data-testid="rework-locate-p-a2"]').trigger('click');
    expect(wrapper.emitted('toggle-locate')).toEqual([['p-a2']]);
    expect(wrapper.get('[data-testid="rework-total-selected"]').text()).toContain('已选 1 / 3');
  });

  it('Enter / Space 可操作定位，状态用 aria-pressed 表达', async () => {
    const wrapper = mountDrawer({ lockedTargetId: 'p-a2' });
    const locate = wrapper.get('[data-testid="rework-locate-p-a2"]');
    expect(locate.attributes('aria-pressed')).toBe('true');
    expect(locate.text()).toBe('定位中');
    await locate.trigger('keydown.enter');
    await locate.trigger('keydown.space');
    expect(wrapper.emitted('toggle-locate')).toEqual([['p-a2'], ['p-a2']]);
    // 阴性对照：没锁定的那一行 aria-pressed 必须是 false。
    expect(wrapper.get('[data-testid="rework-locate-p-a1"]').attributes('aria-pressed')).toBe('false');
  });

  it('悬停目标行抛临时聚焦，移出恢复', async () => {
    const wrapper = mountDrawer();
    await wrapper.get('[data-testid="rework-target-p-a1"]').trigger('mouseenter');
    await wrapper.get('[data-testid="rework-target-p-a1"]').trigger('mouseleave');
    expect(wrapper.emitted('hover-target')).toEqual([['p-a1'], [null]]);
  });
});

describe('⛔ 回料是可选项：一个目标都不配也能保存（owner 2026-09-01 裁定）', () => {
  it('一个目标都没有时保存**仍然可用**，提示只说「不影响保存与发布」', () => {
    // 判据换代：旧断言守的是交接书 §7 的 fail-closed（没有目标不许保存）。
    // owner 推翻了它 —— 回料是可选能力，不许拦住主线。
    const empty = context({
      groups: context().groups.map((group) => ({
        ...group,
        options: group.options.map((option) => ({ ...option, selected: false, inputRatioPercent: null })),
      })),
      selectedCount: 0,
    });
    const wrapper = mountDrawer({ context: empty });
    expect(wrapper.get('[data-testid="rework-save"]').attributes('disabled')).toBeUndefined();
    const hint = wrapper.get('[data-testid="rework-no-target"]').text();
    expect(hint).toContain('不影响保存与发布');
    // 阴性对照：⛔ 不许再出现任何拦截口径的措辞
    expect(hint).not.toContain('拦下');
    expect(hint).not.toContain('至少选择');
  });

  it('清空目标后保存，抛出的 targets 是空数组（不是被吞掉的一次点击）', async () => {
    const empty = context({
      groups: context().groups.map((group) => ({
        ...group,
        options: group.options.map((option) => ({ ...option, selected: false, inputRatioPercent: null })),
      })),
      selectedCount: 0,
    });
    const wrapper = mountDrawer({ context: empty });
    await wrapper.get('[data-testid="rework-save"]').trigger('click');
    expect(wrapper.emitted('save')?.[0]).toEqual([{
      name: '回料 1', skuId: '', unit: 'kg', targets: [],
    }]);
  });

  it('有目标时抛出完整 payload，带每一对各自的投入比例', async () => {
    const wrapper = mountDrawer();
    await wrapper.get('[data-testid="rework-save"]').trigger('click');
    expect(wrapper.emitted('save')?.[0]).toEqual([{
      name: '回料 1', skuId: '', unit: 'kg',
      targets: [{ processNodeId: 'p-a1', inputRatioPercent: 15 }],
    }]);
  });

  it('只读用户不能勾选，也不能保存', () => {
    const wrapper = mountDrawer({ canWrite: false });
    expect(wrapper.get('[data-testid="rework-check-p-a1"] input').attributes('disabled')).toBeDefined();
    expect(wrapper.get('[data-testid="rework-save"]').attributes('disabled')).toBeDefined();
  });
});

describe('比例是配方值，不是上限', () => {
  it('已勾选的目标带一个比例输入框，未勾选的没有', () => {
    const wrapper = mountDrawer();
    expect(wrapper.find('[data-testid="rework-ratio-p-a1"]').exists()).toBe(true);
    expect((wrapper.get('[data-testid="rework-ratio-p-a1"]').element as HTMLInputElement).value).toBe('15');
    // 阴性对照：没勾的那道工序不该出现比例框
    expect(wrapper.find('[data-testid="rework-ratio-p-a2"]').exists()).toBe(false);
  });

  it('改比例只改这一对，保存时带出去', async () => {
    const wrapper = mountDrawer();
    const input = wrapper.get('[data-testid="rework-ratio-p-a1"]');
    (input.element as HTMLInputElement).value = '22.5';
    await input.trigger('change');
    await wrapper.get('[data-testid="rework-save"]').trigger('click');
    expect(wrapper.emitted('save')?.[0]).toEqual([{
      name: '回料 1', skuId: '', unit: 'kg',
      targets: [{ processNodeId: 'p-a1', inputRatioPercent: 22.5 }],
    }]);
  });

  it('超范围的输入被夹回 0–100', async () => {
    const wrapper = mountDrawer();
    const input = wrapper.get('[data-testid="rework-ratio-p-a1"]');
    (input.element as HTMLInputElement).value = '250';
    await input.trigger('change');
    await wrapper.get('[data-testid="rework-save"]').trigger('click');
    expect((wrapper.emitted('save')?.[0]?.[0] as { targets: Array<{ inputRatioPercent: number }> })
      .targets[0].inputRatioPercent).toBe(100);
  });

  it('🔴 措辞是「配方值/按它算」，⛔ 不许出现「上限 / 最多 / 不超过」', () => {
    // owner 订正：这个比例不是天花板，是「就按这个百分比投」的 BOM 值。
    // 说成上限会让人以为系统在卡一个边界，而它其实在声明一个配方。
    const text = mountDrawer().text();
    expect(text).toContain('配方值');
    expect(text).toContain('按这个比例算');
    expect(text).not.toContain('上限');
    expect(text).not.toContain('最多');
    expect(text).not.toContain('不超过');
  });

  it('诚实边界仍在：说明系统还不会自动配料', () => {
    const text = mountDrawer().text();
    expect(text).toContain('不会');
    expect(text).toContain('自动配料');
    expect(text).not.toContain('已生效');
  });
});
