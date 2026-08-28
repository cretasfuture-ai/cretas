/**
 * 平面图叠加层 —— 「接上没有」这一层。
 *
 * 🔴 为什么必须有这个文件: warehouseFloorPlan.spec.ts 验的是几何算术,
 * 它对「画布上按下鼠标时到底有没有人调这些函数」是**盲的**。
 * 本仓 2026-08-26 记过一次同形的教训: 想验货架拖拽的防抖, 浏览器自动化
 * 三次都驱动不了 Vue Flow 的 d3-drag, 「节点纹丝不动」既不能证明生效也不能
 * 证明没生效 —— 那是仪器够不着被测行为。
 *
 * ⇒ 这里的指针交互是**我们自己写的 pointerdown/pointermove/pointerup**,
 *   合成事件打得进去, 所以「拖了会不会动」可以在 jsdom 里确定性地验。
 *   这也是新画的东西不走 Vue Flow 节点、走自绘 SVG 的一个附带好处。
 */
import { nextTick } from 'vue';
import { describe, expect, it } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import type { WarehouseFloor, WarehouseZone } from '@/api/warehouseLayout';
import WarehouseFloorOverlay from './WarehouseFloorOverlay.vue';
import type { MapTool } from './warehouseLayoutModel';

const zones: WarehouseZone[] = [
  { id: 'cold', name: '冷冻库', prefix: 'LD', color: '#dbeafe' },
  { id: 'dry', name: '常温库', prefix: 'CW', color: '#dcfce7' },
];

function floorWith(overrides: Partial<WarehouseFloor> = {}): WarehouseFloor {
  return {
    id: 'f1',
    name: '1 层',
    racks: [],
    zoneShapes: [{ zoneId: 'cold', x: 100, y: 100, width: 200, height: 150 }],
    annotations: [],
    ...overrides,
  };
}

function mountOverlay(props: Partial<Record<string, unknown>> = {}) {
  return mount(WarehouseFloorOverlay, {
    props: {
      floor: floorWith(),
      zones,
      tool: 'select' as MapTool,
      interactive: true,
      selection: '',
      activeZoneId: 'cold',
      strokeColor: '#2563eb',
      showAnnotations: true,
      ...props,
    },
  });
}

/**
 * 按下指针。
 *
 * ⚠️ 不能用 VTU 的 `.trigger('pointerdown', { clientX })`: 它给 pointerdown 造的是
 * `MouseEvent`, 然后往上**赋值** clientX —— 而 jsdom 的 MouseEvent.clientX 只有
 * getter, 当场 TypeError。⇒ 自己造一个真的 PointerEvent 再 dispatch。
 */
async function pointerDown(wrapper: VueWrapper, selector: string, clientX: number, clientY: number) {
  const found = wrapper.find(selector);
  expect(found.exists(), `找不到 ${selector} —— 仪器够不着被测元素, 后面的读数没有意义`).toBe(true);
  found.element.dispatchEvent(new PointerEvent('pointerdown', { clientX, clientY, bubbles: true, cancelable: true }));
  await nextTick();
}

/** 指针在 window 上移动/抬起 —— 组件在 pointerdown 之后就把监听挂到 window 上了。 */
function pointerMove(clientX: number, clientY: number) {
  window.dispatchEvent(new PointerEvent('pointermove', { clientX, clientY, bubbles: true }));
}
function pointerUp() {
  window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
}

describe('区域几何：能拖动、能缩放', () => {
  it('拖区域边框把手 → 位置跟着走，尺寸一个像素不变', async () => {
    const wrapper = mountOverlay();
    await pointerDown(wrapper, '[data-zone-grip="cold"]', 150, 150);
    pointerMove(190, 130);
    pointerUp();

    const emitted = wrapper.emitted('zone-shape');
    expect(emitted, '拖了边框却一个 zone-shape 都没发 —— 交互没接上').toBeTruthy();
    expect(emitted!.at(-1)![0]).toEqual({ zoneId: 'cold', x: 140, y: 80, width: 200, height: 150 });
    // 选中标记也要跟着变，否则缩放控制点永远不出现
    expect(wrapper.emitted('select')!.at(-1)![0]).toBe('zone:cold');
  });

  it('拖右下角控制点 → 尺寸变，左上角不动', async () => {
    const wrapper = mountOverlay({ selection: 'zone:cold' });
    await pointerDown(wrapper, '[data-zone-handle="cold:se"]', 300, 250);
    pointerMove(360, 300);
    pointerUp();

    expect(wrapper.emitted('zone-shape')!.at(-1)![0])
      .toEqual({ zoneId: 'cold', x: 100, y: 100, width: 260, height: 200 });
  });

  it('拖左上角控制点往回拖过头 → 停在最小尺寸，⛔ 不翻面', async () => {
    const wrapper = mountOverlay({ selection: 'zone:cold' });
    await pointerDown(wrapper, '[data-zone-handle="cold:nw"]', 100, 100);
    pointerMove(900, 900);
    pointerUp();

    const shape = wrapper.emitted('zone-shape')!.at(-1)![0] as { x: number; width: number };
    expect(shape.width).toBeGreaterThan(0);
    expect(shape.x + shape.width).toBe(300); // 右边界纹丝不动
  });
});

describe('缩略图状态下这一层完全不吃指针事件（现有画布交互零退化）', () => {
  it('interactive=false 时拖区域边框什么都不会发生', async () => {
    const wrapper = mountOverlay({ interactive: false });
    await pointerDown(wrapper, '[data-zone-grip="cold"]', 150, 150);
    pointerMove(190, 130);
    pointerUp();
    expect(wrapper.emitted('zone-shape')).toBeUndefined();
  });

  it('底层永远是 pointer-events:none —— 区域底色不许挡住货架拖拽', () => {
    const wrapper = mountOverlay();
    const base = wrapper.find('[data-testid="floor-overlay-base"]');
    expect(base.exists()).toBe(true);
    // 底层里连一个可命中的元素都不该有
    expect(base.element.querySelectorAll('[style*="pointer-events: auto"]')).toHaveLength(0);
  });

  it('命中开关跟着 interactive/tool 走：选择态可点，落笔态与只读态都不可点', async () => {
    const wrapper = mountOverlay();
    const grip = () => wrapper.find('[data-zone-grip="cold"]').attributes('style') ?? '';
    const surface = () => wrapper.find('[data-testid="floor-overlay-top"]').attributes('style') ?? '';

    expect(grip()).toContain('pointer-events: stroke');
    expect(surface()).toContain('pointer-events: none');

    // 落笔态：整块画布变成落笔面，已有图形让开
    await wrapper.setProps({ tool: 'freehand' as MapTool });
    expect(grip()).toContain('pointer-events: none');
    expect(surface()).toContain('pointer-events: auto');

    // 只读：两个都不接
    await wrapper.setProps({ tool: 'select' as MapTool, interactive: false });
    expect(grip()).toContain('pointer-events: none');
    expect(surface()).toContain('pointer-events: none');
  });
});

describe('全屏后可以画：多种形状 + 文字标注', () => {
  it('区域笔拖出一块面积 → 落到结构层（zone-shape），⛔ 不是标注', async () => {
    const wrapper = mountOverlay({ tool: 'zone' as MapTool });
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 400, 300);
    pointerMove(600, 460);
    pointerUp();

    expect(wrapper.emitted('zone-shape')!.at(-1)![0])
      .toEqual({ zoneId: 'cold', x: 400, y: 300, width: 200, height: 160 });
    expect(wrapper.emitted('annotation-add')).toBeUndefined();
  });

  it('矩形笔拖出一个图形 → 落到标注层（annotation-add），⛔ 不是区域', async () => {
    const wrapper = mountOverlay({ tool: 'rect' as MapTool });
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 10, 20);
    pointerMove(110, 90);
    pointerUp();

    const annotation = wrapper.emitted('annotation-add')!.at(-1)![0] as Record<string, unknown>;
    expect(annotation).toMatchObject({ kind: 'rect', x: 10, y: 20, width: 100, height: 70, color: '#2563eb' });
    expect(annotation.zoneId).toBeUndefined(); // 标注层不带 zoneId，业务查询永远碰不到它
    expect(wrapper.emitted('zone-shape')).toBeUndefined();
  });

  it('圆形笔画出来的是 ellipse', async () => {
    const wrapper = mountOverlay({ tool: 'ellipse' as MapTool });
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 0, 0);
    pointerMove(80, 60);
    pointerUp();
    expect((wrapper.emitted('annotation-add')!.at(-1)![0] as { kind: string }).kind).toBe('ellipse');
  });

  it('自由笔一路涂过去，点都记进相对坐标里', async () => {
    const wrapper = mountOverlay({ tool: 'freehand' as MapTool });
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 50, 50);
    pointerMove(70, 60);
    pointerMove(90, 90);
    pointerMove(120, 70);
    pointerUp();

    const annotation = wrapper.emitted('annotation-add')!.at(-1)![0] as { kind: string; x: number; y: number; points: number[] };
    expect(annotation.kind).toBe('freehand');
    expect(annotation.x).toBe(50);
    expect(annotation.y).toBe(50);
    expect(annotation.points).toEqual([0, 0, 20, 10, 40, 40, 70, 20]);
  });

  it('多边形逐点单击，双击收笔', async () => {
    const wrapper = mountOverlay({ tool: 'polygon' as MapTool });
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 10, 10);
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 110, 10);
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 110, 90);
    // 还没收笔：一条标注都不该落地
    expect(wrapper.emitted('annotation-add')).toBeUndefined();

    await wrapper.find('[data-testid="floor-overlay-top"]').trigger('dblclick');
    const annotation = wrapper.emitted('annotation-add')!.at(-1)![0] as { kind: string; points: number[] };
    expect(annotation.kind).toBe('polygon');
    expect(annotation.points).toEqual([0, 0, 100, 0, 100, 80]);
  });

  it('文字笔点一下 → 先问文字再落图（text-request），⛔ 不直接落一个空标注', async () => {
    const wrapper = mountOverlay({ tool: 'text' as MapTool });
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 200, 140);
    pointerUp();

    expect(wrapper.emitted('annotation-add')).toBeUndefined();
    expect(wrapper.emitted('text-request')!.at(-1)![0]).toEqual({ x: 200, y: 140, width: 168, height: 40 });
  });

  it('误点一下不会在图上留一个小方块（图形必须真的拖出面积）', async () => {
    const wrapper = mountOverlay({ tool: 'rect' as MapTool });
    await pointerDown(wrapper, '[data-testid="floor-overlay-top"]', 40, 40);
    pointerMove(41, 41); // 抖了 1px，不算拖
    pointerUp();
    expect(wrapper.emitted('annotation-add')).toBeUndefined();
  });
});

describe('标注可以拖动，并且能被藏起来', () => {
  const annotated = () => floorWith({
    annotations: [{ id: 'a1', kind: 'rect', x: 10, y: 10, width: 100, height: 60, color: '#d03050' }],
  });

  it('拖标注 → 位置跟着走', async () => {
    const wrapper = mountOverlay({ floor: annotated() });
    await pointerDown(wrapper, '[data-annotation="a1"]', 50, 50);
    pointerMove(80, 90);
    pointerUp();
    expect(wrapper.emitted('annotation-change')!.at(-1)![0]).toMatchObject({ id: 'a1', x: 40, y: 50, width: 100, height: 60 });
  });

  it('关掉「显示标注」之后它就不在图上了（标注挡住货架时的退路）', async () => {
    const wrapper = mountOverlay({ floor: annotated() });
    expect(wrapper.find('[data-annotation="a1"]').exists()).toBe(true);
    await wrapper.setProps({ showAnnotations: false });
    expect(wrapper.find('[data-annotation="a1"]').exists()).toBe(false);
  });
});

describe('区域被删掉之后遗留的孤块要说实话', () => {
  it('几何指向一个不存在的区域时，标签写「已删除的区域」而不是留白', () => {
    const wrapper = mountOverlay({
      floor: floorWith({ zoneShapes: [{ zoneId: 'ghost', x: 0, y: 0, width: 80, height: 60 }] }),
    });
    expect(wrapper.text()).toContain('已删除的区域');
    // 阳性对照：正常区域显示的是它自己的名字
    const healthy = mountOverlay();
    expect(healthy.text()).toContain('LD · 冷冻库');
  });
});
