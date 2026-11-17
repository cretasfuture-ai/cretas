<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import type { CsTableColumn } from '@/types/csTable';
// 通用确认弹窗 (UModal, 取代 Element 的命令式确认框: 它在 Nuxt UI 弹层之上点不了)。
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listVoucherTemplates,
  createVoucherTemplate,
  updateVoucherTemplate,
  deleteVoucherTemplate,
  setVoucherTemplateDefault,
  VOUCHER_TYPE_LABEL,
  DIRECTION_LABEL,
  type VoucherTemplate,
  type VoucherType,
  type TemplateEntry,
  type TemplateEntryDirection,
} from '@/api/voucher';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId ?? '');
const canWrite = computed(() => permissionStore.canWrite('finance'));

// ==================== State ====================

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const loading = ref(false);
const tableData = ref<VoucherTemplate[]>([]);

// Dialog state
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const saving = ref(false);

const emptyEntry = (): TemplateEntry => ({
  sortOrder: null,
  subjectCode: '',
  subjectName: '',
  direction: 'DEBIT',
  amountExpression: '',
  description: '',
  omitWhenZero: false,
});

const formData = reactive<{
  id: string;
  voucherType: VoucherType | '';
  name: string;
  description: string;
  isActive: boolean;
  entries: TemplateEntry[];
}>({
  id: '',
  voucherType: '',
  name: '',
  description: '',
  isActive: true,
  entries: [emptyEntry()],
});

// ==================== Load ====================

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    tableData.value = await listVoucherTemplates(factoryId.value);
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '凭证模板加载失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

// ==================== Dialog ====================

function openCreate() {
  dialogMode.value = 'create';
  formData.id = '';
  formData.voucherType = '';
  formData.name = '';
  formData.description = '';
  formData.isActive = true;
  formData.entries = [emptyEntry()];
  dialogVisible.value = true;
}

function openEdit(row: VoucherTemplate) {
  dialogMode.value = 'edit';
  formData.id = row.id;
  formData.voucherType = row.voucherType;
  formData.name = row.name;
  formData.description = row.description ?? '';
  formData.isActive = row.isActive;
  formData.entries = row.entries.length > 0
    ? row.entries.map((e) => ({ ...e }))
    : [emptyEntry()];
  dialogVisible.value = true;
}

function addEntry() {
  formData.entries.push(emptyEntry());
}

function removeEntry(index: number) {
  if (formData.entries.length <= 1) {
    ElMessage.warning('至少需要一条分录行');
    return;
  }
  formData.entries.splice(index, 1);
}

async function submitForm() {
  if (!formData.name.trim()) { ElMessage.warning('请填写模板名称'); return; }
  if (!formData.voucherType) { ElMessage.warning('请选择凭证类型'); return; }
  const hasInvalidEntry = formData.entries.some(
    (e) => !e.subjectCode.trim() || !e.subjectName.trim(),
  );
  if (hasInvalidEntry) { ElMessage.warning('请填写所有分录行的科目代码和科目名称'); return; }

  saving.value = true;
  try {
    const payload: Partial<VoucherTemplate> = {
      voucherType: formData.voucherType as VoucherType,
      name: formData.name.trim(),
      description: formData.description.trim() || undefined,
      isActive: formData.isActive,
      entries: formData.entries.map((e, idx) => ({
        ...e,
        sortOrder: e.sortOrder ?? idx,
      })),
    };

    if (dialogMode.value === 'create') {
      await createVoucherTemplate(factoryId.value, payload);
      ElMessage.success('凭证模板创建成功');
    } else {
      await updateVoucherTemplate(factoryId.value, formData.id, payload);
      ElMessage.success('凭证模板更新成功');
    }
    dialogVisible.value = false;
    loadData();
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '保存失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    saving.value = false;
  }
}

// ==================== Actions ====================

async function handleDelete(row: VoucherTemplate) {
  const confirmed = await confirmRef.value!.ask({
    title: '确认删除',
    message: `确认删除凭证模板「${row.name}」？删除后不可恢复。`,
    confirmText: '确认删除',
    danger: true,
  });
  if (confirmed === null) return;
  try {
    await deleteVoucherTemplate(factoryId.value, row.id);
    ElMessage.success('凭证模板已删除');
    loadData();
  } catch (e) {
    ElMessage({
      message: e instanceof Error ? e.message : '删除失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  }
}

async function handleSetDefault(row: VoucherTemplate) {
  if (row.isDefault) return;
  const confirmed = await confirmRef.value!.ask({
    title: '确认设为默认',
    message: `将「${row.name}」设为 ${VOUCHER_TYPE_LABEL[row.voucherType]} 类型的默认模板？` +
      '同类型的其他默认模板将被自动取消。',
    confirmText: '确认',
  });
  if (confirmed === null) return;
  try {
    await setVoucherTemplateDefault(factoryId.value, row.id);
    ElMessage.success('已设为默认模板');
    loadData();
  } catch (e) {
    ElMessage({
      message: e instanceof Error ? e.message : '操作失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  }
}

const VOUCHER_TYPE_ITEMS = Object.entries(VOUCHER_TYPE_LABEL).map(([value, label]) => ({ value, label }));
const DIRECTION_ITEMS = Object.entries(DIRECTION_LABEL).map(([value, label]) => ({ value, label }));
const columns: CsTableColumn<VoucherTemplate>[] = [
  { id: 'name', header: '模板名称' },
  { id: 'voucherType', header: '凭证类型' },
  { id: 'entries', header: '分录数' },
  { id: 'description', header: '说明' },
  { id: 'isActive', header: '状态' },
  { id: 'actions', header: '操作' },
];

onMounted(() => loadData());
</script>

<template>
  <div class="page" data-testid="finance-voucher-template-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>凭证模板管理</h2>
        <span class="count">配置科目↔付款类型映射，凭证生成时优先使用模板</span>
        <div class="head-actions">
          <UButton v-if="canWrite" icon="i-lucide-plus" data-testid="vt-create" @click="openCreate">新建模板</UButton>
        </div>
      </header>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="vt-table">
        <template #empty>暂无凭证模板。点击「新建模板」配置科目↔付款类型映射，凭证生成时将优先匹配模板规则。</template>
        <template #name-cell="{ row }">
          <span class="name">{{ row.original.name }}</span>
          <UBadge v-if="row.original.isDefault" color="success" variant="soft" class="ml-2">默认</UBadge>
        </template>
        <template #voucherType-cell="{ row }">{{ VOUCHER_TYPE_LABEL[row.original.voucherType as VoucherType] || row.original.voucherType }}</template>
        <template #entries-cell="{ row }">{{ row.original.entries?.length ?? 0 }} 行</template>
        <template #description-cell="{ row }"><span class="desc">{{ row.original.description || '-' }}</span></template>
        <template #isActive-cell="{ row }">
          <UBadge :color="row.original.isActive ? 'success' : 'neutral'" variant="soft">{{ row.original.isActive ? '启用' : '停用' }}</UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton variant="link" size="xs" data-testid="vt-edit" @click="openEdit(row.original)">编辑</UButton>
            <UButton v-if="canWrite && !row.original.isDefault" color="success" variant="link" size="xs" data-testid="vt-default" @click="handleSetDefault(row.original)">设为默认</UButton>
            <UButton v-if="canWrite" color="error" variant="link" size="xs" data-testid="vt-delete" @click="handleDelete(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <!-- 新建/编辑 -->
    <UModal
      v-model:open="dialogVisible"
      :title="dialogMode === 'create' ? '新建凭证模板' : `编辑模板 — ${formData.name}`"
      description="配置凭证模板与分录规则"
      :dismissible="false"
      :ui="{ content: 'max-w-[720px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="form">
          <UFormField label="模板名称" required>
            <UInput v-model="formData.name" :maxlength="100" placeholder="例如：销售收款-标准模板" class="w-full" data-testid="vt-name" />
          </UFormField>
          <UFormField label="凭证类型" required>
            <USelect v-model="formData.voucherType" :items="VOUCHER_TYPE_ITEMS" placeholder="请选择凭证类型" class="w-full" data-testid="vt-type" />
          </UFormField>
          <UFormField label="说明">
            <UTextarea v-model="formData.description" :rows="2" :maxlength="200" placeholder="可选，填写模板用途说明" class="w-full" />
          </UFormField>
          <UFormField label="状态">
            <USwitch v-model="formData.isActive" :label="formData.isActive ? '启用' : '停用'" />
          </UFormField>

          <div class="section">分录明细</div>
          <div class="hint">
            配置借贷分录规则。amountExpression 使用 SpEL，如
            <code>#order.totalAmount</code>、<code>#payroll.netAmount</code>。
          </div>

          <div v-for="(entry, idx) in formData.entries" :key="idx" class="entry" data-testid="vt-entry">
            <div class="entry-head">
              <span class="entry-title">第 {{ idx + 1 }} 行</span>
              <UButton v-if="formData.entries.length > 1" color="error" variant="link" size="xs" @click="removeEntry(idx)">删除此行</UButton>
            </div>
            <div class="entry-grid">
              <UFormField label="科目代码" required><UInput v-model="entry.subjectCode" placeholder="如 1122" class="w-full" /></UFormField>
              <UFormField label="科目名称" required><UInput v-model="entry.subjectName" placeholder="如 应收账款" class="w-full" /></UFormField>
              <UFormField label="借贷方向" required><USelect v-model="entry.direction" :items="DIRECTION_ITEMS" class="w-full" /></UFormField>
              <UFormField label="金额表达式"><UInput v-model="entry.amountExpression" placeholder="#order.totalAmount" class="w-full" /></UFormField>
              <UFormField label="摘要描述" class="span2"><UInput v-model="entry.description" placeholder="可选，凭证打印时显示" class="w-full" /></UFormField>
              <UFormField label="零时省略" class="span2">
                <USwitch v-model="entry.omitWhenZero" :label="entry.omitWhenZero ? '是（税额为0时省略）' : '否'" />
              </UFormField>
            </div>
          </div>

          <UButton color="neutral" variant="outline" class="w-full justify-center" data-testid="vt-add-entry" @click="addEntry">+ 添加分录行</UButton>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="saving" data-testid="vt-submit" @click="submitForm">{{ dialogMode === 'create' ? '创建' : '保存' }}</UButton>
      </template>
    </UModal>

    <SalesConfirmModal ref="confirmRef" />
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-2); }
.name { font-weight: 500; }
.desc { color: var(--cs-muted); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.form { display: grid; gap: var(--cs-space-3); }
.section { font-size: 13px; font-weight: 600; color: var(--cs-text); border-top: 1px solid var(--cs-border); padding-top: var(--cs-space-3); }
.hint { color: var(--cs-muted); font-size: 13px; }
.entry { border: 1px solid var(--cs-border); border-radius: var(--cs-radius); padding: var(--cs-space-3); background: var(--cs-surface-subtle); }
.entry-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--cs-space-2); }
.entry-title { font-size: 13px; font-weight: 500; color: var(--cs-text); }
.entry-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--cs-space-2); }
.entry-grid .span2 { grid-column: span 2; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
