import { labelQcToolbarPosition } from './labelQcToolbarPosition';

describe('QC toolbar remains inside the canvas', () => {
  it.each([
    [1, 0, 0, 0.1, 0.95],
    [1, 0, 0, 0, 1],
    [2, 140, -200, 0.01, 0.99],
    [4, -400, 600, 0.8, 1],
  ])('keeps all controls visible at scale %s', (scale, translateX, translateY, yMin, yMax) => {
    const position = labelQcToolbarPosition({ xMin: 0.01, xMax: 0.99, yMin, yMax },
      { width: 390, height: 520, left: 0, top: 0 },
      { scale, translateX, translateY }, { width: 390, height: 520 });
    expect(position.left).toBeGreaterThanOrEqual(4);
    expect(position.left + position.width).toBeLessThanOrEqual(386);
    expect(position.top).toBeGreaterThanOrEqual(4);
    expect(position.top + 50).toBeLessThanOrEqual(516);
  });
});
