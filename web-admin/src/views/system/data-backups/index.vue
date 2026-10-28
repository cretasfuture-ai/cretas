<template>
  <div class="data-backups-page">
    <div class="page-header">
      <h2>数据备份</h2>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never" class="plan-card">
      <template v-if="plan && plan.enabled">
        <div class="plan-line">
          <span class="plan-main">{{ FREQUENCY_LABELS[plan.frequency] }}备份，保留 {{ retentionLabel(plan.retentionDays) }}</span>
          <el-tag size="small" disable-transitions>{{ plan.keyHolder === 'CUSTOMER' ? '本厂自持钥匙' : '平台钥匙加密' }}</el-tag>
          <el-tag size="small" type="info" disable-transitions>{{ plan.includeAttachments ? '含图片录音原文件' : '图片录音只存清单' }}</el-tag>
        </div>
        <div class="muted">
          每次备份导出本厂全部业务数据，加密后存到异地，超过保留期自动清理。
          <template v-if="plan.keyHolder === 'CUSTOMER'">备份件用本厂提供的钥匙加密，可在下方下载，用本厂私钥解开。</template>
          <template v-else>备份件用平台钥匙加密，需要取回某一天的数据时，请联系平台解密后交付。</template>
        </div>
      </template>
      <div v-else class="muted">
        本厂尚未开通单独的数据备份（平台整库灾备照常进行）。如需开通或调整，请联系平台。
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="runs" stripe border>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="方式" width="90">
          <template #default="{ row }">{{ triggerLabel(row.triggerSource) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" disable-transitions :type="RUN_TAG[row.status as BackupRunStatus]">{{ RUN_STATUS_LABELS[row.status as BackupRunStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatBytes(row.encryptedBytes) }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="220">
          <template #default="{ row }">
            <span v-if="row.status === 'FAILED'" class="error-text">备份未成功，平台已收到提醒并会处理。</span>
            <span v-else-if="row.status === 'SKIPPED'" class="muted">{{ row.errorMessage || '未执行' }}</span>
            <span v-else-if="row.status === 'RUNNING'" class="muted">正在备份…</span>
            <span v-else class="muted">保留 {{ retentionLabel(row.retentionDays) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="下载" width="110">
          <template #default="{ row }">
            <el-button v-if="row.status === 'SUCCEEDED' && row.keyHolder === 'CUSTOMER'" link type="primary"
              :loading="downloading === row.id" @click="download(row)">下载</el-button>
            <span v-else-if="row.status === 'SUCCEEDED'" class="muted">找平台取</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">还没有备份记录。</div>
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  FREQUENCY_LABELS,
  RUN_STATUS_LABELS,
  downloadFactoryBackup,
  formatBytes,
  formatTime,
  getFactoryBackups,
  retentionLabel,
  triggerLabel,
  type BackupPlan,
  type BackupRun,
  type BackupRunStatus,
} from '@/api/tenantBackup';

type TagType = 'success' | 'danger' | 'info' | 'primary';
const RUN_TAG: Record<BackupRunStatus, TagType> = {
  RUNNING: 'primary',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  SKIPPED: 'info',
};

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const plan = ref<BackupPlan | null>(null);
const runs = ref<BackupRun[]>([]);
const loading = ref(false);
const downloading = ref<number | null>(null);

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await getFactoryBackups(factoryId.value);
    plan.value = res.success && res.data ? res.data.plan || null : null;
    runs.value = res.success && res.data ? res.data.runs || [] : [];
  } finally {
    loading.value = false;
  }
}

async function download(row: BackupRun) {
  if (!factoryId.value) return;
  downloading.value = row.id;
  try {
    const blob = await downloadFactoryBackup(factoryId.value, row.id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = row.objectKey ? row.objectKey.substring(row.objectKey.lastIndexOf('/') + 1) : `backup-${row.id}.zip.age`;
    a.click();
    URL.revokeObjectURL(url);
  } finally {
    downloading.value = null;
  }
}

onMounted(load);
</script>

<style scoped>
.data-backups-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-header h2 { margin: 0; font-size: 18px; }
.plan-card { margin-bottom: 12px; }
.plan-line { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.plan-main { font-size: 15px; font-weight: 600; }
.muted { color: #909399; }
.error-text { color: #f56c6c; }
.empty { padding: 24px 0; color: #909399; }
</style>
