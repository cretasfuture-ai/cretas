import { describe, expect, it } from 'vitest';
import type { LabelQcPhoto } from '@/api/labelQc';
import { summarizeLabelCoverage } from './labelCoverage';
import { buildObjectReviewDraft, setTrayPresence } from './objectReviewModel';

function photo(id: string, trayCount: number, omittedColorIndexes: number[] = []): LabelQcPhoto {
  return {
    id,
    attachmentId: `attachment-${id}`,
    orderIndex: 0,
    imageWidth: 1000,
    imageHeight: 1200,
    status: 'ANALYZED',
    screeningDetail: JSON.stringify({
      trayCount,
      trays: Array.from({ length: trayCount }, (_, index) => ({
        index,
        bbox: [0, 0, 1, 1],
        labels: [
          { type: 'white', bbox: [0.1, 0.1, 0.2, 0.2] },
          ...omittedColorIndexes.includes(index)
            ? []
            : [{ type: 'color', bbox: [0.3, 0.3, 0.4, 0.4] }],
        ],
      })),
    }),
    annotations: [],
  };
}

describe('label QC optional label coverage hints', () => {
  it('counts one expected white and color label for each detected tray', () => {
    const current = photo('current', 3, [1]);

    expect(summarizeLabelCoverage(buildObjectReviewDraft(current), [current]))
      .toMatchObject({
        expectedTrayCount: 3,
        detectedTrayCount: 3,
        whiteLabelCount: 3,
        colorLabelCount: 2,
        possibleMissingWhiteCount: 0,
        possibleMissingColorCount: 1,
        possibleMissingTrayCount: 0,
        usesBatchReference: false,
      });
  });

  it('uses a repeated same-batch count to warn that a tray and its labels may be undetected', () => {
    const current = photo('current', 2);
    const siblingA = photo('sibling-a', 3);
    const siblingB = photo('sibling-b', 3);

    expect(summarizeLabelCoverage(buildObjectReviewDraft(current), [current, siblingA, siblingB]))
      .toMatchObject({
        expectedTrayCount: 3,
        detectedTrayCount: 2,
        possibleMissingTrayCount: 1,
        possibleMissingWhiteCount: 1,
        possibleMissingColorCount: 1,
        usesBatchReference: true,
      });
  });

  it('does not let duplicate labels on one tray hide a missing label on another tray', () => {
    const current = photo('current', 2);
    current.screeningDetail = JSON.stringify({
      trays: [
        {
          index: 0,
          bbox: [0, 0, 0.48, 1],
          labels: [
            { type: 'white', bbox: [0.05, 0.1, 0.12, 0.2] },
            { type: 'white', bbox: [0.2, 0.1, 0.28, 0.2] },
            { type: 'color', bbox: [0.3, 0.3, 0.38, 0.4] },
          ],
        },
        {
          index: 1,
          bbox: [0.5, 0, 1, 1],
          labels: [{ type: 'color', bbox: [0.65, 0.3, 0.75, 0.4] }],
        },
      ],
    });

    expect(summarizeLabelCoverage(buildObjectReviewDraft(current), [current])).toMatchObject({
      expectedTrayCount: 2,
      whiteLabelCount: 2,
      duplicateWhiteLabelCount: 1,
      possibleMissingWhiteCount: 1,
      duplicateColorLabelCount: 0,
      possibleMissingColorCount: 0,
    });
  });

  it('does not invent a fixed layout from a single larger or tied batch count', () => {
    const current = photo('current', 2);
    const larger = photo('larger', 13);

    expect(summarizeLabelCoverage(buildObjectReviewDraft(current), [current, larger]))
      .toMatchObject({ expectedTrayCount: 2, possibleMissingTrayCount: 0 });

    expect(summarizeLabelCoverage(buildObjectReviewDraft(current), [
      current,
      photo('another-two', 2),
      larger,
      photo('another-thirteen', 13),
    ])).toMatchObject({ expectedTrayCount: 2, usesBatchReference: false });
  });

  it('does not mistake a human-confirmed physical missing label for a detector miss', () => {
    const current = photo('current', 2, [0]);
    const draft = buildObjectReviewDraft(current);
    setTrayPresence(draft.trays[0]!, 'COLOR_LABEL', 'MISSING');

    expect(summarizeLabelCoverage(draft, [current])).toMatchObject({
      colorLabelCount: 1,
      confirmedMissingColorCount: 1,
      possibleMissingColorCount: 0,
    });
  });

  it('ignores malformed screening detail and keeps count hints separate from review validation', () => {
    const current = photo('current', 1, [0]);
    const malformed = { ...photo('malformed', 1), screeningDetail: '{not-json' };

    expect(summarizeLabelCoverage(buildObjectReviewDraft(current), [current, malformed]))
      .toMatchObject({ expectedTrayCount: 1, possibleMissingColorCount: 1 });
  });
});
