<template>
  <UModal
    :open="modelValue"
    title="导入供应商"
    description="从 Excel 批量导入供应商"
    :dismissible="false"
    :ui="modalUi('max-w-[960px]')"
    @update:open="(open: boolean) => $emit('update:modelValue', open)"
  >
    <template #body>
      <UStepper :model-value="activeStep" :items="STEP_ITEMS" disabled class="import-steps" data-testid="supplier-import-steps" />

      <UAlert :ui="alertUi"
        class="zero-write-alert"
        color="info"
        variant="subtle"
        icon="i-lucide-info"
        title="选择文件、字段识别和预览阶段不会创建或修改供应商"
      />

      <div class="mode-row">
        <URadioGroup v-model="mode" :items="MODE_ITEMS" orientation="horizontal" :disabled="Boolean(preview)" data-testid="supplier-import-mode" />
        <span class="mode-help">
          {{ mode === 'STANDARD' ? '严格按标准列名识别' : '支持常见中文别名和任意列顺序，映射需人工确认' }}
        </span>
      </div>

      <div v-if="!preview" data-testid="supplier-import-upload">
        <UFileUpload
          v-model="fileModel"
          accept=".xlsx,.xls"
          label="拖入 Excel 或点击选择"
          description="仅支持 xlsx/xls，最大 10MB；请勿上传带宏文件。"
          icon="i-lucide-upload"
          class="w-full"
          :ui="{ base: 'min-h-32' }"
        />
      </div>

      <template v-if="selectedFile && !preview">
        <div class="mapping-title">
          <strong>源列 → 供应商字段</strong>
          <UBadge v-if="missingRequired.length" color="error" variant="outline" data-testid="supplier-import-missing">
            缺少必填映射：{{ missingRequired.join('、') }}
          </UBadge>
          <UBadge v-else color="success" variant="outline">必填字段映射完整</UBadge>
        </div>
        <UTable :data="mappingRows" :columns="mappingColumns" class="cs-table import-table" data-testid="supplier-import-mapping">
          <template #target-cell="{ row }">
            <USelect
              :model-value="mapping[row.original.sourceHeader] || IGNORE"
              :items="fieldItemsFor(row.original.sourceHeader)"
              class="w-full"
              @update:model-value="(value: string) => setMapping(row.original.sourceHeader, value)"
            />
          </template>
          <template #status-cell="{ row }">
            <UBadge v-if="mapping[row.original.sourceHeader]" color="success" variant="outline">已确认</UBadge>
            <UBadge v-else color="neutral" variant="outline">忽略</UBadge>
          </template>
        </UTable>
        <div class="local-check" data-testid="supplier-import-local-check">
          本地预检：有效候选 {{ localCounts.valid }}，字段错误 {{ localCounts.error }}，空行 {{ localCounts.ignored }}。
          数据库重复项将在“生成预览”时只读核对。
        </div>
      </template>

      <template v-if="preview">
        <div class="preview-counts" data-testid="supplier-import-counts">
          <UBadge color="neutral" variant="outline">总行数 {{ previewCount('TOTAL') }}</UBadge>
          <UBadge color="success" variant="outline">可新增 {{ previewCount('VALID') }}</UBadge>
          <UBadge color="warning" variant="outline">疑似重复 {{ previewCount('DUPLICATE') }}</UBadge>
          <UBadge color="error" variant="outline">字段错误 {{ previewCount('ERROR') }}</UBadge>
          <UBadge color="neutral" variant="outline">空行/忽略 {{ previewCount('IGNORED') }}</UBadge>
        </div>
        <UTable :data="preview.rows" :columns="previewColumns" sticky class="cs-table import-table preview-table" data-testid="supplier-import-preview">
          <template #select-cell="{ row }">
            <UCheckbox v-model="row.original.selected" :disabled="row.original.classification !== 'VALID'" />
          </template>
          <template #name-cell="{ row }">{{ row.original.data?.name || '-' }}</template>
          <template #contact-cell="{ row }">{{ row.original.data?.contactPerson || '-' }}</template>
          <template #phone-cell="{ row }">{{ row.original.data?.phone || '-' }}</template>
          <template #address-cell="{ row }"><span :title="row.original.data?.address || ''">{{ row.original.data?.address || '-' }}</span></template>
          <template #classification-cell="{ row }">
            <UBadge :color="classificationColor(row.original.classification)" variant="soft">
              {{ classificationLabel(row.original.classification) }}
            </UBadge>
          </template>
          <template #note-cell="{ row }">
            <span :title="errorText(row.original.errors)" data-testid="supplier-import-row-note">{{ errorText(row.original.errors) || '校验通过' }}</span>
          </template>
        </UTable>
      </template>
    </template>

    <template #footer>
      <UButton color="neutral" variant="outline" @click="$emit('update:modelValue', false)">取消</UButton>
      <UButton v-if="preview" color="neutral" variant="outline" @click="resetFile">重新选择</UButton>
      <UButton
        v-if="preview && previewCount('ERROR') + previewCount('DUPLICATE') > 0"
        color="neutral"
        variant="outline"
        @click="downloadErrors"
      >下载错误报告</UButton>
      <UButton
        v-if="selectedFile && !preview"
        :loading="previewing"
        :disabled="missingRequired.length > 0"
        data-testid="supplier-import-build-preview"
        @click="buildPreview"
      >生成预览</UButton>
      <UButton
        v-if="preview"
        :loading="confirming"
        :disabled="selectedValidRows.length === 0 || selectedValidRows.length > MAX_CONFIRM_ROWS"
        data-testid="supplier-import-confirm"
        @click="confirmImport"
      >确认新增 {{ selectedValidRows.length }} 条（单次最多 {{ MAX_CONFIRM_ROWS }} 条）</UButton>
    </template>
  </UModal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
import {
  confirmSupplierImport,
  downloadSupplierImportErrors,
  previewSupplierImport,
  type SupplierImportClassification,
  type SupplierImportMode,
  type SupplierImportPreview,
} from '@/api/supplierManagement';
import {
  SUPPLIER_IMPORT_FIELDS,
  mappedSupplierRows,
  missingRequiredMappings,
  parseSupplierWorkbook,
  suggestSupplierMappings,
  type SupplierColumnMapping,
  type SupplierImportField,
} from './supplierImport';
import { alertUi, modalUi } from './supplierOverlayUi';

const props = defineProps<{ modelValue: boolean; factoryId: string }>();
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'imported'): void }>();

// USelect 的选项值不能是空串: 「忽略此列」用 IGNORE 占位，写回 mapping 时还原成 ''
const IGNORE = '__IGNORE__';
const STEP_ITEMS = [{ title: '选择文件' }, { title: '确认字段映射' }, { title: '预览并确认' }];
const MODE_ITEMS = [{ label: '标准模板', value: 'STANDARD' }, { label: '智能识别', value: 'SMART' }];
const mappingColumns: TableColumn<{ sourceHeader: string }>[] = [
  { accessorKey: 'sourceHeader', header: 'Excel 源列' },
  { id: 'target', header: '目标字段' },
  { id: 'status', header: '识别状态' },
];
const previewColumns: TableColumn<SupplierImportPreview['rows'][number]>[] = [
  { id: 'select', header: '' },
  { accessorKey: 'rowNumber', header: '原行号' },
  { id: 'name', header: '供应商名称' },
  { id: 'contact', header: '联系人' },
  { id: 'phone', header: '联系电话' },
  { id: 'address', header: '地址' },
  { id: 'classification', header: '分类' },
  { id: 'note', header: '说明' },
];

const mode = ref<SupplierImportMode>('STANDARD');
const selectedFile = ref<File | null>(null);
const fileModel = ref<File | null>(null);
const headers = ref<string[]>([]);
const localRows = ref<ReturnType<typeof mappedSupplierRows>>([]);
const mapping = ref<SupplierColumnMapping>({});
const preview = ref<SupplierImportPreview | null>(null);
const previewing = ref(false);
const confirming = ref(false);
const MAX_CONFIRM_ROWS = 1000;

const activeStep = computed(() => (preview.value ? 2 : selectedFile.value ? 1 : 0));
const mappingRows = computed(() => headers.value.map((sourceHeader) => ({ sourceHeader })));
const missingRequired = computed(() => missingRequiredMappings(mapping.value));
const localCounts = computed(() => ({
  valid: localRows.value.filter((row) => !row.ignored && row.errors.length === 0).length,
  error: localRows.value.filter((row) => !row.ignored && row.errors.length > 0).length,
  ignored: localRows.value.filter((row) => row.ignored).length,
}));
const selectedValidRows = computed(() => preview.value?.rows.filter(
  (row) => row.classification === 'VALID' && row.selected,
) ?? []);

watch(mode, async () => {
  if (!selectedFile.value || preview.value) return;
  mapping.value = suggestSupplierMappings(headers.value, mode.value);
  await refreshLocalRows();
});

watch(mapping, refreshLocalRows, { deep: true });

// UFileUpload 的 v-model 是 File | null：选了文件 → 解析；清掉（点移除）→ 重置
watch(fileModel, (file) => {
  if (file) {
    if (file !== selectedFile.value) void handleFile(file);
  } else if (selectedFile.value) {
    resetFile();
  }
});

function fieldMappedElsewhere(field: SupplierImportField, currentHeader: string): boolean {
  return Object.entries(mapping.value).some(([header, mapped]) => header !== currentHeader && mapped === field);
}

function fieldItemsFor(currentHeader: string): Array<{ label: string; value: string; disabled?: boolean }> {
  return [
    { label: '忽略此列', value: IGNORE },
    ...SUPPLIER_IMPORT_FIELDS.map((field) => ({
      label: `${field.label}${field.required ? '（必填）' : ''}`,
      value: field.key as string,
      disabled: fieldMappedElsewhere(field.key, currentHeader),
    })),
  ];
}

function setMapping(sourceHeader: string, value: string): void {
  mapping.value = { ...mapping.value, [sourceHeader]: value === IGNORE ? '' : (value as SupplierImportField) };
}

async function refreshLocalRows(): Promise<void> {
  if (!selectedFile.value) return;
  const table = await parseSupplierWorkbook(selectedFile.value);
  localRows.value = mappedSupplierRows(table, mapping.value);
}

async function handleFile(file: File): Promise<void> {
  try {
    const table = await parseSupplierWorkbook(file);
    if (!table.headers.length) throw new Error('未识别到 Excel 表头');
    if (table.rows.length > 5000) throw new Error('单次最多导入 5000 行');
    selectedFile.value = file;
    headers.value = table.headers;
    mapping.value = suggestSupplierMappings(table.headers, mode.value);
    localRows.value = mappedSupplierRows(table, mapping.value);
  } catch (error) {
    resetFile();
    ElMessage.error((error as Error).message);
  }
}

function resetFile(): void {
  selectedFile.value = null;
  fileModel.value = null;
  headers.value = [];
  mapping.value = {};
  localRows.value = [];
  preview.value = null;
}

async function buildPreview(): Promise<void> {
  if (!selectedFile.value || missingRequired.value.length) return;
  previewing.value = true;
  try {
    const result = await previewSupplierImport(
      props.factoryId,
      selectedFile.value,
      mode.value,
      Object.fromEntries(Object.entries(mapping.value).filter((entry) => entry[1])),
    );
    result.rows = result.rows.map((row) => ({
      ...row,
      selected: row.classification === 'VALID',
    }));
    preview.value = result;
  } finally {
    previewing.value = false;
  }
}

async function confirmImport(): Promise<void> {
  if (!preview.value || !selectedValidRows.value.length) return;
  if (selectedValidRows.value.length > MAX_CONFIRM_ROWS) {
    ElMessage.warning(`单次最多确认 ${MAX_CONFIRM_ROWS} 条，请减少勾选数量后重试`);
    return;
  }
  confirming.value = true;
  try {
    const rowKey = selectedValidRows.value.map((row) => row.rowNumber).sort((a, b) => a - b).join('-');
    const result = await confirmSupplierImport(props.factoryId, {
      fileDigest: preview.value.fileDigest,
      idempotencyKey: `supplier-import:${preview.value.fileDigest}:${rowKey}`,
      rows: selectedValidRows.value.flatMap((row) => row.data ? [row.data] : []),
    });
    ElMessage.success(`导入完成：新增 ${result.createdCount}，跳过 ${result.skippedCount}，失败 ${result.failedCount}`);
    emit('imported');
    emit('update:modelValue', false);
  } finally {
    confirming.value = false;
  }
}

async function downloadErrors(): Promise<void> {
  if (preview.value) await downloadSupplierImportErrors(props.factoryId, preview.value);
}

function classificationLabel(value: SupplierImportClassification): string {
  return { VALID: '可新增', DUPLICATE: '疑似重复', ERROR: '字段错误', IGNORED: '已忽略' }[value];
}

function classificationColor(value: SupplierImportClassification) {
  const tag = ({ VALID: 'success', DUPLICATE: 'warning', ERROR: 'danger', IGNORED: 'info' } as Record<string, ElementTagType>)[value];
  return badgeColor(tag);
}

function previewCount(kind: string): number {
  if (!preview.value) return 0;
  return preview.value.counts[kind] ?? preview.value.counts[kind.toLowerCase()] ?? 0;
}

function errorText(errors?: Record<string, string>): string {
  return errors ? Object.values(errors).filter(Boolean).join('；') : '';
}
</script>

<style scoped>
.import-steps { margin-bottom: var(--cs-space-3); }
.zero-write-alert { margin: var(--cs-space-3) 0; }
.mode-row { display: flex; align-items: center; gap: var(--cs-space-3); margin-bottom: var(--cs-space-3); }
.mode-help, .local-check { color: var(--cs-muted); font-size: var(--cs-font-small); }
.mapping-title, .preview-counts { display: flex; align-items: center; gap: var(--cs-space-2); margin: var(--cs-space-4) 0 var(--cs-space-2); }
.local-check { margin-top: var(--cs-space-2); }
.import-table { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.preview-table { max-height: 420px; }
</style>
