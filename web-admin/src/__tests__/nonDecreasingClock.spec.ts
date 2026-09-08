import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { installNonDecreasingClock, nonDecreasingClock } from './helpers/nonDecreasingClock';

afterEach(() => vi.restoreAllMocks());

describe('test clock under WSL backwards corrections', () => {
  it('reinstalls from the original reader when Vitest reuses a worker', () => {
    let wall = 1000;
    const target = { now: () => wall };
    installNonDecreasingClock(target);
    expect(target.now()).toBe(1000);
    wall = 100;
    installNonDecreasingClock(target);
    expect(target.now()).toBe(100);
  });

  it('allows explicit fake dates to move backwards for temporal business tests', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(2000);
      expect(Date.now()).toBe(2000);
      vi.setSystemTime(1000);
      expect(Date.now()).toBe(1000);
    } finally {
      vi.useRealTimers();
    }
  });

  it('preserves forward wall time and catches up after a backwards correction', () => {
    let wall = 1000;
    const now = nonDecreasingClock(() => wall);
    expect(now()).toBe(1000);
    wall = 1050;
    expect(now()).toBe(1050);
    wall = 100;
    expect(now()).toBe(1050);
    wall = 1060;
    expect(now()).toBe(1060);
  });

  it.each([false, true])('real Vue event control with stabilized clock=%s', async (stable) => {
    let wall = 1000;
    const read = () => wall;
    vi.spyOn(Date, 'now').mockImplementation(stable ? nonDecreasingClock(read) : read);
    await flushPromises();
    const clicked = vi.fn();
    const wrapper = mount(defineComponent({
      render: () => h('button', { onClick: clicked }, 'Save'),
    }));
    await flushPromises();

    wall = 100;
    await wrapper.get('button').trigger('click');
    expect(clicked).toHaveBeenCalledTimes(stable ? 1 : 0);

    wall = 1100;
    await wrapper.get('button').trigger('click');
    expect(clicked).toHaveBeenCalledTimes(stable ? 2 : 1);
  });
});
