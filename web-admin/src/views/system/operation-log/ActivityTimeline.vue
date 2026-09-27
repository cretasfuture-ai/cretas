<template>
  <el-card shadow="never" style="margin-top: 16px">
    <template #header>
      <div class="header">
        <strong>用户操作时间线</strong>
        <el-button @click="load(true)" :loading="loading">查询</el-button>
      </div>
    </template>
    <el-form inline @submit.prevent="load(true)">
      <el-form-item label="用户 ID"><el-input v-model="userId" placeholder="全部" clearable style="width: 120px" /></el-form-item>
      <el-form-item label="来源">
        <el-select v-model="source" placeholder="全部" clearable style="width: 120px">
          <el-option label="页面" value="screen" /><el-option label="点击" value="click" />
          <el-option label="输入" value="input" /><el-option label="拦截" value="block" />
          <el-option label="接口" value="api" />
        </el-select>
      </el-form-item>
      <el-form-item label="关联 ID"><el-input v-model="correlationId" placeholder="可选" clearable style="width: 250px" /></el-form-item>
      <el-form-item label="时间"><el-date-picker v-model="range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" range-separator="至" start-placeholder="开始" end-placeholder="结束" /></el-form-item>
    </el-form>
    <el-table :data="rows" v-loading="loading" size="small" border>
      <el-table-column label="发生时间" width="190">
        <template #default="{ row }">{{ new Date(row.occurred_at).toLocaleString() }}</template>
      </el-table-column>
      <el-table-column prop="user_id" label="用户 ID" width="95" />
      <el-table-column prop="client" label="终端" width="105" />
      <el-table-column prop="source" label="来源" width="85" />
      <el-table-column prop="action" label="动作" min-width="140" />
      <el-table-column prop="route" label="页面或接口" min-width="230" show-overflow-tooltip />
      <el-table-column label="细节" min-width="260">
        <template #default="{ row }"><code>{{ describe(row.detail) }}</code></template>
      </el-table-column>
      <el-table-column prop="client_action_id" label="操作 ID" min-width="180" show-overflow-tooltip />
      <el-table-column prop="correlation_id" label="请求 ID" min-width="180" show-overflow-tooltip />
    </el-table>
    <div class="footer">
      <span>本页 {{ rows.length }} 条。客户端动作是操作上报；接口行是服务端观察到的状态，细节含 asyncStarted=true 时只代表异步连接已启动。业务变更请结合上方操作日志核对。</span>
      <el-button :disabled="offset === 0" @click="offset -= 100; load(false)">上一页</el-button>
      <el-button :disabled="rows.length < 100" @click="offset += 100; load(false)">下一页</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

type Row = { id: number; user_id: number; client: string; source: string; action: string;
  route: string; detail: string; client_action_id: string | null; correlation_id: string | null;
  occurred_at: string };
const factoryId = computed(() => useAuthStore().factoryId);
const userId = ref('');
const source = ref('');
const correlationId = ref('');
const range = ref<[string, string] | null>(null);
const rows = ref<Row[]>([]);
const loading = ref(false);
let offset = 0;

function describe(raw: string): string {
  try { return JSON.stringify(JSON.parse(raw)); } catch { return raw || '{}'; }
}

async function load(reset: boolean): Promise<void> {
  if (reset) offset = 0;
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, string | number> = { limit: 100, offset };
    if (userId.value.trim()) {
      if (!/^\d+$/.test(userId.value.trim())) { ElMessage.warning('用户 ID 必须是数字'); return; }
      params.userId = userId.value.trim();
    }
    if (source.value) params.source = source.value;
    if (correlationId.value.trim()) params.correlationId = correlationId.value.trim();
    if (range.value) {
      params.start = new Date(range.value[0]).toISOString();
      params.end = new Date(range.value[1]).toISOString();
    }
    const response = await get<Row[]>(`/${factoryId.value}/activity-events`, { params });
    rows.value = response.data || [];
  } catch {
    ElMessage.error('加载用户操作时间线失败');
  } finally {
    loading.value = false;
  }
}

onMounted((): void => { void load(true); });
</script>

<style scoped>
.header, .footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.footer { margin-top: 12px; color: #606266; font-size: 12px; }
code { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
