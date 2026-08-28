/**
 * 仓库平面图设计器 —— 几何与归一化（2026-08-28 新增）。
 *
 * 判据分两层，⛔ 不要混为一谈：
 *   · 本文件验的是**算术**（纯函数，确定性，不碰 DOM）；
 *   · 「这些函数真的被画布上的指针操作调用」由 WarehouseFloorOverlay.spec.ts
 *     用真实 DOM 事件驱动来验。
 * 只做第一层就是「机制在、没接上」的经典形态：算术全对，画布上一动不动。
 */
import { describe, expect, it } from 'vitest';
import type { WarehouseAnnotation, WarehouseFloor, WarehouseZone } from '@/api/warehouseLayout';
import {
  annotationFromPolyline, annotationPathData, appendFreehandPoint, boundsOfPoints, clampRect,
  defaultZoneShapeFor, floorAnnotations, floorZoneShapes, handleAnchor, MAX_FREEHAND_POINTS,
  MIN_SHAPE_SIZE, minSizeForKind, moveRect, normalizeAnnotation, normalizeAnnotations,
  normalizeWarehouseLayout, normalizeZoneShapes, parseMapSelection, rectFromDrag, resizeRect,
  screenToFlowPoint, thinPoints, toPointPairs, upsertZoneShape, zoneShapeFromPercent, zoneShapeOf,
  zonesMissingShapeOnFloor,
} from './warehouseLayoutModel';

describe('结构层与标注层是两份数据，⛔ 不许互相渗透', () => {
  it('normalizeZoneShapes 丢掉没有 zoneId 的东西 —— 自由图形进不了结构层', () => {
    // 一条自由笔画长这样：有 id/kind/points，没有 zoneId。
    const freehandLookalike = { id: 'a1', kind: 'freehand', x: 1, y: 2, width: 3, height: 4, points: [0, 0, 3, 4] };
    expect(normalizeZoneShapes([freehandLookalike])).toEqual([]);
    // 阳性对照：真正的区域几何必须留下来，否则上面那条是「什么都过不了」的假绿
    expect(normalizeZoneShapes([{ zoneId: 'cold', x: 1, y: 2, width: 30, height: 40 }]))
      .toEqual([{ zoneId: 'cold', x: 1, y: 2, width: 30, height: 40 }]);
  });

  it('normalizeAnnotations 丢掉没有 id/kind 的东西 —— 区域几何进不了标注层', () => {
    expect(normalizeAnnotations([{ zoneId: 'cold', x: 1, y: 2, width: 30, height: 40 }])).toEqual([]);
    expect(normalizeAnnotations([{ id: 'a1', kind: 'rect', x: 1, y: 2, width: 30, height: 40 }])).toHaveLength(1);
  });

  it('同一层同一个区域只有一块面积：再画一次是改，不是加', () => {
    const first = { zoneId: 'cold', x: 0, y: 0, width: 100, height: 100 };
    const moved = { zoneId: 'cold', x: 300, y: 40, width: 120, height: 90 };
    expect(upsertZoneShape([first], moved)).toEqual([moved]);
    // 阴性对照：不同区域是**加**
    expect(upsertZoneShape([first], { ...moved, zoneId: 'dry' })).toHaveLength(2);
    // 归一化侧同口径：重复的 zoneId 只留第一块
    expect(normalizeZoneShapes([first, moved])).toEqual([first]);
  });

  it('区域几何挂在楼层上 —— 同一个区域可以只出现在某一层', () => {
    // 生产实测：六膳门 LIUSHANMEN 3 层楼 6 个区域，并不是每层都有全部区域。
    const zones: WarehouseZone[] = [
      { id: 'cold', name: '冷冻库', prefix: 'LD' },
      { id: 'dry', name: '常温库', prefix: 'CW' },
    ];
    const first: WarehouseFloor = {
      id: 'f1', name: '1 层', racks: [],
      zoneShapes: [{ zoneId: 'cold', x: 0, y: 0, width: 100, height: 80 }],
    };
    const second: WarehouseFloor = { id: 'f2', name: '2 层', racks: [], zoneShapes: [] };
    expect(zonesMissingShapeOnFloor(zones, first).map((zone) => zone.id)).toEqual(['dry']);
    expect(zonesMissingShapeOnFloor(zones, second).map((zone) => zone.id)).toEqual(['cold', 'dry']);
    expect(zoneShapeOf(first, 'cold')?.width).toBe(100);
    expect(zoneShapeOf(second, 'cold')).toBeUndefined();
  });

  it('normalizeWarehouseLayout 把两层都带过来，并且各自过一遍筛子', () => {
    const layout = normalizeWarehouseLayout({
      zones: [{ id: 'cold', name: '冷冻库', prefix: 'LD' }],
      locations: [],
      floors: [{
        id: 'f1',
        name: '1 层',
        racks: [],
        zoneShapes: [
          { zoneId: 'cold', x: 5, y: 6, width: 70, height: 60 },
          { x: 1, y: 1, width: 1, height: 1 },
        ],
        annotations: [
          { id: 'a1', kind: 'text', x: 3, y: 4, width: 100, height: 30, text: '卸货口' },
          { id: 'a2', kind: 'text', x: 0, y: 0, width: 40, height: 30 },
        ],
      }],
    });
    expect(layout.floors[0].zoneShapes).toEqual([{ zoneId: 'cold', x: 5, y: 6, width: 70, height: 60 }]);
    // a2 没有文字，画出来是一片空白，丢掉
    expect(layout.floors[0].annotations?.map((item) => item.id)).toEqual(['a1']);
    // 老数据（没有这两个字段）读进来是空数组而不是 undefined —— 下游不用到处 ?? []
    const legacy = normalizeWarehouseLayout({ zones: [], locations: [], floors: [{ id: 'f1', name: '1 层', racks: [] }] });
    expect(floorZoneShapes(legacy.floors[0])).toEqual([]);
    expect(floorAnnotations(legacy.floors[0])).toEqual([]);
  });
});

describe('几何算术：拉框 / 拖动 / 缩放', () => {
  it('从右下往左上拖也能拉出正的宽高', () => {
    expect(rectFromDrag({ x: 300, y: 260 }, { x: 100, y: 60 })).toEqual({ x: 100, y: 60, width: 200, height: 200 });
  });

  it('手一抖画出来的 0x0 被撑到最小尺寸，⛔ 不是留一个点不中的图形', () => {
    expect(rectFromDrag({ x: 10, y: 10 }, { x: 11, y: 11 }))
      .toEqual({ x: 10, y: 10, width: MIN_SHAPE_SIZE, height: MIN_SHAPE_SIZE });
    // 折线的包围盒最小是 1：一条水平直线的高度合法地就是 0，撑到 24 会让它自己变形
    expect(minSizeForKind('freehand')).toBe(1);
    expect(minSizeForKind('rect')).toBe(MIN_SHAPE_SIZE);
    expect(clampRect({ x: -50, y: -3, width: 0, height: 0 }, 1)).toEqual({ x: 0, y: 0, width: 1, height: 1 });
  });

  it('拖动只改位置，⛔ 一个像素的尺寸都不许动', () => {
    const rect = { x: 100, y: 100, width: 200, height: 150 };
    expect(moveRect(rect, 40, -30)).toEqual({ x: 140, y: 70, width: 200, height: 150 });
    // 拖出画布左上角时停在 0，尺寸仍然不变
    expect(moveRect(rect, -900, -900)).toEqual({ x: 0, y: 0, width: 200, height: 150 });
  });

  it('往回拖过头时对边不动，图形不会翻面', () => {
    const rect = { x: 100, y: 100, width: 200, height: 150 };
    expect(resizeRect(rect, 'nw', 50, 40)).toEqual({ x: 150, y: 140, width: 150, height: 110 });
    expect(resizeRect(rect, 'se', -60, -50)).toEqual({ x: 100, y: 100, width: 140, height: 100 });
    // 过头：右/下边界固定，边长停在最小值，x/y 也跟着停住 —— ⛔ 不是 x 跑到 600、width 变负
    const overshot = resizeRect(rect, 'nw', 500, 500);
    expect(overshot).toEqual({
      x: 300 - MIN_SHAPE_SIZE, y: 250 - MIN_SHAPE_SIZE, width: MIN_SHAPE_SIZE, height: MIN_SHAPE_SIZE,
    });
    expect(overshot.width).toBeGreaterThan(0);
    // 单边把手只动一个方向
    expect(resizeRect(rect, 'e', 30, 999)).toEqual({ x: 100, y: 100, width: 230, height: 150 });
    expect(resizeRect(rect, 's', 999, 30)).toEqual({ x: 100, y: 100, width: 200, height: 180 });
  });

  it('八个控制点的锚点落在包围盒的角和边中点上', () => {
    const rect = { x: 100, y: 200, width: 200, height: 100 };
    expect(handleAnchor(rect, 'nw')).toEqual({ x: 100, y: 200 });
    expect(handleAnchor(rect, 'se')).toEqual({ x: 300, y: 300 });
    expect(handleAnchor(rect, 'n')).toEqual({ x: 200, y: 200 });
    expect(handleAnchor(rect, 'w')).toEqual({ x: 100, y: 250 });
  });

  it('屏幕坐标换算成画布坐标时，平移和缩放都要还原回去', () => {
    expect(screenToFlowPoint(300, 200, { left: 100, top: 50 }, { x: 20, y: 10, zoom: 2 })).toEqual({ x: 90, y: 70 });
    // zoom 为 0（画布还没初始化）时按 1 处理，⛔ 不返回 Infinity
    expect(screenToFlowPoint(300, 200, { left: 0, top: 0 }, { x: 0, y: 0, zoom: 0 })).toEqual({ x: 300, y: 200 });
  });
});

describe('自由笔的落盘体量必须有界（persist PUT 的是整份 layout）', () => {
  it('一笔连续涂画不会往配置里塞进上万个数字', () => {
    let points: number[] = [];
    for (let index = 0; index < 5000; index += 1) points = appendFreehandPoint(points, index * 3, index * 2);
    expect(toPointPairs(points).length).toBeLessThanOrEqual(MAX_FREEHAND_POINTS);
    // 阳性对照：确实画进去了（不是「一个点都没记」那种假绿）
    expect(toPointPairs(points).length).toBeGreaterThan(100);
    // 首尾必须保住 —— 抽稀不许把笔画的两端剪掉
    expect(points.slice(0, 2)).toEqual([0, 0]);
    expect(points.slice(-2)).toEqual([4999 * 3, 4999 * 2]);
  });

  it('抖动出来的重复点不落盘，真正移动了才记', () => {
    const start = appendFreehandPoint([], 10, 10);
    expect(appendFreehandPoint(start, 11, 11)).toEqual(start);
    expect(appendFreehandPoint(start, 30, 10)).toEqual([10, 10, 30, 10]);
    expect(appendFreehandPoint(start, Number.NaN, 10)).toEqual(start);
  });

  it('thinPoints 首尾必留，并且砍不动时不会死循环', () => {
    const dense = Array.from({ length: 2000 }, (_, index) => index);
    const thinned = thinPoints(dense, 100);
    expect(toPointPairs(thinned).length).toBeLessThanOrEqual(100);
    expect(thinned.slice(0, 2)).toEqual([0, 1]);
    expect(thinned.slice(-2)).toEqual([1998, 1999]);
    expect(thinPoints([1, 2], 1)).toEqual([1, 2]);
  });
});

describe('折线/自由笔转成标注：点存相对坐标', () => {
  it('包围盒 + 相对点，整体拖动只改 x/y 一处', () => {
    const annotation = annotationFromPolyline('a1', 'freehand', [10, 20, 40, 60]);
    expect(annotation).toEqual({
      id: 'a1', kind: 'freehand', x: 10, y: 20, width: 30, height: 40, points: [0, 0, 30, 40],
    });
    expect(boundsOfPoints([10, 20, 40, 60])).toEqual({ x: 10, y: 20, width: 30, height: 40 });
  });

  it('画到画布负半轴时整笔平移回来，⛔ 不是被压扁', () => {
    const annotation = annotationFromPolyline('a1', 'freehand', [-10, 5, 20, 25]);
    expect(annotation?.x).toBe(0);
    // 宽高与相对点原样保留 —— 形状没变，只是整体挪了 10
    expect(annotation?.width).toBe(30);
    expect(annotation?.points).toEqual([0, 0, 30, 20]);
  });

  it('不足两个点画不出线，返回 null 而不是一条看不见的空标注', () => {
    expect(annotationFromPolyline('a1', 'polygon', [10, 20])).toBeNull();
    expect(annotationFromPolyline('a1', 'polygon', [])).toBeNull();
  });

  it('多边形闭合、自由笔不闭合', () => {
    const base = { id: 'a1', x: 10, y: 20, width: 30, height: 40, points: [0, 0, 30, 0, 30, 40] };
    expect(annotationPathData({ ...base, kind: 'polygon' } as WarehouseAnnotation)).toBe('M10 20 L40 20 L40 60 Z');
    expect(annotationPathData({ ...base, kind: 'freehand' } as WarehouseAnnotation)).toBe('M10 20 L40 20 L40 60');
  });
});

describe('标注归一化：画不出来的整条丢掉，⛔ 不补默认值让它显示出来', () => {
  it.each([
    ['没有 id', { id: '', kind: 'rect', x: 0, y: 0, width: 30, height: 30 }],
    ['不认识的 kind', { id: 'a', kind: 'star', x: 0, y: 0, width: 30, height: 30 }],
    ['几何是 NaN', { id: 'a', kind: 'rect', x: Number.NaN, y: 0, width: 30, height: 30 }],
    ['文字标注没有文字', { id: 'a', kind: 'text', x: 0, y: 0, width: 30, height: 30, text: '   ' }],
    ['折线只有一个点', { id: 'a', kind: 'polygon', x: 0, y: 0, width: 30, height: 30, points: [1, 2] }],
  ])('%s 的标注读进来是 null', (_label, input) => {
    expect(normalizeAnnotation(input)).toBeNull();
  });

  it('阳性对照：完整的标注一个字段都不许丢', () => {
    expect(normalizeAnnotation({
      id: 'a1', kind: 'rect', x: 1.4, y: 2.6, width: 100, height: 40,
      text: '主通道', color: '#d03050', fill: 'none', strokeWidth: 3, fontSize: 18,
    })).toEqual({
      id: 'a1', kind: 'rect', x: 1, y: 3, width: 100, height: 40,
      text: '主通道', color: '#d03050', fill: 'none', strokeWidth: 3, fontSize: 18,
    });
  });
});

describe('AI 识别的百分比区域框', () => {
  it('0 到 100 的百分比换算成画布坐标', () => {
    expect(zoneShapeFromPercent('cold', { x: 10, y: 20, width: 30, height: 40 }))
      .toEqual({ zoneId: 'cold', x: 100, y: 140, width: 300, height: 280 });
  });

  it('模型没给几何时返回 null —— ⛔ 不在图上凭空画一块图里没有的区域', () => {
    expect(zoneShapeFromPercent('cold', {})).toBeNull();
    expect(zoneShapeFromPercent('cold', { x: 10, y: 20, width: 30 })).toBeNull();
    expect(zoneShapeFromPercent('cold', { x: 10, y: 20, width: 0, height: 40 })).toBeNull();
  });
});

describe('画布选中标记的解析', () => {
  it.each([
    ['zone:cold', { kind: 'zone', id: 'cold' }],
    ['annotation:a1', { kind: 'annotation', id: 'a1' }],
    ['annotation:a:1', { kind: 'annotation', id: 'a:1' }],
    ['', { kind: '', id: '' }],
    ['zone:', { kind: '', id: '' }],
    ['rack:r1', { kind: '', id: '' }],
  ])('%s', (input, expected) => {
    expect(parseMapSelection(input)).toEqual(expected);
  });
});

describe('一键放置区域', () => {
  it('三列排开，第四个换行，互不重叠', () => {
    expect(defaultZoneShapeFor('a', 0)).toEqual({ zoneId: 'a', x: 40, y: 40, width: 240, height: 180 });
    expect(defaultZoneShapeFor('b', 2).x).toBe(580);
    expect(defaultZoneShapeFor('c', 3)).toEqual({ zoneId: 'c', x: 40, y: 250, width: 240, height: 180 });
  });
});
