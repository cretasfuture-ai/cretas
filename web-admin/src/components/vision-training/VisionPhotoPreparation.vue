<template>
  <section class="preparation" data-testid="vision-photo-preparation">
    <div class="heading-row">
      <div>
        <p class="eyebrow">本厂照片资料</p>
        <h3>准备训练照片</h3>
        <p class="help">按四步完成照片、审核和合格要求。只有明确保存的审核结果才会计入准备进度。</p>
      </div>
      <button type="button" class="action-button" :disabled="loading" @click="loadState(true)">重新读取</button>
    </div>

    <div v-if="loading" class="loading-panel">正在读取照片准备进度…</div>
    <div v-else-if="loadError" class="error-panel" data-testid="preparation-error">
      <strong>暂时无法读取照片准备进度</strong><p>{{ loadError }}</p>
      <button type="button" class="action-button" @click="loadState(true)">重新读取</button>
    </div>
    <template v-else-if="state">
      <div v-if="frozen" class="frozen-notice" data-testid="preparation-frozen">
        <strong>资料已提交，当前已冻结</strong>
        <p v-if="state.submission?.status === 'waiting_configuration'">资料已提交，等待本厂执行设置接通，尚未开始训练。</p>
        <p v-else>{{ state.submission?.message || '已进入本厂处理队列，尚未完成训练。' }}</p>
        <p v-if="state.submission?.status === 'waiting_configuration'">如已接通执行设置，请重新核对本厂执行设置。</p>
      </div>
      <p v-if="actionError" class="error-panel action-error" data-testid="preparation-action-error">{{ actionError }}</p>

      <ol class="wizard-steps" aria-label="照片准备步骤">
        <li v-for="(label, index) in steps" :key="label" :class="{ current: step === index, done: step > index }">
          <button type="button" class="step-button" :aria-current="step === index ? 'step' : undefined" @click="step = index"><span>{{ index + 1 }}</span>{{ label }}</button>
        </li>
      </ol>

      <section v-if="step === 0" class="step-panel" data-testid="preparation-step-upload">
        <h4>准备照片</h4>
        <p class="help">每张照片不超过 {{ formatBytes(state.limits.maxBytes) }}，最多 {{ state.limits.maxPhotos }} 张。只能从本地选择 JPEG 或 PNG 照片。</p>
        <div class="upload-columns">
          <div class="upload-card">
            <h5>用来学习</h5>
            <p>拍摄合格和不合格照片，作为本次学习资料。</p>
            <label class="field-label" for="training-capture-group">拍摄批次</label>
            <input id="training-capture-group" v-model="trainingCaptureGroup" class="text-input" :disabled="frozen || uploading" placeholder="例如：2026-10-15 上午" />
            <label class="file-picker" :class="{ disabled: frozen || uploading || storageLimitReached }">选择学习照片
              <input type="file" accept="image/jpeg,image/png" multiple :disabled="frozen || uploading || storageLimitReached" @change="chooseFiles($event, 'training')" />
            </label>
            <strong class="photo-count">已选 {{ countByPurpose('training') }} 张</strong>
          </div>
          <div class="upload-card">
            <h5>另留本厂试用</h5>
            <p>试用照片须另批拍摄，不能和学习照片混用。</p>
            <label class="field-label" for="acceptance-capture-group">拍摄批次</label>
            <input id="acceptance-capture-group" v-model="acceptanceCaptureGroup" class="text-input" :disabled="frozen || uploading" placeholder="例如：2026-10-16 试用批次" />
            <label class="file-picker" :class="{ disabled: frozen || uploading || storageLimitReached }">选择试用照片
              <input type="file" accept="image/jpeg,image/png" multiple :disabled="frozen || uploading || storageLimitReached" @change="chooseFiles($event, 'acceptance')" />
            </label>
            <strong class="photo-count">已选 {{ countByPurpose('acceptance') }} 张</strong>
          </div>
        </div>
        <p v-if="hasStorageBudget" class="storage-budget" data-testid="storage-budget">已用照片存储：{{ formatBytes(state.limits.storedBytes || 0) }} / {{ formatBytes(state.limits.maxStoredBytes || 0) }}</p>
        <p v-if="storageLimitReached" class="field-hint" data-testid="storage-limit-message">已达到照片存储上限，暂不能继续上传，请联系维护人员处理。</p>
        <div class="photo-list" data-testid="preparation-photo-list">
          <div class="list-heading"><strong>照片清单</strong><span>共 {{ state.photos.length }} 张</span></div>
          <button v-for="photo in state.photos" :key="photo.photoId" type="button" class="photo-row" :class="{ selected: selectedPhotoId === photo.photoId }" @click="selectPhoto(photo.photoId)">
            <span class="photo-index">{{ photoIndex(photo.photoId) }}</span><span class="photo-name">{{ photo.fileName }}</span><span>{{ purposeName(photo.purpose) }}</span><span>{{ verdictName(photo.verdict) }}</span>
          </button>
          <p v-if="!state.photos.length" class="empty-panel">还没有照片，请先从本地选择。</p>
        </div>
        <div class="step-next"><button type="button" class="primary-button" data-testid="next-to-review" :disabled="!state.photos.length || frozen || uploading" @click="step = 1">下一步：逐张审核</button></div>
      </section>

      <section v-else-if="step === 1" class="step-panel" data-testid="preparation-step-review">
        <h4>逐张审核</h4>
        <p class="help">请选择一张照片，完成判断并点击“我已逐张核对并保存这张”。系统不会自动确认照片。</p>
        <div class="review-layout" v-if="selectedPhoto">
          <div class="review-photo"><PhotoRegionEditor :image-src="imageSrc" :boxes="reviewBoxes" :labels="state.labels" :active-label="activeLabel" :disabled="frozen || imageLoading || !imageSrc" @update:boxes="reviewBoxes = $event" @update:active-label="activeLabel = $event" /></div>
          <div class="review-form">
            <div class="review-navigation">
              <label class="field-label" for="review-photo-picker">当前照片（第 {{ currentPhotoNumber }} 张 / 共 {{ state.photos.length }} 张）</label>
              <select id="review-photo-picker" class="text-input" :value="selectedPhotoId" :disabled="!canSwitchReview" @change="switchSelectedPhoto">
                <option v-for="photo in state.photos" :key="photo.photoId" :value="photo.photoId">{{ photo.fileName }} · {{ purposeName(photo.purpose) }}</option>
              </select>
              <div class="review-switches"><button type="button" class="action-button" :disabled="!canSwitchReview || currentPhotoNumber <= 1" @click="switchPhoto(-1)">上一张</button><button type="button" class="action-button" :disabled="!canSwitchReview || currentPhotoNumber >= state.photos.length" @click="switchPhoto(1)">下一张</button></div>
            </div>
            <p class="photo-title">{{ selectedPhoto.fileName }} · {{ purposeName(selectedPhoto.purpose) }}</p>
            <p v-if="reviewDirty" class="field-hint">当前照片有尚未保存的判断或框选，请先保存后再切换，避免丢失填写。</p>
            <fieldset :disabled="frozen || savingReview">
              <legend>这张照片的判断</legend>
              <label v-for="item in verdicts" :key="item.value" class="radio-line"><input v-model="reviewVerdict" type="radio" :value="item.value" />{{ item.label }}</label>
            </fieldset>
            <label class="field-label" for="photo-transcription">照片上的正确文字（如有需要）</label>
            <textarea id="photo-transcription" v-model="reviewTranscription" class="text-input text-area" :disabled="frozen || savingReview" rows="3" placeholder="可填写照片中应显示的文字"></textarea>
            <p v-if="ocrRequired" class="field-hint">这类照片需要填写正确文字。</p>
            <button type="button" class="primary-button" :disabled="!canSaveReview || frozen || savingReview" @click="saveReview">{{ savingReview ? '正在保存…' : '我已逐张核对并保存这张' }}</button>
          </div>
        </div>
        <p v-else class="empty-panel">请先在“准备照片”中选择一张照片。</p>
        <div v-if="allReviewed" class="step-next"><button type="button" class="primary-button" data-testid="next-to-standard" :disabled="reviewDirty || frozen" @click="step = 2">下一步：确认合格要求</button></div>
      </section>

      <section v-else-if="step === 2" class="step-panel" data-testid="preparation-step-standard">
        <h4>确认合格要求</h4>
        <p class="help">填写这次检查的合格和不合格说明，以及允许的漏检、误报上限。必须明确确认后保存。</p>
        <div class="standard-form">
          <label class="field-label" for="pass-criteria">什么算合格</label>
          <textarea id="pass-criteria" v-model="passCriteria" class="text-input text-area" maxlength="2000" :disabled="frozen || savingStandard" rows="4"></textarea>
          <label class="field-label" for="fail-criteria">什么算不合格</label>
          <textarea id="fail-criteria" v-model="failCriteria" class="text-input text-area" maxlength="2000" :disabled="frozen || savingStandard" rows="4"></textarea>
          <div class="rate-fields">
            <label class="field-label">允许漏检上限（0–100%）<input v-model.number="maxMissRate" type="number" min="0" max="100" class="text-input" :disabled="frozen || savingStandard" /></label>
            <label class="field-label">允许误报上限（0–100%）<input v-model.number="maxFalseAlarmRate" type="number" min="0" max="100" class="text-input" :disabled="frozen || savingStandard" /></label>
          </div>
          <label class="confirm-line"><input v-model="standardConfirmed" type="checkbox" :disabled="frozen || savingStandard" />我已确认以上合格要求可以用于本次照片审核</label>
          <p v-if="standardSavedButInvalid" class="field-hint">照片或审核结果有变化，请再次确认并保存合格要求。</p>
          <button type="button" class="primary-button" :disabled="!canSaveStandard || frozen || savingStandard" @click="saveStandard">{{ savingStandard ? '正在保存…' : '确认并保存合格要求' }}</button>
          <div v-if="state.standard?.confirmed && !standardSavedButInvalid" class="step-next"><button type="button" class="primary-button" data-testid="next-to-submit" :disabled="frozen || savingStandard" @click="step = 3">下一步：提交训练</button></div>
        </div>
      </section>

      <section v-else class="step-panel" data-testid="preparation-step-submit">
        <h4>提交训练</h4>
        <div class="summary-grid"><div>照片总数<strong>{{ state.photos.length }}</strong></div><div>已审核<strong>{{ reviewedCount }}</strong></div><div>学习照片<strong>{{ countByPurpose('training') }}</strong></div><div>试用照片<strong>{{ countByPurpose('acceptance') }}</strong></div></div>
        <div v-if="state.blockers.length" class="blocker-panel"><strong>还需要补充</strong><ul><li v-for="blocker in state.blockers" :key="blocker">{{ blocker }}</li></ul></div>
        <p v-else class="success-hint">服务端检查已具备提交条件，请确认后提交。</p>
        <button type="button" class="primary-button" data-testid="submit-preparation" :disabled="!canSubmit || submitting" @click="submit">{{ submitting ? '正在提交…' : submitButtonLabel }}</button>
        <p v-if="state.submission" class="submission-message" data-testid="submission-message">{{ state.submission.message }}</p>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ApiError } from '@/types/api';
import { toDateString } from '@/utils/dateFormat';
import PhotoRegionEditor from './PhotoRegionEditor.vue';
import {
  getVisionPreparation, getVisionPreparationImage, reviewVisionPreparationPhoto, saveVisionPreparationStandard,
  submitVisionPreparation, uploadVisionPreparationPhoto,
  type PhotoVerdict, type PreparationPurpose, type VisionPreparationPhoto, type VisionPreparationState, type VisionPhotoBox,
} from '@/api/visionPreparation';

const props = defineProps<{ factoryId: string; projectId: string }>();
const emit = defineEmits<{
  'execution-linked': [link: { factoryId: string; projectId: string; executionProjectId: string | null }];
}>();
const steps = ['准备照片', '逐张审核', '确认合格要求', '提交训练'];
const today = toDateString(new Date());
const verdicts: Array<{ value: PhotoVerdict; label: string }> = [{ value: 'good', label: '合格' }, { value: 'defect', label: '不合格' }, { value: 'uncertain', label: '无法判断' }];
const state = ref<VisionPreparationState | null>(null);
const step = ref(0), loading = ref(false), uploading = ref(false), imageLoading = ref(false), savingReview = ref(false), savingStandard = ref(false), submitting = ref(false);
const loadError = ref(''), actionError = ref('');
const selectedPhotoId = ref(''), imageSrc = ref(''), reviewVerdict = ref<PhotoVerdict | null>(null), reviewBoxes = ref<VisionPhotoBox[]>([]), reviewTranscription = ref(''), activeLabel = ref('');
const trainingCaptureGroup = ref(today), acceptanceCaptureGroup = ref(today + ' 试用批次');
const passCriteria = ref(''), failCriteria = ref(''), maxMissRate = ref(0), maxFalseAlarmRate = ref(0), standardConfirmed = ref(false);
let generation = 0, imageGeneration = 0;

const selectedPhoto = computed(() => state.value?.photos.find(photo => photo.photoId === selectedPhotoId.value) || null);
const frozen = computed(() => !!state.value?.submission);
const currentPhotoNumber = computed(() => selectedPhoto.value ? (state.value?.photos.findIndex(photo => photo.photoId === selectedPhoto.value?.photoId) || 0) + 1 : 0);
const reviewDirty = computed(() => {
  const photo = selectedPhoto.value;
  if (!photo) return false;
  return reviewVerdict.value !== photo.verdict
    || reviewBoxes.value.length !== photo.boxes.length
    || reviewBoxes.value.some((box, index) => {
      const saved = photo.boxes[index];
      return !saved || box.label !== saved.label || box.x0 !== saved.x0 || box.y0 !== saved.y0
        || box.x1 !== saved.x1 || box.y1 !== saved.y1;
    })
    || reviewTranscription.value.trim() !== (photo.transcription || '').trim();
});
const canSwitchReview = computed(() => !reviewDirty.value && !imageLoading.value && !savingReview.value);
const allReviewed = computed(() => !!state.value?.photos.length && state.value.photos.every(photo => photo.reviewed));
const canContinueTraining = computed(() => state.value?.canContinueTraining === true);
const canSubmit = computed(() => !!state.value && ((!frozen.value && state.value.readyToSubmit) || canContinueTraining.value || state.value.submission?.status === 'waiting_configuration'));
const submitButtonLabel = computed(() => canContinueTraining.value ? '提交本厂模型学习' : state.value?.submission?.status === 'waiting_configuration' ? '重新核对本厂执行设置并继续提交' : '确认提交训练资料');
const hasStorageBudget = computed(() => state.value?.limits.maxStoredBytes !== undefined && state.value?.limits.storedBytes !== undefined);
const storageLimitReached = computed(() => hasStorageBudget.value && (state.value?.limits.storedBytes || 0) >= (state.value?.limits.maxStoredBytes || 0));
const ocrRequired = computed(() => state.value?.taskFamily === 'packaging-ocr');
const reviewedCount = computed(() => state.value?.photos.filter(photo => photo.reviewed).length || 0);
const standardSavedButInvalid = computed(() => !!state.value?.standard && (!state.value.standard.confirmed || !standardConfirmed.value));
const canSaveReview = computed(() => {
  if (!selectedPhoto.value || !reviewVerdict.value) return false;
  if ((reviewVerdict.value === 'good' || reviewVerdict.value === 'defect') && !reviewBoxes.value.length) return false;
  return !!imageSrc.value && !imageLoading.value && (!ocrRequired.value || !!reviewTranscription.value.trim());
});
const canSaveStandard = computed(() => {
  const pass = passCriteria.value.trim(); const fail = failCriteria.value.trim();
  return pass.length >= 2 && pass.length <= 2000 && fail.length >= 2 && fail.length <= 2000
    && Number.isFinite(maxMissRate.value) && Number.isFinite(maxFalseAlarmRate.value)
    && maxMissRate.value >= 0 && maxMissRate.value <= 100 && maxFalseAlarmRate.value >= 0 && maxFalseAlarmRate.value <= 100
    && standardConfirmed.value;
});

function formatBytes(value: number) { return value >= 1024 * 1024 ? `${Math.round(value / 1024 / 1024)} 兆` : `${Math.round(value / 1024)} 千字节`; }
function purposeName(value: PreparationPurpose) { return value === 'training' ? '学习照片' : '本厂试用照片'; }
function verdictName(value: PhotoVerdict | null) { return value === 'good' ? '合格' : value === 'defect' ? '不合格' : value === 'uncertain' ? '无法判断' : '待审核'; }
function photoIndex(id: string) { return (state.value?.photos.findIndex(photo => photo.photoId === id) || 0) + 1; }
function countByPurpose(purpose: PreparationPurpose) { return state.value?.photos.filter(photo => photo.purpose === purpose).length || 0; }
function errorMessage(error: unknown) { return error instanceof Error ? error.message : '照片准备服务暂时不可用，请稍后重试'; }
function syncStandard(value: VisionPreparationState['standard']) {
  if (!value) { passCriteria.value = ''; failCriteria.value = ''; maxMissRate.value = 0; maxFalseAlarmRate.value = 0; standardConfirmed.value = false; return; }
  passCriteria.value = value.passCriteria; failCriteria.value = value.failCriteria; maxMissRate.value = value.maxMissRate; maxFalseAlarmRate.value = value.maxFalseAlarmRate; standardConfirmed.value = value.confirmed;
}
function applyState(value: VisionPreparationState, sync = false) {
  state.value = value;
  emit('execution-linked', { factoryId: props.factoryId, projectId: props.projectId, executionProjectId: value.submission?.executionProjectId || null });
  if (sync || value.submission) syncStandard(value.standard);
  else standardConfirmed.value = false;
  if (!selectedPhotoId.value || !value.photos.some(photo => photo.photoId === selectedPhotoId.value)) {
    selectedPhotoId.value = '';
    imageSrc.value = '';
  }
}
async function loadState(preservePosition = false) {
  const previousStep = step.value, previousPhotoId = selectedPhotoId.value;
  const current = ++generation; ++imageGeneration; const factory = props.factoryId; const project = props.projectId;
  state.value = null; loadError.value = ''; actionError.value = ''; selectedPhotoId.value = ''; imageSrc.value = ''; imageLoading.value = false; uploading.value = false; savingReview.value = false; savingStandard.value = false; submitting.value = false; loading.value = true; step.value = 0;
  trainingCaptureGroup.value = today; acceptanceCaptureGroup.value = `${today} 试用批次`; syncStandard(null);
  if (!factory || !project) { loading.value = false; return; }
  try {
    const result = await getVisionPreparation(factory, project);
    if (current !== generation || factory !== props.factoryId || project !== props.projectId) return;
    applyState(result, true);
    if (preservePosition) {
      const photo = result.photos.find(item => item.photoId === previousPhotoId) || result.photos[0];
      if (previousStep === 1 && photo) await selectPhoto(photo.photoId);
      if (current === generation && factory === props.factoryId && project === props.projectId) step.value = previousStep;
    }
  } catch (error) { if (current === generation) loadError.value = errorMessage(error); }
  finally { if (current === generation) loading.value = false; }
}
function resetReview(photo: VisionPreparationPhoto) {
  reviewVerdict.value = photo.verdict; reviewBoxes.value = photo.boxes.map(box => ({ ...box })); reviewTranscription.value = photo.transcription || ''; activeLabel.value = state.value?.labels[0]?.id || '';
}
function switchPhoto(offset: number) {
  if (!state.value || !canSwitchReview.value) return;
  const index = state.value.photos.findIndex(photo => photo.photoId === selectedPhotoId.value);
  const target = state.value.photos[index + offset];
  if (target) void selectPhoto(target.photoId);
}
function switchSelectedPhoto(event: Event) {
  if (!canSwitchReview.value) return;
  const photoId = (event.target as HTMLSelectElement).value;
  if (photoId) void selectPhoto(photoId);
}
async function selectPhoto(photoId: string) {
  const photo = state.value?.photos.find(item => item.photoId === photoId); if (!photo) return;
  if (step.value === 1 && photoId !== selectedPhotoId.value && !canSwitchReview.value) return;
  selectedPhotoId.value = photoId; resetReview(photo); imageSrc.value = ''; const current = ++imageGeneration; const taskGeneration = generation; const factory = props.factoryId; const project = props.projectId; imageLoading.value = true;
  try {
    const image = await getVisionPreparationImage(factory, project, photoId);
    if (taskGeneration !== generation || current !== imageGeneration || factory !== props.factoryId || project !== props.projectId || selectedPhotoId.value !== photoId) return;
    imageSrc.value = image.contentBase64.startsWith('data:') ? image.contentBase64 : `data:${image.mimeType};base64,${image.contentBase64}`;
  } catch (error) {
    if (taskGeneration === generation && current === imageGeneration && factory === props.factoryId && project === props.projectId) { imageSrc.value = ''; actionError.value = errorMessage(error); }
  }
  finally { if (taskGeneration === generation && current === imageGeneration && factory === props.factoryId && project === props.projectId) imageLoading.value = false; }
}
function readFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result || '').split(',').pop() || ''); reader.onerror = () => reject(new Error('照片读取失败，请重新选择')); reader.readAsDataURL(file); });
}
async function chooseFiles(event: Event, purpose: PreparationPurpose) {
  if (frozen.value || !state.value) return;
  const files = Array.from((event.target as HTMLInputElement).files || []); if (!files.length) return;
  const current = generation; const factory = props.factoryId; const project = props.projectId;
  uploading.value = true; actionError.value = '';
  try {
    for (const file of files) {
      if (!['image/jpeg', 'image/png'].includes(file.type)) throw new Error('只能上传 JPEG 或 PNG 照片');
      if (storageLimitReached.value) throw new Error('已达到照片存储上限，暂不能继续上传');
      if (file.size > state.value.limits.maxBytes) throw new Error(`照片“${file.name}”超过大小限制`);
      if (state.value.photos.length >= state.value.limits.maxPhotos) throw new Error('照片数量已达到上限');
      const contentBase64 = await readFile(file);
      if (!state.value || current !== generation || factory !== props.factoryId || project !== props.projectId) return;
      const result = await uploadVisionPreparationPhoto(factory, project, { expectedRevision: state.value.revision, fileName: file.name, contentBase64, purpose, captureGroup: (purpose === 'training' ? trainingCaptureGroup.value : acceptanceCaptureGroup.value).trim() });
      if (!state.value || current !== generation || factory !== props.factoryId || project !== props.projectId) return;
      applyState(result);
    }
  } catch (error) { if (current === generation && factory === props.factoryId && project === props.projectId) actionError.value = errorMessage(error); }
  finally { if (current === generation && factory === props.factoryId && project === props.projectId) uploading.value = false; }
}
async function saveReview() {
  const photo = selectedPhoto.value, currentState = state.value; if (!photo || !currentState || !reviewVerdict.value || !canSaveReview.value) return;
  const current = generation, payload = { expectedRevision: currentState.revision, photoId: photo.photoId, verdict: reviewVerdict.value, boxes: reviewBoxes.value.map(box => ({ ...box })), ...(reviewTranscription.value.trim() ? { transcription: reviewTranscription.value.trim() } : {}) };
  savingReview.value = true; actionError.value = '';
  try {
    const result = await reviewVisionPreparationPhoto(props.factoryId, props.projectId, payload);
    if (current === generation) {
      applyState(result);
      savingReview.value = false;
      const next = result.photos.find(item => !item.reviewed && item.photoId !== photo.photoId);
      if (next) await selectPhoto(next.photoId);
    }
  }
  catch (error) { if (current === generation) actionError.value = error instanceof ApiError && error.status === 409 ? `资料已被其他人修改：${error.message}。请刷新后再继续，刚才的填写仍已保留。` : errorMessage(error); }
  finally { if (current === generation) savingReview.value = false; }
}
async function saveStandard() {
  const currentState = state.value; if (!currentState || !canSaveStandard.value) return;
  const current = generation; savingStandard.value = true; actionError.value = '';
  try {
    const result = await saveVisionPreparationStandard(props.factoryId, props.projectId, { expectedRevision: currentState.revision, passCriteria: passCriteria.value.trim(), failCriteria: failCriteria.value.trim(), maxMissRate: maxMissRate.value, maxFalseAlarmRate: maxFalseAlarmRate.value, confirmed: true });
    if (current === generation) applyState(result, true);
  } catch (error) { if (current === generation) actionError.value = error instanceof ApiError && error.status === 409 ? `资料已被其他人修改：${error.message}。请刷新后再继续，刚才的填写仍已保留。` : errorMessage(error); }
  finally { if (current === generation) savingStandard.value = false; }
}
async function submit() {
  if (!state.value || !canSubmit.value || submitting.value) return;
  const current = generation; const factory = props.factoryId; const project = props.projectId; submitting.value = true; actionError.value = '';
  try {
    const result = await submitVisionPreparation(factory, project, state.value.revision);
    if (current !== generation || factory !== props.factoryId || project !== props.projectId) return;
    applyState(result);
    try {
      const refreshed = await getVisionPreparation(factory, project);
      if (current === generation && factory === props.factoryId && project === props.projectId) applyState(refreshed);
    } catch (error) {
      if (current === generation && factory === props.factoryId && project === props.projectId) actionError.value = errorMessage(error);
    }
  } catch (error) {
    if (current === generation && factory === props.factoryId && project === props.projectId) {
      actionError.value = error instanceof ApiError && error.status === 409 ? `资料已被其他人修改：${error.message}。请刷新后再继续。` : errorMessage(error);
      try {
        const recovered = await getVisionPreparation(factory, project);
        if (current === generation && factory === props.factoryId && project === props.projectId) applyState(recovered);
      } catch (refreshError) {
        if (current === generation && factory === props.factoryId && project === props.projectId) actionError.value = `${actionError.value} 当前状态刷新失败：${errorMessage(refreshError)}`;
      }
    }
  }
  finally { if (current === generation && factory === props.factoryId && project === props.projectId) submitting.value = false; }
}
watch(() => [props.factoryId, props.projectId], () => loadState(), { immediate: true });
watch(step, value => {
  if (value === 1 && !selectedPhotoId.value && state.value?.photos[0]) selectPhoto(state.value.photos[0].photoId);
});
</script>

<style scoped>
.preparation { margin-top: 18px; padding: 20px; border: 1px solid #edf2f7; border-radius: 10px; background: #fff; box-shadow: 0 2px 12px rgba(27,101,168,.06); color: #1a2332; }
.heading-row, .list-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.eyebrow { margin: 0 0 4px; color: #1b65a8; font-size: 12px; font-weight: 700; }
h3, h4, h5 { margin: 0; } h3 { font-size: 20px; } h4 { font-size: 18px; margin-bottom: 8px; } h5 { font-size: 16px; }
.help, .field-hint { color: #7a8599; font-size: 13px; line-height: 1.6; }
.wizard-steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; padding: 0; margin: 20px 0; list-style: none; }
.step-button { width: 100%; min-height: 48px; border: 1px solid #dbe4ef; border-radius: 8px; background: #fff; color: #7a8599; cursor: pointer; text-align: left; padding: 0 10px; }
.step-button span { display: inline-grid; place-items: center; width: 28px; height: 28px; margin-right: 8px; border-radius: 50%; background: #edf2f7; }
.wizard-steps li.current .step-button { border-color: #1b65a8; color: #1b65a8; font-weight: 700; }.wizard-steps li.current .step-button span, .wizard-steps li.done .step-button span { background: #1b65a8; color: #fff; }
.step-panel { padding-top: 4px; }.upload-columns, .review-layout { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }.upload-card { display: grid; gap: 10px; padding: 16px; border: 1px solid #edf2f7; border-radius: 8px; background: #f8fafc; }
.field-label { display: grid; gap: 6px; color: #1a2332; font-weight: 600; }.text-input { box-sizing: border-box; width: 100%; min-height: 44px; padding: 9px 11px; border: 1px solid #dbe4ef; border-radius: 6px; background: #fff; font: inherit; }.text-area { resize: vertical; min-height: 88px; }.file-picker, .action-button, .primary-button { min-height: 44px; display: inline-flex; align-items: center; justify-content: center; padding: 0 14px; border-radius: 6px; cursor: pointer; font: inherit; }.file-picker { border: 1px dashed #1b65a8; color: #1b65a8; background: #fff; }.file-picker input { display: none; }.file-picker.disabled { opacity: .55; cursor: not-allowed; }.action-button { border: 1px solid #cdd8e6; background: #fff; color: #1a2332; }.primary-button { border: 1px solid #1b65a8; background: #1b65a8; color: #fff; }.primary-button:disabled, .action-button:disabled { cursor: not-allowed; opacity: .55; }
.step-next { display: flex; justify-content: flex-end; margin-top: 18px; }.review-navigation { display: grid; gap: 8px; }.review-switches { display: flex; gap: 8px; }
.photo-list { display: grid; gap: 8px; margin-top: 16px; }.photo-row { display: grid; grid-template-columns: 34px minmax(0, 1fr) 120px 80px; align-items: center; gap: 8px; min-height: 44px; padding: 5px 10px; border: 1px solid #edf2f7; border-radius: 6px; background: #fff; cursor: pointer; text-align: left; }.photo-row.selected { border-color: #1b65a8; background: #f0f7ff; }.photo-index { color: #7a8599; }.photo-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.empty-panel, .loading-panel { padding: 24px; border-radius: 8px; background: #f8fafc; color: #7a8599; text-align: center; }.review-layout { align-items: start; }.review-form { display: grid; gap: 12px; }.photo-title { margin: 0; font-weight: 700; }.review-form fieldset { display: grid; gap: 10px; border: 0; padding: 0; margin: 0; }.radio-line, .confirm-line { min-height: 44px; display: flex; align-items: center; gap: 8px; }.rate-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }.standard-form { display: grid; gap: 12px; max-width: 860px; }.summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 16px 0; }.summary-grid > div { padding: 14px; border: 1px solid #edf2f7; border-radius: 8px; color: #7a8599; }.summary-grid strong { display: block; margin-top: 6px; color: #1a2332; font-size: 20px; }.blocker-panel, .frozen-notice, .error-panel, .success-hint, .submission-message { padding: 14px; border-radius: 8px; line-height: 1.6; }.blocker-panel { background: #fff7e6; color: #8a5b00; }.frozen-notice { background: #eef7ff; color: #1b65a8; }.error-panel { background: #fff1f0; color: #b42318; }.success-hint { background: #effaf3; color: #267341; }.submission-message { margin-top: 14px; background: #eef7ff; color: #1b65a8; }
@media (max-width: 800px) { .upload-columns, .review-layout, .rate-fields { grid-template-columns: 1fr; }.wizard-steps { grid-template-columns: repeat(2, 1fr); }.summary-grid { grid-template-columns: repeat(2, 1fr); }.photo-row { grid-template-columns: 28px minmax(0, 1fr); }.photo-row > :nth-child(n+3) { display: none; } }
</style>
