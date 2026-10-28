<!-- SchedulerRunHistory.vue — 某个定时任务的最近一次结果 + 运行历史 (S6-03), 未知结果的人工裁定 (S6-06), 单次 Token 成本 (S6-11) -->
<template>
  <el-drawer v-model="visible" :title="`运行记录 — ${taskCode}`" size="780px" destroy-on-close @open="reload">
    <div v-loading="loading" class="run-history">
      <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="loadError" />

      <section class="latest" data-testid="scheduler-latest-run">
        <h4>最近一次结果</h4>
        <el-empty v-if="!loadError && !latest" description="还没有运行记录" :image-size="60" />
        <template v-else-if="latest">
          <div class="latest-head">
            <el-tag :type="statusType(latest.effectiveStatus, latest)" data-testid="latest-status">
              {{ statusLabel(latest) }}
            </el-tag>
            <span class="muted">{{ latest.startedAt }} → {{ latest.finishedAt }}</span>
          </div>
          <p v-if="latest.summary" class="summary">{{ latest.summary }}</p>
          <p v-if="latest.error" class="error-text">{{ latest.error }}</p>
          <p class="muted">
            Token：<span data-testid="latest-tokens">{{ tokenText(latest) }}</span>
          </p>
          <details v-if="latest.hasDataSnapshot">
            <summary>数据快照</summary>
            <pre class="snapshot">{{ JSON.stringify(latest.dataSnapshot, null, 2) }}</pre>
          </details>
          <p v-else-if="latest.snapshotTruncated" class="muted">结果过大，未保存数据快照。</p>
        </template>
      </section>

      <section>
        <h4>历史</h4>
        <el-table :data="runs" size="small" empty-text="暂无运行记录">
          <el-table-column label="开始时间" prop="startedAt" width="170" />
          <el-table-column label="结果" width="130">
            <template #default="{ row }">
              <el-tag :type="statusType(row.effectiveStatus, row)" size="small">{{ statusLabel(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="摘要" min-width="160">
            <template #default="{ row }">{{ row.summary || row.error || '—' }}</template>
          </el-table-column>
          <el-table-column label="Token" width="150">
            <template #default="{ row }">{{ tokenText(row) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="canResolve(row)" type="primary" link size="small" @click="openResolve(row)">
                裁定结果
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="total > pageSize" class="pager" layout="prev, pager, next" :total="total" :page-size="pageSize"
          :current-page="page" @current-change="onPage"
        />
      </section>
    </div>

    <el-dialog v-model="resolveVisible" title="裁定未知结果" width="460px" append-to-body>
      <p class="muted">
        这次运行没能确认结果。请先去核对业务数据，再选择它实际是成功还是失败，并写明依据。裁定会留下审计记录，不能修改。
      </p>
      <el-form label-width="80px">
        <el-form-item label="裁定为">
          <el-radio-group v-model="resolution">
            <el-radio value="SUCCESS">成功</el-radio>
            <el-radio value="FAILED">失败</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="依据" required>
          <el-input v-model="note" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="例如：已核对库存，确实已扣减" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" :loading="resolving" :disabled="!resolution || !note.trim()" @click="submitResolve">
          确认裁定
        </el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getSchedulerRuns, getLatestSchedulerRun, resolveSchedulerRun } from '@/api/canvasApi'
import type { SchedulerRun } from '@/types/canvas'
import { schedulerRunStatusLabel, schedulerRunStatusType, schedulerRunTokenText } from './schedulerRunDisplay'

const props = defineProps<{ factoryId: string; taskCode: string }>()
const visible = defineModel<boolean>({ default: false })

const loading = ref(false)
const loadError = ref('')
const latest = ref<SchedulerRun | null>(null)
const runs = ref<SchedulerRun[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20

const resolveVisible = ref(false)
const resolving = ref(false)
const target = ref<SchedulerRun | null>(null)
const resolution = ref<'SUCCESS' | 'FAILED' | ''>('')
const note = ref('')

const statusLabel = schedulerRunStatusLabel
const statusType = (status: string, run: SchedulerRun) => schedulerRunStatusType(status, run)
const tokenText = schedulerRunTokenText

function canResolve(run: SchedulerRun): boolean {
  return run.status === 'UNKNOWN' && run.resolution === null
}

async function reload() {
  if (!props.factoryId || !props.taskCode) return
  loading.value = true
  loadError.value = ''
  try {
    const [l, h] = await Promise.all([
      getLatestSchedulerRun(props.factoryId, props.taskCode),
      getSchedulerRuns(props.factoryId, props.taskCode, page.value, pageSize),
    ])
    latest.value = l.data ?? null
    runs.value = h.data?.content ?? []
    total.value = h.data?.totalElements ?? 0
  } catch (e) {
    // 读不到就明说读不到, 不当作「没有运行记录」。
    loadError.value = '加载运行记录失败: ' + (e instanceof Error ? e.message : '未知错误')
    latest.value = null
    runs.value = []
  } finally {
    loading.value = false
  }
}

function onPage(p: number) {
  page.value = p
  void reload()
}

function openResolve(run: SchedulerRun) {
  target.value = run
  resolution.value = ''
  note.value = ''
  resolveVisible.value = true
}

async function submitResolve() {
  if (!target.value || !resolution.value) return
  resolving.value = true
  try {
    await resolveSchedulerRun(props.factoryId, target.value.runId, resolution.value, note.value.trim())
    ElMessage.success('已记录裁定')
    resolveVisible.value = false
    await reload()
  } finally {
    resolving.value = false
  }
}
</script>

<style scoped>
.run-history { display: flex; flex-direction: column; gap: 20px; }
.run-history h4 { margin: 0 0 8px; font-size: 14px; }
.latest-head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
.summary { margin: 4px 0; }
.error-text { color: var(--el-color-danger); margin: 4px 0; word-break: break-all; }
.snapshot {
  max-height: 260px; overflow: auto; background: var(--el-fill-color-light);
  padding: 8px; border-radius: 4px; font-size: 12px;
}
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
