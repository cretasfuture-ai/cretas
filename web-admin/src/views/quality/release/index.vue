<script setup lang="ts">
/**
 * 质检放行 (缺口 11, owner 2026-10-25)。
 *
 * 「需检」产品 (产品管理里开关) 的生产产出、以及 CCP 偏离批次, 一律先进入「待检」:
 *   - 半成品待检 → 下道工序不能领用
 *   - 成品待检   → 不能分配批次、不能发货
 * 质检员在这里逐批「放行」或「不合格」, 必须写检验结论; 系统记录放行人和放行时间。
 *
 * 防呆:
 *   - Rule 1 事前告知: 每行写清楚「待检会挡住什么」; 有未处理 CCP 偏离的批次, 「放行」直接禁用并说明去哪处理
 *   - Rule 3 必填结论: 结论为空时提交按钮禁用, 按钮旁写明原因
 *   - 错误: 后端 409/400 由请求拦截器弹出常驻提示 (后端原话 + 下一步)
 */
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { handleCatchError } from '@/utils/errorToast';
import {
  decideQcRelease,
  listQcPending,
  listQcReleaseRecords,
  QC_RELEASE_TARGET_LABELS,
  type QcPendingItem,
  type QcReleaseDecision,
  type QcReleaseRecord,
} from '@/api/qcRelease';

const authStore = useAuthStore();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId || '');

const activeTab = ref<'pending' | 'records'>('pending');
const loading = ref(false);
const loadError = ref<string | null>(null);
const pending = ref<QcPendingItem[]>([]);
const records = ref<QcReleaseRecord[]>([]);
const recordsLoading = ref(false);

const dialogVisible = ref(false);
const submitting = ref(false);
const current = ref<QcPendingItem | null>(null);
const decision = ref<QcReleaseDecision>('RELEASED');
const conclusion = ref('');

function blockedWhat(item: QcPendingItem): string {
  return item.targetType === 'FINISHED_GOODS' ? '不能分配、不能发货' : '下道工序不能领用';
}

function qty(item: { quantity: number | null; unit: string | null }): string {
  return item.quantity == null ? '—' : `${Number(item.quantity)} ${item.unit ?? ''}`;
}

async function loadPending() {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = null;
  try {
    const res = await listQcPending(factoryId.value);
    pending.value = res.success && Array.isArray(res.data) ? res.data : [];
    if (!res.success) loadError.value = res.message || '待检清单加载失败';
  } catch (e) {
    loadError.value = '待检清单加载失败，请点「刷新」重试';
    handleCatchError(e, '待检清单加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadRecords() {
  if (!factoryId.value) return;
  recordsLoading.value = true;
  try {
    const res = await listQcReleaseRecords(factoryId.value);
    records.value = res.success && Array.isArray(res.data) ? res.data : [];
  } catch (e) {
    handleCatchError(e, '放行记录加载失败');
  } finally {
    recordsLoading.value = false;
  }
}

function openDecision(item: QcPendingItem) {
  current.value = item;
  decision.value = item.openDeviationCount > 0 ? 'REJECTED' : 'RELEASED';
  conclusion.value = '';
  dialogVisible.value = true;
}

// 防呆 Rule 3: 常用结论一点即填 (仍可补充文字), 不逼质检员每次手打
const RELEASE_PRESETS = ['感官检验合格', '理化指标合格', '微生物检验合格', '中心温度复测合格'];
const REJECT_PRESETS = ['感官异常（异味/变色）', '理化指标超标', '微生物超标', '发现异物', 'CCP 偏离未能纠正'];
const conclusionPresets = computed(() => (decision.value === 'RELEASED' ? RELEASE_PRESETS : REJECT_PRESETS));
function applyPreset(text: string) {
  const cur = conclusion.value.trim();
  if (cur.includes(text)) return;
  conclusion.value = cur ? `${cur}；${text}` : text;
}

const releaseBlocked = computed(() => (current.value?.openDeviationCount ?? 0) > 0);

const submitDisabledReason = computed(() => {
  if (!current.value) return '未选择批次';
  if (decision.value === 'RELEASED' && releaseBlocked.value) {
    return `还有 ${current.value.openDeviationCount} 条未处理的 CCP 偏离，不能放行`;
  }
  if (!conclusion.value.trim()) return '请填写检验结论';
  return null;
});

async function submitDecision() {
  if (!current.value || submitDisabledReason.value) return;
  submitting.value = true;
  try {
    const res = await decideQcRelease(factoryId.value, {
      targetType: current.value.targetType,
      targetId: current.value.targetId,
      decision: decision.value,
      conclusion: conclusion.value.trim(),
    });
    if (res.success) {
      ElMessage.success(decision.value === 'RELEASED'
        ? `已放行 ${current.value.batchNumber ?? ''}，现在可以${current.value.targetType === 'FINISHED_GOODS' ? '分配发货' : '被下道领用'}`
        : `已判定不合格 ${current.value.batchNumber ?? ''}`);
      dialogVisible.value = false;
      await Promise.all([loadPending(), loadRecords()]);
    }
  } catch (e) {
    // 拦截器已弹常驻提示 (后端原话 + 下一步); 对话框保持打开, 让质检员看着提示改
    handleCatchError(e, '提交失败，请重试');
    await loadPending();
  } finally {
    submitting.value = false;
  }
}

function goCcp(item: QcPendingItem) {
  router.push({ path: '/quality/ccp', query: item.batchNumber ? { batchNumber: item.batchNumber } : {} });
}

onMounted(() => {
  loadPending();
  loadRecords();
});
</script>

<template>
  <div class="qc-release">
    <div class="page-header">
      <div>
        <h2 class="title">质检放行</h2>
        <p class="subtitle">
          产品设置为「需检」、或批次有 CCP 偏离时，产出先进入待检：半成品待检时下道不能领用，成品待检时不能分配和发货。
        </p>
      </div>
      <el-button :icon="Refresh" :loading="loading || recordsLoading" @click="loadPending(); loadRecords()">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="`待检 (${pending.length})`" name="pending">
        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="mb" />
        <el-table v-loading="loading" :data="pending" stripe empty-text="暂无待检批次" data-testid="qc-pending-table">
          <el-table-column label="形态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.targetType === 'FINISHED_GOODS' ? 'success' : 'info'">{{ row.formLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="batchNumber" label="批次号" min-width="200" show-overflow-tooltip />
          <el-table-column label="产品" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.productName || row.productTypeId || '—' }}</template>
          </el-table-column>
          <el-table-column label="数量" width="130" align="right">
            <template #default="{ row }">{{ qty(row) }}</template>
          </el-table-column>
          <el-table-column label="待检期间" min-width="150">
            <template #default="{ row }">
              <span class="muted">{{ blockedWhat(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="CCP 偏离" width="130" align="center">
            <template #default="{ row }">
              <el-button v-if="row.openDeviationCount > 0" link type="danger" @click="goCcp(row)">
                {{ row.openDeviationCount }} 条未处理
              </el-button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column prop="producedAt" label="产出时间" min-width="160" />
          <el-table-column label="操作" width="120" align="center" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" :data-testid="`qc-decide-${row.targetId}`" @click="openDecision(row)">
                放行 / 不合格
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="放行记录" name="records">
        <el-table v-loading="recordsLoading" :data="records" stripe empty-text="暂无放行记录">
          <el-table-column label="结论" width="100">
            <template #default="{ row }">
              <el-tag :type="row.decision === 'RELEASED' ? 'success' : 'danger'">
                {{ row.decision === 'RELEASED' ? '放行' : '不合格' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="形态" width="110">
            <template #default="{ row }">{{ QC_RELEASE_TARGET_LABELS[row.targetType as keyof typeof QC_RELEASE_TARGET_LABELS] ?? row.targetType }}</template>
          </el-table-column>
          <el-table-column prop="batchNumber" label="批次号" min-width="200" show-overflow-tooltip />
          <el-table-column label="数量" width="130" align="right">
            <template #default="{ row }">{{ qty(row) }}</template>
          </el-table-column>
          <el-table-column prop="conclusion" label="检验结论" min-width="220" show-overflow-tooltip />
          <el-table-column label="放行人" width="120">
            <template #default="{ row }">{{ row.releasedByName || `用户 ${row.releasedBy}` }}</template>
          </el-table-column>
          <el-table-column prop="releasedAt" label="放行时间" min-width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" title="质检放行" width="520px" :close-on-click-modal="false">
      <template v-if="current">
        <el-descriptions :column="1" border size="small" class="mb">
          <el-descriptions-item label="批次号">{{ current.batchNumber || '—' }}</el-descriptions-item>
          <el-descriptions-item label="产品">{{ current.productName || current.productTypeId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="形态 / 数量">{{ current.formLabel }} · {{ qty(current) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="releaseBlocked"
          type="error"
          :closable="false"
          show-icon
          class="mb"
          :title="`这批还有 ${current.openDeviationCount} 条未处理的 CCP 偏离，不能放行`"
          description="请先到「质量管理 → CCP 监控记录」填写纠偏措施并标记已处理；如果决定不合格，可以直接选「不合格」。"
        />
        <el-form label-position="top">
          <el-form-item label="检验结果" required>
            <el-radio-group v-model="decision">
              <el-radio value="RELEASED" :disabled="releaseBlocked" data-testid="qc-decision-released">放行</el-radio>
              <el-radio value="REJECTED" data-testid="qc-decision-rejected">不合格</el-radio>
            </el-radio-group>
            <div class="form-tip">
              {{ decision === 'RELEASED'
                ? (current.targetType === 'FINISHED_GOODS' ? '放行后可以分配批次、发货' : '放行后下道工序可以领用')
                : '判定不合格后这批不能再领用/发货，请按不良品流程处置' }}
            </div>
          </el-form-item>
          <el-form-item label="检验结论" required>
            <div class="presets">
              <el-tag
                v-for="p in conclusionPresets"
                :key="p"
                class="preset"
                effect="plain"
                :type="decision === 'RELEASED' ? 'success' : 'danger'"
                @click="applyPreset(p)"
              >
                {{ p }}
              </el-tag>
            </div>
            <el-input
              v-model="conclusion"
              type="textarea"
              :rows="3"
              maxlength="2000"
              show-word-limit
              placeholder="例如：感官正常，中心温度复测 78℃ 合格"
              data-testid="qc-conclusion"
            />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <span v-if="submitDisabledReason" class="disabled-reason">{{ submitDisabledReason }}</span>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!!submitDisabledReason"
          data-testid="qc-decision-submit"
          @click="submitDecision"
        >
          确认{{ decision === 'RELEASED' ? '放行' : '不合格' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.qc-release {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
  gap: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 4px;
  color: #303133;
}
.page-header .subtitle {
  font-size: 13px;
  color: #606266;
  margin: 0;
}
.muted {
  color: #909399;
  font-size: 13px;
}
.mb {
  margin-bottom: 12px;
}
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
}
.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.preset {
  cursor: pointer;
}
.disabled-reason {
  color: #f56c6c;
  font-size: 12px;
  margin-right: 12px;
}
</style>
