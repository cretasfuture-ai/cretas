import type {
  LabelQcBoundingBox,
  LabelQcObjectReviewItem,
  LabelQcObjectReviewPayload,
  LabelQcObjectType,
  LabelQcPhoto,
  LabelQcPresence,
  LabelQcTrayObjectReview,
} from '@/api/labelQc';
import {
  deduplicateOverlayBoxes,
  deduplicateOverlayBoxesPreservingGroupCoverage,
  type OverlayLabelBox,
} from './overlayGeometry';

export interface LabelQcObjectDraftItem extends LabelQcObjectReviewItem {
  key: string;
}

export interface LabelQcTrayObjectDraft extends Omit<LabelQcTrayObjectReview, 'labels'> {
  key: string;
  confirmed: boolean;
  humanTouched: boolean;
  labels: LabelQcObjectDraftItem[];
}

export interface LabelQcPhotoObjectDraft {
  photoId: string;
  reviewedByHuman: boolean;
  trays: LabelQcTrayObjectDraft[];
  rejectedAiTrayKeys: string[];
}

interface RawLabel {
  type?: string;
  bbox?: number[];
}

interface RawTray {
  index?: number;
  bbox?: number[];
  hasWhite?: boolean;
  hasColor?: boolean;
  labels?: RawLabel[];
}

const toBox = (value?: number[]): LabelQcBoundingBox | null => {
  if (!value || value.length !== 4 || value.some((part) => !Number.isFinite(part))) return null;
  const [xMin, yMin, xMax, yMax] = value;
  if (xMin === undefined || yMin === undefined || xMax === undefined || yMax === undefined) return null;
  if (xMin < 0 || yMin < 0 || xMax > 1 || yMax > 1 || xMax <= xMin || yMax <= yMin) return null;
  return { xMin, yMin, xMax, yMax };
};

const parseRawTrays = (raw?: string | null): RawTray[] => {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as { trays?: unknown };
    return Array.isArray(parsed.trays) ? parsed.trays as RawTray[] : [];
  } catch {
    return [];
  }
};

// Detector booleans are screening hints, never a human-confirmed presence decision.
// Without an actual label box, only an explicit reviewer action may conclude MISSING.
const presenceFrom = (count: number): LabelQcPresence => (
  count > 0 ? 'PRESENT' : 'UNJUDGEABLE'
);

export function buildObjectReviewDraft(photo: LabelQcPhoto): LabelQcPhotoObjectDraft {
  if (photo.objectReview) {
    return {
      photoId: photo.id,
      reviewedByHuman: true,
      rejectedAiTrayKeys: [...photo.objectReview.rejectedAiTrayKeys],
      trays: photo.objectReview.trays.map((tray) => ({
        ...tray,
        key: tray.aiTrayKey ?? `human-tray-${tray.trayIndex}`,
        confirmed: true,
        humanTouched: false,
        rejectedAiObjectKeys: [...tray.rejectedAiObjectKeys],
        labels: tray.labels.map((label, index) => ({
          ...label,
          key: label.aiObjectKey ?? `human-label-${tray.trayIndex}-${index}`,
        })),
      })),
    };
  }

  const trays = parseRawTrays(photo.screeningDetail).flatMap((rawTray, order) => {
    const trayIndex = Number.isInteger(rawTray.index) ? rawTray.index! : order;
    const bbox = toBox(rawTray.bbox);
    if (!bbox) return [];
    const labelCandidates = (rawTray.labels ?? []).flatMap((rawLabel, labelIndex) => {
      const labelBox = toBox(rawLabel.bbox);
      if (!labelBox || (rawLabel.type !== 'white' && rawLabel.type !== 'color')) return [];
      return [{
        key: `label-${trayIndex}-${labelIndex}`,
        aiObjectKey: `label-${trayIndex}-${labelIndex}`,
        type: rawLabel.type === 'white' ? 'WHITE_LABEL' as const : 'COLOR_LABEL' as const,
        bbox: labelBox,
        decision: 'CONFIRMED' as const,
        truncated: false,
      }];
    });
    const { kept: labels, rejected } = deduplicateOverlayBoxes(
      labelCandidates as (LabelQcObjectDraftItem & OverlayLabelBox)[],
    );
    const whiteCount = labels.filter((label) => label.type === 'WHITE_LABEL').length;
    const colorCount = labels.filter((label) => label.type === 'COLOR_LABEL').length;
    return [{
      key: `tray-${trayIndex}`,
      trayIndex,
      aiTrayKey: `tray-${trayIndex}`,
      bbox,
      decision: 'CONFIRMED' as const,
      whitePresence: presenceFrom(whiteCount),
      colorPresence: presenceFrom(colorCount),
      labels,
      rejectedAiObjectKeys: rejected.flatMap((label) => label.aiObjectKey ? [label.aiObjectKey] : []),
      confirmed: true,
      humanTouched: false,
    }];
  });
  const crossTrayCandidates = trays.flatMap((tray) => tray.labels.map((label) => ({
    ...label,
    trayKey: tray.key,
  })));
  const { rejected: crossTrayRejected } = deduplicateOverlayBoxesPreservingGroupCoverage(
    crossTrayCandidates,
    (label) => label.trayKey,
  );
  const rejectedKeys = new Set(crossTrayRejected.map((label) => label.key));
  for (const tray of trays) {
    const rejected = tray.labels.filter((label) => rejectedKeys.has(label.key));
    tray.labels = tray.labels.filter((label) => !rejectedKeys.has(label.key));
    tray.rejectedAiObjectKeys = [...new Set([
      ...tray.rejectedAiObjectKeys,
      ...rejected.flatMap((label) => label.aiObjectKey ? [label.aiObjectKey] : []),
    ])];
  }
  return { photoId: photo.id, reviewedByHuman: false, trays, rejectedAiTrayKeys: [] };
}

export function setTrayPresence(
  tray: LabelQcTrayObjectDraft,
  type: LabelQcObjectType,
  presence: LabelQcPresence,
): void {
  if (type === 'WHITE_LABEL') tray.whitePresence = presence;
  else tray.colorPresence = presence;
  if (presence === 'MISSING') {
    const rejected = tray.labels.filter((label) => label.type === type && label.aiObjectKey)
      .map((label) => label.aiObjectKey!);
    tray.rejectedAiObjectKeys = [...new Set([...tray.rejectedAiObjectKeys, ...rejected])];
    tray.labels = tray.labels.filter((label) => label.type !== type);
  }
  tray.confirmed = false;
  tray.humanTouched = true;
}

export function addObjectLabel(
  tray: LabelQcTrayObjectDraft,
  type: LabelQcObjectType,
  bbox: LabelQcBoundingBox,
  key: string,
): LabelQcObjectDraftItem {
  const label: LabelQcObjectDraftItem = {
    key,
    type,
    bbox,
    decision: 'ADDED',
    truncated: false,
  };
  tray.labels.push(label);
  if (type === 'WHITE_LABEL') tray.whitePresence = 'PRESENT';
  else tray.colorPresence = 'PRESENT';
  tray.confirmed = false;
  tray.humanTouched = true;
  return label;
}

export function rejectObjectLabel(tray: LabelQcTrayObjectDraft, key: string): void {
  const label = tray.labels.find((item) => item.key === key);
  if (!label) return;
  if (label.aiObjectKey) {
    tray.rejectedAiObjectKeys = [...new Set([...tray.rejectedAiObjectKeys, label.aiObjectKey])];
  }
  tray.labels = tray.labels.filter((item) => item.key !== key);
  const remaining = tray.labels.filter((item) => item.type === label.type).length;
  if (remaining === 0) {
    if (label.type === 'WHITE_LABEL') tray.whitePresence = 'UNJUDGEABLE';
    else tray.colorPresence = 'UNJUDGEABLE';
  }
  tray.confirmed = false;
  tray.humanTouched = true;
}

export function hasHumanObjectReview(draft: LabelQcPhotoObjectDraft): boolean {
  return draft.reviewedByHuman
    || draft.rejectedAiTrayKeys.length > 0
    || draft.trays.some((tray) => tray.humanTouched
      || tray.decision !== 'CONFIRMED'
      || tray.labels.some((label) => label.decision !== 'CONFIRMED'));
}

export function markObjectCorrected(item: { decision: 'CONFIRMED' | 'CORRECTED' | 'ADDED' }): void {
  if (item.decision === 'CONFIRMED') item.decision = 'CORRECTED';
}

export function validateTrayObjectDraft(tray: LabelQcTrayObjectDraft): string | null {
  const whiteCount = tray.labels.filter((label) => label.type === 'WHITE_LABEL').length;
  const colorCount = tray.labels.filter((label) => label.type === 'COLOR_LABEL').length;
  if (tray.whitePresence === 'PRESENT' && whiteCount === 0) return `盒子 ${tray.trayIndex + 1} 选择了“有白标”，请补画白标框`;
  if (tray.whitePresence === 'MISSING' && whiteCount > 0) return `盒子 ${tray.trayIndex + 1} 选择了“缺白标”，请先删掉白标框`;
  if (tray.colorPresence === 'PRESENT' && colorCount === 0) return `盒子 ${tray.trayIndex + 1} 选择了“有彩标”，请补画彩标框`;
  if (tray.colorPresence === 'MISSING' && colorCount > 0) return `盒子 ${tray.trayIndex + 1} 选择了“缺彩标”，请先删掉彩标框`;
  return null;
}

export function toObjectReviewPayload(draft: LabelQcPhotoObjectDraft): LabelQcObjectReviewPayload {
  for (const tray of draft.trays) {
    const error = validateTrayObjectDraft(tray);
    if (error) throw new Error(error);
  }
  return {
    version: 1,
    complete: true,
    rejectedAiTrayKeys: [...draft.rejectedAiTrayKeys],
    trays: draft.trays.map(({ key: _key, confirmed: _confirmed, humanTouched: _humanTouched, labels, ...tray }) => ({
      ...tray,
      labels: labels.map(({ key: _labelKey, ...label }) => label),
    })),
  };
}

export function defaultLabelBox(tray: LabelQcTrayObjectDraft): LabelQcBoundingBox {
  const width = (tray.bbox.xMax - tray.bbox.xMin) * 0.35;
  const height = (tray.bbox.yMax - tray.bbox.yMin) * 0.25;
  const centerX = (tray.bbox.xMin + tray.bbox.xMax) / 2;
  const centerY = (tray.bbox.yMin + tray.bbox.yMax) / 2;
  return {
    xMin: centerX - width / 2,
    yMin: centerY - height / 2,
    xMax: centerX + width / 2,
    yMax: centerY + height / 2,
  };
}
