<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Aim,
  Check,
  Close,
  Delete,
  Minus,
  Plus,
  RefreshLeft,
  Right,
  Warning,
} from '@element-plus/icons-vue';
import type {
  LabelQcBoundingBox,
  LabelQcLabel,
  LabelQcObjectType,
  LabelQcPhoto,
  LabelQcPresence,
  LabelQcReviewRequest,
  LabelQcTaskDetail,
} from '@/api/labelQc';
import { getLabelQcPhotoImageBlob } from '@/api/labelQc';
import {
  appendHumanBox,
  buildReviewDraft,
  completedPhotoCount,
  firstIncompletePhotoIndex,
  isPhotoComplete,
  markPhotoNormal,
  markPhotoReviewed,
  moveBox,
  normalizedBox,
  pendingItemCount,
  resizeBox,
  restoreRejectedAiCandidate,
  toReviewRequest,
  validateReviewDraft,
  type LabelQcPhotoDraft,
  type LabelQcReviewDraft,
} from './reviewModel';
import {
  addObjectLabel,
  buildObjectReviewDraft,
  findTrayForLabelBox,
  markObjectCorrected,
  rejectObjectLabel,
  setTrayPresence,
  suggestPointLabelBox,
  validateTrayObjectDraft,
  type LabelQcObjectDraftItem,
  type LabelQcTrayObjectDraft,
} from './objectReviewModel';
import {
  calculateAnchoredPan,
  calculateImagePlaneStyle,
  resolveImageSize,
} from './imageViewport';
import { summarizeLabelCoverage } from './labelCoverage';

const props = defineProps<{
  factoryId: string;
  detail: LabelQcTaskDetail;
  canReview: boolean;
  submitting: boolean;
  retrying: boolean;
}>();

const emit = defineEmits<{
  submit: [payload: LabelQcReviewRequest, keyboardConfirmed?: boolean];
  retry: [];
  dirtyChange: [dirty: boolean];
}>();

const LABEL_TEXT: Record<LabelQcLabel, string> = {
  MISSING_WHITE_LABEL: '缺白标',
  MISSING_COLOR_LABEL: '缺彩标',
  NO_DEFECT: '无问题',
  UNJUDGEABLE: '无法判断',
};
const OBJECT_TYPES: LabelQcObjectType[] = ['WHITE_LABEL', 'COLOR_LABEL'];
const PRESENCE_OPTIONS: LabelQcPresence[] = ['PRESENT', 'MISSING', 'UNJUDGEABLE'];

const drafts = ref<LabelQcPhotoDraft[]>([]);
const activePhotoIndex = ref(0);
const selectedKey = ref<string | null>(null);
const selectedTrayKey = ref<string | null>(null);
const selectedObjectKey = ref<string | null>(null);
const viewportRef = ref<HTMLElement | null>(null);
const planeRef = ref<HTMLElement | null>(null);
const viewportSize = ref({ width: 800, height: 600 });
const zoom = ref(1);
const pan = ref({ x: 0, y: 0 });
const isDirty = ref(false);
const imageLoadState = ref<'loading' | 'loaded' | 'error'>('loading');
const decodedImageSize = ref<{ width: number; height: number } | null>(null);
const imageReloadAttempt = ref(0);
let resizeObserver: ResizeObserver | null = null;
let measureFrame: number | null = null;
let settleMeasureTimer: number | null = null;

type PointerInteraction = {
  type: 'pan' | 'move' | 'resize' | 'draw' | 'supplement';
  pointerId: number;
  startX: number;
  startY: number;
  moved: boolean;
  startPan?: { x: number; y: number };
  itemKey?: string;
  objectKey?: string;
  trayKey?: string;
  startBox?: LabelQcBoundingBox;
};

const pointerInteraction = ref<PointerInteraction | null>(null);
const shiftPressed = ref(false);
const showTechnicalObjectReview = ref(false);
const supplementalLabelType = ref<LabelQcObjectType | null>(null);
const proxiedImageUrl = ref<string | null>(null);
let imageRequestToken = 0;

const activePhoto = computed<LabelQcPhoto>(
  () => props.detail.photos[activePhotoIndex.value]!,
);
const activeDraft = computed<LabelQcPhotoDraft>(
  () => drafts.value[activePhotoIndex.value]!,
);
const selectedItem = computed<LabelQcReviewDraft | null>(
  () => activeDraft.value?.items.find((item) => item.key === selectedKey.value) ?? null,
);
const objectDraft = computed(() => activeDraft.value?.objectReview);
const displayedObjectDraft = computed(() => (
  objectDraft.value ?? buildObjectReviewDraft(activePhoto.value)
));
const selectedTray = computed<LabelQcTrayObjectDraft | null>(() => (
  objectDraft.value?.trays.find((tray) => tray.key === selectedTrayKey.value)
    ?? objectDraft.value?.trays[0]
    ?? null
));
const selectedObject = computed<LabelQcObjectDraftItem | null>(() => (
  selectedTray.value?.labels.find((item) => item.key === selectedObjectKey.value) ?? null
));
const confirmedTrayCount = computed(() => (
  objectDraft.value?.trays.filter((tray) => tray.confirmed).length ?? 0
));
const labelCoverage = computed(() => (
  objectDraft.value ? summarizeLabelCoverage(objectDraft.value, props.detail.photos) : null
));
const addedLabelObjects = computed(() => (
  objectDraft.value?.trays.flatMap((tray) => tray.labels
    .filter((label) => label.decision === 'ADDED')
    .map((label) => ({ tray, label }))) ?? []
));
const hasOptionalCoverageWarning = computed(() => (
  Boolean(labelCoverage.value && (
    labelCoverage.value.possibleMissingWhiteCount > 0
    || labelCoverage.value.possibleMissingColorCount > 0
    || labelCoverage.value.possibleMissingTrayCount > 0
    || labelCoverage.value.duplicateWhiteLabelCount > 0
    || labelCoverage.value.duplicateColorLabelCount > 0
  ))
));
const visibleItems = computed(() => (
  activeDraft.value?.items.filter((item) => Boolean(item.bbox)) ?? []
));
const pendingItems = computed(() => (
  activeDraft.value?.items.filter((item) => !item.label) ?? []
));
const rejectedAiItems = computed(() => (
  activeDraft.value?.items.filter((item) => (
    item.source === 'AI' && item.label === 'NO_DEFECT'
  )) ?? []
));
const currentHumanItemCount = computed(() => (
  activeDraft.value?.items.filter((item) => item.source === 'HUMAN').length ?? 0
));
const normalAiImpactCount = computed(() => (
  activeDraft.value?.items.filter((item) => (
    item.source === 'AI' && item.label !== 'NO_DEFECT'
  )).length ?? 0
));
const completedCount = computed(() => completedPhotoCount(drafts.value));
const allComplete = computed(() => (
  drafts.value.length > 0 && completedCount.value === drafts.value.length
));
const currentPhotoComplete = computed(() => (
  activeDraft.value ? isPhotoComplete(activeDraft.value) : false
));
const reviewPercent = computed(() => (
  drafts.value.length
    ? Math.round((completedCount.value / drafts.value.length) * 100)
    : 0
));
const currentAiTotalCount = computed(() => (
  activeDraft.value?.items.filter((item) => item.source === 'AI').length ?? 0
));
const currentAiPendingCount = computed(() => (
  activeDraft.value?.items.filter((item) => item.source === 'AI' && !item.label).length ?? 0
));
const aiReviewStatusText = computed(() => {
  if (currentAiPendingCount.value > 0) {
    return `${currentAiPendingCount.value} 个 AI 疑点待复核`;
  }
  if (currentAiTotalCount.value > 0) {
    return `${currentAiTotalCount.value} 个 AI 疑点已处理`;
  }
  if (activePhoto.value.status === 'AUTO_PASSED') return '高置信正常，不需要人工逐盒确认';
  return '本图没有模型疑点';
});
const nextButtonText = computed(() => {
  if (!currentPhotoComplete.value) return '请先完成本图';
  if (activePhotoIndex.value < drafts.value.length - 1) return '下一张';
  return '回到未完成照片';
});

const imagePlaneStyle = computed(() => {
  const photo = activePhoto.value;
  const imageSize = resolveImageSize(
    { width: photo.imageWidth, height: photo.imageHeight },
    decodedImageSize.value,
  );
  return calculateImagePlaneStyle(
    imageSize,
    viewportSize.value,
    zoom.value,
    pan.value,
  );
});
const activeImageKey = computed(() => (
  `${activePhoto.value.id}:${imageReloadAttempt.value}`
));
const displayImageUrl = computed(() => proxiedImageUrl.value || activePhoto.value.imageUrl || '');
const mainImageErrorText = computed(() => (
  displayImageUrl.value
    ? '照片加载失败，请重试；仍失败可在新窗口打开原图。'
    : '这张照片没有可用的访问地址，请联系管理员检查附件记录。'
));

function labelText(label?: LabelQcLabel | null): string {
  return label ? LABEL_TEXT[label] : '待确认';
}

function labelColor(label: LabelQcLabel): string {
  if (label === 'MISSING_WHITE_LABEL') return '#e54d42';
  if (label === 'MISSING_COLOR_LABEL') return '#d97706';
  if (label === 'UNJUDGEABLE') return '#6b7280';
  return '#16a36a';
}

function itemColor(item: LabelQcReviewDraft): string {
  if (!item.label) return item.source === 'AI' ? '#f5a524' : '#2f6fdd';
  return labelColor(item.label);
}

/** 预览用的颜色跟着"粘"住的类型走，画之前就知道这一笔会被标成什么 */
const drawColor = computed(() => {
  if (supplementalLabelType.value) return objectColor(supplementalLabelType.value);
  return activeLabel.value ? labelColor(activeLabel.value) : '#2f6fdd';
});

const draftRectStyle = computed<Record<string, string>>(() => {
  const rect = draftRect.value;
  if (!rect) return {};
  return {
    left: `${Math.min(rect.x0, rect.x1)}px`,
    top: `${Math.min(rect.y0, rect.y1)}px`,
    width: `${Math.abs(rect.x1 - rect.x0)}px`,
    height: `${Math.abs(rect.y1 - rect.y0)}px`,
    borderColor: drawColor.value,
  };
});

function confidence(value?: number | null): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`;
}

function annotationStyle(item: LabelQcReviewDraft) {
  const box = item.bbox!;
  return {
    left: `${box.xMin * 100}%`,
    top: `${box.yMin * 100}%`,
    width: `${(box.xMax - box.xMin) * 100}%`,
    height: `${(box.yMax - box.yMin) * 100}%`,
    borderColor: itemColor(item),
    '--overlay-fill': `${itemColor(item)}2e`,
  };
}

function choosePreferredItem(): void {
  const current = activeDraft.value;
  if (!current) {
    selectedKey.value = null;
    return;
  }
  const existing = current.items.find((item) => item.key === selectedKey.value);
  if (existing && !(existing.source === 'AI' && existing.label === 'NO_DEFECT')) return;
  selectedKey.value = current.items.find((item) => !item.label)?.key
    ?? current.items.find((item) => !(item.source === 'AI' && item.label === 'NO_DEFECT'))?.key
    ?? null;
}

function resetView(): void {
  zoom.value = 1;
  pan.value = { x: 0, y: 0 };
}

function resetMainImageState(): void {
  decodedImageSize.value = null;
  imageReloadAttempt.value = 0;
  imageLoadState.value = displayImageUrl.value ? 'loading' : 'error';
}

async function loadActiveImage(): Promise<void> {
  const token = ++imageRequestToken;
  const previousUrl = proxiedImageUrl.value;
  proxiedImageUrl.value = null;
  if (previousUrl) URL.revokeObjectURL(previousUrl);
  resetMainImageState();
  try {
    const blob = await getLabelQcPhotoImageBlob(props.factoryId, activePhoto.value.id);
    if (token !== imageRequestToken) return;
    proxiedImageUrl.value = URL.createObjectURL(blob);
    resetMainImageState();
  } catch {
    if (token === imageRequestToken) resetMainImageState();
  }
}

function selectPhoto(index: number): void {
  if (index !== activePhotoIndex.value && !confirmCurrentObjectBeforeSwitch('照片')) return;
  activePhotoIndex.value = index;
  selectedKey.value = null;
  selectedTrayKey.value = drafts.value[index]?.objectReview?.trays[0]?.key ?? null;
  selectedObjectKey.value = selectedTrayKey.value;
  showTechnicalObjectReview.value = false;
  supplementalLabelType.value = null;
  if (toolMode.value === 'supplement') toolMode.value = 'select';
  resetView();
  resetMainImageState();
  scheduleViewportMeasurement();
  void nextTick(choosePreferredItem);
}

function setDirty(value: boolean): void {
  if (isDirty.value === value) return;
  isDirty.value = value;
  emit('dirtyChange', value);
}

function touchPhoto(): void {
  if (activeDraft.value) activeDraft.value.reviewed = false;
  setDirty(true);
}

function selectItem(item: LabelQcReviewDraft): void {
  selectedKey.value = item.key;
}

function selectNextPending(afterKey?: string): void {
  const items = activeDraft.value?.items ?? [];
  const start = Math.max(-1, items.findIndex((item) => item.key === afterKey));
  for (let offset = 1; offset <= items.length; offset += 1) {
    const item = items[(start + offset) % items.length];
    if (item && !item.label) {
      selectedKey.value = item.key;
      return;
    }
  }
  choosePreferredItem();
}

function resolveSelected(label: LabelQcLabel): void {
  const item = selectedItem.value;
  if (!item || !props.canReview) return;
  item.label = label;
  touchPhoto();
  const key = item.key;
  void nextTick(() => selectNextPending(key));
}

function confirmAiCandidate(): void {
  const item = selectedItem.value;
  if (!item || item.source !== 'AI') return;
  resolveSelected(item.aiLabel ?? 'UNJUDGEABLE');
}

function rejectAiCandidate(): void {
  const item = selectedItem.value;
  if (!item || item.source !== 'AI') return;
  item.notes = item.notes || '人工复核：AI 疑点不成立';
  resolveSelected('NO_DEFECT');
}

function undoRejectedAiCandidate(item: LabelQcReviewDraft): void {
  const draft = activeDraft.value;
  if (!draft || !props.canReview) return;
  const restored = restoreRejectedAiCandidate(draft, item.key);
  if (!restored) return;
  selectedKey.value = restored.key;
  setDirty(true);
  ElMessage.info('已撤销拒绝，请重新确认这个 AI 疑点');
}

function setHumanLabel(label: Exclude<LabelQcLabel, 'NO_DEFECT'>): void {
  const item = selectedItem.value;
  if (!item || item.source !== 'HUMAN') return;
  resolveSelected(label);
}

function deleteHumanItem(): void {
  const item = selectedItem.value;
  const draft = activeDraft.value;
  if (!item || !draft || item.source !== 'HUMAN' || !props.canReview) return;
  draft.items = draft.items.filter((candidate) => candidate.key !== item.key);
  draft.reviewed = false;
  selectedKey.value = null;
  setDirty(true);
  void nextTick(choosePreferredItem);
}

function selectTray(tray: LabelQcTrayObjectDraft): void {
  if (tray.key !== selectedTray.value?.key && !confirmCurrentObjectBeforeSwitch('盒子')) return;
  selectedTrayKey.value = tray.key;
  selectedObjectKey.value = tray.key;
}

function confirmCurrentObjectBeforeSwitch(target: '盒子' | '照片' | '提交'): boolean {
  const tray = selectedTray.value;
  if (!tray || !props.canReview) return true;
  const error = validateTrayObjectDraft(tray);
  if (error) {
    ElMessage.warning(`${error}，请修正后再${target === '提交' ? '提交' : `切换${target}`}`);
    return false;
  }
  tray.confirmed = true;
  return true;
}

function objectBoxStyle(bbox: LabelQcBoundingBox, color: string): Record<string, string> {
  return {
    left: `${bbox.xMin * 100}%`,
    top: `${bbox.yMin * 100}%`,
    width: `${(bbox.xMax - bbox.xMin) * 100}%`,
    height: `${(bbox.yMax - bbox.yMin) * 100}%`,
    borderColor: color,
    '--overlay-fill': `${color}29`,
  };
}

function objectColor(type: LabelQcObjectType): string {
  if (type === 'WHITE_LABEL') return '#d40000';
  return '#6f00b8';
}

function objectLabelText(type: LabelQcObjectType): string {
  return type === 'WHITE_LABEL' ? '白标' : '彩标';
}

function presenceText(presence: LabelQcPresence): string {
  return { PRESENT: '有', MISSING: '缺', UNJUDGEABLE: '看不清' }[presence];
}

function presenceActionText(presence: LabelQcPresence): string {
  return {
    PRESENT: '有标签',
    MISSING: '实物缺标',
    UNJUDGEABLE: '看不清',
  }[presence];
}

function changePresence(type: LabelQcObjectType, presence: LabelQcPresence): void {
  const tray = selectedTray.value;
  if (!tray || !props.canReview) return;
  setTrayPresence(tray, type, presence);
  setDirty(true);
}

function beginLabelSupplement(type: LabelQcObjectType): void {
  if (!objectDraft.value || !props.canReview) return;
  toolMode.value = 'supplement';
  supplementalLabelType.value = type;
  activeLabel.value = null;
  selectedKey.value = null;
  ElMessage.info(`请单击照片中已经存在、但模型漏识别的${objectLabelText(type)}；系统会自动补框，不用画托盘`);
}

function addSupplementalLabel(bbox: LabelQcBoundingBox, preferredTray?: LabelQcTrayObjectDraft): void {
  const draft = objectDraft.value;
  const type = supplementalLabelType.value;
  if (!draft || !type || !props.canReview) return;
  const tray = preferredTray ?? findTrayForLabelBox(draft, bbox);
  if (!tray) {
    ElMessage.warning('该位置没有可确认的所属盒子，可能是模型漏识别托盘；无需补画托盘，可跳过并继续审核');
    return;
  }

  const item = addObjectLabel(
    tray,
    type,
    bbox,
    `human-object-${tray.trayIndex}-${Date.now()}`,
  );
  selectedTrayKey.value = tray.key;
  selectedObjectKey.value = item.key;
  setDirty(true);
  ElMessage.success(`已补上盒子 ${tray.trayIndex + 1} 的${objectLabelText(type)}，可继续补同类标签`);
}

function addSupplementalLabelAt(clientX: number, clientY: number): void {
  const draft = objectDraft.value;
  const type = supplementalLabelType.value;
  const rect = planeRef.value?.getBoundingClientRect();
  if (!draft || !type || !rect || rect.width <= 0 || rect.height <= 0 || !props.canReview) return;

  const x = (clientX - rect.left) / rect.width;
  const y = (clientY - rect.top) / rect.height;
  const suggestion = suggestPointLabelBox(draft, type, x, y);
  if (!suggestion) {
    ElMessage.warning('该位置没有可确认的所属盒子，可能是模型漏识别托盘；无需补画托盘，可跳过并继续审核');
    return;
  }
  addSupplementalLabel(suggestion.bbox, suggestion.tray);
}

function deleteSupplementalLabel(tray: LabelQcTrayObjectDraft, item: LabelQcObjectDraftItem): void {
  if (!props.canReview) return;
  selectedTrayKey.value = tray.key;
  selectedObjectKey.value = item.key;
  deleteObjectLabel(item);
}

function deleteObjectLabel(item: LabelQcObjectDraftItem): void {
  const tray = selectedTray.value;
  if (!tray || !props.canReview) return;
  rejectObjectLabel(tray, item.key);
  selectedObjectKey.value = tray.key;
  setDirty(true);
}

function toggleObjectType(item: LabelQcObjectDraftItem): void {
  const tray = selectedTray.value;
  if (!tray || !props.canReview) return;
  item.type = item.type === 'WHITE_LABEL' ? 'COLOR_LABEL' : 'WHITE_LABEL';
  markObjectCorrected(item);
  if (item.type === 'WHITE_LABEL') tray.whitePresence = 'PRESENT';
  else tray.colorPresence = 'PRESENT';
  tray.confirmed = false;
  tray.humanTouched = true;
  setDirty(true);
}

function toggleTruncated(item: LabelQcObjectDraftItem): void {
  const tray = selectedTray.value;
  if (!tray || !props.canReview) return;
  item.truncated = !item.truncated;
  markObjectCorrected(item);
  tray.confirmed = false;
  tray.humanTouched = true;
  setDirty(true);
}

/** 屏幕坐标 → 图片平面内的像素坐标（已含缩放/平移，因为用的是 plane 的实际 rect） */
function toPlanePoint(clientX: number, clientY: number): { x: number; y: number } | null {
  const rect = planeRef.value?.getBoundingClientRect();
  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
  return { x: clientX - rect.left, y: clientY - rect.top };
}

function startViewportPointer(event: PointerEvent): void {
  // Shift + 左键 / 中键在任何标注模式下都平移；漏识别标签使用单击自动补框。
  const wantsPan = event.button === 1 || event.shiftKey || shiftPressed.value || toolMode.value === 'select';
  if (event.button !== 0 && event.button !== 1) return;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);

  if (!wantsPan && toolMode.value === 'supplement' && props.canReview) {
    pointerInteraction.value = {
      type: 'supplement',
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
    };
    return;
  }

  if (!wantsPan && props.canReview) {
    const point = toPlanePoint(event.clientX, event.clientY);
    if (!point) return;
    draftRect.value = { x0: point.x, y0: point.y, x1: point.x, y1: point.y };
    pointerInteraction.value = {
      type: 'draw',
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
    };
    return;
  }

  pointerInteraction.value = {
    type: 'pan',
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    moved: false,
    startPan: { ...pan.value },
  };
}

function startBoxPointer(
  event: PointerEvent,
  item: LabelQcReviewDraft,
  type: 'move' | 'resize',
): void {
  event.stopPropagation();
  if (event.shiftKey || shiftPressed.value) {
    startViewportPointer(event);
    return;
  }
  if (!props.canReview || !item.bbox) {
    selectItem(item);
    return;
  }
  selectItem(item);
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  pointerInteraction.value = {
    type,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    moved: false,
    itemKey: item.key,
    startBox: { ...item.bbox },
  };
}

function startObjectPointer(
  event: PointerEvent,
  tray: LabelQcTrayObjectDraft,
  object: LabelQcObjectDraftItem | LabelQcTrayObjectDraft,
  type: 'move' | 'resize',
): void {
  event.stopPropagation();
  if (event.shiftKey || shiftPressed.value) {
    startViewportPointer(event);
    return;
  }
  selectTray(tray);
  selectedObjectKey.value = object.key;
  if (!props.canReview) return;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  pointerInteraction.value = {
    type,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    moved: false,
    trayKey: tray.key,
    objectKey: object.key,
    startBox: { ...object.bbox },
  };
}

function movePointer(event: PointerEvent): void {
  const interaction = pointerInteraction.value;
  if (!interaction || interaction.pointerId !== event.pointerId) return;
  const deltaX = event.clientX - interaction.startX;
  const deltaY = event.clientY - interaction.startY;
  if (Math.abs(deltaX) + Math.abs(deltaY) > 4) interaction.moved = true;
  if (interaction.type === 'draw') {
    const point = toPlanePoint(event.clientX, event.clientY);
    if (!point) return;
    if (draftRect.value) {
      draftRect.value = { ...draftRect.value, x1: point.x, y1: point.y };
    }
    return;
  }
  if (interaction.type === 'pan') {
    if (zoom.value > 1 && interaction.startPan) {
      pan.value = {
        x: interaction.startPan.x + deltaX,
        y: interaction.startPan.y + deltaY,
      };
    }
    return;
  }
  const planeRect = planeRef.value?.getBoundingClientRect();
  if (interaction.objectKey && interaction.trayKey) {
    const tray = objectDraft.value?.trays.find((candidate) => candidate.key === interaction.trayKey);
    const object = tray?.key === interaction.objectKey
      ? tray
      : tray?.labels.find((candidate) => candidate.key === interaction.objectKey);
    if (!planeRect || !tray || !object || !interaction.startBox) return;
    const normalizedDeltaX = deltaX / planeRect.width;
    const normalizedDeltaY = deltaY / planeRect.height;
    object.bbox = interaction.type === 'move'
      ? moveBox(interaction.startBox, normalizedDeltaX, normalizedDeltaY)
      : resizeBox(interaction.startBox, normalizedDeltaX, normalizedDeltaY);
    markObjectCorrected(object);
    tray.confirmed = false;
    tray.humanTouched = true;
    setDirty(true);
    return;
  }
  const item = activeDraft.value?.items.find((candidate) => candidate.key === interaction.itemKey);
  if (!planeRect || !item || !interaction.startBox) return;
  const normalizedDeltaX = deltaX / planeRect.width;
  const normalizedDeltaY = deltaY / planeRect.height;
  item.bbox = interaction.type === 'move'
    ? moveBox(interaction.startBox, normalizedDeltaX, normalizedDeltaY)
    : resizeBox(interaction.startBox, normalizedDeltaX, normalizedDeltaY);
  touchPhoto();
}

/**
 * 提交一个新的人工框。带着当前"粘"住的类型一起落 —— 画完即定性, 不用再回右侧点一次,
 * 这正是连续标注提速的关键。没选类型时留空, 走原来的"待确认"流程。
 */
function commitHumanBox(bbox: LabelQcBoundingBox | null): void {
  const draft = activeDraft.value;
  if (!bbox || !draft || !props.canReview) return;
  if (supplementalLabelType.value) {
    addSupplementalLabel(bbox);
    return;
  }
  const item = appendHumanBox(draft, bbox, `human-${draft.photoId}-${Date.now()}`);
  if (activeLabel.value) {
    item.label = activeLabel.value;
    selectedKey.value = null;
  } else {
    selectedKey.value = item.key;
  }
  setDirty(true);
}

function endPointer(event: PointerEvent): void {
  const interaction = pointerInteraction.value;
  if (!interaction || interaction.pointerId !== event.pointerId) return;
  pointerInteraction.value = null;

  if (interaction.type === 'draw') {
    const rect = planeRef.value?.getBoundingClientRect();
    const region = draftRect.value;
    draftRect.value = null;
    if (!rect || !region) return;
    // 太小的拖拽多半是误触而不是标注意图, normalizedBox 会返回 null
    commitHumanBox(
      normalizedBox(region.x0, region.y0, region.x1, region.y1, rect.width, rect.height),
    );
    return;
  }

  if (interaction.type === 'supplement' && !interaction.moved) {
    addSupplementalLabelAt(event.clientX, event.clientY);
  }
}

function changeZoom(delta: number, anchor?: { x: number; y: number }): void {
  const next = Math.min(4, Math.max(1, Number((zoom.value + delta).toFixed(2))));
  if (anchor && planeRef.value) {
    const rect = planeRef.value.getBoundingClientRect();
    pan.value = calculateAnchoredPan(
      zoom.value,
      next,
      pan.value,
      anchor,
      { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 },
    );
  }
  zoom.value = next;
  if (next === 1) pan.value = { x: 0, y: 0 };
}

function handleWheel(event: WheelEvent): void {
  changeZoom(event.deltaY < 0 ? 0.2 : -0.2, { x: event.clientX, y: event.clientY });
}

function confirmCurrentPhoto(): void {
  if (!activeDraft.value) return;
  const error = markPhotoReviewed(activeDraft.value);
  if (error) {
    ElMessage.warning(`${error}，请先处理右侧“当前必须操作”`);
    choosePreferredItem();
    return;
  }
  setDirty(true);
  ElMessage.success(`第 ${activePhotoIndex.value + 1} 张整图结论已确认`);
}

async function confirmCurrentPhotoNormal(): Promise<void> {
  if (!activeDraft.value || !props.canReview) return;
  if (currentHumanItemCount.value > 0) {
    const firstHuman = activeDraft.value.items.find((item) => item.source === 'HUMAN');
    if (firstHuman) selectedKey.value = firstHuman.key;
    ElMessage.warning(`还有 ${currentHumanItemCount.value} 个人工补框，请先确认问题或删除框`);
    return;
  }
  if (normalAiImpactCount.value > 0) {
    try {
      await ElMessageBox.confirm(
        `这会把本图 ${normalAiImpactCount.value} 个 AI 疑点全部记录为误报，并作为后续训练真值。`,
        '确认整图正常？',
        {
          type: 'warning',
          confirmButtonText: `确认拒绝 ${normalAiImpactCount.value} 个疑点`,
          cancelButtonText: '继续逐个检查',
          distinguishCancelAndClose: true,
        },
      );
    } catch {
      return;
    }
  }
  try {
    markPhotoNormal(activeDraft.value);
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '本图仍有未处理的人工补框');
    return;
  }
  selectedKey.value = null;
  setDirty(true);
  ElMessage.success('已标记本图正常，所有 AI 疑点均按误报记录');
}

function reopenCurrentPhoto(): void {
  if (!activeDraft.value || !props.canReview) return;
  activeDraft.value.reviewed = false;
  setDirty(true);
  choosePreferredItem();
}

// ---- 常显标签对照图层 -------------------------------------------------------
// 正常白标、彩标与风险框一起显示，便于质检员对照真实照片；只有疑点需要处理。
// 托盘归属继续保留在 objectDraft，但不要求工厂审核员描画或调整托盘轮廓。
type ScreenLayer = 'white' | 'color';

const LAYER_META: Record<ScreenLayer, { key: string; text: string; color: string }> = {
  white: { key: 'Z', text: '白标', color: '#d40000' },
  color: { key: 'X', text: '彩标', color: '#6f00b8' },
};

const visibleLayers = ref<Record<ScreenLayer, boolean>>({
  white: true, color: true,
});

function toggleLayer(layer: ScreenLayer): void {
  visibleLayers.value[layer] = !visibleLayers.value[layer];
}

// ---- 键盘快捷键 -------------------------------------------------------------
// ---- 标注工具：键盘定类型，鼠标画位置 --------------------------------------
// 质检员一天要过几百张，鼠标往返右侧按钮是主要耗时。左手键盘选类型 + 右手鼠标
// 画位置，是这类逐张标注界面的标准姿势。
//
// 工具与类型都是"粘"的：选一次之后连续画都沿用，不用每画一个框回去点一次。
type ToolMode = 'select' | 'box' | 'supplement';

const toolMode = ref<ToolMode>('select');
const activeLabel = ref<LabelQcLabel | null>(null);

/** 正在拖的框只是预览，松手才落成标注。 */
const draftRect = ref<{ x0: number; y0: number; x1: number; y1: number } | null>(null);

const QUICK_LABELS: { label: LabelQcLabel; key: string; text: string }[] = [
  { label: 'MISSING_WHITE_LABEL', key: '1', text: '缺白标 · 画框' },
  { label: 'MISSING_COLOR_LABEL', key: '2', text: '缺彩标 · 画框' },
  { label: 'NO_DEFECT', key: '3', text: '此框正常' },
  { label: 'UNJUDGEABLE', key: '4', text: '无法判断' },
];

const SUPPLEMENT_SHORTCUTS: { type: LabelQcObjectType; key: string; text: string }[] = [
  { type: 'WHITE_LABEL', key: '5', text: '漏识白标 · 单击' },
  { type: 'COLOR_LABEL', key: '6', text: '漏识彩标 · 单击' },
];

const SHORTCUTS = [
  { keys: '1 / 2', text: '缺白标 / 缺彩标：画框' },
  { keys: '3 / 4', text: '正常 / 无法判断' },
  { keys: '5 / 6', text: '漏识白标 / 彩标：单击' },
  { keys: 'Q / W / E / R / T', text: '确认 / 误报 / 缺白 / 缺彩 / 无法判断' },
  { keys: 'Space / Enter', text: '下一张 / 提交并下一批' },
  { keys: 'Shift + 拖动', text: '移动照片' },
  { keys: 'N', text: '整图正常' },
  { keys: 'A', text: '上一张' },
  { keys: 'Backspace', text: '删除当前框' },
  { keys: 'Z / X', text: '显示或隐藏白标 / 彩标框' },
  { keys: 'Esc', text: '取消选中框' },
] as const;

function selectReviewTool(): void {
  toolMode.value = 'select';
  supplementalLabelType.value = null;
  activeLabel.value = null;
}

/**
 * 按类型键：选中框时立刻定结论并跳下一个待判框；没选中框时只是把类型"粘"住，
 * 接下来画的框自动带上它。同一个键在两种情形下都符合"我要标这个类型"的直觉。
 */
function pickLabel(label: LabelQcLabel): void {
  supplementalLabelType.value = null;
  activeLabel.value = label;
  toolMode.value = label === 'MISSING_WHITE_LABEL' || label === 'MISSING_COLOR_LABEL'
    ? 'box'
    : 'select';
  if (selectedItem.value) resolveSelected(label);
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  return tag === 'INPUT'
    || tag === 'TEXTAREA'
    || tag === 'SELECT'
    || target.isContentEditable;
}

function isActionTarget(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && (target.tagName === 'BUTTON' || target.tagName === 'A');
}

function confirmAndGoNext(): void {
  if (allComplete.value) {
    if (!props.submitting) submitReview(true);
    return;
  }
  if (!confirmCurrentObjectBeforeSwitch('照片') || !activeDraft.value) return;
  if (!activeDraft.value.reviewed) {
    const error = markPhotoReviewed(activeDraft.value);
    if (error) {
      ElMessage.warning(`${error}，请先完成本图再进入下一张`);
      choosePreferredItem();
      return;
    }
    setDirty(true);
  }
  nextPhoto();
}

function deleteSelectedByKeyboard(): void {
  if ((showTechnicalObjectReview.value || selectedObject.value?.decision === 'ADDED') && selectedObject.value) {
    deleteObjectLabel(selectedObject.value);
    return;
  }
  if (selectedItem.value?.source === 'HUMAN') {
    deleteHumanItem();
    return;
  }
  if (selectedItem.value?.source === 'AI') {
    rejectAiCandidate();
    return;
  }
}

function onShortcutKey(event: KeyboardEvent): void {
  // 输入框内打字、以及带修饰键的组合，一律不拦截
  if (isTypingTarget(event.target) || event.ctrlKey || event.metaKey || event.altKey) return;
  if (event.key === 'Shift') {
    shiftPressed.value = true;
    return;
  }
  // 避免 Space / Enter 在已聚焦按钮上同时触发按钮原动作和“下一张”。
  if ((event.key === ' ' || event.key === 'Enter') && isActionTarget(event.target)) return;
  if (!props.canReview) return;

  switch (event.key) {
    case 'Enter':
    case ' ':
      event.preventDefault();
      confirmAndGoNext();
      break;
    case 'n':
    case 'N':
      event.preventDefault();
      void confirmCurrentPhotoNormal();
      break;
    case 'a':
    case 'A':
      event.preventDefault();
      previousPhoto();
      break;
    case 'ArrowRight':
      event.preventDefault();
      confirmAndGoNext();
      break;
    case 'Backspace':
      event.preventDefault();
      deleteSelectedByKeyboard();
      break;
    case '1':
    case '2':
    case '3':
    case '4': {
      const quick = QUICK_LABELS[Number(event.key) - 1];
      if (quick) {
        event.preventDefault();
        pickLabel(quick.label);
      }
      break;
    }
    case '5':
    case '6': {
      const supplement = SUPPLEMENT_SHORTCUTS[Number(event.key) - 5];
      if (supplement && objectDraft.value?.trays.length) {
        event.preventDefault();
        beginLabelSupplement(supplement.type);
      }
      break;
    }
    case 'q':
    case 'Q':
      event.preventDefault();
      confirmAiCandidate();
      break;
    case 'w':
    case 'W':
      event.preventDefault();
      rejectAiCandidate();
      break;
    case 'e':
    case 'E':
      event.preventDefault();
      resolveSelected('MISSING_WHITE_LABEL');
      break;
    case 'r':
    case 'R':
      event.preventDefault();
      resolveSelected('MISSING_COLOR_LABEL');
      break;
    case 't':
    case 'T':
      event.preventDefault();
      resolveSelected('UNJUDGEABLE');
      break;
    case 'z':
    case 'Z':
      if (!displayedObjectDraft.value.trays.length) break;
      event.preventDefault();
      toggleLayer('white');
      break;
    case 'x':
    case 'X':
      if (!displayedObjectDraft.value.trays.length) break;
      event.preventDefault();
      toggleLayer('color');
      break;
    case 'v':
    case 'V':
      event.preventDefault();
      selectReviewTool();
      break;
    case 'Escape':
      if (selectedKey.value) {
        event.preventDefault();
        selectedKey.value = null;
      }
      break;
    default:
      break;
  }
}

function onShortcutKeyUp(event: KeyboardEvent): void {
  if (event.key === 'Shift') shiftPressed.value = false;
}

function clearShiftShortcut(): void {
  shiftPressed.value = false;
}

onMounted(() => {
  window.addEventListener('keydown', onShortcutKey);
  window.addEventListener('keyup', onShortcutKeyUp);
  window.addEventListener('blur', clearShiftShortcut);
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShortcutKey);
  window.removeEventListener('keyup', onShortcutKeyUp);
  window.removeEventListener('blur', clearShiftShortcut);
});
watch(() => activePhoto.value.id, () => { void loadActiveImage(); }, { immediate: true });

function previousPhoto(): void {
  if (activePhotoIndex.value > 0) selectPhoto(activePhotoIndex.value - 1);
}

function nextPhoto(): void {
  if (activePhotoIndex.value < drafts.value.length - 1) {
    selectPhoto(activePhotoIndex.value + 1);
    return;
  }
  const incompleteIndex = firstIncompletePhotoIndex(drafts.value, activePhotoIndex.value);
  if (incompleteIndex >= 0) {
    selectPhoto(incompleteIndex);
    ElMessage.warning(`已回到第 ${incompleteIndex + 1} 张未完成照片`);
  }
}

function submitReview(keyboardConfirmed = false): void {
  if (props.submitting) return;
  if (!confirmCurrentObjectBeforeSwitch('提交')) return;
  const validation = validateReviewDraft(drafts.value);
  if (validation) {
    const incompleteIndex = firstIncompletePhotoIndex(drafts.value);
    if (incompleteIndex >= 0) selectPhoto(incompleteIndex);
    ElMessage.warning(validation);
    return;
  }
  emit('submit', toReviewRequest(drafts.value), keyboardConfirmed);
}

function updateViewportSize(): void {
  const element = viewportRef.value;
  if (!element) return;
  const rect = element.getBoundingClientRect();
  const width = element.clientWidth || rect.width;
  const height = element.clientHeight || rect.height;
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return;
  }
  if (viewportSize.value.width === width && viewportSize.value.height === height) return;
  viewportSize.value = {
    width,
    height,
  };
}

function scheduleViewportMeasurement(): void {
  void nextTick(() => {
    updateViewportSize();
    if (typeof window === 'undefined') return;
    if (measureFrame != null) window.cancelAnimationFrame(measureFrame);
    if (settleMeasureTimer != null) window.clearTimeout(settleMeasureTimer);
    measureFrame = window.requestAnimationFrame(() => {
      updateViewportSize();
      measureFrame = window.requestAnimationFrame(() => {
        updateViewportSize();
        measureFrame = null;
      });
    });
    // Element Plus drawers animate into place; this catches the settled width
    // on browsers where ResizeObserver fires before the transition completes.
    settleMeasureTimer = window.setTimeout(() => {
      updateViewportSize();
      settleMeasureTimer = null;
    }, 320);
  });
}

function handleMainImageLoad(event: Event): void {
  const image = event.currentTarget as HTMLImageElement;
  if (image.dataset.photoId !== activePhoto.value.id) return;
  decodedImageSize.value = resolveImageSize(
    { width: image.naturalWidth, height: image.naturalHeight },
  );
  imageLoadState.value = 'loaded';
  scheduleViewportMeasurement();
}

function handleMainImageError(event: Event): void {
  const image = event.currentTarget as HTMLImageElement;
  if (image.dataset.photoId !== activePhoto.value.id) return;
  imageLoadState.value = 'error';
}

function retryMainImage(): void {
  if (!displayImageUrl.value) return;
  decodedImageSize.value = null;
  imageLoadState.value = 'loading';
  imageReloadAttempt.value += 1;
  void loadActiveImage();
  scheduleViewportMeasurement();
}

watch(
  () => props.detail,
  (detail) => {
    drafts.value = buildReviewDraft(detail);
    activePhotoIndex.value = 0;
    selectedKey.value = null;
    selectedTrayKey.value = drafts.value[0]?.objectReview?.trays[0]?.key ?? null;
    selectedObjectKey.value = selectedTrayKey.value;
    supplementalLabelType.value = null;
    toolMode.value = 'select';
    activeLabel.value = null;
    shiftPressed.value = false;
    setDirty(false);
    resetView();
    resetMainImageState();
    scheduleViewportMeasurement();
    void nextTick(() => {
      choosePreferredItem();
    });
  },
  { immediate: true },
);

onMounted(() => {
  resizeObserver = new ResizeObserver(updateViewportSize);
  if (viewportRef.value) resizeObserver.observe(viewportRef.value);
  scheduleViewportMeasurement();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  if (proxiedImageUrl.value) URL.revokeObjectURL(proxiedImageUrl.value);
  if (typeof window === 'undefined') return;
  if (measureFrame != null) window.cancelAnimationFrame(measureFrame);
  if (settleMeasureTimer != null) window.clearTimeout(settleMeasureTimer);
});
</script>

<template>
  <section class="review-workbench" aria-label="标签拍检人工审核工作台">
    <header class="workbench-status">
      <div class="task-context">
        <span class="context-kicker">正在审核</span>
        <strong>{{ detail.task.skuName }}</strong>
        <span>{{ detail.task.skuCode }} · {{ detail.task.batchNumber }} · {{ detail.task.productionDate }}</span>
      </div>
      <div class="progress-block">
        <span>{{ completedCount }}/{{ drafts.length }} 张已完成</span>
        <div class="progress-track"><i :style="{ width: `${reviewPercent}%` }" /></div>
        <strong>{{ reviewPercent }}%</strong>
      </div>
    </header>

    <div class="workbench-grid">
      <aside class="photo-rail" aria-label="待审核照片">
        <div class="rail-heading">照片</div>
        <button
          v-for="(photo, index) in detail.photos"
          :key="photo.id"
          type="button"
          class="photo-thumb"
          :class="{
            active: index === activePhotoIndex,
            complete: drafts[index] && isPhotoComplete(drafts[index]!),
          }"
          :aria-label="`第 ${index + 1} 张，${drafts[index] && isPhotoComplete(drafts[index]!) ? '已完成' : '待确认'}`"
          @click="selectPhoto(index)"
        >
          <img v-if="photo.imageUrl" :src="photo.imageUrl" :alt="`第 ${index + 1} 张照片`">
          <span class="photo-index">{{ index + 1 }}</span>
          <span class="photo-badge">
            <Check v-if="drafts[index] && isPhotoComplete(drafts[index]!)" />
            <Warning v-else />
          </span>
        </button>
      </aside>

      <main class="image-column" :class="{ 'can-annotate': canReview }">
        <div class="image-toolbar">
          <div>
            <span class="toolbar-kicker">第 {{ activePhotoIndex + 1 }} 张原图</span>
            <strong>{{ aiReviewStatusText }}</strong>
          </div>
          <div class="viewer-controls">
            <div v-if="displayedObjectDraft.trays.length" class="layer-toggles" aria-label="标签框图层显示">
              <button
                v-for="(meta, layer) in LAYER_META"
                :key="layer"
                type="button"
                class="layer-toggle"
                :class="[`layer-${layer}`, { off: !visibleLayers[layer as ScreenLayer] }]"
                :style="{ '--layer-color': meta.color }"
                :aria-pressed="visibleLayers[layer as ScreenLayer]"
                :aria-label="`${visibleLayers[layer as ScreenLayer] ? '隐藏' : '显示'}${meta.text}`"
                @click="toggleLayer(layer as ScreenLayer)"
              >
                <i class="dot" /><span>{{ meta.text }}</span><kbd>{{ meta.key }}</kbd>
              </button>
            </div>
            <div class="zoom-tools" aria-label="照片缩放">
              <button type="button" aria-label="缩小照片" @click="changeZoom(-0.25)"><Minus /></button>
              <span>{{ Math.round(zoom * 100) }}%</span>
              <button type="button" aria-label="放大照片" @click="changeZoom(0.25)"><Plus /></button>
              <button type="button" class="reset-view" @click="resetView"><RefreshLeft />复位</button>
            </div>
          </div>
        </div>

        <div v-if="canReview" class="annotate-bar">
          <div class="tool-group" role="group" aria-label="标注工具">
            <button
              type="button"
              class="tool-btn"
              :class="{ on: toolMode === 'select' && activeLabel === null }"
              :aria-pressed="toolMode === 'select' && activeLabel === null"
              @click="selectReviewTool"
            >
              选择<kbd>V</kbd>
            </button>
          </div>
          <span class="bar-sep" />
          <div class="tool-group review-action-group" role="group" aria-label="数字快捷键标注与补标">
            <button
              v-for="quick in QUICK_LABELS"
              :key="quick.label"
              type="button"
              class="tool-btn label-btn"
              :class="{ on: activeLabel === quick.label }"
              :style="activeLabel === quick.label
                ? { backgroundColor: labelColor(quick.label), borderColor: labelColor(quick.label) }
                : { borderColor: labelColor(quick.label) }"
              :aria-pressed="activeLabel === quick.label"
              @click="pickLabel(quick.label)"
            >
              {{ quick.text }}<kbd>{{ quick.key }}</kbd>
            </button>
            <button
              v-for="supplement in SUPPLEMENT_SHORTCUTS"
              :key="supplement.type"
              type="button"
              class="tool-btn label-btn supplement-btn"
              :class="{ on: supplementalLabelType === supplement.type }"
              :disabled="!objectDraft?.trays.length"
              :style="supplementalLabelType === supplement.type
                ? { backgroundColor: objectColor(supplement.type), borderColor: objectColor(supplement.type) }
                : { borderColor: objectColor(supplement.type) }"
              :aria-pressed="supplementalLabelType === supplement.type"
              @click="beginLabelSupplement(supplement.type)"
            >
              {{ supplement.text }}<kbd>{{ supplement.key }}</kbd>
            </button>
          </div>
        </div>

        <div class="gesture-hint">
          <Aim />
          <span v-if="supplementalLabelType">
            <strong>单击已经存在的{{ objectLabelText(supplementalLabelType) }}</strong> · 只补模型漏识别的现有标签 · Shift + 拖动移动照片
          </span>
          <span v-else-if="toolMode === 'box'">
            <strong>框出实物确实缺{{ activeLabel === 'MISSING_WHITE_LABEL' ? '白标' : '彩标' }}的位置</strong> · 松手即完成标注 · Shift + 拖动移动照片
          </span>
          <span v-else>
            <strong>点选疑点框后按 Q / W / E / R / T 判定</strong> · 1 / 2 画缺标框 · 5 / 6 单击补漏识标签
          </span>
        </div>

        <div
          ref="viewportRef"
          class="image-viewport"
          :class="[`tool-${toolMode}`, { drawing: !!draftRect, 'shift-panning': shiftPressed }]"
          :aria-busy="imageLoadState === 'loading'"
          @pointerdown="startViewportPointer"
          @pointermove="movePointer"
          @pointerup="endPointer"
          @pointercancel="endPointer"
          @wheel.prevent="handleWheel"
          @dblclick="resetView"
        >
          <div ref="planeRef" class="image-plane" :style="imagePlaneStyle">
            <img
              v-if="displayImageUrl"
              :key="activeImageKey"
              :src="displayImageUrl"
              :data-photo-id="activePhoto.id"
              class="main-review-image"
              :class="{ loaded: imageLoadState === 'loaded' }"
              alt="待审核包装标签照片"
              decoding="async"
              fetchpriority="high"
              draggable="false"
              @load="handleMainImageLoad"
              @error="handleMainImageError"
            >
            <!-- 正常白标、彩标与疑点框一起显示；只有显式校正或人工补框时才允许编辑标签。 -->
            <template v-for="tray in displayedObjectDraft.trays" :key="tray.key">
              <div
                v-for="label in tray.labels"
                v-show="visibleLayers[label.type === 'WHITE_LABEL' ? 'white' : 'color']"
                :key="label.key"
                class="object-final-box object-label-box"
                :class="{
                  selected: selectedObjectKey === label.key
                    && (showTechnicalObjectReview || label.decision === 'ADDED'),
                  muted: showTechnicalObjectReview && selectedTray && selectedTray.key !== tray.key,
                  'supplemental-label-box': label.decision === 'ADDED',
                  'reference-label-box': !showTechnicalObjectReview && label.decision !== 'ADDED',
                }"
                :style="objectBoxStyle(label.bbox, objectColor(label.type))"
                @pointerdown="startObjectPointer($event, tray, label, 'move')"
              >
                <button
                  v-if="canReview && selectedObjectKey === label.key
                    && (showTechnicalObjectReview || label.decision === 'ADDED')"
                  type="button"
                  class="resize-handle"
                  aria-label="缩放标签框"
                  @pointerdown="startObjectPointer($event, tray, label, 'resize')"
                />
              </div>
            </template>
            <div
              v-for="item in visibleItems"
              :key="item.key"
              class="annotation-box"
              :class="{
                selected: selectedKey === item.key,
                pending: !item.label,
                human: item.source === 'HUMAN',
              }"
              :style="annotationStyle(item)"
              @pointerdown="startBoxPointer($event, item, 'move')"
            >
              <button
                v-if="canReview"
                type="button"
                class="resize-handle"
                aria-label="缩放标注框"
                @pointerdown="startBoxPointer($event, item, 'resize')"
              />
            </div>

            <!-- 拉框预览不接指针事件，避免截断当前拖拽。 -->
            <div v-if="draftRect" class="draft-rect" :style="draftRectStyle" />
          </div>

          <div
            v-if="imageLoadState === 'loading'"
            class="image-load-state"
            role="status"
            aria-live="polite"
            @pointerdown.stop
          >
            <span class="image-spinner" aria-hidden="true" />
            <strong>正在加载照片…</strong>
            <span>大尺寸原图首次解码可能需要几秒</span>
          </div>
          <div
            v-else-if="imageLoadState === 'error'"
            class="image-load-state image-load-error"
            role="alert"
            @pointerdown.stop
            @click.stop
          >
            <Warning aria-hidden="true" />
            <strong>主图没有加载出来</strong>
            <span>{{ mainImageErrorText }}</span>
            <div class="image-fallback-actions">
              <button
                v-if="displayImageUrl"
                type="button"
                @click="retryMainImage"
              >
                重新加载
              </button>
              <a
                v-if="displayImageUrl"
                :href="displayImageUrl"
                target="_blank"
                rel="noopener noreferrer"
              >
                新窗口打开原图
              </a>
            </div>
          </div>

          <div v-if="zoom > 1" class="zoom-indicator">已放大 · 按住 Shift 拖动可移动画面</div>
        </div>
      </main>

      <aside class="decision-rail">
        <div class="decision-scroll">
          <section class="must-act-card" :class="{ resolved: selectedItem?.label }">
            <div class="must-act-heading">
              <span>当前模型疑点</span>
              <em v-if="pendingItems.length">{{ pendingItems.length }} 个待确认</em>
              <em v-else class="done">框已处理</em>
            </div>

            <template v-if="selectedItem?.source === 'AI'">
              <div class="candidate-title">
                <i :style="{ backgroundColor: itemColor(selectedItem) }">AI</i>
                <div>
                  <strong>{{ labelText(selectedItem.aiLabel) }}</strong>
                  <span>置信度 {{ confidence(selectedItem.aiConfidence) }}</span>
                </div>
              </div>
              <p v-if="selectedItem.aiEvidence" class="evidence">{{ selectedItem.aiEvidence }}</p>
              <div class="primary-decisions">
                <button type="button" class="confirm" :disabled="!canReview" @click="confirmAiCandidate">
                  <Check /> 确认问题：{{ labelText(selectedItem.aiLabel) }} <kbd>Q</kbd>
                </button>
                <button type="button" class="reject" :disabled="!canReview" @click="rejectAiCandidate">
                  <Close /> 误报 / 无问题 <kbd>W</kbd>
                </button>
              </div>
              <div class="correction-row">
                <button type="button" :disabled="!canReview" @click="resolveSelected('MISSING_WHITE_LABEL')">缺白标 <kbd>E</kbd></button>
                <button type="button" :disabled="!canReview" @click="resolveSelected('MISSING_COLOR_LABEL')">缺彩标 <kbd>R</kbd></button>
                <button type="button" :disabled="!canReview" @click="resolveSelected('UNJUDGEABLE')">无法判断 <kbd>T</kbd></button>
              </div>
            </template>

            <template v-else-if="selectedItem?.source === 'HUMAN'">
              <div class="candidate-title human-title">
                <i>人</i>
                <div>
                  <strong>人工补充框</strong>
                  <span>请选择这个框的问题类型</span>
                </div>
              </div>
              <div class="human-decisions">
                <button type="button" :disabled="!canReview" @click="setHumanLabel('MISSING_WHITE_LABEL')">缺白标 <kbd>E</kbd></button>
                <button type="button" :disabled="!canReview" @click="setHumanLabel('MISSING_COLOR_LABEL')">缺彩标 <kbd>R</kbd></button>
                <button type="button" :disabled="!canReview" @click="setHumanLabel('UNJUDGEABLE')">无法判断 <kbd>T</kbd></button>
                <button type="button" class="delete" :disabled="!canReview" @click="deleteHumanItem"><Delete />删除框</button>
              </div>
            </template>

            <div v-else class="no-selection">
              <Check v-if="pendingItems.length === 0" />
              <Aim v-else />
              <strong>{{ pendingItems.length === 0 ? '本图所有框已处理' : '点击照片中的框开始审核' }}</strong>
              <span>{{ pendingItems.length === 0 ? '现在请给出整图结论' : '橙色框为 AI 待确认疑点' }}</span>
            </div>

            <el-input
              v-if="selectedItem"
              v-model="selectedItem.notes"
              class="review-note"
              :disabled="!canReview"
              maxlength="200"
              placeholder="可选：补充判断依据"
              @update:model-value="touchPhoto"
            />
          </section>

          <section v-if="objectDraft && !showTechnicalObjectReview" class="object-review-card object-review-summary">
            <div>
              <span>正常标签与风险位置同时显示</span>
              <strong>本页只需处理下方 {{ currentAiPendingCount }} 个疑点，不要求逐盒标注</strong>
              <p>白标和彩标框始终作为对照；正常标签无需逐个确认，托盘无需描框。</p>
            </div>
            <button type="button" @click="showTechnicalObjectReview = true">标签校正（可选）</button>
          </section>

          <section
            v-if="labelCoverage"
            class="label-coverage-card"
            :class="{ warning: hasOptionalCoverageWarning }"
            aria-label="标签数量参考与可选人工补框"
          >
            <div class="label-coverage-heading">
              <strong>标签数量参考</strong>
              <span>{{ labelCoverage.usesBatchReference ? '按同批固定数量' : '按已识别盒子' }}</span>
            </div>
            <div class="label-coverage-counts">
              <span>
                盒子 <strong>{{ labelCoverage.detectedTrayCount }}/{{ labelCoverage.expectedTrayCount }}</strong>
              </span>
              <span>
                白标 <strong>{{ labelCoverage.whiteLabelCount }}/{{ labelCoverage.expectedTrayCount }}</strong>
              </span>
              <span>
                彩标 <strong>{{ labelCoverage.colorLabelCount }}/{{ labelCoverage.expectedTrayCount }}</strong>
              </span>
            </div>
            <p v-if="hasOptionalCoverageWarning" class="label-coverage-warning">
              该图片可能存在模型未标注的白标或彩标，可按 5 / 6 后单击实际标签位置。
              <template v-if="labelCoverage.possibleMissingTrayCount">
                本图识别盒数少于同批参考，但无需人工补画托盘。
              </template>
              <template v-if="labelCoverage.duplicateWhiteLabelCount || labelCoverage.duplicateColorLabelCount">
                部分盒子的标签框可能重复，请按需校正。
              </template>
            </p>
            <p v-else class="label-coverage-note">白标和彩标数量与当前盒数一致，无需额外补标。</p>
            <div class="label-supplement-actions">
              <button
                v-for="type in OBJECT_TYPES"
                :key="type"
                type="button"
                :class="{ active: supplementalLabelType === type }"
                :disabled="!canReview || labelCoverage.detectedTrayCount === 0"
                :style="{ '--supplement-color': objectColor(type) }"
                @click="beginLabelSupplement(type)"
              >
                漏识{{ objectLabelText(type) }} · 单击 <kbd>{{ type === 'WHITE_LABEL' ? '5' : '6' }}</kbd>
              </button>
            </div>
            <div v-if="addedLabelObjects.length" class="supplemented-label-list">
              <button
                v-for="entry in addedLabelObjects"
                :key="entry.label.key"
                type="button"
                :disabled="!canReview"
                @click="deleteSupplementalLabel(entry.tray, entry.label)"
              >
                已补{{ objectLabelText(entry.label.type) }} · 盒 {{ entry.tray.trayIndex + 1 }} ×
              </button>
            </div>
            <small>仅提醒，不强制补标，不影响审核提交。实物确实没有标签时，请选择缺标结论。</small>
          </section>

          <section v-if="objectDraft && showTechnicalObjectReview" class="object-review-card">
            <div class="object-review-heading">
              <div>
                <span>可选 · 标签位置与归属校正</span>
                <strong>{{ confirmedTrayCount }}/{{ objectDraft.trays.length }} 个标签分组已确认</strong>
              </div>
              <div class="object-review-heading-actions">
                <button type="button" @click="showTechnicalObjectReview = false">收起</button>
              </div>
            </div>
            <div v-if="objectDraft.trays.length" class="tray-chip-row">
              <button
                v-for="tray in objectDraft.trays"
                :key="tray.key"
                type="button"
                :class="{ active: selectedTray?.key === tray.key, done: tray.confirmed }"
                @click="selectTray(tray)"
              >
                {{ tray.trayIndex + 1 }}{{ tray.confirmed ? '✓' : '' }}
              </button>
            </div>
            <div v-else class="empty-object-review">
              模型没有提供可校正的标签分组，请继续处理当前缺陷结论。
            </div>

            <template v-if="selectedTray">
              <div class="missing-label-guide">
                <strong>先看原图，再选动作</strong>
                <div>
                  <span><b>实物没有标签</b>选“实物缺标”，不要画假框</span>
                  <span><b>实物有、AI 漏框</b>按 5 / 6 后直接单击标签</span>
                  <span><b>被挡住看不清</b>选“看不清”，不会当作缺标真值</span>
                </div>
              </div>
              <div class="tray-context-row">
                <strong>盒子 {{ selectedTray.trayIndex + 1 }}</strong>
                <span>白标 {{ presenceText(selectedTray.whitePresence) }} · 彩标 {{ presenceText(selectedTray.colorPresence) }}</span>
              </div>

              <div class="presence-editor">
                <div v-for="type in OBJECT_TYPES" :key="type">
                  <strong>{{ objectLabelText(type) }}</strong>
                  <button
                    v-for="presence in PRESENCE_OPTIONS"
                    :key="presence"
                    type="button"
                    :class="{ on: (type === 'WHITE_LABEL' ? selectedTray.whitePresence : selectedTray.colorPresence) === presence }"
                    :disabled="!canReview"
                    @click="changePresence(type, presence)"
                  >
                    {{ presenceActionText(presence) }}
                  </button>
                  <button type="button" class="add-label" :disabled="!canReview" @click="beginLabelSupplement(type)">
                    + 补{{ objectLabelText(type) }}框
                  </button>
                </div>
              </div>

              <div class="object-list">
                <button
                  v-for="item in selectedTray.labels"
                  :key="item.key"
                  type="button"
                  :class="{ active: selectedObjectKey === item.key }"
                  @click="selectedObjectKey = item.key"
                >
                  <i :style="{ backgroundColor: objectColor(item.type) }" />
                  {{ objectLabelText(item.type) }}
                  <em>{{ item.decision === 'ADDED' ? '人工补' : item.decision === 'CORRECTED' ? '已修正' : 'AI' }}</em>
                </button>
              </div>

              <div v-if="selectedObject" class="object-actions">
                <button type="button" :disabled="!canReview" @click="toggleObjectType(selectedObject)">改为{{ selectedObject.type === 'WHITE_LABEL' ? '彩标' : '白标' }}</button>
                <button type="button" :class="{ on: selectedObject.truncated }" :disabled="!canReview" @click="toggleTruncated(selectedObject)">{{ selectedObject.truncated ? '已标边缘可见' : '这是边缘残缺标' }}</button>
                <button type="button" class="danger-link" :disabled="!canReview" @click="deleteObjectLabel(selectedObject)">删除错框</button>
              </div>

              <p class="object-auto-confirm-hint">切换盒子或照片会保留当前草稿；提交整单后才写入服务器。图层开关只改变显示，不改变审核结论。</p>
            </template>
          </section>

          <section v-if="rejectedAiItems.length" class="rejected-history">
            <div class="rejected-heading">
              <div>
                <strong>已拒绝 AI 疑点</strong>
                <span>这些框以绿色正常框显示，可在提交前撤销</span>
              </div>
              <em>{{ rejectedAiItems.length }}</em>
            </div>
            <div
              v-for="item in rejectedAiItems"
              :key="item.key"
              class="rejected-item"
            >
              <span>{{ labelText(item.aiLabel) }} · 置信度 {{ confidence(item.aiConfidence) }}</span>
              <button
                type="button"
                :disabled="!canReview"
                @click="undoRejectedAiCandidate(item)"
              >
                撤销
              </button>
            </div>
          </section>

          <section v-if="activePhoto.analysisError" class="analysis-error">
            <Warning />
            <div>
              <strong>本图 AI 初筛异常</strong>
              <span>{{ activePhoto.analysisError }}</span>
            </div>
            <button type="button" :disabled="retrying" @click="emit('retry')">
              {{ retrying ? '重试中…' : '重试 AI' }}
            </button>
          </section>
        </div>

        <section class="whole-photo-card" :class="{ complete: currentPhotoComplete }">
          <div class="step-label">完成</div>
          <div>
            <strong>确认疑点处理结果</strong>
            <span v-if="pendingItemCount(activeDraft)">
              先处理上方 {{ pendingItemCount(activeDraft) }} 个待确认框
            </span>
            <span v-else>确认本图除已标注外没有其他问题</span>
          </div>
          <template v-if="!currentPhotoComplete">
            <button
              type="button"
              class="whole-confirm"
              :disabled="!canReview || pendingItemCount(activeDraft) > 0"
              @click="confirmCurrentPhoto"
            >
              <Check /> 确认本图结论
            </button>
            <button
              type="button"
              class="whole-normal"
              :disabled="!canReview || currentHumanItemCount > 0"
              @click="confirmCurrentPhotoNormal"
            >
              <template v-if="currentHumanItemCount">
                请先处理 {{ currentHumanItemCount }} 个人工补框
              </template>
              <template v-else-if="normalAiImpactCount">
                整图正常 · 拒绝 {{ normalAiImpactCount }} 个 AI 疑点
              </template>
              <template v-else>
                整图正常 · 本图没有其他问题
              </template>
            </button>
          </template>
          <button v-else type="button" class="reopen" :disabled="!canReview" @click="reopenCurrentPhoto">
            <Check /> 本图已完成 · 点击重新检查
          </button>
        </section>
      </aside>
    </div>

    <footer class="review-navigation">
      <button type="button" class="previous" :disabled="activePhotoIndex === 0" @click="previousPhoto">
        上一张 <kbd>A</kbd>
      </button>
      <div class="current-state" :class="{ complete: currentPhotoComplete }">
        <span>第 {{ activePhotoIndex + 1 }}/{{ drafts.length }} 张</span>
        <strong>{{ currentPhotoComplete ? '本图已完成' : '本图待结论' }}</strong>
        <div v-if="canReview" class="shortcut-hints">
          <span v-for="s in SHORTCUTS" :key="s.keys" class="shortcut">
            <kbd>{{ s.keys }}</kbd>{{ s.text }}
          </span>
        </div>
      </div>
      <button
        v-if="!allComplete"
        type="button"
        class="next"
        :disabled="!currentPhotoComplete"
        @click="nextPhoto"
      >
        {{ nextButtonText }} <kbd>Space / Enter</kbd>
        <Right />
      </button>
      <button
        v-else
        type="button"
        class="submit"
        :disabled="!canReview || submitting"
        @click="submitReview()"
      >
        <Check /> {{ submitting ? '提交中…' : '提交并进入下一批' }} <kbd v-if="!submitting">Space / Enter</kbd>
      </button>
    </footer>
  </section>
</template>

<style scoped>
.review-workbench {
  --ink: #12261f;
  --muted: #62706b;
  --line: #dfe6e1;
  --paper: #f7f5ef;
  --green: #00a987;
  --green-dark: #08745f;
  --orange: #f5a524;
  display: grid;
  height: calc(100vh - 92px);
  min-height: 650px;
  grid-template-rows: 62px minmax(0, 1fr) 72px;
  overflow: hidden;
  color: var(--ink);
  background: var(--paper);
}

button {
  font: inherit;
}

.workbench-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 8px 20px;
  border-bottom: 1px solid var(--line);
  background: #fffefa;
}

.task-context {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 10px;
}

.task-context strong {
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-context > span:last-child {
  color: var(--muted);
  font-size: 12px;
}

.context-kicker,
.toolbar-kicker,
.rail-heading {
  color: var(--green-dark);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.progress-block {
  display: grid;
  min-width: 270px;
  grid-template-columns: auto 110px 38px;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}

.progress-track {
  height: 6px;
  overflow: hidden;
  border-radius: 99px;
  background: #dfe9e4;
}

.progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--green);
  transition: width .2s ease;
}

.workbench-grid {
  display: grid;
  min-height: 0;
  grid-template-columns: 92px minmax(430px, 1fr) minmax(300px, 350px);
  gap: 14px;
  padding: 14px;
}

.photo-rail {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  scrollbar-width: thin;
}

.rail-heading {
  padding: 2px 4px 4px;
}

.photo-thumb {
  position: relative;
  flex: 0 0 102px;
  overflow: hidden;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 9px;
  background: #dfe4e1;
  cursor: pointer;
  transition: border-color .15s ease, transform .15s ease;
}

.photo-thumb:hover {
  transform: translateY(-1px);
}

.photo-thumb.active {
  border-color: var(--green);
  box-shadow: 0 0 0 2px rgba(0, 169, 135, .14);
}

.photo-thumb.complete .photo-badge {
  background: var(--green);
}

.photo-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.photo-index,
.photo-badge {
  position: absolute;
  display: grid;
  place-items: center;
  border-radius: 99px;
  color: #fff;
}

.photo-index {
  top: 5px;
  left: 5px;
  width: 23px;
  height: 23px;
  background: rgba(18, 38, 31, .86);
  font-size: 11px;
  font-weight: 800;
}

.photo-badge {
  right: 5px;
  bottom: 5px;
  width: 22px;
  height: 22px;
  background: var(--orange);
}

.photo-badge :deep(svg) {
  width: 13px;
}

.image-column {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: auto auto minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid #253a33;
  border-radius: 14px;
  background: #152620;
  box-shadow: 0 10px 28px rgba(18, 38, 31, .13);
}

.image-column.can-annotate {
  grid-template-rows: auto auto auto minmax(0, 1fr);
}

.image-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 14px;
  color: #f6faf8;
}

.viewer-controls {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.image-toolbar > div:first-child {
  display: grid;
  gap: 2px;
}

.image-toolbar .toolbar-kicker {
  color: #8bd6c2;
}

.zoom-tools {
  display: flex;
  align-items: center;
  gap: 4px;
}

.zoom-tools button {
  display: inline-flex;
  height: 30px;
  align-items: center;
  gap: 4px;
  justify-content: center;
  padding: 0 8px;
  border: 1px solid #4b625a;
  border-radius: 7px;
  color: #eafff8;
  background: #263c34;
  cursor: pointer;
}

.zoom-tools button:not(.reset-view) {
  width: 30px;
  padding: 0;
}

.zoom-tools :deep(svg) {
  width: 14px;
}

.zoom-tools > span {
  min-width: 46px;
  color: #a9bbb5;
  font-size: 11px;
  text-align: center;
}

.gesture-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-block: 1px solid #3d514a;
  color: #c7d6d1;
  background: #20332d;
  font-size: 12px;
}

.gesture-hint :deep(svg) {
  width: 16px;
  color: #62d0b5;
}

.gesture-hint strong {
  color: #fff;
}

.image-viewport {
  position: relative;
  min-height: 0;
  overflow: hidden;
  cursor: crosshair;
  touch-action: none;
  user-select: none;
  background:
    radial-gradient(circle at center, rgba(255, 255, 255, .04), transparent 48%),
    #0d1915;
}

.image-plane {
  position: absolute;
  transform-origin: center;
  transition: transform .08s linear;
}

.image-plane > img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  opacity: 0;
  pointer-events: none;
}

.image-plane > img.loaded {
  opacity: 1;
}

.image-load-state {
  position: absolute;
  z-index: 30;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  padding: 28px;
  color: #e8f4ef;
  background: rgba(13, 25, 21, .82);
  text-align: center;
}

.image-load-state > span:not(.image-spinner) {
  max-width: 420px;
  color: #a9bbb5;
  font-size: 12px;
}

.image-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid rgba(139, 214, 194, .25);
  border-top-color: #8bd6c2;
  border-radius: 50%;
  animation: image-spin .8s linear infinite;
}

.image-load-error :deep(svg) {
  width: 32px;
  color: #f5a524;
}

.image-fallback-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 6px;
}

.image-fallback-actions button,
.image-fallback-actions a {
  display: inline-flex;
  height: 34px;
  align-items: center;
  padding: 0 14px;
  border: 1px solid #587168;
  border-radius: 8px;
  color: #effbf7;
  background: #263c34;
  font-size: 13px;
  font-weight: 700;
  text-decoration: none;
  cursor: pointer;
}

.image-fallback-actions button:hover,
.image-fallback-actions a:hover {
  border-color: #8bd6c2;
  background: #315047;
}

@keyframes image-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .image-spinner { animation: none; }
}

/* ---- 标注工具条 ---- */
.annotate-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.tool-group { display: flex; gap: 6px; }
.review-action-group { flex-wrap: wrap; }

.bar-sep {
  width: 1px;
  height: 18px;
  background: var(--el-border-color);
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.tool-btn kbd {
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(0, 0, 0, .09);
  font-size: 10px;
  font-family: inherit;
}

.tool-btn.on { background: #2f6fdd; border-color: #2f6fdd; color: #fff; }
.tool-btn.on kbd { background: rgba(255, 255, 255, .25); }
.tool-btn.label-btn.on { color: #fff; }
.tool-btn.supplement-btn { border-style: dashed; }

/* 十字光标明确提示当前正在补问题框。 */
.image-viewport.tool-box { cursor: crosshair; }
.image-viewport.tool-select { cursor: default; }
.image-viewport.tool-supplement { cursor: copy; }
.image-viewport.shift-panning,
.image-viewport.shift-panning .annotation-box,
.image-viewport.shift-panning .object-final-box { cursor: grab; }

/* ---- 落笔预览：一律不接指针事件，否则会截断正在进行的拖拽 ---- */
.draft-rect {
  position: absolute;
  z-index: 5;
  border: 2px dashed;
  border-radius: 4px;
  background: rgba(47, 111, 221, .10);
  pointer-events: none;
}

/* 单层细框：原先是 3px 边框 + 1px 白描边 (选中时再叠 3px 白 + 6px 绿 = 一圈 9px)，
   在密排的肉盒上糊成一片。改为 2px 单线，选中只加深不加层。 */
.annotation-box {
  position: absolute;
  z-index: 2;
  border: 2px solid;
  border-radius: 4px;
  background: var(--overlay-fill);
  cursor: move;
}

.annotation-box.pending {
  border-style: solid;
}

.annotation-box.human {
  background: var(--overlay-fill);
}

.annotation-box.selected {
  border-width: 3px;
  z-index: 4;
  filter: drop-shadow(0 0 3px rgba(0, 0, 0, .55));
}

.resize-handle {
  position: absolute;
  right: -9px;
  bottom: -9px;
  width: 18px;
  height: 18px;
  border: 3px solid #fff;
  border-radius: 99px;
  background: #17332a;
  cursor: nwse-resize;
}

.zoom-indicator {
  position: absolute;
  right: 12px;
  bottom: 12px;
  z-index: 8;
  padding: 6px 9px;
  border-radius: 7px;
  color: #d9eee7;
  background: rgba(12, 30, 24, .82);
  font-size: 11px;
  pointer-events: none;
}

.decision-rail {
  display: grid;
  min-height: 0;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 12px;
  overflow: hidden;
}

.decision-scroll {
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
}

.must-act-card,
.whole-photo-card,
.analysis-error {
  border: 1px solid var(--line);
  border-radius: 13px;
  background: #fff;
}

.must-act-card {
  margin-bottom: 12px;
  padding: 14px;
  border-top: 4px solid var(--orange);
}

.must-act-card.resolved {
  border-top-color: var(--green);
}

.must-act-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.must-act-heading > span {
  font-size: 14px;
  font-weight: 900;
}

.must-act-heading em {
  padding: 4px 8px;
  border-radius: 99px;
  color: #9a5c00;
  background: #fff1d3;
  font-size: 11px;
  font-style: normal;
  font-weight: 800;
}

.must-act-heading em.done {
  color: var(--green-dark);
  background: #dff6ef;
}

.candidate-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 14px 0 8px;
}

.candidate-title i {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 9px;
  color: #fff;
  font-size: 12px;
  font-style: normal;
  font-weight: 900;
}

.candidate-title div {
  display: grid;
  gap: 2px;
}

.candidate-title span,
.whole-photo-card span,
.analysis-error span,
.no-selection span {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.45;
}

.human-title i {
  background: #2f6fdd;
}

.evidence {
  margin: 8px 0;
  padding: 8px;
  border-radius: 7px;
  color: #6f5940;
  background: #fff8e9;
  font-size: 11px;
  line-height: 1.5;
}

.primary-decisions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 10px;
}

.primary-decisions button,
.whole-photo-card button,
.human-decisions button,
.correction-row button {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 8px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}

.primary-decisions :deep(svg),
.whole-photo-card :deep(svg),
.human-decisions :deep(svg) {
  width: 15px;
}

.primary-decisions kbd,
.correction-row kbd,
.human-decisions kbd,
.label-supplement-actions kbd {
  flex: 0 0 auto;
  padding: 1px 5px;
  border: 1px solid currentColor;
  border-radius: 4px;
  font: 700 10px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace;
  opacity: .76;
}

.primary-decisions .confirm {
  border: 1px solid var(--green);
  color: #fff;
  background: var(--green);
}

.primary-decisions .reject {
  border: 1px solid #f0aaa5;
  color: #b83127;
  background: #fff1ef;
}

.correction-row,
.human-decisions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin-top: 8px;
}

.correction-row button,
.human-decisions button {
  min-height: 34px;
  border: 1px solid #d7ded9;
  color: #34443e;
  background: #f7f8f5;
  font-size: 11px;
}

.human-decisions {
  grid-template-columns: 1fr 1fr;
  margin-top: 14px;
}

.human-decisions .delete {
  color: #b83127;
  border-color: #f0aaa5;
  background: #fff5f3;
}

button:disabled {
  cursor: not-allowed !important;
  opacity: .45;
}

.no-selection {
  display: grid;
  justify-items: center;
  gap: 5px;
  padding: 26px 8px 17px;
  text-align: center;
}

.no-selection :deep(svg) {
  width: 30px;
  color: var(--green);
}

.review-note {
  margin-top: 10px;
}

.whole-photo-card {
  display: grid;
  gap: 9px;
  padding: 14px;
  border-color: #c8dfd7;
  background: #f1fbf7;
  box-shadow: 0 -6px 18px rgba(18, 38, 31, .07);
}

.whole-photo-card.complete {
  border-color: #8ed5c2;
  background: #e5f8f1;
}

.whole-photo-card > div:not(.step-label) {
  display: grid;
  gap: 3px;
}

.step-label {
  justify-self: start;
  padding: 4px 8px;
  border-radius: 99px;
  color: #fff;
  background: var(--green);
  font-size: 10px;
  font-weight: 900;
}

.whole-photo-card .whole-confirm {
  border: 1px solid var(--green);
  color: #fff;
  background: var(--green);
}

.whole-photo-card .whole-normal,
.whole-photo-card .reopen {
  border: 1px solid #8bcdbb;
  color: var(--green-dark);
  background: #fff;
}

.rejected-history {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid #ead7b2;
  border-radius: 11px;
  background: #fffaf0;
}

.rejected-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.rejected-heading > div {
  display: grid;
  gap: 2px;
}

.rejected-heading strong {
  font-size: 12px;
}

.rejected-heading span {
  color: var(--muted);
  font-size: 10px;
  line-height: 1.4;
}

.rejected-heading em {
  display: grid;
  min-width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 99px;
  color: #8a5013;
  background: #ffe8bc;
  font-size: 11px;
  font-style: normal;
  font-weight: 900;
}

.rejected-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0e3ca;
}

.rejected-item span {
  overflow: hidden;
  color: #685845;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rejected-item button {
  flex: 0 0 auto;
  padding: 4px 8px;
  border: 1px solid #d9ad70;
  border-radius: 6px;
  color: #80501e;
  background: #fff;
  font-size: 10px;
  font-weight: 800;
  cursor: pointer;
}

.analysis-error {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 9px;
  margin-top: 12px;
  padding: 12px;
  color: #8a5013;
  background: #fff8e9;
}

.analysis-error :deep(svg) {
  width: 20px;
}

.analysis-error div {
  display: grid;
}

.analysis-error button {
  border: 1px solid #d8aa71;
  border-radius: 7px;
  color: #8a5013;
  background: #fff;
}

.review-navigation {
  display: grid;
  grid-template-columns: 126px minmax(160px, 1fr) minmax(190px, 250px);
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-top: 1px solid var(--line);
  background: #fffefa;
  box-shadow: 0 -8px 24px rgba(18, 38, 31, .07);
}

.shortcut-hints {
  display: flex;
  gap: 14px;
  margin-top: 6px;
  flex-wrap: wrap;
  justify-content: center;
}

.shortcut {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--el-text-color-secondary, #8a94a6);
  white-space: nowrap;
}

.layer-toggles {
  display: flex;
  gap: 8px;
  justify-content: center;
}

.layer-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 32px;
  padding: 4px 9px;
  border: 2px solid var(--layer-color);
  border-radius: 8px;
  background: color-mix(in srgb, var(--layer-color) 22%, #14231e);
  font-size: 12px;
  color: #fff;
  font-weight: 800;
  cursor: pointer;
  white-space: nowrap;
}

.layer-toggle .dot {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  background: var(--layer-color);
}

.layer-toggle.off {
  opacity: .55;
  background: transparent;
}

.layer-toggle.off .dot {
  background: transparent;
  border: 1px solid var(--layer-color);
}

.shortcut kbd,
.layer-toggle kbd,
.review-navigation > button kbd {
  display: inline-block;
  min-width: 18px;
  padding: 1px 6px;
  border: 1px solid var(--el-border-color, #d9dde5);
  border-bottom-width: 2px;
  border-radius: 4px;
  background: var(--el-fill-color-light, #f4f6f9);
  font: 600 11px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--el-text-color-regular, #4a5262);
}

.review-navigation > button {
  display: inline-flex;
  height: 48px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 10px;
  font-weight: 800;
  cursor: pointer;
}

.review-navigation .previous {
  border: 1px solid #d9dedb;
  color: #53615c;
  background: #f8f7f3;
}

.review-navigation .next,
.review-navigation .submit {
  border: 1px solid var(--green);
  color: #fff;
  background: var(--green);
}

.review-navigation :deep(svg) {
  width: 17px;
}

.current-state {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 9px;
}

.current-state span {
  color: var(--muted);
  font-size: 12px;
}

.current-state strong {
  color: #a45e0b;
}

.current-state.complete strong {
  color: var(--green-dark);
}

@media (max-width: 1180px) {
  .workbench-grid {
    grid-template-columns: 76px minmax(380px, 1fr) 290px;
    gap: 10px;
    padding: 10px;
  }

  .photo-thumb {
    flex-basis: 88px;
  }

  .decision-rail {
    font-size: 12px;
  }

  .primary-decisions {
    grid-template-columns: 1fr;
  }

  .correction-row {
    grid-template-columns: 1fr 1fr;
  }

  .correction-row button:last-child {
    grid-column: 1 / -1;
  }
}

@media (max-width: 880px) {
  .review-workbench {
    height: auto;
    min-height: calc(100vh - 70px);
    grid-template-rows: auto auto 72px;
    overflow: visible;
  }

  .workbench-status {
    align-items: flex-start;
    flex-direction: column;
  }

  .progress-block {
    width: 100%;
  }

  .workbench-grid {
    grid-template-columns: 1fr;
  }

  .photo-rail {
    flex-direction: row;
    overflow-x: auto;
  }

  .rail-heading {
    display: none;
  }

  .photo-thumb {
    min-width: 74px;
    height: 82px;
    flex-basis: 74px;
  }

  .image-column {
    height: 620px;
  }
}
.object-final-box {
  position: absolute;
  z-index: 2;
  border: 3px solid;
  border-radius: 3px;
  background: var(--overlay-fill);
  box-shadow: 0 0 0 1px rgb(255 255 255 / 72%);
  pointer-events: auto;
  cursor: move;
}

.object-final-box.muted { opacity: .68; }
.object-final-box.selected { z-index: 3; box-shadow: 0 0 0 3px rgb(255 255 255 / 80%); }
.object-final-box.reference-label-box {
  border-width: 2px;
  opacity: .82;
  pointer-events: none;
  cursor: default;
}
.supplemental-label-box { z-index: 3; }
.object-review-card {
  margin-bottom: 12px;
  padding: 14px;
  border: 1px solid #bfdbfe;
  border-radius: 12px;
  background: #eff6ff;
}
.object-review-summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.object-review-summary strong, .object-review-summary span { display: block; }
.object-review-summary p { margin: 6px 0 0; color: var(--muted); font-size: 12px; }
.object-review-summary button, .object-review-heading-actions button { min-height: 36px; border: 1px solid #cbd8d2; border-radius: 8px; background: #fff; cursor: pointer; }
.object-review-heading-actions { display: flex; gap: 8px; }

.label-coverage-card {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid #d7e4f0;
  border-radius: 10px;
  background: #fff;
}

.label-coverage-card.warning {
  border-color: #f0c77d;
  background: #fffaf1;
}

.label-coverage-heading,
.label-coverage-counts {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.label-coverage-heading > strong { color: #1a2332; font-size: 13px; }
.label-coverage-heading > span { color: #7a8599; font-size: 11px; }
.label-coverage-counts > span { color: #627086; font-size: 12px; }
.label-coverage-counts > span > strong { color: #1a2332; }

.label-coverage-warning,
.label-coverage-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
}

.label-coverage-warning { color: #975a16; }
.label-coverage-note { color: #607086; }

.label-supplement-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.label-supplement-actions button,
.supplemented-label-list button {
  min-height: 36px;
  border: 1px solid var(--supplement-color, #d7e4f0);
  border-radius: 8px;
  color: var(--supplement-color, #526074);
  background: #fff;
  cursor: pointer;
}

.label-supplement-actions button.active {
  color: #fff;
  background: var(--supplement-color);
}

.supplemented-label-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.supplemented-label-list button { min-height: 30px; padding: 0 8px; font-size: 11px; }
.label-coverage-card > small { color: #7a8599; font-size: 11px; line-height: 1.5; }

.missing-label-guide {
  display: grid;
  gap: 8px;
  margin: 12px 0;
  padding: 11px 12px;
  border: 1px solid #f4b35e;
  border-left: 5px solid #e47c00;
  border-radius: 9px;
  color: #5c3b12;
  background: #fff8e9;
}

.missing-label-guide > strong {
  font-size: 13px;
}

.missing-label-guide > div {
  display: grid;
  gap: 5px;
}

.missing-label-guide span {
  color: #6f512d;
  font-size: 11px;
  line-height: 1.45;
}

.missing-label-guide b {
  display: inline-block;
  min-width: 96px;
  color: #9a4b00;
}

.object-review-heading,
.tray-context-row,
.object-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.object-review-heading div { display: grid; gap: 2px; }
.object-review-heading span { color: #1d4ed8; font-size: 11px; font-weight: 800; }
.object-review-heading button,
.tray-chip-row button,
.presence-editor button,
.object-actions button,
.confirm-tray,
.confirm-all-trays {
  min-height: 36px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
}

.tray-chip-row { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.tray-chip-row button { min-width: 42px; font-weight: 800; }
.tray-chip-row button.active { border-color: #2563eb; background: #dbeafe; }
.tray-chip-row button.done { color: #047857; border-color: #6ee7b7; }
.tray-context-row { margin: 10px 0; }
.tray-context-row span { color: #475569; font-size: 12px; }
.danger-link { color: #b91c1c !important; }
.presence-editor { display: grid; gap: 8px; }
.presence-editor > div { display: grid; grid-template-columns: 48px repeat(3, minmax(62px, 1fr)); gap: 6px; align-items: center; }
.presence-editor button.on { color: #fff; border-color: #2563eb; background: #2563eb; }
.presence-editor .add-label { grid-column: 2 / -1; color: #1d4ed8; }
.object-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
.object-list button { display: inline-flex; align-items: center; gap: 5px; min-height: 34px; border: 1px solid #cbd5e1; border-radius: 7px; background: #fff; }
.object-list button.active { border-color: #2563eb; box-shadow: 0 0 0 2px #bfdbfe; }
.object-list i { width: 9px; height: 9px; border-radius: 50%; }
.object-list em { color: #64748b; font-size: 10px; font-style: normal; }
.object-actions { justify-content: flex-start; flex-wrap: wrap; }
.object-actions button.on { color: #fff; background: #475569; }
.confirm-tray { width: 100%; margin-top: 12px; color: #fff; border-color: #047857; background: #047857; font-weight: 800; }
.confirm-all-trays { width: 100%; margin-top: 7px; color: #1d4ed8; }
.empty-object-review { margin-top: 10px; color: #475569; font-size: 12px; line-height: 1.6; }
.object-auto-confirm-hint { margin: 10px 0 0; color: #526779; font-size: 12px; line-height: 1.55; }
</style>
