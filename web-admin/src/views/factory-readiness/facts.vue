<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  FACT_STATUS_LABEL, describeFactSource, getFactHistory, resolveFactConflict,
  type FactChoice, type FactHistory,
} from '@/api/factoryConfirmedFacts'
import { useAuthStore } from '@/store/modules/auth'

// 本厂已确认资料: 与已确认事实不同的新回答, 逐条选择用哪个来源; 下方是每一项的版本历史。
// 选择之前已确认事实不会被改写; 选择之后旧值只改状态、保留在历史里。
const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)
const data = ref<FactHistory | null>(null)
const loading = ref(false)
const error = ref('')
const saving = ref('')

async function load() {
  if (!factoryId.value) { error.value = '当前账号没有所属工厂'; return }
  loading.value = true; error.value = ''
  try { data.value = await getFactHistory(factoryId.value) }
  catch (failure) { data.value = null; error.value = failure instanceof Error ? failure.message : '已确认资料读取失败' }
  finally { loading.value = false }
}

async function choose(field: string, choice: FactChoice) {
  saving.value = field
  try {
    await resolveFactConflict(factoryId.value, field, choice)
    ElMessage.success(choice === 'USE_NEW' ? '已改用新回答，旧值保留在历史里' : '已保留原已确认值，新回答记为否决')
    await load()
  } catch (failure) {
    ElMessage({ message: failure instanceof Error ? failure.message : '来源选择保存失败', type: 'error', duration: 0, showClose: true })
  } finally { saving.value = '' }
}

onMounted(load)
</script>

<template>
  <div class="facts-page" data-testid="factory-facts-page">
    <div class="head">
      <h2>已确认资料与版本历史</h2>
      <el-button :loading="loading" data-testid="facts-refresh" @click="load">刷新</el-button>
    </div>
    <el-alert v-if="error" type="error" :closable="false" :title="error" data-testid="facts-error" />
    <el-card v-if="data" shadow="never">
      <template #header><strong>需要你选择来源的项目（{{ data.conflicts.length }}）</strong></template>
      <el-empty v-if="!data.conflicts.length" description="没有待选择的冲突" data-testid="facts-no-conflicts" />
      <div v-for="item in data.conflicts" :key="item.pendingId" class="conflict" :data-testid="`conflict-${item.field}`">
        <div class="title">{{ item.label || '其他事项' }}</div>
        <div class="pair">
          <div><span class="tag">已确认</span> {{ item.confirmedValue }}
            <small>{{ describeFactSource('USER_ANSWER', item.confirmedSource) }}</small></div>
          <div><span class="tag new">新回答</span> {{ item.newValue }}
            <small>{{ describeFactSource('USER_ANSWER', item.newSource) }}</small></div>
        </div>
        <div class="actions">
          <el-button :loading="saving === item.field" @click="choose(item.field, 'KEEP_CONFIRMED')">保留已确认值</el-button>
          <el-button type="primary" :loading="saving === item.field" @click="choose(item.field, 'USE_NEW')">改用新回答</el-button>
        </div>
      </div>
    </el-card>
    <el-card v-if="data" shadow="never">
      <template #header><strong>版本历史</strong></template>
      <el-table :data="data.history" data-testid="facts-history" empty-text="还没有已确认资料">
        <el-table-column label="事项" min-width="140"><template #default="{ row }">{{ row.label || '其他事项' }}</template></el-table-column>
        <el-table-column label="内容" prop="value" min-width="160" />
        <el-table-column label="版本" width="70"><template #default="{ row }">第{{ row.version }}版</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }">{{ FACT_STATUS_LABEL[row.status as keyof typeof FACT_STATUS_LABEL] }}</template></el-table-column>
        <el-table-column label="来源" min-width="200"><template #default="{ row }">{{ describeFactSource(row.sourceKind, row.sourceRef) }}</template></el-table-column>
        <el-table-column label="记录时间" prop="createdAt" min-width="160" />
        <el-table-column label="选定人" prop="decidedBy" min-width="120" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.facts-page { display: flex; flex-direction: column; gap: 16px; padding: 16px; }
.head { align-items: center; display: flex; justify-content: space-between; }
.head h2 { font-size: 20px; margin: 0; }
.conflict { border-bottom: 1px solid var(--el-border-color-lighter); padding: 12px 0; }
.title { font-weight: 600; margin-bottom: 6px; }
.pair { display: grid; gap: 8px; grid-template-columns: 1fr 1fr; }
.pair small { color: var(--el-text-color-secondary); display: block; }
.tag { background: var(--el-fill-color); border-radius: 4px; margin-right: 6px; padding: 0 6px; }
.tag.new { background: var(--el-color-warning-light-9); }
.actions { display: flex; gap: 8px; margin-top: 8px; }
@media (max-width: 560px) { .pair { grid-template-columns: 1fr; } }
</style>
