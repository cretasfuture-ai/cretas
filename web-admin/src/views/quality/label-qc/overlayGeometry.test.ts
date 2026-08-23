import { describe, expect, it } from 'vitest';
import {
  buildHorizontalGuideRows,
  deduplicateOverlayBoxes,
  deduplicateOverlayBoxesPreservingGroupCoverage,
} from './overlayGeometry';

const box = (
  key: string,
  type: 'WHITE_LABEL' | 'COLOR_LABEL',
  values: [number, number, number, number],
) => ({
  key,
  type,
  bbox: { xMin: values[0], yMin: values[1], xMax: values[2], yMax: values[3] },
});

describe('label QC overlay geometry', () => {
  it('collapses a three-box duplicate clique and keeps the largest proposal', () => {
    const result = deduplicateOverlayBoxes([
      box('medium', 'COLOR_LABEL', [0.10, 0.10, 0.30, 0.30]),
      box('large', 'COLOR_LABEL', [0.09, 0.09, 0.31, 0.31]),
      box('small', 'COLOR_LABEL', [0.11, 0.11, 0.29, 0.29]),
    ]);

    expect(result.kept.map((item) => item.key)).toEqual(['large']);
    expect(result.rejected.map((item) => item.key)).toEqual(['medium', 'small']);
  });

  it('does not merge adjacent labels or labels of different types', () => {
    const result = deduplicateOverlayBoxes([
      box('white-left', 'WHITE_LABEL', [0.10, 0.10, 0.24, 0.20]),
      box('white-right', 'WHITE_LABEL', [0.25, 0.10, 0.39, 0.20]),
      box('color-same-place', 'COLOR_LABEL', [0.10, 0.10, 0.24, 0.20]),
    ]);

    expect(result.kept).toHaveLength(3);
    expect(result.rejected).toHaveLength(0);
  });

  it('removes a neighbouring-tray spill when that tray has another label of the same type', () => {
    const result = deduplicateOverlayBoxesPreservingGroupCoverage([
      { ...box('tray-a-own', 'COLOR_LABEL', [0.10, 0.10, 0.20, 0.20]), tray: 'a' },
      { ...box('tray-a-spill', 'COLOR_LABEL', [0.60, 0.60, 0.80, 0.80]), tray: 'a' },
      { ...box('tray-b-own', 'COLOR_LABEL', [0.59, 0.59, 0.81, 0.81]), tray: 'b' },
    ], (item) => item.tray);

    expect(result.kept.map((item) => item.key)).toEqual(['tray-a-own', 'tray-b-own']);
    expect(result.rejected.map((item) => item.key)).toEqual(['tray-a-spill']);
  });

  it('keeps an ownership-ambiguous overlap when both trays would lose their only label', () => {
    const result = deduplicateOverlayBoxesPreservingGroupCoverage([
      { ...box('tray-a-only', 'WHITE_LABEL', [0.20, 0.20, 0.40, 0.40]), tray: 'a' },
      { ...box('tray-b-only', 'WHITE_LABEL', [0.19, 0.19, 0.41, 0.41]), tray: 'b' },
    ], (item) => item.tray);

    expect(result.kept.map((item) => item.key)).toEqual(['tray-a-only', 'tray-b-only']);
    expect(result.rejected).toHaveLength(0);
  });

  it('creates only horizontal row spans and keeps label types separate', () => {
    const guides = buildHorizontalGuideRows([
      box('w1', 'WHITE_LABEL', [0.10, 0.10, 0.20, 0.20]),
      box('w2', 'WHITE_LABEL', [0.40, 0.11, 0.50, 0.21]),
      box('c1', 'COLOR_LABEL', [0.12, 0.40, 0.22, 0.50]),
      box('c2', 'COLOR_LABEL', [0.42, 0.41, 0.52, 0.51]),
    ]);

    expect(guides).toHaveLength(2);
    expect(guides.map((guide) => guide.type)).toEqual(['WHITE_LABEL', 'COLOR_LABEL']);
    expect(guides[0]).toMatchObject({ count: 2 });
    expect(guides[0]!.left).toBeCloseTo(0.15);
    expect(guides[0]!.width).toBeCloseTo(0.3);
    expect(guides[0]!.top).toBeCloseTo(0.155);
  });
});
