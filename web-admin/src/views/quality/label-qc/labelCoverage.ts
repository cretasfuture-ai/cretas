import type { LabelQcPhoto } from '@/api/labelQc';
import type { LabelQcPhotoObjectDraft } from './objectReviewModel';

export interface LabelCoverageSummary {
  expectedTrayCount: number;
  detectedTrayCount: number;
  whiteLabelCount: number;
  colorLabelCount: number;
  duplicateWhiteLabelCount: number;
  duplicateColorLabelCount: number;
  confirmedMissingWhiteCount: number;
  confirmedMissingColorCount: number;
  possibleMissingWhiteCount: number;
  possibleMissingColorCount: number;
  possibleMissingTrayCount: number;
  usesBatchReference: boolean;
}

function screenedTrayCount(photo: LabelQcPhoto): number | null {
  if (photo.objectReview) return photo.objectReview.trays.length;
  if (!photo.screeningDetail) return null;

  try {
    const screening = JSON.parse(photo.screeningDetail) as {
      trayCount?: unknown;
      trays?: unknown;
    };
    if (Array.isArray(screening.trays)) return screening.trays.length;
    return Number.isSafeInteger(screening.trayCount) && Number(screening.trayCount) >= 0
      ? Number(screening.trayCount)
      : null;
  } catch {
    return null;
  }
}

function repeatedBatchTrayCount(photos: LabelQcPhoto[]): number | null {
  const frequencies = new Map<number, number>();
  for (const photo of photos) {
    const count = screenedTrayCount(photo);
    if (!count) continue;
    frequencies.set(count, (frequencies.get(count) ?? 0) + 1);
  }

  const ranked = [...frequencies.entries()]
    .sort((left, right) => right[1] - left[1] || right[0] - left[0]);
  const [winner, runnerUp] = ranked;
  if (!winner || winner[1] < 2 || runnerUp?.[1] === winner[1]) return null;
  return winner[0];
}

export function summarizeLabelCoverage(
  draft: LabelQcPhotoObjectDraft,
  batchPhotos: LabelQcPhoto[],
): LabelCoverageSummary {
  const detectedTrayCount = draft.trays.length;
  const batchReference = repeatedBatchTrayCount(batchPhotos);
  const expectedTrayCount = Math.max(detectedTrayCount, batchReference ?? detectedTrayCount);
  let whiteLabelCount = 0;
  let colorLabelCount = 0;
  let coveredWhiteTrayCount = 0;
  let coveredColorTrayCount = 0;
  let duplicateWhiteLabelCount = 0;
  let duplicateColorLabelCount = 0;
  let confirmedMissingWhiteCount = 0;
  let confirmedMissingColorCount = 0;

  for (const tray of draft.trays) {
    const trayWhiteLabelCount = tray.labels.filter((label) => label.type === 'WHITE_LABEL').length;
    const trayColorLabelCount = tray.labels.filter((label) => label.type === 'COLOR_LABEL').length;
    whiteLabelCount += trayWhiteLabelCount;
    colorLabelCount += trayColorLabelCount;
    if (trayWhiteLabelCount > 0) coveredWhiteTrayCount += 1;
    if (trayColorLabelCount > 0) coveredColorTrayCount += 1;
    duplicateWhiteLabelCount += Math.max(0, trayWhiteLabelCount - 1);
    duplicateColorLabelCount += Math.max(0, trayColorLabelCount - 1);
    if (tray.whitePresence === 'MISSING') confirmedMissingWhiteCount += 1;
    if (tray.colorPresence === 'MISSING') confirmedMissingColorCount += 1;
  }

  return {
    expectedTrayCount,
    detectedTrayCount,
    whiteLabelCount,
    colorLabelCount,
    duplicateWhiteLabelCount,
    duplicateColorLabelCount,
    confirmedMissingWhiteCount,
    confirmedMissingColorCount,
    possibleMissingWhiteCount: Math.max(
      0,
      expectedTrayCount - coveredWhiteTrayCount - confirmedMissingWhiteCount,
    ),
    possibleMissingColorCount: Math.max(
      0,
      expectedTrayCount - coveredColorTrayCount - confirmedMissingColorCount,
    ),
    possibleMissingTrayCount: Math.max(0, expectedTrayCount - detectedTrayCount),
    usesBatchReference: batchReference !== null && batchReference >= detectedTrayCount,
  };
}
