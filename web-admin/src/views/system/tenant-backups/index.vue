<template>
  <div class="tenant-backups-page">
    <div class="page-header">
      <h2>工厂数据备份</h2>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon class="intro">
      <template #title>
        平台整库灾备每天自动进行，所有工厂都有，这里不显示。本页管理的是在它之上、按工厂单独开通的「工厂数据备份」：
        按套餐定时导出本厂数据、加密后存到异地，超过保留期自动清理。套餐跟合同走，只有平台能改，每次改动都会留痕。
      </template>
    </el-alert>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="按工厂编号或名称筛选" clearable class="search" />
        <el-checkbox v-model="onlyEnabled">只看已开通</el-checkbox>
        <span class="muted">已开通 {{ enabledCount }} 个工厂；需关注 {{ attentionCount }} 个</span>
      </div>
      <el-table v-loading="loading" :data="filtered" stripe border>
        <el-table-column label="工厂" min-width="180">
          <template #default="{ row }">
            <div>{{ row.factoryName || '—' }}</div>
            <div class="muted sub">{{ row.factoryId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="套餐" min-width="150">
          <template #default="{ row }">
            <template v-if="row.plan && row.plan.enabled">
              <div>{{ planText(row.plan) }}</div>
              <div class="muted sub">
                {{ row.plan.keyHolder === 'CUSTOMER' ? '客户自持钥匙' : '平台钥匙' }}
                · {{ row.plan.includeAttachments ? '含附件原文件' : '附件只存清单' }}
              </div>
            </template>
            <span v-else class="muted">{{ row.plan ? '已关闭' : '未开通' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近一次成功" width="170">
          <template #default="{ row }">
            <div>{{ formatTime(row.lastSuccess?.startedAt) }}</div>
            <div v-if="row.lastSuccess" class="muted sub">{{ formatBytes(row.lastSuccess.encryptedBytes) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tooltip v-if="row.health === 'LAST_FAILED'" :content="row.lastRun?.errorMessage || ''" placement="top">
              <el-tag type="danger" disable-transitions>最近失败</el-tag>
            </el-tooltip>
            <el-tag v-else :type="HEALTH[row.health].type" disable-transitions>{{ HEALTH[row.health].label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">{{ row.plan ? '修改套餐' : '开通' }}</el-button>
            <el-button link type="primary" :disabled="!row.plan?.enabled" @click="runNow(row)">立即备份</el-button>
            <el-button link @click="openDetail(row)">记录</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 修改套餐 -->
    <el-dialog v-model="edit.visible" :title="`备份套餐 — ${edit.factoryName} (${edit.factoryId})`" width="560px">
      <el-form label-width="110px">
        <el-form-item label="开通">
          <el-switch v-model="edit.form.enabled" active-text="开通" inactive-text="关闭" />
        </el-form-item>
        <template v-if="edit.form.enabled">
          <el-form-item label="备份频率">
            <el-radio-group v-model="edit.form.frequency">
              <el-radio-button value="DAILY">每天</el-radio-button>
              <el-radio-button value="WEEKLY">每周</el-radio-button>
              <el-radio-button value="MONTHLY">每月</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="保留期">
            <el-radio-group v-model="edit.form.retentionDays">
              <el-radio-button v-for="d in RETENTION_OPTIONS" :key="d" :value="d">{{ retentionLabel(d) }}</el-radio-button>
            </el-radio-group>
            <div class="hint">同时保留约 {{ copiesKept }} 份，超过保留期的自动清理（最新一份始终保留）。</div>
          </el-form-item>
          <el-form-item label="附件原文件">
            <el-switch v-model="edit.form.includeAttachments" active-text="一起备份" inactive-text="只存清单" />
            <div class="hint">图片、录音等原文件可能比数据本身大很多，按合同约定开启。</div>
          </el-form-item>
          <el-form-item label="加密钥匙">
            <el-radio-group v-model="edit.form.keyHolder">
              <el-radio value="PLATFORM">平台钥匙（默认）</el-radio>
              <el-radio value="CUSTOMER">客户自持钥匙</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="edit.form.keyHolder === 'CUSTOMER'" label="客户公钥">
            <el-input v-model="edit.form.customerRecipient" placeholder="age1 开头，共 62 位" />
            <el-alert type="warning" :closable="false" show-icon class="key-warning">
              <template #title>
                用客户自己的钥匙加密后，平台也解不开；客户丢了私钥，这些备份就无法恢复。
                客户可在自己的「数据备份」页面下载备份件。
              </template>
            </el-alert>
            <div v-if="edit.form.customerRecipient && !recipientValid" class="error-text">公钥格式不对：应以 age1 开头，共 62 位小写字母和数字。</div>
          </el-form-item>
        </template>
        <el-form-item label="修改原因" required>
          <el-select v-model="edit.reasonKind" placeholder="请选择" class="full">
            <el-option v-for="r in REASONS" :key="r" :label="r" :value="r" />
          </el-select>
          <el-input v-model="edit.reasonText" :placeholder="edit.reasonKind === '其他' ? '请说明原因' : '合同编号或补充说明（选填）'" class="reason-text" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="edit.visible = false">取消</el-button>
        <el-button type="primary" :loading="edit.saving" :disabled="!canSave" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 记录 -->
    <el-drawer v-model="detail.visible" :title="`备份记录 — ${detail.data?.factoryName || ''} (${detail.factoryId})`" size="760px">
      <div v-loading="detail.loading">
        <template v-if="detail.data">
          <h4>执行记录（最近 50 次）</h4>
          <el-table :data="detail.data.runs" size="small" border>
            <el-table-column label="开始" width="160"><template #default="{ row }">{{ formatTime(row.startedAt) }}</template></el-table-column>
            <el-table-column label="触发" width="80"><template #default="{ row }">{{ triggerLabel(row.triggerSource) }}</template></el-table-column>
            <el-table-column label="结果" width="80">
              <template #default="{ row }">
                <el-tag size="small" disable-transitions :type="RUN_TAG[row.status as BackupRunStatus]">{{ RUN_STATUS_LABELS[row.status as BackupRunStatus] }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="90"><template #default="{ row }">{{ formatBytes(row.encryptedBytes) }}</template></el-table-column>
            <el-table-column label="说明" min-width="200">
              <template #default="{ row }">
                <span v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span>
                <span v-else class="muted mono">{{ row.objectKey || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>

          <h4>异地现存备份件</h4>
          <el-alert v-if="detail.data.storedError" type="error" :closable="false" :title="detail.data.storedError" />
          <el-table v-else :data="detail.data.stored" size="small" border>
            <el-table-column label="时间" width="160"><template #default="{ row }">{{ formatTime(row.takenAt) }}</template></el-table-column>
            <el-table-column label="大小" width="100"><template #default="{ row }">{{ formatBytes(row.size) }}</template></el-table-column>
            <el-table-column label="对象" min-width="260"><template #default="{ row }"><span class="mono">{{ row.key }}</span></template></el-table-column>
            <template #empty><div class="muted">还没有备份件。</div></template>
          </el-table>

          <h4>套餐改动记录</h4>
          <el-table :data="detail.data.changes" size="small" border>
            <el-table-column label="时间" width="160"><template #default="{ row }">{{ formatTime(row.changedAt) }}</template></el-table-column>
            <el-table-column label="操作人" width="110">
              <template #default="{ row }">{{ row.actorType === 'SYSTEM' ? '系统' : `平台 #${row.actorId}` }}</template>
            </el-table-column>
            <el-table-column label="改动" min-width="220">
              <template #default="{ row }">{{ describeChange(row.beforePlan, row.afterPlan) }}</template>
            </el-table-column>
            <el-table-column label="原因" min-width="140"><template #default="{ row }">{{ row.reason || '—' }}</template></el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import {
  FREQUENCY_LABELS,
  RETENTION_OPTIONS,
  RUN_STATUS_LABELS,
  formatBytes,
  formatTime,
  getTenantBackupDetail,
  listTenantBackups,
  retentionLabel,
  runTenantBackupNow,
  triggerLabel,
  updateTenantBackupPlan,
  type BackupFactoryDetail,
  type BackupFactoryRow,
  type BackupFrequency,
  type BackupHealth,
  type BackupKeyHolder,
  type BackupPlan,
  type BackupRunStatus,
} from '@/api/tenantBackup';

type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary';

const HEALTH: Record<BackupHealth, { label: string; type: TagType }> = {
  NOT_ENABLED: { label: '未开通', type: 'info' },
  WAITING_FIRST_RUN: { label: '等待首次备份', type: 'primary' },
  OK: { label: '正常', type: 'success' },
  OVERDUE: { label: '逾期未备份', type: 'warning' },
  LAST_FAILED: { label: '最近失败', type: 'danger' },
};

const RUN_TAG: Record<BackupRunStatus, TagType> = {
  RUNNING: 'primary',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  SKIPPED: 'info',
};

const REASONS = ['新签合同', '合同变更', '合同到期', '客户申请', '其他'];
const AGE_RECIPIENT = /^age1[02-9ac-hj-np-z]{58}$/;

const rows = ref<BackupFactoryRow[]>([]);
const loading = ref(false);
const keyword = ref('');
const onlyEnabled = ref(false);

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  return rows.value.filter((r) => {
    if (onlyEnabled.value && !r.plan?.enabled) return false;
    if (!k) return true;
    return r.factoryId.toLowerCase().includes(k) || (r.factoryName || '').toLowerCase().includes(k);
  });
});
const enabledCount = computed(() => rows.value.filter((r) => r.plan?.enabled).length);
const attentionCount = computed(() => rows.value.filter((r) => r.health === 'OVERDUE' || r.health === 'LAST_FAILED').length);

function planText(p: BackupPlan): string {
  return `${FREQUENCY_LABELS[p.frequency]} × 保留 ${retentionLabel(p.retentionDays)}`;
}

async function load() {
  loading.value = true;
  try {
    const res = await listTenantBackups();
    rows.value = res.success && res.data ? res.data : [];
  } finally {
    loading.value = false;
  }
}

// ------------------------------------------------------------------ 修改套餐
const edit = reactive({
  visible: false,
  saving: false,
  factoryId: '',
  factoryName: '',
  reasonKind: '',
  reasonText: '',
  form: {
    enabled: true,
    frequency: 'DAILY' as BackupFrequency,
    retentionDays: 7,
    includeAttachments: false,
    keyHolder: 'PLATFORM' as BackupKeyHolder,
    customerRecipient: '',
  },
});

const recipientValid = computed(() => AGE_RECIPIENT.test(edit.form.customerRecipient.trim()));
const copiesKept = computed(() => {
  const every = edit.form.frequency === 'DAILY' ? 1 : edit.form.frequency === 'WEEKLY' ? 7 : 30;
  return Math.max(1, Math.ceil(edit.form.retentionDays / every));
});
const reason = computed(() => {
  if (!edit.reasonKind) return '';
  if (edit.reasonKind === '其他') return edit.reasonText.trim();
  return edit.reasonText.trim() ? `${edit.reasonKind}：${edit.reasonText.trim()}` : edit.reasonKind;
});
const canSave = computed(() => {
  if (!reason.value) return false;
  if (edit.form.enabled && edit.form.keyHolder === 'CUSTOMER' && !recipientValid.value) return false;
  return true;
});

function openEdit(row: BackupFactoryRow) {
  const p = row.plan;
  edit.factoryId = row.factoryId;
  edit.factoryName = row.factoryName || row.factoryId;
  edit.form.enabled = p ? p.enabled : true;
  edit.form.frequency = p?.frequency || 'DAILY';
  edit.form.retentionDays = p?.retentionDays || 7;
  edit.form.includeAttachments = p?.includeAttachments || false;
  edit.form.keyHolder = p?.keyHolder || 'PLATFORM';
  edit.form.customerRecipient = p?.customerRecipient || '';
  edit.reasonKind = p ? '' : '新签合同';
  edit.reasonText = '';
  edit.visible = true;
}

async function save() {
  edit.saving = true;
  try {
    const res = await updateTenantBackupPlan(edit.factoryId, {
      ...edit.form,
      customerRecipient: edit.form.keyHolder === 'CUSTOMER' ? edit.form.customerRecipient.trim() : null,
      reason: reason.value,
    });
    if (res.success) {
      ElMessage.success(`「${edit.factoryName}」的备份套餐已保存`);
      edit.visible = false;
      await load();
    }
  } finally {
    edit.saving = false;
  }
}

async function runNow(row: BackupFactoryRow) {
  const name = row.factoryName || row.factoryId;
  try {
    await ElMessageBox.confirm(`现在为「${name}」(${row.factoryId}) 执行一次备份？在后台进行，几分钟后在「记录」里查看结果。`,
      '立即备份', { confirmButtonText: '开始', cancelButtonText: '取消' });
  } catch {
    return;
  }
  const res = await runTenantBackupNow(row.factoryId);
  if (res.success) ElMessage.success(res.data || '已开始');
}

// ------------------------------------------------------------------ 记录
const detail = reactive({
  visible: false,
  loading: false,
  factoryId: '',
  data: null as BackupFactoryDetail | null,
});

async function openDetail(row: BackupFactoryRow) {
  detail.factoryId = row.factoryId;
  detail.data = null;
  detail.visible = true;
  detail.loading = true;
  try {
    const res = await getTenantBackupDetail(row.factoryId);
    detail.data = res.success && res.data ? res.data : null;
  } finally {
    detail.loading = false;
  }
}

interface PlanJson {
  enabled: boolean;
  frequency: BackupFrequency;
  retentionDays: number;
  includeAttachments: boolean;
  keyHolder: BackupKeyHolder;
}

function summarize(json?: string | null): string {
  if (!json) return '无';
  const p = JSON.parse(json) as PlanJson;
  if (!p.enabled) return '关闭';
  return `${FREQUENCY_LABELS[p.frequency]} × ${retentionLabel(p.retentionDays)}`
    + (p.includeAttachments ? ' · 含附件' : '')
    + (p.keyHolder === 'CUSTOMER' ? ' · 客户钥匙' : '');
}

function describeChange(before?: string | null, after?: string | null): string {
  return `${summarize(before)} → ${summarize(after)}`;
}

onMounted(load);
</script>

<style scoped>
.tenant-backups-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-header h2 { margin: 0; font-size: 18px; }
.intro { margin-bottom: 12px; }
.toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 12px; flex-wrap: wrap; }
.search { width: 240px; }
.muted { color: #909399; }
.sub { font-size: 12px; margin-top: 2px; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.hint { font-size: 12px; color: #909399; line-height: 1.5; width: 100%; }
.key-warning { margin-top: 8px; }
.error-text { color: #f56c6c; font-size: 12px; }
.full { width: 100%; }
.reason-text { margin-top: 8px; }
h4 { margin: 16px 0 8px; }
</style>
