<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import {
  listLabelQcPlatformCandidates,
  getLabelQcPhotoImageBlob,
  reviewLabelQcPlatformCandidate,
  type LabelQcBoundingBox,
  type LabelQcLabel,
  type LabelQcPlatformCandidate,
} from '@/api/labelQc';

const auth = useAuthStore();
const DEFAULT_PLATFORM_FACTORY_ID = 'LIUSHANMEN';
const targetFactoryId = computed(() => auth.factoryId || DEFAULT_PLATFORM_FACTORY_ID);
const reviewed = ref(false);
const candidates = ref<LabelQcPlatformCandidate[]>([]);
const selectedId = ref<string | null>(null);
const decision = ref<LabelQcLabel>('NO_DEFECT');
const notes = ref('');
const dirty = ref(false);
const loading = ref(false);
const saving = ref(false);
const total = ref(0);
const page = ref(1);
const pageSize = 40;
const proxiedImageUrl = ref<string | null>(null);
let imageRequestToken = 0;

const selected = computed(
  () => candidates.value.find((item) => item.id === selectedId.value) ?? null,
);
const selectedImageUrl = computed(() => proxiedImageUrl.value || selected.value?.imageUrl || '');

async function loadSelectedImage(): Promise<void> {
  const item = selected.value;
  const token = ++imageRequestToken;
  const previousUrl = proxiedImageUrl.value;
  proxiedImageUrl.value = null;
  if (previousUrl) URL.revokeObjectURL(previousUrl);
  if (!item) return;
  try {
    const blob = await getLabelQcPhotoImageBlob(targetFactoryId.value, item.photoId, true);
    if (token !== imageRequestToken) return;
    proxiedImageUrl.value = URL.createObjectURL(blob);
  } catch {
    // Keep the original signed URL as a last-resort fallback.
  }
}

const fullCanvasStyle = computed(() => {
  const item = selected.value;
  return item ? { aspectRatio: `${item.imageWidth} / ${item.imageHeight}` } : {};
});

const candidateStyle = computed(() => {
  const box = selected.value?.bbox;
  if (!box) return {};
  return {
    left: `${box.xMin * 100}%`,
    top: `${box.yMin * 100}%`,
    width: `${(box.xMax - box.xMin) * 100}%`,
    height: `${(box.yMax - box.yMin) * 100}%`,
  };
});

const paddedCrop = computed<LabelQcBoundingBox | null>(() => {
  const box = selected.value?.bbox;
  if (!box) return null;
  const width = box.xMax - box.xMin;
  const height = box.yMax - box.yMin;
  const padX = Math.max(width * 0.45, 0.04);
  const padY = Math.max(height * 1.8, 0.04);
  return {
    xMin: Math.max(0, box.xMin - padX),
    yMin: Math.max(0, box.yMin - padY),
    xMax: Math.min(1, box.xMax + padX),
    yMax: Math.min(1, box.yMax + padY),
  };
});

const cropCanvasStyle = computed(() => {
  const item = selected.value;
  const crop = paddedCrop.value;
  if (!item || !crop) return {};
  return {
    aspectRatio: `${item.imageWidth * (crop.xMax - crop.xMin)} / ${item.imageHeight * (crop.yMax - crop.yMin)}`,
  };
});

const cropImageStyle = computed(() => {
  const crop = paddedCrop.value;
  if (!crop) return {};
  const width = crop.xMax - crop.xMin;
  const height = crop.yMax - crop.yMin;
  return {
    width: `${100 / width}%`,
    height: `${100 / height}%`,
    left: `${-crop.xMin / width * 100}%`,
    top: `${-crop.yMin / height * 100}%`,
  };
});

const cropCandidateStyle = computed(() => {
  const box = selected.value?.bbox;
  const crop = paddedCrop.value;
  if (!box || !crop) return {};
  const width = crop.xMax - crop.xMin;
  const height = crop.yMax - crop.yMin;
  return {
    left: `${(box.xMin - crop.xMin) / width * 100}%`,
    top: `${(box.yMin - crop.yMin) / height * 100}%`,
    width: `${(box.xMax - box.xMin) / width * 100}%`,
    height: `${(box.yMax - box.yMin) / height * 100}%`,
  };
});

function resetDraft(item: LabelQcPlatformCandidate | null): void {
  decision.value = item?.humanLabel ?? 'NO_DEFECT';
  notes.value = item?.reviewerNotes ?? '';
  dirty.value = false;
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const response = await listLabelQcPlatformCandidates(targetFactoryId.value, {
      reviewed: reviewed.value,
      page: page.value,
      size: pageSize,
    });
    candidates.value = response.data?.content ?? [];
    total.value = response.data?.totalElements ?? 0;
    selectedId.value = candidates.value[0]?.id ?? null;
    resetDraft(candidates.value[0] ?? null);
  } finally {
    loading.value = false;
  }
}

async function saveCurrent(advance = false): Promise<boolean> {
  const item = selected.value;
  if (!dirty.value || !item) return true;
  saving.value = true;
  try {
    const response = await reviewLabelQcPlatformCandidate(targetFactoryId.value, item.id, {
      expectedVersion: item.version,
      label: decision.value,
      bbox: item.bbox,
      notes: notes.value.trim() || undefined,
    });
    if (!response.data) return false;
    const index = candidates.value.findIndex((candidate) => candidate.id === item.id);
    if (!reviewed.value) {
      candidates.value.splice(index, 1);
      total.value = Math.max(0, total.value - 1);
      if (!candidates.value.length && total.value > 0) {
        await load();
        return true;
      }
      const next = candidates.value[Math.min(index, candidates.value.length - 1)] ?? null;
      selectedId.value = next?.id ?? null;
      resetDraft(next);
    } else if (index >= 0) {
      candidates.value[index] = response.data;
      resetDraft(response.data);
    }
    ElMessage.success(advance ? '已保存，继续下一项' : '诊断结果已保存');
    return true;
  } catch {
    return false;
  } finally {
    saving.value = false;
  }
}

function choose(label: LabelQcLabel, defaultNotes: string): void {
  decision.value = label;
  if (!notes.value.trim()) notes.value = defaultNotes;
  dirty.value = true;
}

async function selectCandidate(item: LabelQcPlatformCandidate): Promise<void> {
  if (item.id === selectedId.value) return;
  if (!await saveCurrent()) return;
  selectedId.value = item.id;
  resetDraft(item);
}

async function changeQueue(next: boolean): Promise<void> {
  if (next === reviewed.value) return;
  if (!await saveCurrent()) return;
  reviewed.value = next;
  page.value = 1;
  await load();
}

async function changePage(nextPage: number): Promise<void> {
  if (!await saveCurrent()) return;
  page.value = nextPage;
  await load();
}

function onKeydown(event: KeyboardEvent): void {
  const target = event.target as HTMLElement | null;
  if (target?.matches('input, textarea, select, [contenteditable="true"]')) return;
  const actions: Record<string, [LabelQcLabel, string]> = {
    '1': ['NO_DEFECT', '背景、重复或错误盒框，不是客户缺标风险'],
    '2': ['UNJUDGEABLE', '当前画面不足以形成可靠人工真值'],
    '3': ['MISSING_WHITE_LABEL', '平台复核确认缺少白标'],
    '4': ['MISSING_COLOR_LABEL', '平台复核确认缺少彩标'],
  };
  const action = actions[event.key];
  if (!action || saving.value || !selected.value) return;
  event.preventDefault();
  choose(action[0], action[1]);
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  void load();
});
watch(() => selected.value?.id, () => { void loadSelectedImage(); });
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown);
  if (proxiedImageUrl.value) URL.revokeObjectURL(proxiedImageUrl.value);
});
</script>

<template>
  <section class="page-container" v-loading="loading">
    <header class="page-header">
      <div>
        <p class="eyebrow">平台专用 · 六扇门 · 不进入工厂审核量</p>
        <h1>标签识别诊断</h1>
        <p class="subtitle">这里只处理盒框过小、远景碎片和标签归属冲突；真正缺标仍留在工厂队列。</p>
      </div>
      <div class="queue-switch" aria-label="诊断队列">
        <button :class="{ active: !reviewed }" type="button" @click="changeQueue(false)">待判断 {{ !reviewed ? total : '' }}</button>
        <button :class="{ active: reviewed }" type="button" @click="changeQueue(true)">已完成 {{ reviewed ? total : '' }}</button>
      </div>
    </header>

    <div v-if="candidates.length" class="workspace">
      <aside class="queue-card">
        <div class="queue-title"><strong>逐项判断</strong><span>{{ candidates.length }} / {{ total }}</span></div>
        <button
          v-for="(item, index) in candidates"
          :key="item.id"
          type="button"
          class="queue-item"
          :class="{ active: item.id === selectedId }"
          @click="selectCandidate(item)"
        >
          <span class="queue-index">{{ index + 1 }}</span>
          <span><strong>照片 {{ item.photoId.slice(0, 8) }}</strong><small>{{ item.aiEvidence || '盒框无法可靠判断' }}</small></span>
        </button>
      </aside>

      <main v-if="selected" class="review-card">
        <div class="problem-banner">
          <span class="problem-dot" />
          <div><strong>只看红框这一处</strong><p>其他框已隐藏。系统没有判定它缺标，只是不确定这个盒框是否有效。</p></div>
          <span class="confidence">AI {{ Math.round((selected.aiConfidence ?? 0) * 100) }}%</span>
        </div>

        <div class="visual-grid">
          <section>
            <div class="visual-title"><strong>全图位置</strong><span>确认它是否属于当前工作台</span></div>
            <div class="full-canvas" :style="fullCanvasStyle">
              <img :src="selectedImageUrl" alt="诊断原图" draggable="false">
              <div class="problem-box" :style="candidateStyle"><span>当前问题</span></div>
            </div>
          </section>
          <section>
            <div class="visual-title"><strong>局部放大</strong><span>红框保持原始位置和大小</span></div>
            <div class="crop-canvas" :style="cropCanvasStyle">
              <img :src="selectedImageUrl" :style="cropImageStyle" alt="问题区域放大" draggable="false">
              <div class="problem-box" :style="cropCandidateStyle"><span>当前问题</span></div>
            </div>
          </section>
        </div>

        <div class="decision-panel">
          <div class="decision-copy"><strong>你看到的实际情况是？</strong><span>先选择，再确认保存；数字键只选择，不会误提交。</span></div>
          <div class="decision-grid">
            <button class="safe" :class="{ selected: decision === 'NO_DEFECT' }" type="button" :disabled="saving" @click="choose('NO_DEFECT', '背景、重复或错误盒框，不是客户缺标风险')"><kbd>1</kbd><strong>误报 / 无问题</strong><small>背景、碎片、重复框或错框</small></button>
            <button :class="{ selected: decision === 'UNJUDGEABLE' }" type="button" :disabled="saving" @click="choose('UNJUDGEABLE', '当前画面不足以形成可靠人工真值')"><kbd>2</kbd><strong>仍然不确定</strong><small>保留为困难样本，不当缺标真值</small></button>
            <button class="danger" :class="{ selected: decision === 'MISSING_WHITE_LABEL' }" type="button" :disabled="saving" @click="choose('MISSING_WHITE_LABEL', '平台复核确认缺少白标')"><kbd>3</kbd><strong>确认缺白标</strong><small>平台发现真实缺标</small></button>
            <button class="danger" :class="{ selected: decision === 'MISSING_COLOR_LABEL' }" type="button" :disabled="saving" @click="choose('MISSING_COLOR_LABEL', '平台复核确认缺少彩标')"><kbd>4</kbd><strong>确认缺彩标</strong><small>平台发现真实缺标</small></button>
          </div>
          <label class="notes"><span>补充说明（可选）</span><textarea v-model="notes" rows="2" maxlength="500" @input="dirty = true" /></label>
          <button class="save-decision" type="button" :disabled="!dirty || saving" @click="saveCurrent(true)">{{ saving ? '保存中…' : '确认保存并进入下一项' }}</button>
        </div>
      </main>
    </div>
    <el-empty v-else :description="reviewed ? '还没有已完成的诊断' : '当前没有待判断项，工厂无需为这些技术问题做审核'" />
    <el-pagination v-if="total > pageSize" class="queue-pagination" layout="prev, pager, next, total" :current-page="page" :page-size="pageSize" :total="total" @current-change="changePage" />
  </section>
</template>

<style scoped lang="scss">
.page-container { min-height: calc(100vh - 92px); padding: 20px; color: #1a2332; background: #f4f6f9; }
.page-header { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-bottom: 16px; }
.eyebrow { margin: 0 0 4px; color: #1b65a8; font-size: 12px; font-weight: 700; letter-spacing: .08em; }
h1 { margin: 0; font-size: 28px; line-height: 1.25; }.subtitle { margin: 6px 0 0; color: #7a8599; font-size: 13px; }
.queue-switch { display: flex; padding: 4px; border: 1px solid #e0e7ef; border-radius: 10px; background: #fff; box-shadow: 0 2px 12px rgba(27,101,168,.06); }
.queue-switch button { min-height: 38px; padding: 0 16px; border: 0; border-radius: 7px; color: #657289; background: transparent; cursor: pointer; }.queue-switch button.active { color: #fff; background: #1b65a8; }
.workspace { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 16px; min-height: 690px; }
.queue-card,.review-card { border: 1px solid #edf2f7; border-radius: 10px; background: #fff; box-shadow: 0 2px 12px rgba(27,101,168,.06); }
.queue-card { max-height: calc(100vh - 150px); padding: 12px; overflow: auto; }.queue-title { display: flex; justify-content: space-between; padding: 4px 4px 12px; color: #7a8599; font-size: 12px; }.queue-title strong { color: #1a2332; font-size: 14px; }
.queue-item { display: grid; grid-template-columns: 30px 1fr; gap: 8px; width: 100%; margin-bottom: 8px; padding: 10px; border: 1px solid #edf2f7; border-radius: 8px; text-align: left; background: #fff; cursor: pointer; }.queue-item.active { border-color: #1b65a8; background: #f2f7fc; box-shadow: inset 3px 0 #1b65a8; }.queue-index { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 7px; color: #1b65a8; background: #e9f2fb; font-weight: 700; }.queue-item strong,.queue-item small { display: block; }.queue-item small { margin-top: 4px; overflow: hidden; color: #7a8599; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.review-card { min-width: 0; padding: 16px; }.problem-banner { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: center; padding: 12px 14px; border: 1px solid #f0c7c3; border-radius: 10px; background: #fff7f6; }.problem-dot { width: 12px; height: 12px; border-radius: 50%; background: #d92d20; box-shadow: 0 0 0 5px rgba(217,45,32,.12); }.problem-banner strong { font-size: 15px; }.problem-banner p { margin: 3px 0 0; color: #7a4b47; font-size: 12px; }.confidence { padding: 5px 8px; border-radius: 99px; color: #9f241b; background: #ffe4e1; font-size: 11px; font-weight: 700; }
.visual-grid { display: grid; grid-template-columns: 1.35fr 1fr; gap: 16px; margin-top: 16px; }.visual-title { display: flex; justify-content: space-between; margin-bottom: 8px; }.visual-title span { color: #7a8599; font-size: 11px; }.full-canvas,.crop-canvas { position: relative; width: 100%; max-height: 500px; overflow: hidden; border-radius: 10px; background: #111827; }.full-canvas img { display: block; width: 100%; height: 100%; object-fit: contain; }.crop-canvas img { position: absolute; max-width: none; object-fit: fill; }
.problem-box { position: absolute; box-sizing: border-box; min-width: 8px; min-height: 8px; border: 4px solid #e31b0c; border-radius: 2px; background: rgba(227,27,12,.12); box-shadow: 0 0 0 1px rgba(255,255,255,.9), 0 0 12px rgba(227,27,12,.45); }.problem-box span { position: absolute; top: -27px; left: -4px; padding: 3px 7px; color: #fff; background: #d92d20; font-size: 11px; font-weight: 800; white-space: nowrap; }
.decision-panel { margin-top: 16px; padding: 16px; border: 1px solid #edf2f7; border-radius: 10px; background: #fbfcfe; }.decision-copy { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }.decision-copy span { color: #7a8599; font-size: 12px; }.decision-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 12px; }.decision-grid button { position: relative; min-height: 84px; padding: 12px; border: 1px solid #dbe3ec; border-radius: 10px; text-align: left; background: #fff; cursor: pointer; transition: border-color .2s, transform .2s, box-shadow .2s; }.decision-grid button:hover { border-color: #1b65a8; box-shadow: 0 4px 14px rgba(27,101,168,.12); transform: translateY(-1px); }.decision-grid button.safe { border-color: #b9dfcf; }.decision-grid button.danger { border-color: #efc8c4; }.decision-grid strong,.decision-grid small { display: block; }.decision-grid small { margin-top: 6px; color: #7a8599; font-size: 11px; }.decision-grid kbd { position: absolute; top: 8px; right: 8px; min-width: 22px; padding: 2px 5px; border: 1px solid #dbe3ec; border-radius: 5px; color: #657289; background: #f6f8fb; text-align: center; font: 11px inherit; }.notes { display: grid; grid-template-columns: 140px 1fr; gap: 12px; align-items: center; margin-top: 12px; color: #657289; font-size: 12px; }.notes textarea { width: 100%; box-sizing: border-box; padding: 8px 10px; resize: vertical; border: 1px solid #dbe3ec; border-radius: 8px; font: inherit; }
.decision-grid button.selected { border-color: #1b65a8; box-shadow: 0 0 0 3px rgba(27,101,168,.14); }
.save-decision { display: block; min-width: 240px; min-height: 42px; margin: 14px 0 0 auto; border: 0; border-radius: 9px; color: #fff; background: #1b65a8; font-weight: 700; cursor: pointer; }.save-decision:disabled { cursor: not-allowed; opacity: .5; }
.queue-pagination { justify-content: center; margin-top: 16px; }
@media (max-width: 1100px) { .workspace { grid-template-columns: 220px minmax(0,1fr); }.visual-grid { grid-template-columns: 1fr; }.decision-grid { grid-template-columns: repeat(2,1fr); } }
@media (max-width: 760px) { .page-header { align-items: stretch; flex-direction: column; }.workspace { grid-template-columns: 1fr; }.queue-card { max-height: 190px; }.decision-grid { grid-template-columns: 1fr; }.decision-copy,.notes { align-items: stretch; grid-template-columns: 1fr; flex-direction: column; } }
</style>
