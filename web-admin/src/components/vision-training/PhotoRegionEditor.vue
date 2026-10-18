<template>
  <div class="region-editor" data-testid="photo-region-editor">
    <div class="region-toolbar">
      <label for="region-label">要标记的位置</label>
      <select id="region-label" :value="activeLabel" :disabled="disabled || !labels.length" @change="onLabelChange">
        <option v-for="label in labels" :key="label.id" :value="label.id">{{ label.name }}</option>
      </select>
    </div>
    <div class="region-toolbar" aria-label="照片查看工具">
      <button type="button" class="touch-button" :disabled="zoom <= 1 || !imageSrc" @click="setZoom(zoom / 1.25)">缩小</button>
      <span class="zoom-value" aria-live="polite">{{ Math.round(zoom * 100) }}%</span>
      <button type="button" class="touch-button" :disabled="zoom >= 6 || !imageSrc" @click="setZoom(zoom * 1.25)">放大</button>
      <button type="button" class="touch-button" :disabled="!imageSrc" @click="resetZoom">适应画面</button>
    </div>
    <div class="region-toolbar" aria-label="照片标注工具">
      <button type="button" class="touch-button" :class="{ active: drawMode === 'add' }" :disabled="disabled || !imageSrc" @click="drawMode = 'add'">新增框</button>
      <button type="button" class="touch-button" :class="{ active: drawMode === 'replace' }" :disabled="disabled || !selectedBox" @click="drawMode = 'replace'">重新拉框</button>
      <button type="button" class="touch-button" :disabled="disabled || !selectedBox" @click="removeSelected">删除选中框</button>
      <button type="button" class="touch-button" :disabled="disabled || !undoCount" @click="undo">撤销</button>
      <button type="button" class="touch-button" :disabled="disabled || !boxes.length" @click="commit([])">清除框</button>
    </div>
    <div ref="viewport" class="region-viewport" @wheel.prevent="wheelZoom">
      <div ref="stage" class="region-stage" :class="{ disabled }" :style="{ width: (zoom * 100) + '%' }">
        <img v-if="imageSrc" :src="imageSrc" alt="待审核照片" draggable="false" />
        <div v-else class="region-empty">正在读取照片</div>
        <svg v-if="imageSrc" viewBox="0 0 1 1" preserveAspectRatio="none" tabindex="0" role="group" aria-label="照片标注区域"
          @pointerdown="beginDraw" @pointermove="moveDraw" @pointerup="finishDraw" @pointercancel="cancelDraw" @keydown="onKeydown">
          <rect v-for="(box, index) in displayBoxes" :key="box.label + '-' + index" :x="Math.min(box.x0, box.x1)" :y="Math.min(box.y0, box.y1)"
            :width="Math.abs(box.x1 - box.x0)" :height="Math.abs(box.y1 - box.y0)" class="region-box" :class="{ selected: selectedIndex === index }" />
          <rect v-if="draftBox" :x="draftBox.x0" :y="draftBox.y0" :width="draftBox.x1 - draftBox.x0" :height="draftBox.y1 - draftBox.y0" class="region-box drawing" />
        </svg>
      </div>
    </div>
    <p v-if="drawMode === 'replace'" class="region-help">拖出新的区域后才替换旧框；未画完不会删除旧框。</p>
    <p v-else class="region-help">{{ disabled ? '当前照片只读，可放大查看。' : '拖空白处新增框；拖框内移动，拖四角调整。画错可撤销，滚轮可放大。' }}</p>
    <div v-if="boxes.length" class="region-list" aria-label="已标记区域">
      <button v-for="(box, index) in boxes" :key="index" type="button" class="region-chip" :aria-pressed="selectedIndex === index" @click="selectBox(index)">
        区域 {{ index + 1 }} · {{ labels.find(label => label.id === box.label)?.name || '检查区域' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import type { VisionPhotoBox } from '@/api/visionPreparation';
import { clamp, hitBox, moveBox, resizeBox, normaliseBox, zoomAnchor, createBoxHistory, type BoxCoordinates } from './boxEditorCore.js';

const props = withDefaults(defineProps<{
  imageSrc: string;
  boxes: VisionPhotoBox[];
  labels: Array<{ id: string; name: string }>;
  activeLabel?: string;
  disabled?: boolean;
}>(), { activeLabel: '', disabled: false });
const emit = defineEmits<{
  'update:boxes': [boxes: VisionPhotoBox[]];
  'update:activeLabel': [label: string];
}>();
const viewport = ref<HTMLDivElement | null>(null), stage = ref<HTMLDivElement | null>(null);
const selectedIndex = ref(-1), zoom = ref(1), undoCount = ref(0);
const drawMode = ref<'auto' | 'add' | 'replace'>('auto');
const history = createBoxHistory<VisionPhotoBox>();
const previewBoxes = ref<VisionPhotoBox[] | null>(null), draftBox = ref<VisionPhotoBox | null>(null);
type Point = { x: number; y: number; inside: boolean };
type Gesture = { mode: 'new' | 'move' | 'resize'; index: number; corner: number; start: Point; before: VisionPhotoBox[]; replacement: number; outside: boolean };
let gesture: Gesture | null = null;
const selectedBox = computed(() => props.boxes[selectedIndex.value] || null);
const displayBoxes = computed(() => previewBoxes.value || props.boxes);
const coordinates = (box: VisionPhotoBox): BoxCoordinates => [box.x0, box.y0, box.x1, box.y1];
const withCoordinates = (box: VisionPhotoBox, value: BoxCoordinates): VisionPhotoBox => ({ ...box, x0: value[0], y0: value[1], x1: value[2], y1: value[3] });
const copyBoxes = () => props.boxes.map(box => ({ ...box }));

function point(event: PointerEvent): Point | null {
  const rect = (event.currentTarget as SVGElement | null)?.getBoundingClientRect();
  if (!rect?.width || !rect.height) return null;
  return { x: clamp((event.clientX - rect.left) / rect.width), y: clamp((event.clientY - rect.top) / rect.height),
    inside: event.clientX >= rect.left && event.clientX <= rect.right && event.clientY >= rect.top && event.clientY <= rect.bottom };
}
function beginDraw(event: PointerEvent) {
  if (props.disabled || !props.labels.length || !props.imageSrc) return;
  const value = point(event); if (!value?.inside) return;
  const svg = event.currentTarget as SVGElement;
  svg.setPointerCapture?.(event.pointerId); svg.focus?.();
  const rect = svg.getBoundingClientRect();
  const hit = drawMode.value === 'auto' ? hitBox(props.boxes.map(coordinates), value.x, value.y, rect.width, rect.height) : { index: -1, mode: 'new' as const, corner: -1 };
  const replacement = drawMode.value === 'replace' ? selectedIndex.value : -1;
  gesture = { ...hit, start: value, before: copyBoxes(), replacement, outside: false };
  if (hit.mode === 'new') {
    draftBox.value = { label: replacement >= 0 ? props.boxes[replacement]!.label : props.activeLabel || props.labels[0]!.id,
      x0: value.x, y0: value.y, x1: value.x, y1: value.y };
  } else selectedIndex.value = hit.index;
}
function moveDraw(event: PointerEvent) {
  if (!gesture) return;
  const value = point(event); if (!value) return;
  const g = gesture;
  if (g.mode === 'new' && draftBox.value) {
    if (!value.inside) g.outside = true;
    draftBox.value = { ...draftBox.value, x0: Math.min(g.start.x, value.x), y0: Math.min(g.start.y, value.y), x1: Math.max(g.start.x, value.x), y1: Math.max(g.start.y, value.y) };
  } else {
    const box = g.before[g.index]; if (!box) return;
    const updated = g.mode === 'move' ? moveBox(coordinates(box), value.x - g.start.x, value.y - g.start.y) : resizeBox(coordinates(box), g.corner, value.x, value.y);
    previewBoxes.value = g.before.map((item, index) => index === g.index ? withCoordinates(item, updated) : item);
  }
}
function finishDraw(event: PointerEvent) {
  if (!gesture) return;
  if (props.disabled) { cancelDraw(); return; }
  moveDraw(event);
  const g = gesture; const result = g.before.map(box => ({ ...box }));
  if (g.mode === 'new' && draftBox.value && !g.outside) {
    const valid = normaliseBox(coordinates(draftBox.value), .02);
    if (valid) {
      const box = withCoordinates(draftBox.value, valid);
      if (g.replacement >= 0 && g.replacement < result.length) { result[g.replacement] = box; selectedIndex.value = g.replacement; }
      else { result.push(box); selectedIndex.value = result.length - 1; }
    }
  } else if (g.mode !== 'new' && previewBoxes.value?.[g.index]) {
    const valid = normaliseBox(coordinates(previewBoxes.value[g.index]!), .002);
    if (valid) result[g.index] = withCoordinates(result[g.index]!, valid);
  }
  cancelDraw(); drawMode.value = 'auto';
  if (result.length !== props.boxes.length || result.some((box, index) => {
    const before = props.boxes[index];
    return !before || box.label !== before.label || coordinates(box).some((value, axis) => value !== coordinates(before)[axis]);
  })) commit(result);
}
function cancelDraw() { gesture = null; draftBox.value = null; previewBoxes.value = null; }
function commit(boxes: VisionPhotoBox[]) {
  if (props.disabled) return;
  history.push(props.boxes); undoCount.value = history.length; emit('update:boxes', boxes);
}
function undo() {
  if (props.disabled) return;
  const previous = history.pop(); if (!previous) return;
  cancelDraw(); selectedIndex.value = -1; undoCount.value = history.length; emit('update:boxes', previous);
}
function removeSelected() {
  if (props.disabled || !selectedBox.value) return;
  commit(props.boxes.filter((_, index) => index !== selectedIndex.value)); selectedIndex.value = -1; drawMode.value = 'auto';
}
function selectBox(index: number) { selectedIndex.value = index; drawMode.value = 'auto'; }
function onLabelChange(event: Event) {
  if (props.disabled) return;
  const label = (event.target as HTMLSelectElement).value;
  if (!props.labels.some(item => item.id === label)) return;
  emit('update:activeLabel', label);
  // Choosing the next class must not silently relabel the existing selection.
  selectedIndex.value = -1; drawMode.value = 'add';
}
function onKeydown(event: KeyboardEvent) {
  if (props.disabled) return;
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') { event.preventDefault(); undo(); }
  else if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); removeSelected(); }
  else if (event.key === 'Escape') { cancelDraw(); drawMode.value = 'auto'; }
}
async function setZoom(next: number, anchor?: { x: number; y: number }) {
  if (!props.imageSrc || !viewport.value || !stage.value) return;
  const view = viewport.value, element = stage.value, rect = view.getBoundingClientRect();
  const localX = anchor ? anchor.x - rect.left : view.clientWidth / 2;
  const localY = anchor ? anchor.y - rect.top : view.clientHeight / 2;
  const x = zoomAnchor(view.scrollLeft, localX, element.clientWidth), y = zoomAnchor(view.scrollTop, localY, element.clientHeight);
  zoom.value = Math.max(1, Math.min(6, next)); await nextTick();
  if (viewport.value !== view || stage.value !== element) return;
  view.scrollLeft = x * element.clientWidth - localX; view.scrollTop = y * element.clientHeight - localY;
}
function resetZoom() { zoom.value = 1; if (viewport.value) { viewport.value.scrollLeft = 0; viewport.value.scrollTop = 0; } }
function wheelZoom(event: WheelEvent) { void setZoom(zoom.value * (event.deltaY < 0 ? 1.18 : 1 / 1.18), { x: event.clientX, y: event.clientY }); }
watch(() => [props.disabled, props.imageSrc], () => { cancelDraw(); selectedIndex.value = -1; drawMode.value = 'auto'; history.clear(); undoCount.value = 0; resetZoom(); });
watch(() => props.boxes.length, length => { if (selectedIndex.value >= length) selectedIndex.value = -1; });
</script>

<style scoped>
.region-editor { display: grid; gap: 10px; min-width: 0; }
.region-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.region-toolbar label { color: #1a2332; font-weight: 600; }
.region-toolbar select { min-height: 44px; min-width: 180px; max-width: 100%; padding: 0 10px; border: 1px solid #dbe4ef; border-radius: 6px; background: #fff; }
.touch-button { min-height: 44px; padding: 0 12px; border: 1px solid #cdd8e6; border-radius: 6px; background: #fff; cursor: pointer; font: inherit; }
.touch-button:disabled { cursor: not-allowed; opacity: .55; }.touch-button.active { border-color: #1b65a8; background: #eef7ff; color: #1b65a8; }
.zoom-value { min-width: 44px; text-align: center; color: #7a8599; }
.region-viewport { overflow: auto; max-height: 600px; border: 1px solid #dbe4ef; border-radius: 8px; background: #f4f6f9; }
.region-stage { position: relative; line-height: 0; }.region-stage img { display: block; width: 100%; height: auto; }
.region-stage svg { position: absolute; inset: 0; width: 100%; height: 100%; cursor: crosshair; touch-action: none; }
.region-stage svg:focus-visible { outline: 2px solid #1b65a8; outline-offset: -2px; }.region-stage.disabled svg { cursor: default; }
.region-box { fill: rgba(0, 167, 196, .20); stroke: #008a9f; stroke-width: 2; vector-effect: non-scaling-stroke; }
.region-box.selected { fill: rgba(255, 210, 63, .22); stroke: #a36a00; stroke-dasharray: 7 4; }
.region-box.drawing { fill: rgba(64, 158, 255, .18); stroke: #409eff; }
.region-empty { min-height: 260px; display: grid; place-items: center; color: #7a8599; }
.region-help { margin: 0; color: #7a8599; font-size: 13px; line-height: 1.6; }
.region-list { display: flex; gap: 8px; flex-wrap: wrap; max-height: 160px; overflow: auto; }
.region-chip { min-height: 36px; border: 1px solid #dbe4ef; border-radius: 6px; background: #fff; padding: 4px 10px; color: #1a2332; cursor: pointer; }
.region-chip[aria-pressed='true'] { border-color: #1b65a8; color: #1b65a8; background: #eef7ff; }
</style>
