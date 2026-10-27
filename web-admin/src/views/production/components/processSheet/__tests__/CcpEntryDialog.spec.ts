import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ElementPlus, { ElInputNumber } from 'element-plus';
import CcpEntryDialog from '../CcpEntryDialog.vue';
import { isOutOfLimit, type CcpStepCheckpoint } from '@/api/ccp';

/**
 * 缺口 13: 正式报工前的 CCP 录入弹窗。断言打在 collect() 的返回值和确认按钮可用性上:
 * 限值先显示、少填不能确认、超限当场提示后果、取消返回 null (调用方据此不报工)。
 */
const cps: CcpStepCheckpoint[] = [
  { checkpointId: 1, checkpointCode: 'CCP-T', name: '中心温度', hazardType: 'BIOLOGICAL',
    criticalLimitMin: 75, criticalLimitMax: 100, unit: '℃', monitoringProcedure: '红外测温', correctiveAction: '继续加热' },
  { checkpointId: 2, checkpointCode: 'CCP-H', name: '保温时间', hazardType: 'BIOLOGICAL',
    criticalLimitMin: 15, criticalLimitMax: 60, unit: 'min', monitoringProcedure: null, correctiveAction: null },
];

function confirmBtn(): HTMLButtonElement {
  return document.querySelector('[data-testid="ccp-confirm"]') as HTMLButtonElement;
}

describe('CcpEntryDialog', () => {
  it('限值先显示; 少填不能确认; 超限提示后果; 填全后返回实测值', async () => {
    document.body.innerHTML = '';
    const wrapper = mount(CcpEntryDialog, { global: { plugins: [ElementPlus] }, attachTo: document.body });
    const pending = (wrapper.vm as unknown as { collect: (c: CcpStepCheckpoint[], t: string) => Promise<unknown> })
      .collect(cps, '卤煮 · CLK-W-1');
    await flushPromises();
    expect(document.body.textContent).toContain('75 ~ 100 ℃');
    expect(document.body.textContent).toContain('15 ~ 60 min');
    expect(confirmBtn().disabled).toBe(true);
    expect(document.body.textContent).toContain('还差：中心温度、保温时间');

    const inputs = wrapper.findAllComponents(ElInputNumber);
    inputs[0].vm.$emit('update:modelValue', 70);
    await flushPromises();
    expect(document.querySelector('[data-testid="ccp-out-CCP-T"]'), '70℃ 低于下限应当场提示').toBeTruthy();
    expect(confirmBtn().disabled, '还差保温时间').toBe(true);
    inputs[1].vm.$emit('update:modelValue', 30);
    await flushPromises();
    expect(confirmBtn().disabled).toBe(false);
    confirmBtn().click();
    await expect(pending).resolves.toEqual([
      { checkpointId: 1, measuredValue: 70, notes: null },
      { checkpointId: 2, measuredValue: 30, notes: null },
    ]);
    wrapper.unmount();
  });

  it('取消 → collect 返回 null (调用方不提交报工)', async () => {
    document.body.innerHTML = '';
    const wrapper = mount(CcpEntryDialog, { global: { plugins: [ElementPlus] }, attachTo: document.body });
    const pending = (wrapper.vm as unknown as { collect: (c: CcpStepCheckpoint[], t: string) => Promise<unknown> })
      .collect(cps, '卤煮');
    await flushPromises();
    const back = Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.includes('返回修改'));
    back!.click();
    await expect(pending).resolves.toBeNull();
    wrapper.unmount();
  });

  it('限值判定与后端同口径: 闭区间', () => {
    expect(isOutOfLimit(75, 75, 100)).toBe(false);
    expect(isOutOfLimit(100, 75, 100)).toBe(false);
    expect(isOutOfLimit(74.99, 75, 100)).toBe(true);
    expect(isOutOfLimit(100.01, 75, 100)).toBe(true);
    expect(isOutOfLimit(null, 75, 100)).toBe(false);
  });
});
