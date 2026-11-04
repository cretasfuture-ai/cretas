// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import BlacklakePeriodControls from '../BlacklakePeriodControls.vue';

describe('BlacklakePeriodControls', () => {
  it('can return to the legacy all-history view', async () => {
    const wrapper = mount(BlacklakePeriodControls, { props: { modelValue: { start_date: '2026-11-04', end_date: '2026-11-04', basis: 'completed', kind: 'day' } } });
    await wrapper.get('button.all-history').trigger('click');
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toBeNull();
    expect(wrapper.text()).toContain('全部已采集');
  });

  it('changes basis without changing the selected range', async () => {
    const wrapper = mount(BlacklakePeriodControls, { props: { modelValue: { start_date: '2026-11-01', end_date: '2026-11-04', basis: 'completed', kind: 'custom' } } });
    await wrapper.get('select').setValue('created');
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ start_date: '2026-11-01', end_date: '2026-11-04', basis: 'created', kind: 'custom' });
  });

  it('switches a manually shortened natural month to custom', async () => {
    const wrapper = mount(BlacklakePeriodControls, { props: { modelValue: { start_date: '2026-11-01', end_date: '2026-11-30', basis: 'completed', kind: 'month' } } });
    await wrapper.findAll('input[type="date"]')[1].setValue('2026-11-20');
    expect(wrapper.emitted('change')?.at(-1)?.[0]).toMatchObject({ start_date: '2026-11-01', end_date: '2026-11-20', basis: 'completed', kind: 'custom' });
  });

  it('keeps invalid or reversed dates local and reports the error', async () => {
    const wrapper = mount(BlacklakePeriodControls, { props: { modelValue: { start_date: '2026-11-20', end_date: '2026-11-30', basis: 'completed', kind: 'custom' } } });
    const inputs = wrapper.findAll('input[type="date"]');
    await inputs[1].setValue('');
    expect(wrapper.emitted('change')).toBeUndefined();
    expect(wrapper.get('[role="alert"]').text()).toContain('请填写');
    await inputs[1].setValue('2026-11-10');
    expect(wrapper.emitted('change')).toBeUndefined();
    expect(wrapper.get('[role="alert"]').text()).toContain('不能晚于');
    expect((inputs[0].element as HTMLInputElement).value).toBe('2026-11-20');
  });
});
