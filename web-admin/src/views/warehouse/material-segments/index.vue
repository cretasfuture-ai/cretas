<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import { del, get, post, put } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { findLabelConflict, SEGMENT_LEVEL_DEFINITIONS, type SegmentLevel } from './materialSegmentRules';

interface ClassificationNode {
  id: number;
  segmentLabel: string;
  level: SegmentLevel;
  parentId: number | null;
  sortOrder: number;
  isActive: boolean;
  children?: ClassificationNode[];
}

interface ClassificationForm {
  id: number | null;
  level: SegmentLevel;
  segmentLabel: string;
  parentId: number | null;
  sortOrder: number;
  isActive: boolean;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
// 🔴 2026-08-22: 绑到 system —— 原来绑的是 warehouse||production, 而**物料分类字典的
//   增/改/停用/删除/恢复后端一律要 system:read_write**(MaterialCodeSegmentController)。
//   ⇒ 10 个角色(含只读的 viewer)看得见这些按钮, 点下去必 403。
// ⛔ 不放宽后端: 分类字典是全厂共享的主数据, 改一个节点会影响所有物料。
// ⚠️ **读**不受影响: 列表/树端点后端允许 production|warehouse|system, 本页照常能看。
// ▎判据: 前端守卫要绑**后端实际要求的那个模块**, 不是「这个页面挂在哪个菜单下」。
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const treeData = ref<ClassificationNode[]>([]);
const selectedLevel = ref<SegmentLevel>(1);
const showDeleted = ref(false);
const deletedRows = ref<ClassificationNode[]>([]);

const form = reactive<ClassificationForm>({
  id: null,
  level: 1,
  segmentLabel: '',
  parentId: null,
  sortOrder: 0,
  isActive: true,
});

const levelItems = [
  { label: '一级分类', value: 1 },
  { label: '二级分类', value: 2 },
  { label: '三级分类', value: 3 },
];

// 确认弹窗（UModal，取代 ElMessageBox）：同名分类 / 删除。confirmAsk 返回用户的选择。
const confirmOpen = ref(false);
const confirmState = reactive({ title: '', message: '', okText: '确定', cancelText: '取消', danger: false });
let confirmResolve: ((ok: boolean) => void) | null = null;
function confirmAsk(opts: { title: string; message: string; okText: string; cancelText: string; danger?: boolean }): Promise<boolean> {
  Object.assign(confirmState, { danger: false, ...opts });
  confirmOpen.value = true;
  return new Promise((resolve) => { confirmResolve = resolve; });
}
function settleConfirm(ok: boolean) {
  const r = confirmResolve;
  confirmResolve = null;
  confirmOpen.value = false;
  r?.(ok);
}
// 点遮罩 / Esc / 关闭 = 取消
function onConfirmOpenChange(open: boolean) {
  if (!open && confirmResolve) settleConfirm(false);
}

const R_ACTIONS = { class: { th: 'text-right', td: 'text-right' } };
const columns = computed<TableColumn<ClassificationNode>[]>(() => [
  { accessorKey: 'segmentLabel', header: '分类名称' },
  { id: 'parent', header: '上级分类' },
  { id: 'status', header: '状态' },
  ...(canWrite.value ? [{ id: 'actions', header: '操作', meta: R_ACTIONS } as TableColumn<ClassificationNode>] : []),
]);
const deletedColumns = computed<TableColumn<ClassificationNode>[]>(() => [
  { accessorKey: 'segmentLabel', header: '分类名称' },
  { id: 'status', header: '状态' },
  ...(canWrite.value ? [{ id: 'actions', header: '操作', meta: R_ACTIONS } as TableColumn<ClassificationNode>] : []),
]);

const flatRows = computed(() => {
  const rows: ClassificationNode[] = [];
  const walk = (nodes: ClassificationNode[]) => {
    nodes.forEach((node) => {
      rows.push(node);
      if (node.children?.length) walk(node.children);
    });
  };
  walk(treeData.value);
  return rows;
});

const tableRows = computed(() => flatRows.value.filter((row) => row.level === selectedLevel.value));
const parentOptions = computed(() => {
  const parentLevel = form.level - 1;
  return flatRows.value.filter((row) => row.level === parentLevel && row.isActive !== false);
});
const parentItems = computed(() => parentOptions.value.map((item) => ({ label: item.segmentLabel, value: item.id })));
const deletedRowsForLevel = computed(
  () => deletedRows.value.filter((row) => row.level === selectedLevel.value),
);

function levelLabel(level: SegmentLevel): string {
  return level === 1 ? '一级分类' : level === 2 ? '二级分类' : '三级分类';
}

function parentName(parentId: number | null): string {
  if (parentId == null) return '-';
  return flatRows.value.find((row) => row.id === parentId)?.segmentLabel || `分类 #${parentId}`;
}

function showStickyError(message: string) {
  ElMessage({ message, type: 'error', duration: 0, showClose: true });
}

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}

async function loadTree() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get<ClassificationNode[]>(`/${factoryId.value}/material-segments/tree`);
    treeData.value = Array.isArray(response.data) ? response.data : [];
  } catch (error) {
    showStickyError(errorMessage(error, '分类加载失败，请刷新重试。'));
  } finally {
    loading.value = false;
  }
}

async function loadDeleted() {
  if (!factoryId.value) return;
  // ⚠️ 模板挡住开关还不够: loadDeleted 另有 watch 和「刷新」两条调用路径。
  //   ▎挡入口 ≠ 挡调用 —— 三条路都要经过这一句。
  if (!canWrite.value) return;
  try {
    const response = await get<ClassificationNode[]>(`/${factoryId.value}/material-segments/deleted`);
    deletedRows.value = Array.isArray(response.data) ? response.data : [];
  } catch (error) {
    showStickyError(errorMessage(error, '已删除分类加载失败。'));
  }
}

function resetForm(level: SegmentLevel = selectedLevel.value) {
  form.id = null;
  form.level = level;
  form.segmentLabel = '';
  form.parentId = null;
  form.sortOrder = 0;
  form.isActive = true;
}

function openCreate(level: SegmentLevel = selectedLevel.value) {
  resetForm(level);
  dialogVisible.value = true;
}

function openEdit(row: ClassificationNode) {
  form.id = row.id;
  form.level = row.level;
  form.segmentLabel = row.segmentLabel;
  form.parentId = row.parentId;
  form.sortOrder = row.sortOrder ?? 0;
  form.isActive = row.isActive !== false;
  dialogVisible.value = true;
}

function validateForm(): boolean {
  if (!form.segmentLabel.trim()) {
    showStickyError('请填写分类名称。');
    return false;
  }
  if (form.level > 1 && form.parentId == null) {
    showStickyError('请选择上级分类。');
    return false;
  }
  return true;
}

async function saveSegment() {
  if (!factoryId.value || !validateForm()) return;
  const conflict = findLabelConflict(flatRows.value, form.segmentLabel, form.id);
  if (conflict) {
    const samePosition = conflict.level === form.level && conflict.parentId === form.parentId;
    if (samePosition) {
      showStickyError(`当前上级下已存在分类“${conflict.segmentLabel}”，请直接使用。`);
      return;
    }
    const ok = await confirmAsk({
      title: '检测到同名分类',
      message: `“${conflict.segmentLabel}”已存在于${levelLabel(conflict.level)}。确认仍在当前位置创建同名分类吗？`,
      okText: '仍然创建',
      cancelText: '返回调整',
    });
    if (!ok) return;
  }

  saving.value = true;
  const payload = {
    level: form.level,
    segmentLabel: form.segmentLabel.trim(),
    parentId: form.level === 1 ? null : form.parentId,
    sortOrder: Number(form.sortOrder || 0),
    isActive: form.isActive,
  };
  try {
    if (form.id) {
      await put(`/${factoryId.value}/material-segments/${form.id}`, payload);
      ElMessage.success('分类已更新。');
    } else {
      await post(`/${factoryId.value}/material-segments`, payload);
      ElMessage.success('分类已创建。');
    }
    dialogVisible.value = false;
    await loadTree();
  } catch (error) {
    await loadTree();
    showStickyError(errorMessage(error, '保存失败，请检查分类名称和上级分类。'));
  } finally {
    saving.value = false;
  }
}

async function toggleActive(row: ClassificationNode) {
  if (!factoryId.value) return;
  const nextActive = row.isActive === false;
  try {
    await put(`/${factoryId.value}/material-segments/${row.id}`, { isActive: nextActive });
    ElMessage.success(nextActive ? '已启用。' : '已停用。新建物料时不再提供此分类。');
    await loadTree();
  } catch (error) {
    showStickyError(errorMessage(error, nextActive ? '启用失败。' : '停用失败。'));
  }
}

async function deleteSegment(row: ClassificationNode) {
  if (!factoryId.value) return;
  const ok = await confirmAsk({
    title: '删除确认',
    message: `确认删除分类“${row.segmentLabel}”？历史物料仍需保留归属时，请选择“停用”。`,
    okText: '删除',
    cancelText: '取消',
    danger: true,
  });
  if (!ok) return;
  try {
    await del(`/${factoryId.value}/material-segments/${row.id}`);
    ElMessage.success('分类已删除，可在“显示已删除的分类”中恢复。');
    await refreshAll();
  } catch (error) {
    showStickyError(errorMessage(error, '删除失败，请先处理下级分类或改为停用。'));
  }
}

async function restoreSegment(row: ClassificationNode) {
  if (!factoryId.value) return;
  try {
    await post(`/${factoryId.value}/material-segments/${row.id}/restore`, {});
    ElMessage.success(`分类“${row.segmentLabel}”已恢复。`);
    await refreshAll();
  } catch (error) {
    showStickyError(errorMessage(error, '恢复失败。'));
  }
}

async function refreshAll() {
  await loadTree();
  if (showDeleted.value) await loadDeleted();
}

watch(
  () => form.level,
  () => { if (!form.id) form.parentId = null; },
);
watch(showDeleted, (visible) => { if (visible) void loadDeleted(); });
onMounted(loadTree);
</script>

<template>
  <div class="page-wrapper">
    <section class="cs-card">
      <header class="cs-card-head card-header">
        <div>
          <div class="page-title-row">
            <h2 class="page-title">物料分类字典</h2>
            <UPopover :content="{ side: 'bottom', align: 'start' }">
              <UButton class="definition-help" variant="link" color="neutral" size="xs" icon="i-lucide-circle-help" aria-label="查看分类层级说明" />
              <template #content>
                <div class="definition-list">
                  <div v-for="item in SEGMENT_LEVEL_DEFINITIONS" :key="item.level" class="definition-item">
                    <strong>{{ item.title }}</strong>
                    <div>{{ item.description }}</div>
                    <div class="definition-example">例如：{{ item.example }}</div>
                  </div>
                </div>
              </template>
            </UPopover>
          </div>
          <div class="page-subtitle">这里只维护分类名称和上下级关系；料号在新建原料时单独维护</div>
        </div>
        <div class="header-actions">
          <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="loadTree">刷新</UButton>
          <UButton v-if="canWrite" icon="i-lucide-plus" @click="openCreate()">新增分类</UButton>
        </div>
      </header>

      <div class="cs-card-body">
        <UAlert
          v-if="treeData.length === 0 && !loading"
          color="info"
          variant="subtle"
          icon="i-lucide-info"
          title="本工厂暂未配置详细物料分类"
          class="empty-alert"
        >
          <template #description>
            <div>不配置也能新建原料类型，系统会按基本类型建议简短料号。</div>
            <div v-if="canWrite" class="empty-alert__hint">需要更细的归类和筛选时，再新增分类。</div>
          </template>
        </UAlert>

        <div class="level-tabs" role="tablist" aria-label="分类层级">
          <UButton
            v-for="opt in levelItems"
            :key="opt.value"
            size="sm"
            role="tab"
            :aria-selected="selectedLevel === opt.value"
            :color="selectedLevel === opt.value ? 'primary' : 'neutral'"
            :variant="selectedLevel === opt.value ? 'soft' : 'ghost'"
            @click="selectedLevel = opt.value as SegmentLevel"
          >{{ opt.label }}</UButton>
        </div>
      </div>

      <UTable :data="tableRows" :columns="columns" :loading="loading" class="cs-table" data-testid="segment-table">
        <template #empty>暂无数据</template>
        <template #parent-cell="{ row }">{{ parentName(row.original.parentId) }}</template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(row.original.isActive === false ? 'info' : 'success')" variant="soft">{{ row.original.isActive === false ? '停用' : '启用' }}</UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" icon="i-lucide-pencil" @click="openEdit(row.original)">编辑</UButton>
            <UButton size="xs" variant="link" color="warning" @click="toggleActive(row.original)">{{ row.original.isActive === false ? '启用' : '停用' }}</UButton>
            <UButton size="xs" variant="link" color="error" icon="i-lucide-trash-2" @click="deleteSegment(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>

      <div class="cs-card-body deleted-section">
        <!-- 🔴 2026-08-22: 挂到 canWrite。/material-segments/deleted 要 production,
             而本页角色只有 warehouse ⇒ 一打开开关就弹「已删除分类加载失败」。
             ▎不能**恢复**的人看这份列表也没有意义 —— 恢复按钮已经在 canWrite 之下了。 -->
        <USwitch v-if="canWrite" v-model="showDeleted" label="显示已删除的分类" />
        <span class="deleted-hint">删除记录可恢复；日常不再使用时优先选择“停用”。</span>
      </div>

      <UTable
        v-if="showDeleted"
        :data="deletedRowsForLevel"
        :columns="deletedColumns"
        class="cs-table deleted-table"
        data-testid="segment-deleted-table"
      >
        <template #empty>该层级没有已删除的分类</template>
        <template #status-cell><UBadge color="error" variant="soft">已删除</UBadge></template>
        <template #actions-cell="{ row }">
          <UButton size="xs" variant="link" @click="restoreSegment(row.original)">恢复</UButton>
        </template>
      </UTable>
    </section>

    <UModal v-model:open="dialogVisible" :title="form.id ? '编辑分类' : '新增分类'" description="维护分类名称、层级和上级分类" :ui="{ content: 'max-w-[520px]', description: 'sr-only' }">
      <template #body>
        <div class="form">
          <UFormField label="层级" required>
            <USelect v-model="form.level" :items="levelItems" :disabled="!!form.id" class="w-full" />
          </UFormField>
          <UFormField v-if="form.level > 1" label="上级分类" required>
            <USelectMenu v-model="form.parentId" value-key="value" :items="parentItems" :disabled="!!form.id" placeholder="选择上级分类" class="w-full" />
            <div v-if="form.id" class="field-hint">需要调整上级时，请新建分类并停用旧分类，以免改变历史归属。</div>
          </UFormField>
          <UFormField label="分类名称" required>
            <UInput v-model="form.segmentLabel" :maxlength="100" placeholder="如：肉类 / 牛肉类 / 牛腱" class="w-full" />
          </UFormField>
          <UFormField label="排序">
            <UInputNumber :step-snapping="false" v-model="form.sortOrder" :min="0" :increment="false" :decrement="false" class="w-full" />
          </UFormField>
          <UFormField label="是否启用">
            <USwitch v-model="form.isActive" :label="form.isActive ? '启用' : '停用'" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="saving" @click="saveSegment">保存</UButton>
      </template>
    </UModal>

    <UModal :open="confirmOpen" :title="confirmState.title" description="请确认" :ui="{ content: 'max-w-[440px]', description: 'sr-only' }" @update:open="onConfirmOpenChange">
      <template #body>
        <p class="confirm">{{ confirmState.message }}</p>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="settleConfirm(false)">{{ confirmState.cancelText }}</UButton>
        <UButton :color="confirmState.danger ? 'error' : 'primary'" @click="settleConfirm(true)">{{ confirmState.okText }}</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.page-wrapper { padding: var(--cs-space-4); }
.card-header { justify-content: space-between; align-items: flex-start; }
.page-title { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.page-title-row { display: flex; align-items: center; gap: 4px; }
.definition-help { color: var(--cs-muted); }
.definition-list { display: grid; gap: 12px; width: 420px; max-width: 80vw; padding: var(--cs-space-3); line-height: 1.55; color: var(--cs-muted); font-size: 13px; }
.definition-item strong { color: var(--cs-text); }
.definition-example, .field-hint, .deleted-hint { color: var(--cs-muted); font-size: 12px; }
.page-subtitle { margin-top: 4px; color: var(--cs-muted); font-size: 13px; font-weight: 400; }
.header-actions { display: flex; gap: 8px; }
.empty-alert { margin-bottom: 14px; }
.empty-alert__hint { margin-top: 6px; color: var(--cs-muted); font-size: 13px; }
.level-tabs { display: flex; gap: var(--cs-space-1); }
.row-actions { display: flex; gap: var(--cs-space-1); justify-content: flex-end; }
.deleted-section { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; border-top: 1px solid var(--cs-border); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.confirm { margin: 0; color: var(--cs-text); }
</style>
