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
import { Refresh, Download, Search } from '@element-plus/icons-vue';
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
    <div class="page-header">
      <div>
        <h2 class="title">CCP 监控记录</h2>
        <p class="subtitle">
          关键控制点在报工时录入；超出临界限值会记偏离并让该批次待检。处理完偏离，才能在「质检放行」放行。
          控制点与所属工序在「Canvas 配置编辑器 → 食品安全 → HACCP 关键控制点」维护。
        </p>
      </div>
      <el-button @click="goRelease">去质检放行</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="`未处理偏离 (${openRows.length})`" name="open">
        <div class="toolbar">
          <el-button :icon="Refresh" :loading="openLoading" @click="loadOpen">刷新</el-button>
        </div>
        <el-table v-loading="openLoading" :data="openRows" stripe empty-text="没有未处理的偏离" data-testid="ccp-open-table">
          <el-table-column prop="batchNumber" label="批次号" min-width="190" show-overflow-tooltip />
          <el-table-column label="控制点" min-width="140">
            <template #default="{ row }">{{ row.checkpointName || row.checkpointCode || row.checkpointId }}</template>
          </el-table-column>
          <el-table-column label="实测值" width="120" align="right">
            <template #default="{ row }"><span class="bad">{{ row.measuredValue }} {{ row.unit }}</span></template>
          </el-table-column>
          <el-table-column label="合格范围" width="150">
            <template #default="{ row }">{{ limitText(row) }}</template>
          </el-table-column>
          <el-table-column label="录入" min-width="170">
            <template #default="{ row }">{{ row.operatorName || `用户 ${row.operatorUserId}` }} · {{ timeText(row.monitoringTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center" fixed="right">
            <template #default="{ row }">
              <el-button type="danger" size="small" :data-testid="`ccp-handle-${row.id}`" @click="openHandle(row)">处理偏离</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="按批次查看 / 导出" name="batch">
        <div class="toolbar">
          <el-input
            v-model="batchNumber"
            placeholder="输入批次号（成品批号会带出同一生产计划全部工序的记录）"
            clearable
            style="width: 420px"
            data-testid="ccp-batch-input"
            @keyup.enter="searchBatch"
          />
          <el-button type="primary" :icon="Search" :loading="batchLoading" @click="searchBatch">查询</el-button>
          <el-button :icon="Download" :loading="exporting" :disabled="!batchNumber.trim()" @click="doExport">导出 CSV</el-button>
        </div>
        <el-table v-if="batchRows" v-loading="batchLoading" :data="batchRows" stripe empty-text="这个批次没有 CCP 记录">
          <el-table-column label="时间" min-width="160">
            <template #default="{ row }">{{ timeText(row.monitoringTime) }}</template>
          </el-table-column>
          <el-table-column prop="batchNumber" label="批次号" min-width="170" show-overflow-tooltip />
          <el-table-column prop="processOrder" label="工序序" width="80" align="center" />
          <el-table-column label="控制点" min-width="130">
            <template #default="{ row }">{{ row.checkpointName || row.checkpointCode }}</template>
          </el-table-column>
          <el-table-column label="实测值" width="110" align="right">
            <template #default="{ row }">{{ row.measuredValue }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column label="合格范围" width="150">
            <template #default="{ row }">{{ limitText(row) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="120">
            <template #default="{ row }">
              <el-tag :type="RESULT_TAG[row.result]">{{ RESULT_LABELS[row.result] ?? row.result }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="deviationAction" label="纠偏措施" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="110" align="center">
            <template #default="{ row }">
              <el-button v-if="row.result === 'DEVIATION_OPEN'" link type="danger" @click="openHandle(row)">处理偏离</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="handleVisible" title="处理 CCP 偏离" width="520px" :close-on-click-modal="false">
      <template v-if="handling">
        <el-descriptions :column="1" border size="small" class="mb">
          <el-descriptions-item label="批次号">{{ handling.batchNumber }}</el-descriptions-item>
          <el-descriptions-item label="控制点">{{ handling.checkpointName || handling.checkpointCode }}</el-descriptions-item>
          <el-descriptions-item label="实测 / 合格范围">
            <span class="bad">{{ handling.measuredValue }} {{ handling.unit }}</span> / {{ limitText(handling) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="handling.correctiveActionGuide" label="规定纠偏措施">
            {{ handling.correctiveActionGuide }}
          </el-descriptions-item>
        </el-descriptions>
        <el-form label-position="top">
          <el-form-item label="实际采取的纠偏措施" required>
            <div class="presets">
              <el-button v-if="handling.correctiveActionGuide" class="preset" size="small" plain type="primary"
                @click="applyAction(handling.correctiveActionGuide || '')">
                按规定：{{ handling.correctiveActionGuide }}
              </el-button>
              <el-button v-for="a in COMMON_ACTIONS" :key="a" class="preset" size="small" plain @click="applyAction(a)">{{ a }}</el-button>
            </div>
            <el-input
              v-model="action"
              type="textarea"
              :rows="3"
              maxlength="1000"
              show-word-limit
              placeholder="例如：继续加热 5 分钟，复测中心温度 79℃ 合格"
              data-testid="ccp-action"
            />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <span v-if="!action.trim()" class="disabled-reason">请填写纠偏措施</span>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!action.trim()" data-testid="ccp-handle-submit" @click="submitHandle">
          确认已处理
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ccp-page {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 12px;
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
  line-height: 1.6;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.bad {
  color: #f56c6c;
  font-weight: 600;
}
.mb {
  margin-bottom: 12px;
}
.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.preset {
  margin-left: 0;
}
.disabled-reason {
  color: #f56c6c;
  font-size: 12px;
  margin-right: 12px;
}
</style>
