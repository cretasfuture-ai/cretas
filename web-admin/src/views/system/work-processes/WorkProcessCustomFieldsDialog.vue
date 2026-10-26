<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  updateWorkProcess,
  type ProcessSheetCustomFieldDef,
  type WorkProcessItem,
} from '@/api/processProduction';

type CustomFieldType = ProcessSheetCustomFieldDef['type'];
type DraftFieldType = CustomFieldType | '';

interface DraftField {
  key: string;
  label: string;
  type: DraftFieldType;
  enabled: boolean;
}

const props = withDefaults(defineProps<{
  modelValue: boolean;
  factoryId: string;
  workProcess: WorkProcessItem | null;
  canWrite: boolean;
}>(), {
  canWrite: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [schema: ProcessSheetCustomFieldDef[]];
}>();

const fields = ref<DraftField[]>([]);
const saving = ref(false);
const validationError = ref('');
let requestVersion = 0;

const contextKey = computed(() => `${props.factoryId}:${props.workProcess?.id ?? ''}`);
const typeOptions: Array<{ label: string; value: CustomFieldType }> = [
  { label: '数字', value: 'number' },
  { label: '文本', value: 'text' },
  { label: '日期', value: 'date' },
];

function isCustomFieldType(value: unknown): value is CustomFieldType {
  return value === 'number' || value === 'text' || value === 'date';
}

function readField(value: ProcessSheetCustomFieldDef | null | undefined): DraftField {
  const source = value as (Partial<ProcessSheetCustomFieldDef> & { type?: unknown }) | null | undefined;
  return {
    key: typeof source?.key === 'string' ? source.key : '',
    label: typeof source?.label === 'string' ? source.label : '',
    // An unsupported server value stays visibly invalid; it must never become number by default.
    type: isCustomFieldType(source?.type) ? source.type : '',
    enabled: source?.enabled !== false,
  };
}

function hydrate(): void {
  fields.value = Array.isArray(props.workProcess?.customFieldSchema)
    ? props.workProcess.customFieldSchema.map(field => readField(field))
    : [];
  validationError.value = '';
}

watch(contextKey, () => {
  requestVersion += 1;
  hydrate();
}, { immediate: true });

watch(() => props.modelValue, (visible) => {
  requestVersion += 1;
  if (visible) hydrate();
  else validationError.value = '';
});

watch(() => props.canWrite, (canWrite) => {
  if (!canWrite) requestVersion += 1;
});

function addField(): void {
  if (!props.canWrite || saving.value) return;
  validationError.value = '';
  fields.value.push({ key: '', label: '', type: 'text', enabled: true });
}

function removeField(index: number): void {
  if (!props.canWrite || saving.value) return;
  validationError.value = '';
  fields.value.splice(index, 1);
}

function validateFields(): ProcessSheetCustomFieldDef[] | null {
  const schema = fields.value.map(field => ({
    key: field.key.trim(),
    label: field.label.trim(),
    type: field.type,
    enabled: field.enabled,
  }));
  const invalidIndex = schema.findIndex(field => !field.key || !field.label);
  if (invalidIndex >= 0) {
    validationError.value = `第 ${invalidIndex + 1} 个字段必须填写 key 和显示名称`;
    return null;
  }
  const keys = schema.map(field => field.key);
  if (new Set(keys).size !== keys.length) {
    validationError.value = '自定义字段 key 不能重复';
    return null;
  }
  if (schema.some(field => !isCustomFieldType(field.type))) {
    validationError.value = '存在不支持的字段类型，请选择数字、文本或日期';
    return null;
  }
  return schema as ProcessSheetCustomFieldDef[];
}

function buildPayload(row: WorkProcessItem, schema: ProcessSheetCustomFieldDef[]): Partial<WorkProcessItem> {
  const payload: Partial<WorkProcessItem> = {
    processName: row.processName,
    processCategory: row.processCategory,
    estimatedMinutes: row.estimatedMinutes,
    standardYieldMin: row.standardYieldMin,
    standardYieldMax: row.standardYieldMax,
    needsInput: row.needsInput,
    defaultOutputMaterialKind: row.defaultOutputMaterialKind,
    semiFinishedOutputCode: row.semiFinishedOutputCode ?? null,
    standardHourlyRate: row.standardHourlyRate,
    customFieldSchema: schema,
  };
  if (row.unit !== undefined) payload.unit = row.unit;
  if (row.outputUnit !== undefined) payload.outputUnit = row.outputUnit;
  return payload;
}

async function save(): Promise<void> {
  if (!props.canWrite || saving.value || !props.workProcess || !props.factoryId) return;
  validationError.value = '';
  const schema = validateFields();
  if (!schema) {
    ElMessage.error(validationError.value);
    return;
  }

  const row = props.workProcess;
  const requestContext = contextKey.value;
  const request = ++requestVersion;
  saving.value = true;
  try {
    const response = await updateWorkProcess(props.factoryId, row.id, buildPayload(row, schema));
    if (!response.success) {
      if (request !== requestVersion || requestContext !== contextKey.value || !props.canWrite) return;
      validationError.value = response.message || '保存自定义字段失败';
      ElMessage.error(validationError.value);
      return;
    }
    // A factory/object switch or permission revocation invalidates this response.
    if (request !== requestVersion || requestContext !== contextKey.value || !props.canWrite) return;
    emit('saved', schema);
    emit('update:modelValue', false);
    ElMessage.success('自定义字段已保存');
  } catch (_error) {
    if (request !== requestVersion || requestContext !== contextKey.value || !props.canWrite) return;
    validationError.value = '保存自定义字段失败，请稍后重试';
    ElMessage.error(validationError.value);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="自定义字段"
    width="720px"
    append-to-body
    :close-on-click-modal="false"
    data-testid="work-process-custom-fields-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="custom-fields-context">
      <span>工序：{{ workProcess?.processName || '-' }}</span>
      <span class="custom-fields-id">ID：{{ workProcess?.id || '-' }}</span>
    </div>
    <el-alert
      v-if="validationError"
      type="error"
      :closable="false"
      :title="validationError"
      data-testid="custom-fields-error"
    />
    <div v-if="fields.length === 0" class="custom-fields-empty">尚未配置自定义字段</div>
    <div v-for="(field, index) in fields" :key="index" class="custom-field-row">
      <el-input
        v-model="field.key"
        :disabled="!canWrite || saving"
        placeholder="字段 key"
        :data-testid="`custom-field-key-${index}`"
      />
      <el-input
        v-model="field.label"
        :disabled="!canWrite || saving"
        placeholder="显示名称"
        :data-testid="`custom-field-label-${index}`"
      />
      <el-select
        v-model="field.type"
        :disabled="!canWrite || saving"
        placeholder="字段类型"
        :data-testid="`custom-field-type-${index}`"
      >
        <el-option v-for="option in typeOptions" :key="option.value" v-bind="option" />
      </el-select>
      <el-switch
        v-model="field.enabled"
        :disabled="!canWrite || saving"
        :data-testid="`custom-field-enabled-${index}`"
      />
      <el-button
        type="danger"
        text
        :disabled="!canWrite || saving"
        :data-testid="`custom-field-remove-${index}`"
        @click="removeField(index)"
      >删除</el-button>
    </div>
    <el-button
      type="primary"
      plain
      :disabled="!canWrite || saving"
      data-testid="custom-field-add"
      @click="addField"
    >添加字段</el-button>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="!canWrite || saving || !workProcess"
        data-testid="custom-field-save"
        @click="save"
      >保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.custom-fields-context { display: flex; gap: 16px; margin-bottom: 16px; color: var(--el-text-color-primary); }
.custom-fields-id { color: var(--el-text-color-secondary); }
.custom-fields-empty { padding: 20px 0; color: var(--el-text-color-secondary); text-align: center; }
.custom-field-row { display: grid; grid-template-columns: 1fr 1fr 130px 60px 56px; gap: 8px; align-items: center; margin-bottom: 12px; }
</style>
