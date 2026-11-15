<script setup lang="ts">
/**
 * CCP 监控记录 (缺口 13, owner 2026-10-25)。
 *
 * 关键控制点 (CCP) 挂在工序上, 操作员报工时录实测值; 超出临界限值自动记「偏离」, 该批次 (及同一生产计划的产出)
 * 转为待检, 质检放行前必须先在这里处理偏离 (填纠偏措施)。
 *
 *   - 「未处理偏离」: 全厂待处理的偏离, 逐条填纠偏措施
 *   - 「按批次查看 / 导出」: 输入批次号 (成品批号也行, 会带出同一生产计划整条链), 查看并导出 CSV
 *
 * 防呆: 纠偏措施必填 (常用措施一点即填, Rule 3); 失败由请求拦截器弹常驻提示 (后端原话 + 下一步)。
 */
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import iconCcp from '@/assets/quality/icon-ccp.webp';
import { useAuthStore } from '@/store/modules/auth';
import { handleCatchError } from '@/utils/errorToast';
import {
  exportCcpRecords,
  handleCcpDeviation,
  listCcpRecordsForBatch,
  listOpenDeviations,
  type CcpRecordView,
} from '@/api/ccp';

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId || '');

const activeTab = ref<'open' | 'batch'>('open');
const openLoading = ref(false);
const openRows = ref<CcpRecordView[]>([]);
const batchNumber = ref('');
const batchLoading = ref(false);
const batchRows = ref<CcpRecordView[] | null>(null);
const exporting = ref(false);

const R = { class: { th: 'text-right', td: 'text-right' } };
const C = { class: { th: 'text-center', td: 'text-center' } };
const OPEN_COLUMNS: TableColumn<CcpRecordView>[] = [
  { accessorKey: 'batchNumber', header: '批次号' },
  { id: 'checkpoint', header: '控制点' },
  { id: 'measured', header: '实测值', meta: R },
  { id: 'limit', header: '合格范围' },
  { id: 'operator', header: '录入' },
  { id: 'actions', header: '操作', meta: C },
];
const BATCH_COLUMNS: TableColumn<CcpRecordView>[] = [
  { id: 'time', header: '时间' },
  { accessorKey: 'batchNumber', header: '批次号' },
  { accessorKey: 'processOrder', header: '工序序' },
  { id: 'checkpoint', header: '控制点' },
  { id: 'measured', header: '实测值', meta: R },
  { id: 'limit', header: '合格范围' },
  { id: 'result', header: '结果' },
  { accessorKey: 'deviationAction', header: '纠偏措施' },
  { id: 'actions', header: '操作', meta: C },
];
const tabItems = computed<TabsItem[]>(() => [
  { label: `未处理偏离 (${openRows.value.length})`, value: 'open', slot: 'open' },
  { label: '按批次查看 / 导出', value: 'batch', slot: 'batch' },
]);

const handleVisible = ref(false);
const handling = ref<CcpRecordView | null>(null);
const action = ref('');
const submitting = ref(false);

const RESULT_LABELS: Record<string, string> = { OK: '合格', DEVIATION_OPEN: '偏离·未处理', DEVIATION_HANDLED: '偏离·已处理' };
const RESULT_TAG: Record<string, 'success' | 'danger' | 'warning'> = { OK: 'success', DEVIATION_OPEN: 'danger', DEVIATION_HANDLED: 'warning' };
const COMMON_ACTIONS = ['继续加工后复测合格', '整批隔离，按不合格品处置', '设备校准后复测合格', '调整工艺参数后复测合格'];

function limitText(r: CcpRecordView): string {
  if (r.criticalLimitMin == null && r.criticalLimitMax == null) return '—';
  return `${r.criticalLimitMin ?? ''} ~ ${r.criticalLimitMax ?? ''} ${r.unit ?? ''}`;
}
function timeText(t: string | null): string {
  return t ? t.replace('T', ' ').substring(0, 19) : '—';
}

async function loadOpen() {
  if (!factoryId.value) return;
  openLoading.value = true;
  try {
    const res = await listOpenDeviations(factoryId.value);
    openRows.value = res.success && Array.isArray(res.data) ? res.data : [];
  } catch (e) {
    handleCatchError(e, '未处理偏离加载失败');
  } finally {
    openLoading.value = false;
  }
}

async function searchBatch() {
  const no = batchNumber.value.trim();
  if (!no) {
    ElMessage.warning('请输入批次号');
    return;
  }
  batchLoading.value = true;
  try {
    const res = await listCcpRecordsForBatch(factoryId.value, no);
    batchRows.value = res.success && Array.isArray(res.data) ? res.data : [];
  } catch (e) {
    batchRows.value = null;
    handleCatchError(e, 'CCP 记录查询失败');
  } finally {
    batchLoading.value = false;
  }
}

async function doExport() {
  const no = batchNumber.value.trim();
  if (!no) {
    ElMessage.warning('请先输入批次号');
    return;
  }
  exporting.value = true;
  try {
    const blob = await exportCcpRecords(factoryId.value, no);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `CCP记录-${no}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    handleCatchError(e, '导出失败，请重试');
  } finally {
    exporting.value = false;
  }
}

function openHandle(row: CcpRecordView) {
  handling.value = row;
  action.value = '';
  handleVisible.value = true;
}

function applyAction(text: string) {
  action.value = action.value.trim() ? `${action.value.trim()}；${text}` : text;
}

async function submitHandle() {
  if (!handling.value || !action.value.trim()) return;
  submitting.value = true;
  try {
    const res = await handleCcpDeviation(factoryId.value, handling.value.id, action.value.trim());
    if (res.success) {
      ElMessage.success(`偏离已处理。批次 ${handling.value.batchNumber} 可以到「质检放行」放行了（如无其他未处理偏离）`);
      handleVisible.value = false;
      await loadOpen();
      if (batchRows.value) await searchBatch();
    }
  } catch (e) {
    handleCatchError(e, '处理失败，请重试');
  } finally {
    submitting.value = false;
  }
}

function goRelease() {
  router.push('/quality/release');
}

onMounted(() => {
  const q = route.query.batchNumber;
  if (typeof q === 'string' && q) {
    batchNumber.value = q;
    activeTab.value = 'batch';
    void searchBatch();
  }
  void loadOpen();
});
</script>

<template>
  <div class="ccp-page">
    <div class="cs-page-head">
      <div class="head-main">
        <img :src="iconCcp" alt="" class="head-icon" width="56" height="56">
        <div>
          <h2>CCP 监控记录</h2>
          <p>
            关键控制点在报工时录入；超出临界限值会记偏离并让该批次待检。处理完偏离，才能在「质检放行」放行。
            控制点与所属工序在「Canvas 配置编辑器 → 食品安全 → HACCP 关键控制点」维护。
          </p>
        </div>
      </div>
      <UButton color="neutral" variant="outline" @click="goRelease">去质检放行</UButton>
    </div>

    <section class="cs-card">
      <UTabs v-model="activeTab" :items="tabItems" variant="link" :ui="{ content: 'pt-0' }">
        <template #open>
          <div class="toolbar">
            <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="openLoading" @click="loadOpen">刷新</UButton>
          </div>
          <UTable :data="openRows" :columns="OPEN_COLUMNS" :loading="openLoading" class="cs-table" data-testid="ccp-open-table">
            <template #empty><div class="cs-empty-art"><img :src="iconCcp" alt="" width="96"><span>没有未处理的偏离</span></div></template>
            <template #checkpoint-cell="{ row }">{{ row.original.checkpointName || row.original.checkpointCode || row.original.checkpointId }}</template>
            <template #measured-cell="{ row }"><span class="bad num">{{ row.original.measuredValue }} {{ row.original.unit }}</span></template>
            <template #limit-cell="{ row }">{{ limitText(row.original) }}</template>
            <template #operator-cell="{ row }">{{ row.original.operatorName || `用户 ${row.original.operatorUserId}` }} · {{ timeText(row.original.monitoringTime) }}</template>
            <template #actions-cell="{ row }">
              <UButton color="error" size="xs" :data-testid="`ccp-handle-${row.original.id}`" @click="openHandle(row.original)">处理偏离</UButton>
            </template>
          </UTable>
        </template>

        <template #batch>
          <div class="toolbar">
            <UInput
              v-model="batchNumber"
              icon="i-lucide-search"
              placeholder="输入批次号（成品批号会带出同一生产计划全部工序的记录）"
              class="w-105"
              data-testid="ccp-batch-input"
              @keyup.enter="searchBatch"
            />
            <UButton :loading="batchLoading" icon="i-lucide-search" @click="searchBatch">查询</UButton>
            <UButton color="neutral" variant="outline" icon="i-lucide-download" :loading="exporting" :disabled="!batchNumber.trim()" @click="doExport">导出 CSV</UButton>
          </div>
          <UTable v-if="batchRows" :data="batchRows" :columns="BATCH_COLUMNS" :loading="batchLoading" class="cs-table">
            <template #empty>这个批次没有 CCP 记录</template>
            <template #time-cell="{ row }">{{ timeText(row.original.monitoringTime) }}</template>
            <template #checkpoint-cell="{ row }">{{ row.original.checkpointName || row.original.checkpointCode }}</template>
            <template #measured-cell="{ row }"><span class="num">{{ row.original.measuredValue }} {{ row.original.unit }}</span></template>
            <template #limit-cell="{ row }">{{ limitText(row.original) }}</template>
            <template #result-cell="{ row }">
              <UBadge :color="badgeColor(RESULT_TAG[row.original.result])" variant="soft">{{ RESULT_LABELS[row.original.result] ?? row.original.result }}</UBadge>
            </template>
            <template #actions-cell="{ row }">
              <UButton v-if="row.original.result === 'DEVIATION_OPEN'" color="error" variant="link" size="xs" @click="openHandle(row.original)">处理偏离</UButton>
            </template>
          </UTable>
        </template>
      </UTabs>
    </section>

    <UModal
      v-model:open="handleVisible"
      title="处理 CCP 偏离"
      description="填写实际采取的纠偏措施"
      :dismissible="false"
      :ui="{ content: 'max-w-[520px]', description: 'sr-only' }"
    >
      <template #body>
        <template v-if="handling">
          <dl class="summary">
            <div><dt>批次号</dt><dd>{{ handling.batchNumber }}</dd></div>
            <div><dt>控制点</dt><dd>{{ handling.checkpointName || handling.checkpointCode }}</dd></div>
            <div><dt>实测 / 合格范围</dt><dd><span class="bad">{{ handling.measuredValue }} {{ handling.unit }}</span> / {{ limitText(handling) }}</dd></div>
            <div v-if="handling.correctiveActionGuide"><dt>规定纠偏措施</dt><dd>{{ handling.correctiveActionGuide }}</dd></div>
          </dl>
          <UFormField label="实际采取的纠偏措施" required class="mt-3">
            <div class="presets">
              <UButton v-if="handling.correctiveActionGuide" class="preset" size="xs" color="primary" variant="soft"
                @click="applyAction(handling.correctiveActionGuide || '')">
                按规定：{{ handling.correctiveActionGuide }}
              </UButton>
              <UButton v-for="a in COMMON_ACTIONS" :key="a" class="preset" size="xs" color="neutral" variant="soft" @click="applyAction(a)">{{ a }}</UButton>
            </div>
            <UTextarea
              v-model="action"
              :rows="3"
              :maxlength="1000"
              placeholder="例如：继续加热 5 分钟，复测中心温度 79℃ 合格"
              class="w-full"
              data-testid="ccp-action"
            />
          </UFormField>
        </template>
      </template>
      <template #footer>
        <span v-if="!action.trim()" class="disabled-reason">请填写纠偏措施</span>
        <UButton color="neutral" variant="outline" @click="handleVisible = false">取消</UButton>
        <UButton :loading="submitting" :disabled="!action.trim()" data-testid="ccp-handle-submit" @click="submitHandle">确认已处理</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.ccp-page { padding: var(--cs-space-4); }
.head-main { flex: 1; min-width: 0; display: flex; gap: var(--cs-space-3); align-items: center; }
.head-icon { flex: none; border-radius: var(--cs-radius); }
.toolbar { display: flex; gap: var(--cs-space-2); align-items: center; padding: var(--cs-space-3) var(--cs-space-5); }
.bad { color: var(--cs-danger); font-weight: 600; }
.presets { display: flex; flex-wrap: wrap; gap: var(--cs-space-2); margin-bottom: var(--cs-space-2); }
.disabled-reason { color: var(--cs-danger); font-size: var(--cs-font-small); margin-right: var(--cs-space-3); }
.summary { display: grid; gap: var(--cs-space-2); margin: 0; padding: var(--cs-space-3) var(--cs-space-4); background: var(--cs-surface-subtle); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.summary dt { font-size: var(--cs-font-small); color: var(--cs-muted); }
.summary dd { margin: 2px 0 0; color: var(--cs-text); }
</style>
