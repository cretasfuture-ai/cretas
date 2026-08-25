import { describe, expect, it } from 'vitest';
import type { LabelQcPhoto } from '@/api/labelQc';
import {
  addObjectLabel,
  buildObjectReviewDraft,
  hasHumanObjectReview,
  setTrayPresence,
  toObjectReviewPayload,
} from './objectReviewModel';

const photo = (screeningDetail: string): LabelQcPhoto => ({
  id: 'photo-1',
  attachmentId: 'attachment-1',
  orderIndex: 0,
  imageWidth: 1000,
  imageHeight: 1200,
  status: 'ANALYZED',
  screeningDetail,
  annotations: [],
});

describe('label QC object review model', () => {
  it('hydrates every AI tray and label as default-accepted human-review draft', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0.1, 0.1, 0.9, 0.9],
        labels: [
          { type: 'white', bbox: [0.2, 0.2, 0.4, 0.4] },
          { type: 'color', bbox: [0.5, 0.5, 0.7, 0.7] },
        ],
      }],
    })));

    expect(draft.trays).toHaveLength(1);
    expect(draft.trays[0]).toMatchObject({
      key: 'tray-0',
      confirmed: true,
      whitePresence: 'PRESENT',
      colorPresence: 'PRESENT',
    });
    expect(draft.trays[0]?.labels.map((label) => label.aiObjectKey))
      .toEqual(['label-0-0', 'label-0-1']);
    expect(hasHumanObjectReview(draft)).toBe(false);
  });

  it('keeps detector-negative trays uncertain until a reviewer explicitly confirms missing labels', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{ index: 0, bbox: [0.1, 0.1, 0.9, 0.9], hasWhite: false, hasColor: false, labels: [] }],
    })));

    expect(draft.trays[0]).toMatchObject({
      whitePresence: 'UNJUDGEABLE',
      colorPresence: 'UNJUDGEABLE',
      humanTouched: false,
    });
    expect(hasHumanObjectReview(draft)).toBe(false);
    expect(toObjectReviewPayload(draft).trays[0]).toMatchObject({
      whitePresence: 'UNJUDGEABLE',
      colorPresence: 'UNJUDGEABLE',
    });
  });

  it('does not require reviewers to draw boxes merely because detector flags say labels exist', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{ index: 0, bbox: [0.1, 0.1, 0.9, 0.9], hasWhite: true, hasColor: true, labels: [] }],
    })));

    expect(draft.trays[0]).toMatchObject({
      whitePresence: 'UNJUDGEABLE',
      colorPresence: 'UNJUDGEABLE',
    });
    expect(() => toObjectReviewPayload(draft)).not.toThrow();
  });

  it('keeps an undetected second label uncertain when the first label has a real AI box', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0.1, 0.1, 0.9, 0.9],
        hasWhite: true,
        hasColor: false,
        labels: [{ type: 'white', bbox: [0.2, 0.2, 0.4, 0.4] }],
      }],
    })));

    expect(draft.trays[0]).toMatchObject({
      whitePresence: 'PRESENT',
      colorPresence: 'UNJUDGEABLE',
    });
    expect(hasHumanObjectReview(draft)).toBe(false);
  });

  it('preserves existing explicitly reviewed tray annotations when reopening a task', () => {
    const savedPhoto = photo(JSON.stringify({ trays: [] }));
    savedPhoto.objectReview = {
      version: 1,
      complete: true,
      rejectedAiTrayKeys: [],
      trays: [{
        trayIndex: 0,
        bbox: { xMin: 0.1, yMin: 0.1, xMax: 0.9, yMax: 0.9 },
        decision: 'ADDED',
        whitePresence: 'UNJUDGEABLE',
        colorPresence: 'UNJUDGEABLE',
        labels: [],
        rejectedAiObjectKeys: [],
      }],
    };

    const draft = buildObjectReviewDraft(savedPhoto);

    expect(draft.reviewedByHuman).toBe(true);
    expect(hasHumanObjectReview(draft)).toBe(true);
    expect(toObjectReviewPayload(draft)).toEqual(savedPhoto.objectReview);
  });

  it('records rejected AI labels when the inspector marks a label missing', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0, 0, 1, 1],
        labels: [{ type: 'white', bbox: [0.2, 0.2, 0.4, 0.4] }],
      }],
    })));
    const tray = draft.trays[0]!;

    setTrayPresence(tray, 'WHITE_LABEL', 'MISSING');

    expect(tray.labels).toHaveLength(0);
    expect(tray.rejectedAiObjectKeys).toEqual(['label-0-0']);
    expect(toObjectReviewPayload(draft).trays[0]?.whitePresence).toBe('MISSING');
    expect(hasHumanObjectReview(draft)).toBe(true);
    expect(toObjectReviewPayload(draft).trays[0]).not.toHaveProperty('humanTouched');
  });

  it('exports a valid edited tray without requiring a separate confirmation click', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{ index: 0, bbox: [0.1, 0.1, 0.9, 0.9], labels: [] }],
    })));
    const tray = draft.trays[0]!;
    addObjectLabel(
      tray,
      'WHITE_LABEL',
      { xMin: 0.2, yMin: 0.2, xMax: 0.4, yMax: 0.4 },
      'human-white-1',
    );
    setTrayPresence(tray, 'COLOR_LABEL', 'MISSING');
    const payload = toObjectReviewPayload(draft);
    expect(payload.complete).toBe(true);
    expect(payload.trays[0]?.labels[0]).toMatchObject({
      type: 'WHITE_LABEL',
      decision: 'ADDED',
    });
    expect(payload.trays[0]?.labels[0]).not.toHaveProperty('aiObjectKey');
  });

  it('automatically removes obvious duplicate AI label proposals and records the rejection', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [{
        index: 0,
        bbox: [0, 0, 1, 1],
        labels: [
          { type: 'color', bbox: [0.20, 0.20, 0.40, 0.40] },
          { type: 'color', bbox: [0.19, 0.19, 0.41, 0.41] },
          { type: 'color', bbox: [0.21, 0.21, 0.39, 0.39] },
        ],
      }],
    })));

    expect(draft.trays[0]?.labels).toHaveLength(1);
    expect(draft.trays[0]?.labels[0]?.bbox).toEqual({
      xMin: 0.19,
      yMin: 0.19,
      xMax: 0.41,
      yMax: 0.41,
    });
    expect(draft.trays[0]?.rejectedAiObjectKeys).toEqual(['label-0-0', 'label-0-2']);
    expect(hasHumanObjectReview(draft)).toBe(false);
  });

  it('removes a cross-tray spill without changing either tray presence conclusion', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [
        {
          index: 0,
          bbox: [0, 0, 0.7, 1],
          hasColor: true,
          labels: [
            { type: 'color', bbox: [0.10, 0.10, 0.30, 0.30] },
            { type: 'color', bbox: [0.60, 0.60, 0.80, 0.80] },
          ],
        },
        {
          index: 1,
          bbox: [0.3, 0, 1, 1],
          hasColor: true,
          labels: [{ type: 'color', bbox: [0.59, 0.59, 0.81, 0.81] }],
        },
      ],
    })));

    expect(draft.trays[0]?.labels.map((label) => label.aiObjectKey)).toEqual(['label-0-0']);
    expect(draft.trays[0]?.rejectedAiObjectKeys).toEqual(['label-0-1']);
    expect(draft.trays[1]?.labels.map((label) => label.aiObjectKey)).toEqual(['label-1-0']);
    expect(draft.trays.map((tray) => tray.colorPresence)).toEqual(['PRESENT', 'PRESENT']);
    expect(() => toObjectReviewPayload(draft)).not.toThrow();
  });

  it('keeps two overlapping sole labels for human ownership review', () => {
    const draft = buildObjectReviewDraft(photo(JSON.stringify({
      trays: [
        { index: 0, bbox: [0, 0, 0.7, 1], labels: [{ type: 'white', bbox: [0.2, 0.2, 0.4, 0.4] }] },
        { index: 1, bbox: [0.3, 0, 1, 1], labels: [{ type: 'white', bbox: [0.19, 0.19, 0.41, 0.41] }] },
      ],
    })));

    expect(draft.trays[0]?.labels).toHaveLength(1);
    expect(draft.trays[1]?.labels).toHaveLength(1);
    expect(draft.trays.flatMap((tray) => tray.rejectedAiObjectKeys)).toEqual([]);
  });
});
