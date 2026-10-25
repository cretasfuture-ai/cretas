<template>
  <div class="platform-access-log-page">
    <div class="page-header">
      <h2>平台访问记录</h2>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon class="intro">
      <template #title>
        平台工作人员每次查看本厂数据，系统都会自动记录：谁、什么时候、看了哪个功能。
        这里只显示平台人员的访问，本厂员工自己的操作不在此列。标「后台自动」的是页面自己发出的请求（如刷新未读消息数），不含业务数据。
      </template>
    </el-alert>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe border>
        <el-table-column label="访问时间" width="160">
          <template #default="{ row }">{{ formatTime(row.accessedAt) }}</template>
        </el-table-column>
        <el-table-column label="访问人" min-width="120">
          <template #default="{ row }">
            <span v-if="row.actorUsername">{{ row.actorUsername }}</span>
            <span v-else-if="row.actorUserId" class="muted">#{{ row.actorUserId }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="身份" min-width="100">
          <template #default="{ row }">{{ roleLabel(row.actorRole) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="methodTagType(row)">{{ methodLabel(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="访问内容" min-width="220">
          <template #default="{ row }">
            <div :class="{ muted: describeAccessPath(row.requestPath).background }">
              {{ describeAccessPath(row.requestPath).label }}
            </div>
            <div class="path muted">
              {{ row.requestPath }}<span v-if="row.queryParamNames">（参数：{{ row.queryParamNames }}）</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.responseStatus != null && row.responseStatus < 400" size="small" type="success">成功</el-tag>
            <el-tag v-else-if="row.responseStatus != null" size="small" type="danger">被拒绝</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="来源 IP" width="140">
          <template #default="{ row }">{{ row.clientIp || '—' }}</template>
        </el-table-column>
        <template #empty>
          <div class="empty">平台工作人员还没有访问过本厂数据。</div>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="load"
        @size-change="load"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { listPlatformAccessLog, type PlatformAccessLogEntry } from '@/api/platformAccessLog';
import { describeAccessPath } from './pathLabels';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const rows = ref<PlatformAccessLogEntry[]>([]);
const loading = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);

const ROLE_LABELS: Record<string, string> = {
  super_admin: '平台管理员',
  platform_admin: '平台管理员',
  platform_super_admin: '平台管理员',
  developer: '平台技术人员',
};

function roleLabel(role?: string | null): string {
  if (!role) return '—';
  return ROLE_LABELS[role] || role;
}

// 页面自动发出的请求 (未读消息数、使用记录上报) 不是有人在看或改业务数据, 单独标「后台自动」
function methodLabel(row: PlatformAccessLogEntry): string {
  if (describeAccessPath(row.requestPath).background) return '后台自动';
  return row.httpMethod === 'GET' ? '查看' : '修改';
}

function methodTagType(row: PlatformAccessLogEntry): 'info' | 'warning' | 'primary' {
  if (describeAccessPath(row.requestPath).background) return 'info';
  return row.httpMethod === 'GET' ? 'primary' : 'warning';
}

function formatTime(s?: string | null): string {
  return s ? s.replace('T', ' ').substring(0, 19) : '—';
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listPlatformAccessLog(factoryId.value, page.value, size.value);
    if (res.success && res.data) {
      rows.value = res.data.content || [];
      total.value = res.data.totalElements || 0;
    } else {
      rows.value = [];
      total.value = 0;
    }
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.platform-access-log-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-header h2 { margin: 0; font-size: 18px; }
.intro { margin-bottom: 12px; }
.path { font-family: Consolas, monospace; font-size: 11px; word-break: break-all; margin-top: 2px; }
.muted { color: #909399; }
.empty { padding: 24px 0; color: #909399; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
