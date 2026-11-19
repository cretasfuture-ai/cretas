import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ElementPlus from 'element-plus';
import WorkflowTimeLimitConfigDrawer from '../WorkflowTimeLimitConfigDrawer.vue';
import WorkflowProcessNode from '../WorkflowProcessNode.vue';
import type { ProcessNodeData } from '../types';
import type { TimeLimitPortRow } from '../workflowTimeLimit';

/** 缺口 14: 抽屉的存取 + 节点上的入口与角标。 */
const ports: TimeLimitPortRow[] = [
  { portId: 'in-raw', materialName: '冻猪蹄', raw: true, limit: null },
  { portId: 'in-semi', materialName: '焯水猪蹄', raw: false, limit: { maxMinutes: 90, startEvent: 'OUTPUT_POSTED' } },
];

function mountDrawer(canWrite = true) {
  return mount(WorkflowTimeLimitConfigDrawer, {
    props: { visible: true, canWrite, processName: '卤制', ports },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  });
}

const body = (selector: string) => document.querySelector(selector) as HTMLElement;
const saveButton = () => body('[data-testid="time-limit-save"]') as HTMLButtonElement;

describe('WorkflowTimeLimitConfigDrawer', () => {
  it('打开时只有配过的投入展开; 没改直接保存: 没配的仍是 null, 配过的原样', async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(body('[data-testid="time-limit-port-in-raw"]').querySelector('[data-testid="time-limit-max"]')).toBeNull();
    expect(body('[data-testid="time-limit-port-in-semi"]').querySelector('[data-testid="time-limit-max"]')).not.toBeNull();

    saveButton().click();
    await flushPromises();
    const [limits] = wrapper.emitted('save')![0] as [Record<string, { maxMinutes: number } | null>];
    expect(limits['in-raw']).toBeNull();
    expect(limits['in-semi']).toMatchObject({ maxMinutes: 90, startEvent: 'OUTPUT_POSTED', mixRule: 'EARLIEST' });
    wrapper.unmount();
  });

  it('打开限时但没填时限: 写明原因, 不能保存', async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    (body('[data-testid="time-limit-port-in-raw"]').querySelector('.el-switch') as HTMLElement).click();
    await flushPromises();
    expect(body('[data-testid="time-limit-port-in-raw"]').textContent).toContain('请填时限（分钟）');
    expect(saveButton().disabled).toBe(true);
    wrapper.unmount();
  });

  it('PROTO-Q41 说明按开关写: 默认只警告并留痕, 开了超时拦截才不能领用; 不再无条件说「不能领用」', async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    const scope = body('[data-testid="time-limit-drawer-scope"]').textContent ?? '';
    expect(scope).toContain('默认超时只警告');
    expect(scope).toContain('供复盘');
    // 不露英文配置名, 用业务阈值页上的中文名 (BP 问题 41 复测)
    expect(scope).not.toContain('production.time_limit.block_on_exceed');
    expect(scope).toContain('工序时限超时拦不拦领用');
    // 默认那句只讲警告+留痕, 复检放行/判不合格只属于开了超时拦截之后
    const defaultSentence = scope.slice(0, scope.indexOf('业务阈值'));
    expect(defaultSentence).not.toContain('复检放行');
    expect(scope.indexOf('复检放行')).toBeGreaterThan(scope.indexOf('超时拦截'));
    // 「不能领用」只能出现在「开了超时拦截后」那一句里
    expect(scope.indexOf('不能领用')).toBeGreaterThan(scope.indexOf('超时拦截'));
    wrapper.unmount();
  });

  it('只读: 不能保存', async () => {
    const wrapper = mountDrawer(false);
    await flushPromises();
    expect(saveButton().disabled).toBe(true);
    wrapper.unmount();
  });
});

describe('WorkflowProcessNode 时限入口', () => {
  const data: ProcessNodeData = {
    workProcessId: 'WP-1',
    processName: '卤制',
    inputUnit: 'kg',
    outputUnit: 'kg',
    ports: [
      { id: 'in-1', direction: 'INPUT', unit: 'kg', ordinal: 0, timeLimit: { maxMinutes: 90, startEvent: 'RAW_OUTBOUND' } },
      { id: 'in-2', direction: 'INPUT', unit: 'kg', ordinal: 1 },
      { id: 'out-1', direction: 'OUTPUT', unit: 'kg', ordinal: 0 },
    ],
    conversionRule: { mode: 'ACTUAL_WEIGHT' },
    reportingRequired: true,
  };

  function mountNode(canWrite: boolean) {
    return mount(WorkflowProcessNode, {
      props: { data, canWrite, semiOptions: [], finishedOptions: [] },
      global: { plugins: [ElementPlus], stubs: { Handle: true, WorkflowSkuPicker: true } },
    });
  }

  it('配了时限的投入有角标; 入口写已配几个; 点入口发事件', async () => {
    const wrapper = mountNode(true);
    expect(wrapper.findAll('[data-testid="time-limit-badge"]').map((badge) => badge.text())).toEqual(['限时 1 小时 30 分']);
    expect(wrapper.get('[data-testid="time-limit-summary"]').text()).toBe('已配 1 / 2');
    await wrapper.get('[data-testid="time-limit-config"]').trigger('click');
    expect(wrapper.emitted('configureTimeLimit')).toHaveLength(1);
  });

  it('只读用户也能进去看', () => {
    expect(mountNode(false).get('[data-testid="time-limit-config"]').text()).toBe('查看');
  });
});
