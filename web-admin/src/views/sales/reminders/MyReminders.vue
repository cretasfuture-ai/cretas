<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  listReminders,
  snoozeReminder,
  dismissReminder,
  type Reminder,
  type ReminderStatus,
} from '@/api/reminder';
import { toDateString } from '@/utils/dateFormat';
import type { CsTableColumn } from '@/types/csTable';
import SalesConfirmModal from '../components/SalesConfirmModal.vue';

const router = useRouter();
const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const reminders = ref<Reminder[]>([]);
const total = ref(0);
const page = ref(0);
const size = ref(20);
const loading = ref(false);
const statusFilter = ref<string>('PENDING,SNOOZED');

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await listReminders({
      status: statusFilter.value,
      page: page.value,
      size: size.value,
    });
    reminders.value = res?.data?.content ?? [];
    total.value = res?.data?.totalElements ?? 0;
  } catch (e) {
    ElMessage.error('加载失败');
  } finally {
    loading.value = false;
  }
}

const STATUS_FILTERS = [
  { value: 'PENDING,SNOOZED', label: '活跃' },
  { value: 'PENDING', label: '仅待处理' },
  { value: 'SNOOZED', label: '仅已延后' },
  { value: 'DISMISSED', label: '已处理' },
];
const columns: CsTableColumn<Reminder>[] = [
  { id: 'type', header: '类型' },
  { id: 'dueDate', header: '到期日' },
  { accessorKey: 'message', header: '内容' },
  { id: 'status', header: '状态' },
  { id: 'snoozedUntil', header: '延后至' },
  { id: 'actions', header: '操作' },
];
function pickStatus(v: string): void {
  if (statusFilter.value === v) return;
  statusFilter.value = v;
  onStatusChange();
}

function statusTag(status: ReminderStatus): { type: 'warning' | 'neutral' | 'success'; label: string } {
  if (status === 'PENDING') return { type: 'warning', label: '待处理' };
  if (status === 'SNOOZED') return { type: 'neutral', label: '已延后' };
  return { type: 'success', label: '已处理' };
}

function typeLabel(type: string): string {
  if (type === 'PAYMENT_DUE') return '收款提醒';
  return type;
}

const isOverdue = computed(() => (dueDate: string) => {
  return new Date(dueDate) < new Date(new Date().toDateString());
});

async function handleSnooze(r: Reminder): Promise<void> {
  const value = await confirmRef.value!.ask({
    title: '延后提醒',
    message: '把这条提醒延后到哪一天?',
    input: { label: '延后到 (YYYY-MM-DD)', initial: defaultSnoozeDate(), required: true, pattern: /^\d{4}-\d{2}-\d{2}$/, errorText: '请输入 YYYY-MM-DD 格式' },
  });
  if (value === null) return;
  try {
    await snoozeReminder(r.id, value);
    ElMessage.success('已延后');
    load();
  } catch {
    // 拦截器已提示失败原因
  }
}

function defaultSnoozeDate(): string {
  const d = new Date();
  d.setDate(d.getDate() + 3);
  return toDateString(d);
}

async function handleDismiss(r: Reminder): Promise<void> {
  const ok = await confirmRef.value!.ask({ title: '确认', message: '标记此提醒为已处理?' });
  if (ok === null) return;
  try {
    await dismissReminder(r.id);
    ElMessage.success('已处理');
    load();
  } catch {
    // 拦截器已提示失败原因
  }
}

function goSource(r: Reminder): void {
  if (r.sourceType === 'SALES_ORDER') {
    router.push(`/sales/orders/${r.sourceId}`);
  }
}

function onStatusChange(): void {
  page.value = 0;
  load();
}

function onPageChange(p: number): void {
  if (p - 1 === page.value) return;
  page.value = p - 1;
  load();
}

onMounted(load);
</script>

<template>
  <div class="page my-reminders">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>我的提醒</h2>
        <span class="count">共 {{ total }} 条</span>
        <div class="seg head-actions" data-testid="reminder-status-filter">
          <UButton
            v-for="f in STATUS_FILTERS"
            :key="f.value"
            size="sm"
            :color="statusFilter === f.value ? 'primary' : 'neutral'"
            :variant="statusFilter === f.value ? 'solid' : 'outline'"
            @click="pickStatus(f.value)"
          >{{ f.label }}</UButton>
        </div>
      </header>

      <UTable :data="reminders" :columns="columns" :loading="loading" class="cs-table" data-testid="reminder-table">
        <template #empty>暂无提醒</template>
        <template #type-cell="{ row }">{{ typeLabel(row.original.type) }}</template>
        <template #dueDate-cell="{ row }">
          <span :class="{ overdue: isOverdue(row.original.dueDate) && row.original.status === 'PENDING' }">{{ row.original.dueDate }}</span>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="statusTag(row.original.status).type" variant="soft">{{ statusTag(row.original.status).label }}</UBadge>
        </template>
        <template #snoozedUntil-cell="{ row }">{{ row.original.snoozedUntil ?? '—' }}</template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton variant="link" size="xs" @click="goSource(row.original)">查看来源</UButton>
            <UButton v-if="row.original.status !== 'DISMISSED'" variant="link" size="xs" @click="handleSnooze(row.original)">延后</UButton>
            <UButton v-if="row.original.status !== 'DISMISSED'" color="success" variant="link" size="xs" @click="handleDismiss(row.original)">处理</UButton>
          </div>
        </template>
      </UTable>

      <div v-if="total > 0" class="pager">
        <UPagination :page="page + 1" :items-per-page="size" :total="total" show-edges :sibling-count="1" @update:page="onPageChange" />
      </div>
    </section>

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
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-4) var(--cs-space-5); }
.overdue { color: var(--cs-danger); font-weight: 600; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
