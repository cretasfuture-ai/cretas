import { boundedMapOffset, pinchMapTransform } from '../../../../screens/warehouse/inventory/warehouseMapGestureModel';

describe('warehouse map gestures', () => {
  const content = { width: 1000, height: 800 };
  const viewport = { width: 400, height: 600 };

  it('keeps the point between two fingers in place while zooming', () => {
    expect(pinchMapTransform(
      { scale: 1, translateX: 0, translateY: 0 },
      { x: 280, y: 340 }, { x: 280, y: 340 },
      2, 0.4, 3, content, viewport,
    )).toEqual({ scale: 2, translateX: -80, translateY: -40 });
  });

  it('tracks diagonal movement of the pinch focal point', () => {
    expect(pinchMapTransform(
      { scale: 1, translateX: 0, translateY: 0 },
      { x: 200, y: 300 }, { x: 240, y: 270 },
      1.5, 0.4, 3, content, viewport,
    )).toEqual({ scale: 1.5, translateX: 40, translateY: -30 });
  });

  it('prevents panning past map edges and zooming outside limits', () => {
    expect(boundedMapOffset(900, content.width, viewport.width, 1)).toBe(300);
    expect(boundedMapOffset(-900, content.height, viewport.height, 1)).toBe(-100);
    expect(boundedMapOffset(100, 200, viewport.width, 1)).toBe(0);
    expect(pinchMapTransform(
      { scale: 1, translateX: 0, translateY: 0 },
      { x: 200, y: 300 }, { x: 200, y: 300 },
      20, 0.4, 3, content, viewport,
    ).scale).toBe(3);
  });
});
