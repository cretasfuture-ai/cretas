<template>
  <el-card class="vision-setup" shadow="never" data-testid="vision-training-setup">
    <el-skeleton v-if="loading" :rows="4" animated />
    <template v-else-if="loadError">
      <el-alert type="error" :closable="false" :title="friendlyError(loadError)" show-icon />
      <el-button class="retry" @click="loadFactory">重新读取</el-button>
      <details class="technical-error"><summary>给维护人员看原始原因</summary><p>{{ loadError }}</p></details>
    </template>
    <template v-else>
      <div v-if="drafts.length" class="vision-resume">
        <div class="resume-picker"><label for="vision-saved-project">查看已有检查</label>
          <el-select v-model="selectedProject" id="vision-saved-project" data-testid="vision-saved-project"
            placeholder="选择要查看的检查" :disabled="busy || projectLoading || !!submitting" @change="loadProject">
            <el-option v-for="(item, index) in drafts" :key="item.project_id" :value="item.project_id" :label="isPreparedExecution(item) ? `学习执行记录 · ${businessValue(item.spec.workstation_id, '检查位置')}` : projectLabel(item, index)" />
          </el-select>
        </div>
        <el-button :disabled="busy || projectLoading || !!submitting" @click="newProject">新增检查</el-button>
      </div>
      <el-skeleton v-if="projectLoading" :rows="3" animated />
      <template v-else-if="!selectedProject">
        <ol class="wizard-steps" aria-label="新增检查步骤">
          <li v-for="(label, index) in wizardLabels" :key="label" :class="{ current: wizardStep === index, done: wizardStep > index }"
            :aria-current="wizardStep === index ? 'step' : undefined"><span>{{ index + 1 }}</span>{{ label }}</li>
        </ol>
        <section v-if="wizardStep === 0" data-testid="vision-step-purpose" class="wizard-panel">
          <h2>想检查什么？</h2><p class="help">选择一项，就能为这项检查准备照片和合格要求。</p>
          <div class="task-choices" data-testid="vision-template">
            <button v-for="item in templates" :key="item.template_id" type="button" :disabled="busy"
              :data-template="item.template_id" :aria-pressed="templateId === item.template_id"
              :class="{ selected: templateId === item.template_id }" @click="chooseTemplate(item.template_id)">
              <strong>{{ templateName(item) }}</strong><span>{{ templateHint(item) }}</span>
            </button>
          </div>
          <el-empty v-if="!templates.length" description="本厂还没有可用的检查类型，请联系管理员确认。" />
        </section>
        <section v-else-if="wizardStep === 1" data-testid="vision-step-location" class="wizard-panel">
          <h2>在哪检查、检查哪些产品？</h2><p class="help">填写工厂里平时使用的名称即可。</p>
          <el-form label-position="top" :disabled="busy" class="vision-form">
            <el-form-item label="生产线或检查位置" required>
              <el-input v-model="spec.workstation_id" maxlength="100" placeholder="例如：一号包装线出口" data-testid="vision-workstation" />
            </el-form-item>
            <el-form-item label="要检查的产品" required>
              <el-input v-model="productsText" :list="productListId" placeholder="例如：卤猪蹄、卤鸭掌" data-testid="vision-products" />
              <datalist v-if="productOptions.length" :id="productListId"><option v-for="item in productOptions" :key="item" :value="item" /></datalist>
            </el-form-item>
            <el-form-item label="产品怎么包装" required>
              <el-input v-model="packagingText" placeholder="例如：五百克袋装、整盒装" data-testid="vision-packaging" />
            </el-form-item>
          </el-form>
          <p class="help">有多种产品或包装时，用顿号“、”隔开。每项最多一百字，最多一百项。</p>
          <p v-if="(productsText.trim() && !validScope(spec.product_scope)) || (packagingText.trim() && !validScope(spec.packaging_scope))" class="field-warning">请检查名称：每项最多一百字，最多一百项，同一名称不要重复填写。</p>
        </section>
        <section v-else class="wizard-panel" data-testid="vision-preview">
          <h2>确认这项检查</h2>
          <dl class="business-summary">
            <div><dt>检查内容</dt><dd>{{ templateName(selectedTemplate) }}</dd></div>
            <div><dt>检查位置</dt><dd>{{ spec.workstation_id }}</dd></div>
            <div><dt>产品</dt><dd>{{ spec.product_scope.join('、') }}</dd></div>
            <div><dt>包装</dt><dd>{{ spec.packaging_scope.join('、') }}</dd></div>
          </dl>
          <div class="preparation-note"><strong>接下来需要准备</strong>
            <ul><li>本厂现场拍摄的合格与不合格照片。</li><li>由质量负责人确认：哪些情况算不合格、照片中要标记哪里。</li>
              <li>另外留一组本厂新照片，学习后用来试用检查。</li></ul>
          </div>
          <p class="help">保存后可以继续准备资料。保存设置不会开始学习，也不会启用模型。</p>
        </section>
        <div class="wizard-actions">
          <el-button v-if="wizardStep > 0" :disabled="busy" @click="wizardStep--">返回上一步</el-button>
          <el-button type="primary" :loading="busy" :disabled="!canContinue" @click="continueSetup">
            {{ wizardStep === 2 ? (preview ? '保存检查任务' : '重新确认检查') : '下一步' }}
          </el-button>
        </div>
      </template>
      <el-alert v-if="actionError" class="action-error" type="error" :closable="false" :title="friendlyError(actionError)" show-icon />
      <details v-if="actionError" class="technical-error"><summary>给维护人员看原始原因</summary><p>{{ actionError }}</p></details>
      <div v-if="selectedProject && !draft && !projectLoading" class="recovery-actions">
        <el-button type="primary" @click="loadProject">重新读取这项检查</el-button><el-button @click="newProject">返回新增检查</el-button>
      </div>
      <section v-if="draft && !projectLoading" class="vision-progress" data-testid="vision-project">
        <div class="section-heading"><div><p class="eyebrow">本厂检查</p><h2>{{ templateName(draft.template) }}</h2></div>
          <el-button :loading="projectLoading" :disabled="busy || !!submitting" @click="loadProject">更新进度</el-button></div>
        <dl class="business-summary">
          <div><dt>检查位置</dt><dd>{{ businessValue(draft.spec.workstation_id, '检查位置') }}</dd></div>
          <div><dt>产品</dt><dd>{{ businessScope(draft.spec.product_scope, '产品') }}</dd></div>
          <div><dt>包装</dt><dd>{{ businessScope(draft.spec.packaging_scope, '包装') }}</dd></div>
        </dl>
        <p v-if="draft.template.scope === 'specialized'" class="help">这是本厂专用检查，其他工厂需要用各自的照片重新准备和试用。</p>
        <p v-if="isPreparedExecution(draft)" class="help">这是已提交资料的学习执行记录，从整理学习数据开始。照片审核与合格要求请回到原检查查看。</p>
        <VisionPhotoPreparation v-else :factory-id="factoryId" :project-id="draft.project_id" @execution-linked="linkPreparationExecution" />
        <div v-if="preparedExecutionId" class="preparation-note" data-testid="vision-linked-execution">
          <p>这批照片与合格要求已关联本厂学习记录，可查看数据整理和模型学习的实际进度。</p>
          <el-button :disabled="projectLoading || !!submitting" @click="openPreparationExecution">查看本次学习进度</el-button>
        </div>
        <details v-else class="execution-details" :open="isPreparedExecution(draft)"><summary>查看后台处理进度与执行记录</summary>
        <div v-if="plan && !isPreparedExecution(draft)" class="next-action" data-testid="vision-next-action">
          <div><strong>{{ nextActionTitle }}</strong><p>{{ nextActionDescription }}</p></div>
          <el-button v-if="nextStage && executable.includes(nextStage)" type="primary" data-testid="vision-next-stage"
            :disabled="!canEnqueue(nextStage)" :loading="!!submitting" @click="enqueue(nextStage)">{{ actionLabels[nextStage] }}</el-button>
          <el-button v-if="nextStage && !canEnqueue(nextStage)" @click="showPreparation = !showPreparation">{{ showPreparation ? '收起准备清单' : '查看准备清单' }}</el-button>
        </div>
        <div v-if="showPreparation" class="preparation-note" data-testid="vision-preparation">
          <strong>请按顺序准备</strong><ul><li>由质量负责人整理本厂合格、不合格照片，确认照片标记和判断要求。</li>
            <li>请维护人员把照片、检查要求和本厂执行设置接好；系统记录中可核对对应资料。</li>
            <li>完成学习和效果检查后，用另一组本厂新照片试用。通过前不能启用。</li></ul>
          <el-button text @click="showTechnical = true">查看交给维护人员的系统记录</el-button>
        </div>
        <ol v-if="plan" class="progress-list" aria-label="本厂检查进度">
          <li v-for="(item, index) in plan.items.filter(item => !isPreparedExecution(draft) || item.stage !== 'collect')" :key="item.stage" :data-stage="item.stage">
            <span class="stage-number">{{ index + 1 }}</span><span class="stage-name">{{ stageLabels[item.stage] }}</span>
            <el-tag :type="stageStatus(item.stage, item.status) === '已完成' ? 'success' : 'info'">{{ stageStatus(item.stage, item.status) }}</el-tag>
          </li>
        </ol>
        <el-alert v-if="plan" :type="plan.accepted ? 'success' : 'info'" :closable="false" show-icon
          :title="plan.accepted ? '本厂新照片试用已通过' : '本厂新照片试用待完成'"
          :description="plan.enabled ? '服务端记录：本厂模型已启用。' : '尚未启用模型；学习完成后，也要先通过本厂试用检查。'" />
        <section v-if="plan?.blockers.length || jobs.length" class="daily-records">
          <h3>进度说明</h3><p v-for="reason in translatedBlockers" :key="reason">{{ reason }}</p>
          <p v-for="job in jobs" :key="job.job_id">{{ stageLabels[job.stage] }}：{{ jobLabels[job.status] }}<template v-if="job.blocked_reason">。{{ friendlyReason(job.blocked_reason) }}</template></p>
        </section>
        </details>
      </section>
      <div v-if="selectedTemplate && !projectLoading" class="technical-section">
        <el-button text :aria-expanded="showTechnical" data-testid="vision-technical-toggle" @click="showTechnical = !showTechnical">{{ showTechnical ? '收起系统记录' : '系统记录（维护人员）' }}</el-button>
        <section v-if="showTechnical" data-testid="vision-technical-details" class="technical-details">
          <p>这里保留原始编号与设置，供维护人员核对。本次设置的记录编号由系统生成；编号不代表照片、判断要求或试用资料已准备完成。</p>
          <p>工厂编号：{{ factoryId }}<template v-if="draft"> · 项目编号：{{ draft.project_id }}</template></p>
          <p>检查类型原始编号：{{ selectedTemplate.template_id }} · {{ spec.task_family }}<template v-if="selectedTemplate.adapter_id"> · {{ selectedTemplate.adapter_id }}</template></p>
          <p>检查位置原值：{{ spec.workstation_id }}；产品原值：{{ spec.product_scope.join('、') }}；包装原值：{{ spec.packaging_scope.join('、') }}</p>
          <el-form label-position="top" class="vision-form" :disabled="!!draft || busy || !!selectedProject">
            <el-form-item label="本厂执行设置">
              <el-select v-model="spec.recipe_id" clearable placeholder="尚未关联执行设置" data-testid="vision-recipe" @change="chooseRecipe">
                <el-option v-for="(item, index) in matchingRecipes" :key="item.recipe_id" :value="item.recipe_id" :disabled="!item.available" :label="`本厂设置 ${index + 1} · ${item.available ? '可用' : '待接通'}`" />
              </el-select>
            </el-form-item>
            <el-form-item v-for="field in versionFields" :key="field.key" :label="field.label">
              <el-input v-model="spec[field.key]" maxlength="100" :data-testid="`vision-${field.key}`" :disabled="!!draft || !!spec.recipe_id" />
            </el-form-item>
            <el-form-item v-if="selectedTemplate.ocr_rules_required" label="文字校验规则记录">
              <el-input v-model="spec.ocr_rules_version" maxlength="100" data-testid="vision-ocr_rules_version" :disabled="!!draft || !!spec.recipe_id" />
            </el-form-item>
          </el-form>
          <p>执行设置原始编号：{{ spec.recipe_id || '未关联' }}</p><p v-for="item in matchingRecipes" :key="item.recipe_id">{{ item.recipe_id }}：{{ item.reason }}</p>
          <p v-for="reason in plan?.blockers" :key="reason">{{ reason }}</p>
          <p v-for="job in jobs" :key="job.job_id">{{ job.job_id }} · {{ job.stage }} · {{ job.status }} {{ job.blocked_reason || '' }}</p>
          <p v-if="draft">原始检查位置、编号和版本不会因页面中文化而更改；需要改变检查内容时，请新增检查。</p>
        </section>
      </div>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { getVisionTemplates, listVisionDrafts, getVisionDraft, getVisionPlan, listVisionJobs,
  previewVisionTraining, saveVisionDraft, enqueueVisionStage,
  type VisionTaskSpec, type VisionTemplate, type VisionRecipe, type VisionPreview,
  type VisionDraft, type VisionPlan, type VisionJob, type VisionStage, type VisionReceiptStatus } from '@/api/visionTraining';
import { actionLabels, stageLabels, templateName, templateHint, businessValue, businessScope, projectLabel,
  friendlyReason, friendlyError, newSettingReferences } from './presentation';
import VisionPhotoPreparation from './VisionPhotoPreparation.vue';

const props = withDefaults(defineProps<{ factoryId: string; productOptions?: string[] }>(), { productOptions: () => [] });
const jobLabels: Record<VisionJob['status'], string> = { queued: '等待处理', running: '正在处理', succeeded: '已完成', failed: '处理失败，请核对记录', blocked: '资料还没准备好' };
const executable: VisionStage[] = ['collect', 'dataset', 'train', 'evaluate'];
const wizardLabels = ['选择检查内容', '填写生产信息', '确认并保存'];
const versionFields = [
  { key: 'data_version', label: '照片资料记录' }, { key: 'recipe_version', label: '执行设置记录' },
  { key: 'human_standard_version', label: '合格判断要求记录' }, { key: 'independent_acceptance_version', label: '本厂试用照片记录' },
] as const;
const emptySpec = (): VisionTaskSpec => ({ factory_id: props.factoryId, workstation_id: '', task_family: '', product_scope: [], packaging_scope: [],
  ...newSettingReferences(), recipe_id: null });
const spec = ref<VisionTaskSpec>(emptySpec()), templateId = ref('');
const templates = ref<VisionTemplate[]>([]), recipes = ref<VisionRecipe[]>([]), drafts = ref<VisionDraft[]>([]), jobs = ref<VisionJob[]>([]);
const preview = ref<VisionPreview | null>(null), draft = ref<VisionDraft | null>(null), plan = ref<VisionPlan | null>(null);
const selectedProject = ref(''), loading = ref(false), busy = ref(false), projectLoading = ref(false), submitting = ref<VisionStage | null>(null);
const preparedExecutionId = ref('');
const loadError = ref(''), actionError = ref('');
const wizardStep = ref(0), showTechnical = ref(false), showPreparation = ref(false);
let factoryGeneration = 0, projectGeneration = 0;
const productListId = computed(() => `vision-products-${props.factoryId}`);
// Frozen preparation executions use a reserved content-derived data version.
function isPreparedExecution(value: VisionDraft): boolean {
  return !!value.spec.recipe_id && /^prep-[0-9a-f]{64}$/.test(value.spec.data_version);
}
const splitScope = (value: string) => value.split(/[、，,\n]/).map(item => item.trim()).filter(Boolean);
const productsInput = ref(''), packagingInput = ref('');
const productsText = computed({ get: () => productsInput.value, set: (value: string) => { productsInput.value = value; spec.value.product_scope = splitScope(value); } });
const packagingText = computed({ get: () => packagingInput.value, set: (value: string) => { packagingInput.value = value; spec.value.packaging_scope = splitScope(value); } });
const selectedTemplate = computed(() => templates.value.find(item => item.template_id === templateId.value));
const matchingRecipes = computed(() => recipes.value.filter(item => item.factory_id === props.factoryId && item.task_family === selectedTemplate.value?.task_family));
const availableRecipe = computed(() => matchingRecipes.value.find(item => item.available && item.recipe_id === spec.value.recipe_id
  && item.recipe_version === spec.value.recipe_version && item.data_version === spec.value.data_version
  && item.human_standard_version === spec.value.human_standard_version
  && item.independent_acceptance_version === spec.value.independent_acceptance_version
  && (item.ocr_rules_version || null) === (spec.value.ocr_rules_version || null)));
const validScope = (values: string[]) => values.length > 0 && values.length <= 100
  && values.every(value => value.trim().length > 0 && value.length <= 100) && new Set(values).size === values.length;
const complete = computed(() => !!selectedTemplate.value && !!spec.value.workstation_id.trim() && spec.value.workstation_id.length <= 100
  && validScope(spec.value.product_scope) && validScope(spec.value.packaging_scope)
  && versionFields.every(field => !!spec.value[field.key].trim() && spec.value[field.key].length <= 100)
  && (!selectedTemplate.value.ocr_rules_required || !!spec.value.ocr_rules_version?.trim()));
const canContinue = computed(() => !busy.value && (wizardStep.value === 0 ? !!selectedTemplate.value : complete.value));
const nextStage = computed(() => plan.value?.items.find(item => item.stage !== 'preview' && item.status !== 'succeeded')?.stage);
const translatedBlockers = computed(() => [...new Set((plan.value?.blockers || []).map(friendlyReason))]);
const nextActionTitle = computed(() => nextStage.value ? `下一步：${stageLabels[nextStage.value]}` : '本次检查准备进度');
const nextActionDescription = computed(() => {
  const stage = nextStage.value;
  if (!stage) return plan.value?.enabled ? '服务端已记录本厂模型启用状态。' : '请核对下面的本厂试用与启用状态。';
  const job = jobs.value.find(item => item.stage === stage);
  if (job) return job.status === 'queued' || job.status === 'running' ? '本步骤正在等待或处理中，请稍后点击“更新进度”。' : '本步骤已有执行记录，请核对进度说明；失败时请让维护人员处理。';
  if (stage === 'independent_acceptance') return '请用没有参与学习的本厂新照片试用，由质量负责人单独验收。';
  if (stage === 'enable') return '请按现有发布流程单独确认启用，页面不会自动切换模型。';
  if (!availableRecipe.value || !availableRecipe.value.stage_input_hashes?.[stage]) return '这一步的本厂资料或执行设置还没接好。可以先看准备清单，再请维护人员补齐。';
  return canEnqueue(stage) ? '本厂设置已接好，可以提交这一步；系统会保留处理结果。' : '前一步还没完成，请先按顺序完成准备，并更新进度。';
});
function chooseTemplate(id: string) {
  if (busy.value) return;
  templateId.value = id; spec.value.task_family = selectedTemplate.value?.task_family || '';
  spec.value.recipe_id = null; Object.assign(spec.value, newSettingReferences());
  if (selectedTemplate.value?.ocr_rules_required) spec.value.ocr_rules_version = spec.value.recipe_version;
  // Catalog membership alone cannot prove the recipe fits this product or
  // inspection position. New tasks stay unbound until explicitly configured.
}
watch(() => JSON.stringify(spec.value), () => { preview.value = null; });
function message(error: unknown) { return error instanceof Error ? error.message : '检查服务暂时不可用，请稍后重试'; }
function chooseRecipe() {
  const recipe = matchingRecipes.value.find(item => item.recipe_id === spec.value.recipe_id);
  if (!recipe) { Object.assign(spec.value, newSettingReferences()); if (selectedTemplate.value?.ocr_rules_required) spec.value.ocr_rules_version = spec.value.recipe_version; return; }
  spec.value.recipe_version = recipe.recipe_version;
  spec.value.data_version = recipe.data_version || '';
  spec.value.human_standard_version = recipe.human_standard_version || '';
  spec.value.independent_acceptance_version = recipe.independent_acceptance_version || '';
  spec.value.ocr_rules_version = recipe.ocr_rules_version || null;
}
function newProject() {
  projectGeneration++; selectedProject.value = ''; preparedExecutionId.value = ''; draft.value = null; preview.value = null; plan.value = null; jobs.value = []; actionError.value = '';
  spec.value = emptySpec(); templateId.value = ''; wizardStep.value = 0; showTechnical.value = false; showPreparation.value = false;
  productsInput.value = ''; packagingInput.value = '';
}
async function loadFactory() {
  const generation = ++factoryGeneration, factory = props.factoryId;
  newProject(); templates.value = []; recipes.value = []; drafts.value = [];
  loadError.value = ''; loading.value = true; busy.value = false; projectLoading.value = false; submitting.value = null;
  try {
    const [catalog, saved] = await Promise.all([getVisionTemplates(factory), listVisionDrafts(factory)]);
    if (generation !== factoryGeneration) return;
    if (saved.some(item => item.spec.factory_id !== factory) || catalog.recipes.some(item => item.factory_id !== factory)) throw new Error('读回的视觉项目范围与当前工厂不一致');
    templates.value = catalog.templates; recipes.value = catalog.recipes; drafts.value = saved;
  } catch (error) { if (generation === factoryGeneration) loadError.value = message(error); }
  finally { if (generation === factoryGeneration) loading.value = false; }
}
async function loadProject() {
  const factory = props.factoryId, project = selectedProject.value, generation = ++projectGeneration, owner = factoryGeneration;
  if (!project) return;
  projectLoading.value = true; actionError.value = ''; preparedExecutionId.value = ''; draft.value = null; preview.value = null; plan.value = null; jobs.value = [];
  spec.value = emptySpec(); templateId.value = ''; showTechnical.value = false; showPreparation.value = false;
  productsInput.value = ''; packagingInput.value = '';
  try {
    const [saved, progress, executions] = await Promise.all([getVisionDraft(factory, project), getVisionPlan(factory, project), listVisionJobs(factory, project)]);
    if (owner !== factoryGeneration || generation !== projectGeneration) return;
    if (saved.spec.factory_id !== factory || saved.project_id !== project || progress.project_id !== project
      || executions.some(job => job.identity.factory_id !== factory || job.identity.project_id !== project)) throw new Error('读回的训练记录与当前工厂或项目不一致');
    templateId.value = saved.template.template_id; spec.value = { ...saved.spec, product_scope: [...saved.spec.product_scope], packaging_scope: [...saved.spec.packaging_scope] };
    productsInput.value = saved.spec.product_scope.join('、'); packagingInput.value = saved.spec.packaging_scope.join('、');
    draft.value = saved; plan.value = progress; jobs.value = executions;
    if (!drafts.value.some(item => item.project_id === saved.project_id)) drafts.value = [saved, ...drafts.value];
  } catch (error) { if (owner === factoryGeneration && generation === projectGeneration) actionError.value = message(error); }
  finally { if (owner === factoryGeneration && generation === projectGeneration) projectLoading.value = false; }
}
function linkPreparationExecution(link: { factoryId: string; projectId: string; executionProjectId: string | null }) {
  if (!draft.value || isPreparedExecution(draft.value) || link.factoryId !== props.factoryId || link.projectId !== draft.value.project_id) return;
  preparedExecutionId.value = link.executionProjectId && link.executionProjectId !== draft.value.project_id ? link.executionProjectId : '';
}
async function openPreparationExecution() {
  if (!preparedExecutionId.value || projectLoading.value || submitting.value) return;
  selectedProject.value = preparedExecutionId.value;
  await loadProject();
}
async function continueSetup() {
  if (!canContinue.value || selectedProject.value) return;
  if (wizardStep.value === 0) { wizardStep.value = 1; return; }
  if (wizardStep.value === 2 && preview.value) { await saveTask(); return; }
  await previewTask(); if (preview.value) wizardStep.value = 2;
}
async function previewTask() {
  if (!complete.value || busy.value || selectedProject.value) return;
  const generation = factoryGeneration, payload: VisionTaskSpec = { ...spec.value, product_scope: [...spec.value.product_scope], packaging_scope: [...spec.value.packaging_scope] }, currentTemplate = templateId.value;
  const fingerprint = JSON.stringify(spec.value);
  busy.value = true; actionError.value = '';
  try {
    const result = await previewVisionTraining(props.factoryId, payload, currentTemplate);
    if (generation !== factoryGeneration || fingerprint !== JSON.stringify(spec.value)) return;
    if (result.spec.factory_id !== props.factoryId) throw new Error('预览工厂与当前工厂不一致');
    preview.value = result;
  } catch (error) { if (generation === factoryGeneration) actionError.value = message(error); }
  finally { if (generation === factoryGeneration) busy.value = false; }
}
async function saveTask() {
  const reviewed = preview.value, factory = props.factoryId, generation = factoryGeneration;
  if (!reviewed || busy.value || reviewed.spec.factory_id !== factory) return;
  busy.value = true; actionError.value = '';
  try {
    const saved = await saveVisionDraft(factory, reviewed);
    if (generation !== factoryGeneration) return;
    if (saved.spec.factory_id !== factory) throw new Error('保存结果与当前工厂不一致');
    drafts.value = [saved, ...drafts.value.filter(item => item.project_id !== saved.project_id)];
    selectedProject.value = saved.project_id; await loadProject();
    if (generation === factoryGeneration && !actionError.value) ElMessage.success('本厂检查任务已保存');
  } catch (error) { if (generation === factoryGeneration) actionError.value = message(error); }
  finally { if (generation === factoryGeneration) busy.value = false; }
}
function stageStatus(stage: VisionStage, status: VisionReceiptStatus): string {
  const job = jobs.value.find(item => item.stage === stage);
  return job ? jobLabels[job.status] : status === 'succeeded' ? '已完成' : status === 'failed' ? '处理失败' : '待完成';
}
function canEnqueue(stage: VisionStage): boolean {
  const recipe = availableRecipe.value;
  if (!draft.value || !recipe || !recipe.stage_input_hashes?.[stage] || projectLoading.value || submitting.value || jobs.value.some(job => job.stage === stage)) return false;
  const index = executable.indexOf(stage);
  return index >= 0 && (index === 0 || plan.value?.items.some(item => item.stage === executable[index - 1] && item.status === 'succeeded') === true);
}
async function enqueue(stage: VisionStage) {
  if (!canEnqueue(stage) || !draft.value?.spec.recipe_id) return;
  const factory = props.factoryId, project = draft.value.project_id, generation = factoryGeneration;
  submitting.value = stage; actionError.value = '';
  try {
    const job = await enqueueVisionStage(factory, project, stage, draft.value.spec.recipe_id);
    if (generation !== factoryGeneration || selectedProject.value !== project) return;
    if (job.identity.factory_id !== factory || job.identity.project_id !== project) throw new Error('执行任务与当前工厂或项目不一致');
    if (job.status === 'blocked' || job.status === 'failed') ElMessage.warning('资料或设置还没准备好，请查看进度说明');
    else ElMessage.success('已提交，等待本厂处理');
    await loadProject();
  } catch (error) { if (generation === factoryGeneration) actionError.value = message(error); }
  finally { if (generation === factoryGeneration) submitting.value = null; }
}
watch(() => props.factoryId, loadFactory, { immediate: true });
</script>

<style scoped>
.vision-setup { margin-top: 16px; border-radius: 10px; }
.execution-details { margin-top: 20px; }
.execution-details > summary { display: flex; align-items: center; min-height: 44px; cursor: pointer; color: #1b65a8; }
.vision-resume, .section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.vision-resume { padding-bottom: 20px; border-bottom: 1px solid #edf2f7; margin-bottom: 24px; }
.resume-picker { flex: 1; max-width: 620px; }
.resume-picker label { display: block; margin-bottom: 8px; font-size: 13px; color: #7a8599; }
.resume-picker .el-select, .vision-form .el-select { width: 100%; }
.wizard-steps { list-style: none; display: flex; padding: 0; margin: 0 0 28px; gap: 24px; }
.wizard-steps li { display: flex; gap: 8px; align-items: center; color: #7a8599; font-size: 13px; }
.wizard-steps li span, .stage-number { display: inline-flex; justify-content: center; align-items: center; width: 28px; height: 28px; border-radius: 50%; background: #f4f6f9; flex-shrink: 0; }
.wizard-steps .current { color: #1b65a8; font-weight: 600; }
.wizard-steps .current span, .wizard-steps .done span { color: #fff; background: #1b65a8; }
h2 { margin: 0; font-size: 20px; line-height: 1.5; color: #1a2332; }
h3 { font-size: 15px; }
.help, .technical-details p { color: #7a8599; line-height: 1.7; font-size: 13px; }
.task-choices { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin: 24px 0; }
.task-choices button { cursor: pointer; text-align: left; background: #fff; border: 1px solid #dce2ea; border-radius: 10px; padding: 24px; min-height: 112px; font: inherit; color: #1a2332; }
.task-choices button:hover, .task-choices button.selected { border-color: #1b65a8; background: #f0f6fc; }
.task-choices button:focus-visible { outline: 2px solid #1b65a8; outline-offset: 3px; }
.task-choices strong, .task-choices span { display: block; }
.task-choices strong { font-size: 17px; margin-bottom: 10px; }
.task-choices span { font-size: 13px; color: #7a8599; line-height: 1.65; }
.vision-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 20px; margin-top: 20px; }
.vision-form :deep(.el-input__wrapper) { min-height: 44px; }
.wizard-actions { display: flex; justify-content: flex-end; gap: 12px; padding-top: 20px; margin-top: 24px; border-top: 1px solid #edf2f7; }
.wizard-actions .el-button, .vision-resume .el-button, .next-action .el-button, .recovery-actions .el-button { min-height: 44px; margin-left: 0; }
.business-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; margin: 24px 0; padding: 20px; background: #f4f6f9; border-radius: 10px; }
.business-summary dt { font-size: 13px; color: #7a8599; margin-bottom: 6px; }
.business-summary dd { margin: 0; line-height: 1.7; color: #1a2332; overflow-wrap: anywhere; }
.preparation-note { margin-top: 16px; padding: 20px; background: #f4f6f9; border-radius: 10px; color: #1a2332; }
.preparation-note ul { padding-left: 20px; line-height: 1.9; margin-bottom: 0; font-size: 14px; }
.eyebrow { font-size: 12px; color: #1b65a8; margin: 0 0 4px; }
.next-action { padding: 20px; border: 1px solid #dceaf5; background: #f0f6fc; border-radius: 10px; display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.next-action > div { flex: 1; min-width: 240px; }
.next-action p { color: #596579; line-height: 1.7; margin: 8px 0 0; font-size: 13px; }
.progress-list { list-style: none; padding: 0; margin: 24px 0; }
.progress-list li { display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid #edf2f7; }
.stage-number { font-size: 12px; color: #7a8599; }
.stage-name { flex: 1; font-size: 14px; }
.daily-records { margin-top: 24px; font-size: 13px; color: #596579; line-height: 1.75; }
.technical-section { margin-top: 24px; border-top: 1px solid #edf2f7; padding-top: 12px; }
.technical-details { padding: 16px; background: #f8f9fb; border: 1px dashed #dce2ea; border-radius: 10px; overflow-wrap: anywhere; }
.technical-error { margin: 16px 0; color: #7a8599; font-size: 13px; overflow-wrap: anywhere; }
.technical-error summary { cursor: pointer; }
.action-error, .recovery-actions, .retry { margin-top: 16px; }
.field-warning { color: #b05012; font-size: 13px; }
@media (max-width: 640px) {
  .vision-form, .task-choices, .business-summary { grid-template-columns: 1fr; }
  .vision-resume, .section-heading { align-items: flex-start; flex-direction: column; }
  .resume-picker { width: 100%; }.wizard-steps { gap: 10px; }.wizard-steps li { font-size: 12px; gap: 4px; }
}
</style>
