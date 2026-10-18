import { describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import PhotoRegionEditor from '../PhotoRegionEditor.vue';

describe('PhotoRegionEditor', () => {
  async function editable() {
    const wrapper = mount(PhotoRegionEditor, { props: { imageSrc: 'data:image/png;base64,a', boxes: [{ label: 'seal', x0: .1, y0: .2, x1: .5, y1: .6 }], labels: [{ id: 'seal', name: '封口' }, { id: 'date', name: '日期' }] } });
    const svg = wrapper.get('svg').element as SVGElement;
    Object.defineProperty(svg, 'getBoundingClientRect', { value: () => new DOMRect(0, 0, 1000, 500) });
    function pointer(type: string, x: number, y: number) {
      const event = new Event(type, { bubbles: true }); Object.defineProperties(event, { clientX: { value: x }, clientY: { value: y }, pointerId: { value: 5 } }); svg.dispatchEvent(event);
    }
    async function acceptEmission() { await wrapper.setProps({ boxes: wrapper.emitted('update:boxes')!.at(-1)![0] as Array<{ label: string; x0: number; y0: number; x1: number; y1: number }> }); }
    async function button(name: string) { const target = wrapper.findAll('button').find(item => item.text() === name); expect(target).toBeDefined(); await target!.trigger('click'); }
    return { wrapper, pointer, button, acceptEmission };
  }

  it('moves, resizes, deletes and undoes a selected label without losing its class', async () => {
    const { wrapper, pointer, button, acceptEmission } = await editable();
    pointer('pointerdown', 300, 200); pointer('pointerup', 400, 250); await acceptEmission();
    expect(wrapper.props('boxes')[0]!.label).toBe('seal');
    expect(wrapper.props('boxes')[0]!.x0).toBeCloseTo(.2, 8);
    expect(wrapper.props('boxes')[0]!.y0).toBeCloseTo(.3, 8);
    pointer('pointerdown', 200, 150); pointer('pointerup', 100, 100); await acceptEmission();
    expect(wrapper.props('boxes')[0]).toMatchObject({ label: 'seal', x0: .1, y0: .2 });
    await button('删除选中框'); await acceptEmission(); expect(wrapper.props('boxes')).toEqual([]);
    await button('撤销'); await acceptEmission(); expect(wrapper.props('boxes')).toHaveLength(1);
  });

  it('preserves the old box while redrawing, and changing the next class never relabels existing boxes', async () => {
    const { wrapper, pointer, button, acceptEmission } = await editable();
    await wrapper.get('.region-chip').trigger('click'); await button('重新拉框');
    pointer('pointerdown', 700, 300); pointer('pointerup', 701, 301);
    expect(wrapper.emitted('update:boxes')).toBeUndefined();
    await button('重新拉框'); pointer('pointerdown', 600, 300); pointer('pointerup', 900, 450); await acceptEmission();
    expect(wrapper.props('boxes')).toEqual([{ label: 'seal', x0: .6, y0: .6, x1: .9, y1: .9 }]);
    await wrapper.get('select').setValue('date');
    expect(wrapper.props('boxes')[0]!.label).toBe('seal');
    expect(wrapper.emitted('update:boxes')).toHaveLength(1);
    await wrapper.setProps({ activeLabel: 'date' });
    pointer('pointerdown', 100, 50); pointer('pointerup', 300, 150); await acceptEmission();
    expect(wrapper.props('boxes').map(box => box.label)).toEqual(['seal', 'date']);
  });

  it('allows zoom while frozen but blocks every edit, and clears history between photos', async () => {
    const { wrapper, pointer, button, acceptEmission } = await editable();
    pointer('pointerdown', 300, 200); pointer('pointerup', 400, 250); await acceptEmission();
    await wrapper.setProps({ imageSrc: 'data:image/png;base64,b' });
    expect(wrapper.findAll('button').find(item => item.text() === '撤销')!.attributes('disabled')).toBeDefined();
    await wrapper.setProps({ disabled: true }); await button('放大'); await flushPromises();
    expect(wrapper.text()).toContain('125%');
    pointer('pointerdown', 300, 200); pointer('pointerup', 400, 250);
    expect(wrapper.emitted('update:boxes')).toHaveLength(1);
    expect(wrapper.text()).toContain('当前照片只读');
  });
  it('emits normalized boxes and rejects a tiny drag', async () => {
    const wrapper = mount(PhotoRegionEditor, { props: { imageSrc: 'data:image/png;base64,a', boxes: [], labels: [{ id: '0', name: '封口' }] } });
    const svg = wrapper.get('svg').element as SVGElement;
    Object.defineProperty(svg, 'getBoundingClientRect', { value: () => new DOMRect(0, 0, 100, 100) });
    const fire = (type: string, x: number, y: number, pointerId: number) => {
      const event = new Event(type, { bubbles: true });
      Object.defineProperties(event, { clientX: { value: x }, clientY: { value: y }, pointerId: { value: pointerId } });
      svg.dispatchEvent(event);
    };
    fire('pointerdown', 10, 20, 1); fire('pointermove', 80, 70, 1); fire('pointerup', 80, 70, 1);
    expect(wrapper.emitted('update:boxes')?.at(-1)?.[0]).toEqual([{ label: '0', x0: .1, y0: .2, x1: .8, y1: .7 }]);
    fire('pointerdown', 10, 20, 2); fire('pointerup', 11, 21, 2);
    expect(wrapper.emitted('update:boxes')).toHaveLength(1);
  });

  it('keeps normalized coordinates aligned for a non-square image and drops a draw disabled mid-gesture', async () => {
    const wrapper = mount(PhotoRegionEditor, { props: { imageSrc: 'data:image/png;base64,a', boxes: [], labels: [{ id: 'seal', name: '封口' }] } });
    const svg = wrapper.get('svg').element as SVGElement;
    Object.defineProperty(svg, 'getBoundingClientRect', { value: () => new DOMRect(10, 20, 200, 100) });
    const fire = (type: string, x: number, y: number) => {
      const event = new Event(type, { bubbles: true });
      Object.defineProperties(event, { clientX: { value: x }, clientY: { value: y }, pointerId: { value: 3 } });
      svg.dispatchEvent(event);
    };
    fire('pointerdown', 30, 30); fire('pointermove', 190, 90); fire('pointerup', 190, 90);
    expect(wrapper.emitted('update:boxes')?.at(-1)?.[0]).toEqual([{ label: 'seal', x0: 0.1, y0: 0.1, x1: 0.9, y1: 0.7 }]);
    fire('pointerdown', 30, 30); await wrapper.setProps({ disabled: true }); fire('pointerup', 190, 90);
    expect(wrapper.emitted('update:boxes')).toHaveLength(1);
  });
});
