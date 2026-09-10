import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import OrderDeliveryDatePicker from '../OrderDeliveryDatePicker.vue';
import { parseOrderDeliveryDate } from '../orderDeliveryDate';

describe('order delivery calendar date input', () => {
  it.each([
    ['20260820', '2026-08-20'], ['20240229', '2024-02-29'],
    ['20000229', '2000-02-29'], ['2026-12-31', '2026-12-31'], ['', ''],
    ['20260229', null], ['19000229', null], ['20260431', null],
    ['2026820', null], ['202608200', null], ['20261301', null],
    ['20260001', null], ['20260100', null], ['00000101', null],
  ])('parses %s without local-time conversion', (input, output) => {
    expect(parseOrderDeliveryDate(input)).toBe(output);
  });

  it('normalizes typed values, rejects invalid edits, and permits clearing', async () => {
    const wrapper = mount(OrderDeliveryDatePicker, {
      props: { modelValue: '2026-08-19', 'onUpdate:modelValue': value => wrapper.setProps({ modelValue: value }) },
      global: { plugins: [ElementPlus] },
    });
    const input = wrapper.get('input[aria-label="交货日期"]');
    await input.setValue('20260820');
    expect(wrapper.props('modelValue')).toBe('2026-08-20');
    expect((input.element as HTMLInputElement).value).toBe('2026-08-20');
    await input.setValue('20260229');
    expect(wrapper.props('modelValue')).toBe('2026-08-20');
    expect(wrapper.get('[role="alert"]').text()).toContain('日期无效');
    await input.setValue('');
    expect(wrapper.props('modelValue')).toBe('');
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
