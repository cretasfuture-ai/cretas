<script setup lang="ts">
/**
 * B8 生产进度数字打屏看板
 * 客户原话 4802-4811s: "做一个数字打屏,看到当天所有商品工序生产的一个进度"
 *
 * 深色全屏大字风格,30秒自动刷新,适合挂大屏展示
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { get } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { ElMessage } from 'element-plus'

interface ProcessRow {
  processName: string
  plannedQty: number
  reportedQty: number
  pct: number
  status: 'DONE' | 'IN_PROGRESS' | 'PENDING'
}
interface PlanRow {
  planId: string
  planNumber: string
  productName: string
  customerName: string
  plannedQuantity: number
  reportedQuantity: number
  progressPct: number
  status: 'DONE' | 'IN_PROGRESS' | 'PENDING'
  processes: ProcessRow[]
}
interface Summary {
  totalPlans: number
  totalWorkOrders: number
  completedWorkOrders: number
  inProgressWorkOrders: number
  pendingWorkOrders: number
  overallProgressPct: number
}
interface ProgressResp {
  date: string
  summary: Summary
  plans: PlanRow[]
}

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)

const loading = ref(false)
const data = ref<ProgressResp | null>(null)
const now = ref(new Date())
const errorMsg = ref('')

/**
 * 🔴 「没有读数」和「读数是 0」必须分得开。
 *
 * 当天一条计划都没排时, 后端如实返回 summary 全 0 —— 那是**定义上的** 0,
 * 不是一个测量结果。照旧把它印成大字, 墙上就是
 * 「总计划 0 / 已完成 0 / 整体进度 0%」, 车间读到的是「今天产线停了」。
 * ⇒ 零计划时只出空态, 不出那一排大字。
 *
 * ⚠️ 判据是 totalPlans, 不是 plans.length —— 两者理论上同源, 但顶部大字的
 *    含义由 summary 定义, 用哪个数决定要不要显示 summary 就该问哪个数。
 */
const hasPlans = computed(() => (data.value?.summary?.totalPlans ?? 0) > 0)

/**
 * 交货预警(已超期计划数)。数据来自**另一个端点**
 * `GET /{factoryId}/production-plans/delivery-warnings` (已分 OVERDUE/URGENT/WARN/NORMAL),
 * 与主看板互不影响 —— 它挂了不许拖垮整块墙, 它也不许借主看板的成功冒充自己成功。
 *
 * 三态, ⛔ 不是两态:
 *   null + !failed  = 还没问到 (首帧)          → '—'
 *   failed          = 问了但读不到              → '—' + 「读取失败」
 *   number          = 真读数 (0 是合法读数)     → 数字
 *
 * ⛔ 失败绝不许落成 0。「已超期 0」是车间最想听到的一句话,
 *   不能由一个失败的请求替我们说出来。
 */
const overdueCount = ref<number | null>(null)
const overdueFailed = ref(false)
const overdueText = computed(() =>
  overdueFailed.value || overdueCount.value === null ? '—' : String(overdueCount.value),
)

let timer: ReturnType<typeof setInterval> | null = null
let clockTimer: ReturnType<typeof setInterval> | null = null

const today = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const nowText = computed(() => {
  const d = now.value
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
})

async function fetchData() {
  if (!factoryId.value) {
    errorMsg.value = '未获取到工厂ID'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await get<ProgressResp>(`/${factoryId.value}/dashboard/production-progress`, {
      params: { date: today.value }
    })
    data.value = res.data ?? null
  } catch (e) {
    const msg = (e as Error)?.message || '加载失败'
    errorMsg.value = msg
    ElMessage.error('加载生产进度失败: ' + msg)
  } finally {
    loading.value = false
  }
}

interface DeliveryWarn {
  planId: string
  planNumber: string
  warnLevel: 'OVERDUE' | 'URGENT' | 'WARN' | 'NORMAL'
  daysUntilDeadline: number | null
}

async function fetchOverdue() {
  if (!factoryId.value) return
  try {
    const res = await get<DeliveryWarn[]>(`/${factoryId.value}/production-plans/delivery-warnings`, {
      params: { windowDays: 7 }
    })
    const list = res.data
    // ⛔ 拿不到数组不许当空数组用 —— `?? []` 会把「接口换了形状」渲染成「零超期」。
    if (!Array.isArray(list)) {
      overdueFailed.value = true
      overdueCount.value = null
      return
    }
    overdueCount.value = list.filter(w => w?.warnLevel === 'OVERDUE').length
    overdueFailed.value = false
  } catch {
    // ⛔ 不弹 toast: 这一格失败不该盖住主看板的错误提示; 失败本身已经写在格子里。
    overdueFailed.value = true
    overdueCount.value = null
  }
}

/** 两条请求各自独立成败 —— 一条挂了另一条照常出数。 */
function refreshAll() {
  void fetchData()
  void fetchOverdue()
}

function statusColor(status: string): string {
  if (status === 'DONE') return '#67c23a'
  if (status === 'IN_PROGRESS') return '#409eff'
  return '#909399'
}
function statusText(status: string): string {
  if (status === 'DONE') return '已完成'
  if (status === 'IN_PROGRESS') return '生产中'
  return '未开始'
}

onMounted(() => {
  refreshAll()
  timer = setInterval(refreshAll, 30000)
  clockTimer = setInterval(() => { now.value = new Date() }, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <div class="dashboard-screen">
    <!-- 顶部 -->
    <div class="header">
      <div class="header-left">
        <div class="title">生产进度数字看板</div>
        <div class="subtitle">Production Progress Dashboard · B8</div>
      </div>
      <div class="header-center">
        <div class="date-big">{{ today }}</div>
        <div class="clock">{{ nowText }}</div>
      </div>
      <div class="header-right">
        <el-button type="primary" :loading="loading" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <!-- Summary Cards —— 🔴 只在**真的有计划**时渲染。
         零计划时这一排全是定义上的 0, 印出来就是在说「今天停产」。见 hasPlans 注释。 -->
    <div class="summary-row" v-if="hasPlans && data && data.summary">
      <div class="summary-card">
        <div class="summary-label">总计划</div>
        <div class="summary-value">{{ data.summary.totalPlans }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">总工单</div>
        <div class="summary-value">{{ data.summary.totalWorkOrders }}</div>
      </div>
      <div class="summary-card done">
        <div class="summary-label">已完成</div>
        <div class="summary-value">{{ data.summary.completedWorkOrders }}</div>
      </div>
      <div class="summary-card progress">
        <div class="summary-label">进行中</div>
        <div class="summary-value">{{ data.summary.inProgressWorkOrders }}</div>
      </div>
      <div class="summary-card pending">
        <div class="summary-label">未开始</div>
        <div class="summary-value">{{ data.summary.pendingWorkOrders }}</div>
      </div>
      <!-- 竞品 10 个 KPI 里我们缺的那个。数来自另一个端点, 所以它有自己的失败态:
           读不到写 '—' + 「读取失败」, ⛔ 永远不写 0。 -->
      <div class="summary-card overdue" :class="{ 'is-stale': overdueFailed }">
        <div class="summary-label">已超期</div>
        <div class="summary-value">{{ overdueText }}</div>
        <div v-if="overdueFailed" class="summary-note">交货预警读取失败</div>
      </div>
      <div class="summary-card overall">
        <div class="summary-label">整体进度</div>
        <div class="summary-value big-pct">{{ data.summary.overallProgressPct }}%</div>
        <el-progress
          :percentage="data.summary.overallProgressPct"
          :stroke-width="12"
          :show-text="false"
          color="#409eff"
        />
      </div>
    </div>

    <!-- 生产计划卡片 -->
    <div class="plans-grid" v-if="data?.plans?.length">
      <div v-for="plan in data.plans" :key="plan.planId" class="plan-card">
        <div class="plan-header">
          <div class="plan-product">{{ plan.productName }}</div>
          <div class="plan-status" :style="{ background: statusColor(plan.status) }">
            {{ statusText(plan.status) }}
          </div>
        </div>
        <div class="plan-meta">
          <span>{{ plan.planNumber }}</span>
          <span class="sep">·</span>
          <span>客户: {{ plan.customerName }}</span>
        </div>
        <div class="plan-progress">
          <div class="plan-qty">
            <span class="reported">{{ plan.reportedQuantity }}</span>
            <span class="divider"> / </span>
            <span class="planned">{{ plan.plannedQuantity }}</span>
          </div>
          <div class="plan-pct">{{ plan.progressPct }}%</div>
        </div>
        <el-progress
          :percentage="plan.progressPct"
          :stroke-width="18"
          :show-text="false"
          :color="statusColor(plan.status)"
        />
        <div class="processes">
          <div
            v-for="(p, idx) in plan.processes"
            :key="idx"
            class="process-tag"
            :style="{ borderColor: statusColor(p.status), color: statusColor(p.status) }"
          >
            {{ p.processName }} {{ p.pct }}%
          </div>
        </div>
      </div>
    </div>

    <div v-else-if="!loading && data" class="empty-state">
 <div class="empty-icon"></div>
      <div class="empty-text">当天暂无生产计划</div>
      <!-- 超期是**跨天**的事实, 与「今天排没排计划」无关。
           零计划时上面那排大字整体不渲染, 如果这里也不说, 「今天没排计划」
           就会顺带把「有 N 个计划已经超期了」一起藏掉 —— 那是另一种说谎。 -->
      <div v-if="overdueCount !== null && overdueCount > 0" class="empty-overdue">
        另有 {{ overdueCount }} 个生产计划已超期
      </div>
      <div v-else-if="overdueFailed" class="empty-overdue is-stale">
        交货预警读取失败 —— 是否有超期计划本次未知
      </div>
    </div>

    <div v-if="errorMsg" class="error-banner">{{ errorMsg }}</div>
  </div>
</template>

<style scoped>
.dashboard-screen {
  min-height: 100vh;
  background: linear-gradient(180deg, #0a0f1e 0%, #0f172a 100%);
  color: #ffffff;
  padding: 24px 32px;
  font-family: -apple-system, 'Segoe UI', 'PingFang SC', sans-serif;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 20px;
  border-bottom: 2px solid rgba(64, 158, 255, 0.3);
  margin-bottom: 24px;
}
.header-left .title {
  font-size: 36px;
  font-weight: 700;
  color: #ffffff;
  letter-spacing: 2px;
}
.header-left .subtitle {
  font-size: 14px;
  color: #64748b;
  margin-top: 4px;
  letter-spacing: 1px;
}
.header-center {
  text-align: center;
}
.date-big {
  font-size: 32px;
  font-weight: 600;
  color: #409eff;
  letter-spacing: 2px;
}
.clock {
  font-size: 20px;
  color: #94a3b8;
  font-family: 'Courier New', monospace;
  margin-top: 4px;
}
.header-right {
  min-width: 100px;
  text-align: right;
}

.summary-row {
  display: grid;
  /* 6 → 7: 加了「已超期」一格 (交货预警)。 */
  grid-template-columns: repeat(7, 1fr);
  gap: 16px;
  margin-bottom: 28px;
}
.summary-card {
  background: rgba(30, 41, 59, 0.6);
  border: 1px solid rgba(64, 158, 255, 0.2);
  border-radius: 12px;
  padding: 20px 18px;
  text-align: center;
}
.summary-card.done { border-color: rgba(103, 194, 58, 0.5); }
.summary-card.progress { border-color: rgba(64, 158, 255, 0.5); }
.summary-card.pending { border-color: rgba(144, 147, 153, 0.5); }
.summary-card.overdue { border-color: rgba(245, 108, 108, 0.5); }
.summary-card.overdue .summary-value { color: #f56c6c; }
/* 读不到时**不用**红色 —— 红色是「有超期」的意思, 读取失败不是那个意思。 */
.summary-card.overdue.is-stale { border-color: rgba(144, 147, 153, 0.5); }
.summary-card.overdue.is-stale .summary-value { color: #94a3b8; }
.summary-note {
  margin-top: 8px;
  font-size: 12px;
  color: #e6a23c;
  letter-spacing: 0.5px;
}
.summary-card.overall {
  grid-column: span 1;
  border-color: #409eff;
  background: rgba(64, 158, 255, 0.1);
}
.summary-label {
  font-size: 14px;
  color: #94a3b8;
  margin-bottom: 8px;
  letter-spacing: 1px;
}
.summary-value {
  font-size: 42px;
  font-weight: 700;
  color: #ffffff;
  line-height: 1;
}
.summary-value.big-pct { color: #409eff; }
.summary-card.done .summary-value { color: #67c23a; }
.summary-card.progress .summary-value { color: #409eff; }
.summary-card.overall :deep(.el-progress-bar__outer) {
  background-color: rgba(255,255,255,0.1);
  margin-top: 10px;
}

.plans-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 20px;
}
.plan-card {
  background: rgba(30, 41, 59, 0.7);
  border: 1px solid rgba(64, 158, 255, 0.25);
  border-radius: 14px;
  padding: 22px 24px;
  transition: all 0.3s;
}
.plan-card:hover {
  border-color: #409eff;
  transform: translateY(-2px);
}
.plan-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.plan-product {
  font-size: 28px;
  font-weight: 700;
  color: #ffffff;
}
.plan-status {
  padding: 4px 14px;
  border-radius: 16px;
  font-size: 13px;
  font-weight: 600;
  color: #ffffff;
}
.plan-meta {
  color: #64748b;
  font-size: 13px;
  margin-bottom: 14px;
}
.plan-meta .sep { margin: 0 8px; }
.plan-progress {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.plan-qty {
  font-size: 18px;
  color: #cbd5e1;
}
.plan-qty .reported {
  font-size: 32px;
  font-weight: 700;
  color: #409eff;
}
.plan-qty .divider { color: #475569; }
.plan-qty .planned { color: #94a3b8; }
.plan-pct {
  font-size: 42px;
  font-weight: 800;
  color: #409eff;
  line-height: 1;
}
.plan-card :deep(.el-progress-bar__outer) {
  background-color: rgba(255,255,255,0.08);
}
.processes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
.process-tag {
  border: 1px solid;
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 13px;
  font-weight: 600;
  background: rgba(0,0,0,0.2);
}

.empty-state {
  text-align: center;
  padding: 100px 0;
  color: #64748b;
}
.empty-icon { font-size: 80px; margin-bottom: 16px; }
.empty-text { font-size: 22px; }
.empty-overdue {
  margin-top: 14px;
  font-size: 18px;
  color: #f56c6c;
  letter-spacing: 1px;
}
.empty-overdue.is-stale { color: #94a3b8; font-size: 15px; }

.error-banner {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(245, 108, 108, 0.2);
  border: 1px solid #f56c6c;
  color: #fca5a5;
  padding: 12px 24px;
  border-radius: 8px;
}
</style>
