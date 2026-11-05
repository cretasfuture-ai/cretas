<script setup lang="ts">
/**
 * 交付风险: 扫描未交订单, 有风险的成为处理实例, 走 证据 → 影响 → 处理建议 → 负责人 → 处理结果。
 * 「无法判断」单独列出, 不算正常。这里只记录任务和结果, 排程、采购到各自页面处理。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listDeliveryRisks,
  scanDeliveryRisks,
  getDeliveryRisk,
  listAssignees,
  assignDeliveryRisk,
  recheckDeliveryRisk,
  finishDeliveryRisk,
  type Assignee,
  type DeliveryRiskCase,
  type DeliveryRiskDetail,
  type RiskLevel,
  type ScanResult,
} from '@/api/deliveryRisks';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
// 与后端写接口的 @RequirePermission 同一份口径
const canWrite = computed(() => permissionStore.canWrite('production') || permissionStore.canWrite('sales'));

const LEVEL_LABEL: Record<RiskLevel, string> = { RISK: '有风险', UNKNOWN: '无法判断', OK: '正常' };
const LEVEL_TAG: Record<RiskLevel, 'danger' | 'warning' | 'success'> = { RISK: 'danger', UNKNOWN: 'warning', OK: 'success' };
const ROLE_LABEL: Record<string, string> = { production_manager: '生产', procurement_manager: '采购' };

const openOnly = ref(true);
const loading = ref(false);
const loadFailed = ref(false);
const rows = ref<DeliveryRiskCase[]>([]);
const scanning = ref(false);
const scan = ref<ScanResult | null>(null);

const detailVisible = ref(false);
const detail = ref<DeliveryRiskDetail | null>(null);
const assignees = ref<Assignee[]>([]);
const ownerUserId = ref<number | null>(null);
const task = ref('');
const result = ref('');
const busy = ref(false);
const isOpen = computed(() => !!detail.value && detail.value.case.closedAt == null);

function time(v: string | null) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—';
}
function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback;
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    const res = await listDeliveryRisks(factoryId.value, openOnly.value);
    if (res.success) {
      rows.value = res.data ?? [];
    } else {
      loadFailed.value = true;
      ElMessage.error(res.message || '交付风险加载失败');
    }
  } catch (e) {
    loadFailed.value = true;
    ElMessage.error(errorText(e, '交付风险加载失败，请稍后重试'));
  } finally {
    loading.value = false;
  }
}

async function runScan() {
  scanning.value = true;
  try {
    const res = await scanDeliveryRisks(factoryId.value);
    if (res.success) {
      scan.value = res.data;
      await load();
    } else {
      ElMessage.error(res.message || '扫描失败');
    }
  } catch (e) {
    ElMessage.error(errorText(e, '扫描失败，请稍后重试'));
  } finally {
    scanning.value = false;
  }
}

async function openDetail(row: DeliveryRiskCase) {
  detailVisible.value = true;
  // 同一条实例刷新时保留旧内容, 不让抽屉闪空
  if (detail.value?.case.id !== row.id) detail.value = null;
  task.value = '';
  result.value = '';
  try {
    const [d, a] = await Promise.all([getDeliveryRisk(factoryId.value, row.id), listAssignees(factoryId.value)]);
    if (d.success) {
      detail.value = d.data;
      // 负责人以服务端最新值为准: 传进来的 row 可能是指派之前的
      ownerUserId.value = d.data.case.ownerUserId;
    } else ElMessage.error(d.message || '详情加载失败');
    assignees.value = a.success ? a.data ?? [] : [];
  } catch (e) {
    ElMessage.error(errorText(e, '详情加载失败'));
  }
}

async function act(run: () => Promise<{ success: boolean; message?: string }>, done: string) {
  if (!detail.value) return;
  const current = detail.value.case;
  busy.value = true;
  try {
    const res = await run();
    if (res.success) {
      ElMessage.success(done);
      await Promise.all([openDetail(current), load()]);
    } else {
      ElMessage.error(res.message || '操作失败');
    }
  } catch (e) {
    ElMessage.error(errorText(e, '操作失败，请稍后重试'));
  } finally {
    busy.value = false;
  }
}
const assign = () => act(() => assignDeliveryRisk(factoryId.value, detail.value!.case.id, ownerUserId.value!, task.value), '已指派');
const recheck = () => act(() => recheckDeliveryRisk(factoryId.value, detail.value!.case.id), '已按当前数据复核');
const finish = (d: 'CLOSE' | 'CANCEL') => act(() => finishDeliveryRisk(factoryId.value, detail.value!.case.id, d, result.value), d === 'CLOSE' ? '已关闭' : '已取消');

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h2>交付风险</h2>
        <p class="sub">按交期、计划、入库进度、工序和物料找出可能延期的订单。这里只记录任务和结果，不改排程、不下采购。</p>
      </div>
      <div>
        <el-checkbox v-model="openOnly" @change="load">只看未结束</el-checkbox>
        <el-button v-if="canWrite" type="primary" :icon="Refresh" :loading="scanning" data-testid="scan" @click="runScan">扫描未交订单</el-button>
      </div>
    </div>

    <el-alert v-if="loadFailed" type="error" :closable="false" title="加载失败，下面的列表不代表没有风险" />

    <el-card v-if="scan" class="block" data-testid="scan-result">
      <template #header>
        本次扫描 {{ scan.scanned }} 张：有风险 {{ scan.risk }}，无法判断 {{ scan.unknown }}，正常 {{ scan.ok }}；新建 {{ scan.created }}，更新 {{ scan.refreshed }}
      </template>
      <el-alert v-if="scan.truncated" type="warning" :closable="false" title="订单过多，只扫描了最新的一部分" />
      <el-table :data="scan.orders" max-height="320">
        <el-table-column prop="orderNumber" label="订单" min-width="160" />
        <el-table-column label="交期" width="120"><template #default="{ row }">{{ row.dueDate ?? '未知' }}</template></el-table-column>
        <el-table-column label="结论" width="100">
          <template #default="{ row }"><el-tag :type="LEVEL_TAG[row.level as RiskLevel]">{{ LEVEL_LABEL[row.level as RiskLevel] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="说明" min-width="320">
          <template #default="{ row }">{{ [...row.signals.map((s: { label: string }) => s.label), ...row.unknowns].join('；') || '排程或产能显示能按期' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="block">
      <template #header>处理实例</template>
      <el-table v-loading="loading" :data="rows" empty-text="没有处理实例" @row-click="openDetail">
        <el-table-column label="订单" min-width="160"><template #default="{ row }">{{ row.orderNumber ?? '订单已不存在' }}</template></el-table-column>
        <el-table-column label="客户" min-width="120"><template #default="{ row }">{{ row.customerName ?? '未知' }}</template></el-table-column>
        <el-table-column label="交期" width="120"><template #default="{ row }">{{ row.dueDate ?? '未知' }}</template></el-table-column>
        <el-table-column label="当前结论" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.riskLevel" :type="LEVEL_TAG[row.riskLevel as RiskLevel]">{{ LEVEL_LABEL[row.riskLevel as RiskLevel] }}</el-tag>
            <span v-else>未知</span>
          </template>
        </el-table-column>
        <el-table-column prop="stageLabel" label="阶段" width="100" />
        <el-table-column label="负责人" width="130">
          <template #default="{ row }">{{ row.ownerName ?? `待指派（${ROLE_LABEL[row.ownerRole] ?? row.ownerRole}）` }}</template>
        </el-table-column>
        <el-table-column label="最近评估" width="150"><template #default="{ row }">{{ time(row.assessedAt) }}</template></el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" size="600px" :title="detail ? `交付风险 ${detail.case.orderNumber ?? ''}` : '交付风险'">
      <template v-if="detail">
        <p>
          <el-tag :type="LEVEL_TAG[detail.assessment.level]" disable-transitions data-testid="detail-level">{{ LEVEL_LABEL[detail.assessment.level] }}</el-tag>
          {{ detail.case.stageLabel }} · 交期 {{ detail.assessment.dueDate ?? '未知' }}
        </p>
        <h4>异常</h4>
        <ul><li v-for="s in detail.assessment.signals" :key="s.code">{{ s.label }}</li><li v-if="!detail.assessment.signals.length">当前没有风险信号</li></ul>
        <h4>证据</h4>
        <ul><li v-for="(t, i) in detail.assessment.evidence" :key="i">{{ t }}</li></ul>
        <template v-if="detail.assessment.unknowns.length">
          <h4>不知道的</h4>
          <ul><li v-for="(t, i) in detail.assessment.unknowns" :key="i">{{ t }}</li></ul>
        </template>
        <h4>影响</h4>
        <ul><li v-for="(t, i) in detail.assessment.impact" :key="i">{{ t }}</li></ul>
        <h4>处理建议</h4>
        <ul><li v-for="(t, i) in detail.assessment.suggestions" :key="i">{{ t }}</li><li v-if="!detail.assessment.suggestions.length">暂无</li></ul>

        <template v-if="isOpen && canWrite">
          <h4>负责人与任务</h4>
          <el-select v-model="ownerUserId" filterable placeholder="选择负责人" class="full" data-testid="owner">
            <el-option v-for="u in assignees" :key="u.id" :label="u.name" :value="u.id" />
          </el-select>
          <el-input v-model="task" type="textarea" :rows="2" placeholder="要做什么" class="gap" data-testid="task" />
          <el-button class="gap" type="primary" :loading="busy" :disabled="!ownerUserId || !task.trim()" data-testid="assign" @click="assign">指派</el-button>
          <el-button class="gap" :loading="busy" data-testid="recheck" @click="recheck">按当前数据复核</el-button>

          <h4>处理结果</h4>
          <el-input v-model="result" type="textarea" :rows="2" placeholder="做了什么、结果如何" data-testid="result" />
          <el-button class="gap" type="success" :loading="busy" :disabled="!result.trim()" data-testid="close" @click="finish('CLOSE')">写结果并关闭</el-button>
          <el-button class="gap" :loading="busy" :disabled="!result.trim()" data-testid="cancel" @click="finish('CANCEL')">误报，取消</el-button>
        </template>

        <h4>处理任务与结果</h4>
        <ul>
          <li v-for="a in detail.actions" :key="a.id">{{ time(a.createdAt) }} {{ a.actionType === 'HANDLING_TASK' ? '任务' : '结果' }}：{{ a.reason }}</li>
          <li v-if="!detail.actions.length">暂无</li>
        </ul>
        <h4>结果核对</h4>
        <ul>
          <li v-for="c in detail.checks" :key="c.id">{{ time(c.createdAt) }} {{ c.verdictLabel }}：{{ c.detail }}</li>
          <li v-if="!detail.checks.length">暂无</li>
        </ul>
        <h4>留痕</h4>
        <ul><li v-for="(e, i) in detail.events" :key="i">{{ time(e.createdAt) }} {{ e.detail }}</li></ul>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page { padding: 16px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 12px; }
.head h2 { margin: 0; }
.sub { margin: 4px 0 0; color: var(--el-text-color-secondary); }
.block { margin-top: 12px; }
.full { width: 100%; }
.gap { margin-top: 8px; }
</style>
