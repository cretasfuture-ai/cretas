import { describe, expect, it } from 'vitest';
import { hitBox, moveBox, resizeBox, normaliseBox, zoomAnchor, createBoxHistory } from '../boxEditorCore.js';

describe('shared VisionLab box editing core', () => {
  it('uses screen pixels to find corners on a non-square image, and selects the topmost box', () => {
    const box: [number, number, number, number] = [.1, .2, .8, .9];
    expect(hitBox([box], .1, .25, 1000, 100).mode).toBe('resize');
    expect(hitBox([box], .12, .25, 1000, 100).mode).toBe('move');
    expect(hitBox([box, box], .5, .5, 1000, 100).index).toBe(1);
  });
  it('keeps moved boxes inside the image without changing their size', () => {
    expect(moveBox([.1, .2, .6, .8], 9, -9)).toEqual([.5, 0, 1, .6000000000000001]);
  });
  it('normalises crossed resize corners and rejects invalid or tiny boxes', () => {
    expect(normaliseBox(resizeBox([.2, .2, .6, .6], 0, .8, .9))).toEqual([.6, .6, .8, .9]);
    expect(normaliseBox([0, 0, .001, .9])).toBeNull();
    expect(normaliseBox([Number.NaN, 0, .8, .9])).toBeNull();
  });
  it('bounds history and preserves independent snapshots for both legacy arrays and labelled boxes', () => {
    const history = createBoxHistory<{ label: string; x0: number }>(2);
    const source = [{ label: 'seal', x0: .1 }]; history.push(source); source[0]!.x0 = .9;
    expect(history.pop()).toEqual([{ label: 'seal', x0: .1 }]);
    const arrays = createBoxHistory<number[]>(2); arrays.push([[1]]); arrays.push([[2]]); arrays.push([[3]]);
    expect(arrays.pop()).toEqual([[3]]); expect(arrays.pop()).toEqual([[2]]); expect(arrays.pop()).toBeNull();
    arrays.push([[4]]); arrays.clear(); expect(arrays.length).toBe(0);
  });
  it('preserves the cursor anchor when zooming an already scrolled image', () => {
    const anchor = zoomAnchor(100, 150, 500);
    expect(anchor * 1000 - 150).toBe(350);
  });
});
