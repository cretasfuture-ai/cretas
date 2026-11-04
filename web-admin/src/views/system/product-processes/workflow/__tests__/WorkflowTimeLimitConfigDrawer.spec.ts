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
