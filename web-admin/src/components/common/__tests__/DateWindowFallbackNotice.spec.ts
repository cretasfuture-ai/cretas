import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import DateWindowFallbackNotice from '../DateWindowFallbackNotice.vue';

describe('DateWindowFallbackNotice', () => {
  it('回退时显示说明与「切回原窗口」，点击触发 restore', async () => {
    const w = mount(DateWindowFallbackNotice, { props: { notice: '最近 7 天没有数据，已显示最近有数据的 11-01 至 11-07', fellBack: true } });
    expect(w.text()).toContain('已显示最近有数据的 11-01 至 11-07');
    await w.get('[data-testid="window-fallback-restore"]').trigger('click');
    expect(w.emitted('restore')).toHaveLength(1);
  });
  it('全无数据：只说明，不给切回按钮', () => {
    const w = mount(DateWindowFallbackNotice, { props: { notice: '本月没有数据，向前 90 天内也没有找到有数据的区间', fellBack: false } });
    expect(w.text()).toContain('也没有找到有数据的区间');
    expect(w.find('[data-testid="window-fallback-restore"]').exists()).toBe(false);
  });
  it('原窗口有数据（说明为空）：不渲染', () => {
    const w = mount(DateWindowFallbackNotice, { props: { notice: '', fellBack: false } });
    expect(w.find('[data-testid="window-fallback-notice"]').exists()).toBe(false);
  });
});
