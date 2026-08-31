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
          },
          {
            processNodeId: 'p-a2',
            processName: '熟制A',
            fullLabel: '熟制A · 产品甲',
            routeProductTypeIds: ['SKU-A'],
            selected: false,
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

describe('§7 fail closed：没有目标工序不许保存', () => {
  it('一个目标都没有时保存按钮禁用，并给出明确原因', () => {
    const empty = context({
      groups: context().groups.map((group) => ({
        ...group,
        options: group.options.map((option) => ({ ...option, selected: false })),
      })),
      selectedCount: 0,
    });
    const wrapper = mountDrawer({ context: empty });
    expect(wrapper.get('[data-testid="rework-save"]').attributes('disabled')).toBeDefined();
    expect(wrapper.get('[data-testid="rework-no-target"]').text()).toContain('至少选择 1 道');
  });

  it('阳性对照：有目标时保存按钮可用并抛出完整 payload', async () => {
    const wrapper = mountDrawer();
    expect(wrapper.get('[data-testid="rework-save"]').attributes('disabled')).toBeUndefined();
    await wrapper.get('[data-testid="rework-save"]').trigger('click');
    expect(wrapper.emitted('save')?.[0]).toEqual([{
      name: '回料 1', skuId: '', unit: 'kg', targetProcessIds: ['p-a1'],
    }]);
  });

  it('只读用户不能勾选，也不能保存', () => {
    const wrapper = mountDrawer({ canWrite: false });
    expect(wrapper.get('[data-testid="rework-check-p-a1"] input').attributes('disabled')).toBeDefined();
    expect(wrapper.get('[data-testid="rework-save"]').attributes('disabled')).toBeDefined();
  });
});

describe('诚实边界：说「已配置」，不说「已限制投料」', () => {
  it('抽屉写明系统当前不会按它拦截投料', () => {
    const text = mountDrawer().text();
    expect(text).toContain('不会');
    expect(text).toContain('拦截投料');
    // 阴性对照：⛔ 不许出现宣称已经生效的措辞。
    expect(text).not.toContain('已生效');
    expect(text).not.toContain('已限制');
  });
});
