<script setup lang="ts">
/**
 * 采购审批规则 自由配置 (F006 客户)
 *
 * 配置3 个字段决定 PO 提交后是否需要财务复核:
 *   - priceVarianceThreshold: 价格偏差%阈值 (任一行偏差 > 此值 → 财务审)
 *   - amountThreshold: 金额阈值 (订单总额 > 此值 → 财务审, null = 不参与判断)
 *   - enabled: 启用/关闭 (关闭后所有 PO 直接 APPROVED, 不经财务)
 *
 * 评估: 任一条件触发 → PENDING_FINANCE_REVIEW. 都不触发 → APPROVED.
 */
import { ref, computed, onMounted, reactive } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { get, post, put, del } from '@/api/request';
import { getFactoryId } from '@/api/smartbi/common';

interface ApprovalRule {
  id: string;
  factoryId: string;
  ruleName: string;
  priceVarianceThreshold: number;
  amountThreshold: number | null;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

const rules = ref<ApprovalRule[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const formRef = ref<{ submit: () => Promise<void> } | null>(null);

const form = reactive({
  ruleName: '',
  priceVarianceThreshold: 10.00,
  amountThreshold: null as number | null,
  enabled: true,
});

// 与原 Element 规则同一组判据与文案: 名称必填且 ≤100 字; 价格偏差阈值必填且 0-100
function validateForm(state: typeof form): Array<{ name: string; message: string }> {
  const errors: Array<{ name: string; message: string }> = [];
  if (!String(state.ruleName ?? '').trim()) errors.push({ name: 'ruleName', message: '规则名称不能为空' });
  else if (state.ruleName.length > 100) errors.push({ name: 'ruleName', message: '名称不超过 100 字' });
  const v = state.priceVarianceThreshold as number | null | undefined;
  if (v == null || Number.isNaN(Number(v))) errors.push({ name: 'priceVarianceThreshold', message: '价格偏差阈值不能为空' });
  else if (v < 0 || v > 100) errors.push({ name: 'priceVarianceThreshold', message: '请填 0-100 之间数' });
  return errors;
}

const columns: TableColumn<ApprovalRule>[] = [
  { id: 'ruleName', header: '规则名称' },
  { id: 'price', header: '价格偏差阈值' },
  { id: 'amount', header: '金额阈值' },
  { id: 'enabled', header: '状态' },
  { id: 'updatedAt', header: '最后修改' },
  { id: 'actions', header: '操作' },
];

const factoryId = computed(() => {
  try {
    return getFactoryId();
  } catch {
    return '';
  }
});

async function loadRules() {
  if (!factoryId.value) {
    ElMessage.error('未登录或工厂 ID 缺失');
    return;
  }
  loading.value = true;
  try {
    const resp = await get<ApprovalRule[]>(`/${factoryId.value}/purchase-approval-rules`);
    rules.value = resp?.data ?? [];
  } catch (e) {
    ElMessage.error('加载规则失败: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    loading.value = false;
  }
}

function openCreateDialog() {
  editingId.value = null;
  form.ruleName = '';
  form.priceVarianceThreshold = 10.00;
  form.amountThreshold = null;
  form.enabled = true;
  dialogVisible.value = true;
}

function openEditDialog(row: ApprovalRule) {
  editingId.value = row.id;
  form.ruleName = row.ruleName;
  form.priceVarianceThreshold = Number(row.priceVarianceThreshold);
  form.amountThreshold = row.amountThreshold !== null ? Number(row.amountThreshold) : null;
  form.enabled = row.enabled;
  dialogVisible.value = true;
}

async function handleSave() {
  // 校验由 UForm 的 validate（validateForm）在 @submit 之前完成
  try {
    const payload = {
      ruleName: form.ruleName,
      priceVarianceThreshold: form.priceVarianceThreshold,
      amountThreshold: form.amountThreshold,
      enabled: form.enabled,
    };
    if (editingId.value) {
      await put(`/${factoryId.value}/purchase-approval-rules/${editingId.value}`, payload);
      ElMessage.success('规则更新成功');
    } else {
      await post(`/${factoryId.value}/purchase-approval-rules`, payload);
      ElMessage.success('规则创建成功');
    }
    dialogVisible.value = false;
    await loadRules();
  } catch (e) {
    ElMessage.error('保存失败: ' + (e instanceof Error ? e.message : String(e)));
  }
}

async function handleToggle(row: ApprovalRule) {
  try {
    await post(`/${factoryId.value}/purchase-approval-rules/${row.id}/toggle`, {});
    ElMessage.success(row.enabled ? '规则已禁用' : '规则已启用');
    await loadRules();
  } catch (e) {
    ElMessage.error('操作失败: ' + (e instanceof Error ? e.message : String(e)));
  }
}

async function handleDelete(row: ApprovalRule) {
  try {
    await ElMessageBox.confirm(
      `确定删除规则「${row.ruleName}」? 删除后此工厂将使用系统默认 (10% 偏差, 无金额阈值).`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
    await del(`/${factoryId.value}/purchase-approval-rules/${row.id}`);
    ElMessage.success('规则已删除');
    await loadRules();
  } catch (e) {
    if (e === 'cancel' || (e as Error)?.message === 'cancel') return;
    ElMessage.error('删除失败: ' + (e instanceof Error ? e.message : String(e)));
  }
}

onMounted(loadRules);
</script>

<template>
  <div class="page-wrapper">
    <div class="cs-page-head">
      <div>
        <h2>采购审批规则</h2>
        <p>配置 PO 提交后, 满足什么条件需要财务复核. 不满足任何条件 → 直接 APPROVED.</p>
      </div>
      <UButton icon="i-lucide-plus" @click="openCreateDialog">新增规则</UButton>
    </div>

    <UAlert color="info" variant="subtle" icon="i-lucide-info" class="mb-4" title="评估逻辑 (任一条件触发 → 进财务待审)">
      <template #description>
        <ul class="rule-notes">
          <li><strong>价格偏差</strong>: 任一行单价 vs (BOM 均价 / 上次入库价) 偏差 &gt; 阈值</li>
          <li><strong>金额阈值</strong>: 订单总额 &gt; 阈值 (留空 = 不参与判断)</li>
          <li>多条同时启用时取 <strong>最新</strong> 一条 (按创建时间). 关闭所有规则 = 全部直接 APPROVED.</li>
        </ul>
      </template>
    </UAlert>

    <section class="cs-card">
      <UTable :data="rules" :columns="columns" :loading="loading" class="cs-table">
        <template #empty>
          <div class="empty">
            <p>暂无规则 — 系统将使用默认 <strong>10%</strong> 价格偏差阈值, 无金额阈值</p>
            <p class="empty-hint">点 + 新增规则 自定义本工厂阈值</p>
          </div>
        </template>
        <template #ruleName-cell="{ row }">{{ row.original.ruleName }}</template>
        <template #price-cell="{ row }">
          <UBadge color="warning" variant="soft">{{ Number(row.original.priceVarianceThreshold).toFixed(2) }}%</UBadge>
        </template>
        <template #amount-cell="{ row }">
          <UBadge v-if="row.original.amountThreshold !== null" color="error" variant="soft">¥{{ Number(row.original.amountThreshold).toLocaleString() }}</UBadge>
          <span v-else class="muted">— 不参与</span>
        </template>
        <template #enabled-cell="{ row }">
          <UBadge v-if="row.original.enabled" color="success" variant="soft">启用</UBadge>
          <UBadge v-else color="neutral" variant="soft">已禁用</UBadge>
        </template>
        <template #updatedAt-cell="{ row }">
          <span v-if="row.original.updatedAt" class="muted">{{ new Date(row.original.updatedAt).toLocaleString('zh-CN') }}</span>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" @click="openEditDialog(row.original)">编辑</UButton>
            <UButton size="xs" variant="link" :color="row.original.enabled ? 'warning' : 'success'" @click="handleToggle(row.original)">{{ row.original.enabled ? '禁用' : '启用' }}</UButton>
            <UButton size="xs" variant="link" color="error" @click="handleDelete(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <UModal v-model:open="dialogVisible" :title="editingId ? '编辑规则' : '新增规则'" description="配置采购审批规则" :dismissible="false" :ui="{ footer: 'justify-end', content: 'max-w-[500px]', description: 'sr-only' }">
      <template #body>
        <UForm ref="formRef" :state="form" :validate="validateForm" class="form" @submit="handleSave">
          <UFormField label="规则名称" name="ruleName" required>
            <UInput v-model="form.ruleName" placeholder="例: 默认采购审批规则" :maxlength="100" class="w-full" />
          </UFormField>
          <UFormField label="价格偏差阈值 %" name="priceVarianceThreshold" required help="任一行单价偏差 (vs BOM 均价/上次入库价) 超此值 → 进财务待审">
            <UInputNumber v-model="form.priceVarianceThreshold" :min="0" :max="100" :step="1" :format-options="{ minimumFractionDigits: 2, maximumFractionDigits: 2 }" class="w-full" />
          </UFormField>
          <UFormField label="金额阈值 ¥" name="amountThreshold" help="订单总金额超过此值 → 进财务待审. 留空 (清零) = 不按金额触发">
            <UInputNumber v-model="form.amountThreshold" :min="0" :step="1000" placeholder="留空 = 不参与判断" :format-options="{ minimumFractionDigits: 2, maximumFractionDigits: 2 }" class="w-full" />
          </UFormField>
          <UFormField label="启用" name="enabled" help="关闭后此规则不参与评估. 全部规则都关 = 所有 PO 直接 APPROVED">
            <USwitch v-model="form.enabled" />
          </UFormField>
        </UForm>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton @click="formRef?.submit()">{{ editingId ? '保存' : '创建' }}</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.page-wrapper { padding: var(--cs-space-4); }
.row-actions { display: flex; gap: var(--cs-space-1); align-items: center; }
.muted { color: var(--cs-muted); }
.rule-notes { margin: var(--cs-space-1) 0 0 var(--cs-space-5); padding: 0; }
.empty { padding: var(--cs-space-6) 0; color: var(--cs-muted); text-align: center; }
.empty p { margin: 0 0 var(--cs-space-1); }
.empty-hint { font-size: var(--cs-font-small); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
</style>
