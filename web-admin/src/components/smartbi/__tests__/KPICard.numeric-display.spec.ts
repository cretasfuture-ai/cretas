import { nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import KPICard from '../KPICard.vue';

const mountCard = (props: Record<string, unknown>) => mount(KPICard, {
  props: { title: '成本', ...props },
  global: { stubs: { 'el-icon': true } },
});

describe('KPICard numeric display contract', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it.each([
    ['2,500', '2,500'],
    ['1,600', '1,600'],
    ['50%', '50%'],
    ['N/A', 'N/A'],
  ])('keeps custom value %s intact', async (value, expected) => {
    const wrapper = mountCard({ value, format: 'custom' });
    await nextTick();
    expect(wrapper.find('.kpi-value').text()).toContain(expected);
  });

  it.each([
    [2500, '2,500'],
    [-12.5, '-13'],
    [0, '0'],
    [12.345, '12'],
  ])('animates finite numeric value %s to %s', async (value, expected) => {
    const wrapper = mountCard({ value, format: 'number' });
    vi.advanceTimersByTime(1000);
    await nextTick();
    expect(wrapper.find('.kpi-value').text()).toContain(expected);
  });

  it('honors numeric precision', async () => {
    const wrapper = mountCard({ value: 12.345, format: 'number', precision: 2 });
    vi.advanceTimersByTime(1000);
    await nextTick();
    expect(wrapper.find('.kpi-value').text()).toContain('12.35');
  });

  it.each(['50%', '10kg', '1,234abc', '', 'Infinity'])('rejects non-strict numeric text %s', async (value) => {
    const wrapper = mountCard({ value, format: 'number' });
    vi.advanceTimersByTime(1000);
    await nextTick();
    expect(wrapper.find('.kpi-value').text()).toContain(value.trim() || '—');
  });

  it.each([null, undefined, Number.NaN, Number.POSITIVE_INFINITY])('does not invent a value for %s', async (value) => {
    const wrapper = mountCard({ value });
    vi.advanceTimersByTime(1000);
    await nextTick();
    expect(wrapper.find('.kpi-value').text()).toContain('—');
  });

  it.each(['2,500', '1,600'])('keeps custom formatted value after animation window: %s', async (value) => {
    const wrapper = mountCard({ value, format: 'custom' });
    vi.advanceTimersByTime(1000);
    await nextTick();
    expect(wrapper.find('.kpi-value').text()).toContain(value);
  });

  it('does not retain an old animated value after an invalid update', async () => {
    const wrapper = mountCard({ value: 2500, format: 'number' });
    vi.advanceTimersByTime(1000);
    await nextTick();
    await wrapper.setProps({ value: 'N/A' });
    expect(wrapper.find('.kpi-value').text()).toContain('N/A');
  });

  it('uses the same strict number contract for the legacy progress bar', async () => {
    const valid = mountCard({ value: 2500, targetValue: 3000, showProgress: true });
    expect(valid.find('.progress-fill').attributes('style')).toContain('width: 83.33333333333334%');

    const formatted = mountCard({ value: '2,500', targetValue: 3000, showProgress: true });
    expect(formatted.find('.progress-fill').attributes('style')).toContain('width: 0%');

    const invalid = mountCard({ value: '50%', targetValue: 100, showProgress: true });
    await nextTick();
    expect(invalid.find('.progress-fill').attributes('style')).toContain('width: 0%');
  });
});
