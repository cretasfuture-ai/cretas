<template>
  <div class="page-container" data-testid="salted-deductions">
    <!-- 页面标题 -->
    <div class="cs-page-head">
      <div>
        <h2>盐化仓管理</h2>
        <p>盐化(代加工)独立扣量记录与独立报表 — 不混入销售报表口径</p>
      </div>
    </div>

    <!-- Tab 切换 -->
    <UTabs v-model="activeTab" :items="tabItems" :content="false" variant="link" class="mb-3" data-testid="salted-tabs" />

    <!-- ===== TAB: 扣量记录 ===== -->
    <div v-if="activeTab === 'records'">
      <!-- 过滤栏 -->
      <section class="cs-card">
        <div class="filters">
          <UInput v-model="listFilter.warehouseId" placeholder="盐化仓 ID（必填）" class="w-60" data-testid="salted-list-warehouse" @keyup.enter="handleListFilterChange">
            <template v-if="listFilter.warehouseId" #trailing>
              <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="listFilter.warehouseId = ''" />
            </template>
          </UInput>
          <UInput v-model="listFilter.startDate" type="date" placeholder="开始日期" class="w-44" aria-label="开始日期" @change="handleListFilterChange" />
          <UInput v-model="listFilter.endDate" type="date" placeholder="结束日期" class="w-44" aria-label="结束日期" @change="handleListFilterChange" />
          <UButton icon="i-lucide-search" @click="handleListFilterChange">查询</UButton>
          <UButton color="neutral" variant="outline" @click="resetListFilter">重置</UButton>
          <UButton v-if="canWrite" class="ml-auto" color="success" icon="i-lucide-plus" data-testid="salted-create" @click="openCreateDialog">录入扣量</UButton>
        </div>
        <div v-if="!listFilter.warehouseId" class="filter-hint warn">请先输入盐化仓 ID 再查询</div>
      </section>

      <!-- 扣量记录表格 -->
      <section class="cs-card">
        <UTable :data="listData" :columns="listColumns" :loading="listLoading" class="cs-table" data-testid="salted-list-table">
          <template #empty>暂无数据，请输入盐化仓 ID 并查询</template>
          <template #deductionDate-cell="{ row }">{{ row.original.deductionDate || '—' }}</template>
          <template #customerName-cell="{ row }">{{ row.original.customerName || '—' }}</template>
          <template #materialBatchId-cell="{ row }"><span class="muted">{{ row.original.materialBatchId }}</span></template>
          <template #quantity-cell="{ row }"><span class="num">{{ row.original.quantity }} {{ displayUnit(row.original.quantityUnit) }}</span></template>
          <!-- @PriceSensitive: unitPrice/totalAmount 无价格权限时返回 null -->
          <template #unitPrice-cell="{ row }">
            <span v-if="row.original.unitPrice !== null && row.original.unitPrice !== undefined" class="num">¥{{ formatAmount(row.original.unitPrice) }}</span>
            <span v-else class="price-hidden">—</span>
          </template>
          <template #totalAmount-cell="{ row }">
            <span v-if="row.original.totalAmount !== null && row.original.totalAmount !== undefined" class="num">¥{{ formatAmount(row.original.totalAmount) }}</span>
            <span v-else class="price-hidden">—</span>
          </template>
          <template #remarks-cell="{ row }">{{ row.original.remarks || '—' }}</template>
        </UTable>
      </section>
    </div>

    <!-- ===== TAB: 盐化报表 ===== -->
    <div v-if="activeTab === 'report'">
      <!-- 报表查询条件 -->
      <section class="cs-card">
        <div class="filters">
          <UInput v-model="reportFilter.warehouseId" placeholder="盐化仓 ID（空 = 全厂汇总）" class="w-60" data-testid="salted-report-warehouse">
            <template v-if="reportFilter.warehouseId" #trailing>
              <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="reportFilter.warehouseId = ''" />
            </template>
          </UInput>
          <UInput v-model="reportFilter.startDate" type="date" placeholder="开始日期" class="w-44" aria-label="开始日期" />
          <UInput v-model="reportFilter.endDate" type="date" placeholder="结束日期" class="w-44" aria-label="结束日期" />
          <UButton :loading="reportLoading" icon="i-lucide-search" @click="fetchReport">查询报表</UButton>
          <UButton color="neutral" variant="outline" @click="resetReportFilter">重置</UButton>
        </div>
        <div class="filter-hint">
          盐化报表独立口径 — 不混入销售报表。空仓库 ID 汇总工厂下全部盐化仓。
        </div>
      </section>

      <!-- 报表汇总 -->
      <div v-if="report" class="summary-row">
        <div class="cs-card summary-card">
          <div class="summary-label">报表期间</div>
          <div class="summary-value">{{ report.startDate }} ~ {{ report.endDate }}</div>
        </div>
        <div class="cs-card summary-card">
          <div class="summary-label">盐化仓</div>
          <div class="summary-value">{{ report.warehouseName || report.warehouseId || '全厂汇总' }}</div>
        </div>
        <div class="cs-card summary-card">
          <div class="summary-label">盐化总出量</div>
          <div class="summary-value summary-value--large">{{ report.totalQuantity }} kg</div>
        </div>
        <div class="cs-card summary-card">
          <div class="summary-label">盐化总金额</div>
          <div class="summary-value summary-value--large">
            <span v-if="report.totalAmount !== null && report.totalAmount !== undefined">¥{{ formatAmount(report.totalAmount) }}</span>
            <span v-else class="price-hidden">— (含无单价行)</span>
          </div>
        </div>
      </div>

      <!-- 报表明细行 -->
      <section v-if="report" class="cs-card">
        <header class="cs-card-head"><span>扣量明细 (共 {{ report.lineCount }} 行)</span></header>
        <UTable :data="report.lines" :columns="reportColumns" class="cs-table" data-testid="salted-report-table">
          <template #customerName-cell="{ row }">{{ row.original.customerName || '—' }}</template>
          <template #materialName-cell="{ row }">{{ row.original.materialName || '—' }}</template>
          <template #batchNumber-cell="{ row }">{{ row.original.batchNumber || '—' }}</template>
          <template #quantity-cell="{ row }"><span class="num">{{ row.original.quantity }} {{ displayUnit(row.original.quantityUnit) }}</span></template>
          <template #unitPrice-cell="{ row }">
            <span v-if="row.original.unitPrice !== null && row.original.unitPrice !== undefined" class="num">¥{{ formatAmount(row.original.unitPrice) }}</span>
            <span v-else class="price-hidden">—</span>
          </template>
          <template #totalAmount-cell="{ row }">
            <span v-if="row.original.totalAmount !== null && row.original.totalAmount !== undefined" class="num">¥{{ formatAmount(row.original.totalAmount) }}</span>
            <span v-else class="price-hidden">—</span>
          </template>
          <template #remarks-cell="{ row }">{{ row.original.remarks || '—' }}</template>
        </UTable>
      </section>

      <section v-else-if="!reportLoading" class="cs-card cs-card-body empty-hint">请选择日期区间后点击「查询报表」</section>
    </div>

    <!-- ===== 录入扣量 Dialog ===== -->
    <UModal v-model:open="createDialogVisible" title="录入盐化扣量" description="盐化扣量走独立口径" :dismissible="false" :ui="{ content: 'max-w-[560px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <!-- Rule 1: 先显示可扣上限提示 -->
        <UAlert
          color="info"
          variant="subtle"
          icon="i-lucide-info"
          title="防呆提示：请先在「分仓库存查询」确认盐化仓当前可用库存，扣量数量不得超出批次可用量。盐化扣量走独立口径，不混入销售报表。"
          class="mb-3"
        />
        <div class="form">
          <UFormField label="盐化仓 ID" required :error="createErrors.warehouseId">
            <UInput v-model="createForm.warehouseId" placeholder="盐化仓 ID (type=SALTED)" class="w-full" data-testid="salted-form-warehouse" />
          </UFormField>
          <UFormField label="原料批次 ID" required :error="createErrors.materialBatchId">
            <UInput v-model="createForm.materialBatchId" placeholder="被扣减的原料批次 ID" class="w-full" data-testid="salted-form-batch" />
          </UFormField>
          <!-- Rule 2: 含单号/幂等提示 -->
          <UFormField label="盐化供单号" required :error="createErrors.orderNumber">
            <UInput v-model="createForm.orderNumber" placeholder="如 SD-20261020-001 (幂等去重 key)" class="w-full" data-testid="salted-form-order" />
          </UFormField>
          <UFormField label="扣量日期" required :error="createErrors.deductionDate">
            <UInput v-model="createForm.deductionDate" type="date" class="w-full" data-testid="salted-form-date" />
          </UFormField>
          <UFormField label="扣减数量" required :error="createErrors.quantity">
            <UInput v-model.number="createForm.quantity" type="number" :min="0.001" step="0.1" placeholder="扣减数量 (kg)" class="w-full" data-testid="salted-form-qty" />
          </UFormField>
          <UFormField label="单价（选填）">
            <UInput v-model.number="createForm.unitPrice" type="number" :min="0" step="0.01" placeholder="用于期中盘账勾稽（可不填）" class="w-full" />
          </UFormField>
          <UFormField label="客户名称（选填）">
            <UInput v-model="createForm.customerName" placeholder="外商/代加工客户名称" class="w-full" />
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="createForm.remarks" :rows="2" placeholder="备注（可选）" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createDialogVisible = false">取消</UButton>
        <UButton :loading="createSubmitting" data-testid="salted-create-submit" @click="doCreate">确认录入</UButton>
      </template>
    </UModal>
  </div>
</template>

<script setup lang="ts">
/* 组件全部是 Nuxt UI (UTable / UTabs / UModal / UFormField / UInput / UTextarea / UAlert) + --cs-*；日期用原生 date 输入（值即 YYYY-MM-DD）。提示仍用 ElMessage。 */
import { ref, computed, reactive } from 'vue'
import { displayUnit } from '@/utils/unitPricing';
import { ElMessage } from 'element-plus'
import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable'
import { get, post } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'

// ─── 类型定义 ────────────────────────────────────────────────
interface SaltedDeduction {
  id: string
  orderNumber: string
  warehouseId: string
  materialBatchId: string
  deductionDate: string
  quantity: number
  quantityUnit: string
  unitPrice: number | null
  totalAmount: number | null
  customerName: string | null
  operatorId: number | null
  remarks: string | null
  createdAt: string | null
}

interface SaltedReportLine {
  id: string
  orderNumber: string
  deductionDate: string
  quantity: number
  quantityUnit: string
  unitPrice: number | null
  totalAmount: number | null
  customerName: string | null
  materialBatchId: string
  batchNumber: string | null
  materialName: string | null
  remarks: string | null
}

interface SaltedReport {
  factoryId: string
  warehouseId: string | null
  warehouseName: string | null
  startDate: string
  endDate: string
  totalQuantity: number
  totalAmount: number | null
  lines: SaltedReportLine[]
  lineCount: number
}

// ─── Store ────────────────────────────────────────────────────
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
const canWrite = computed(() => permStore.canWrite('warehouse'))

// ─── Tab ─────────────────────────────────────────────────────
const activeTab = ref<'records' | 'report'>('records')
const tabItems: TabsItem[] = [
  { label: '扣量记录', value: 'records' },
  { label: '盐化报表', value: 'report' },
]

// ─── 表格列 ──────────────────────────────────────────────────
const R = { class: { th: 'text-right', td: 'text-right' } }
const listColumns: TableColumn<SaltedDeduction>[] = [
  { accessorKey: 'orderNumber', header: '扣量单号' },
  { id: 'deductionDate', header: '扣量日期' },
  { id: 'customerName', header: '客户名称' },
  { id: 'materialBatchId', header: '原料批次 ID' },
  { id: 'quantity', header: '扣量', meta: R },
  { id: 'unitPrice', header: '单价', meta: R },
  { id: 'totalAmount', header: '总金额', meta: R },
  { id: 'remarks', header: '备注' },
]
const reportColumns: TableColumn<SaltedReportLine>[] = [
  { accessorKey: 'orderNumber', header: '扣量单号' },
  { accessorKey: 'deductionDate', header: '扣量日期' },
  { id: 'customerName', header: '客户名称' },
  { id: 'materialName', header: '原料名称' },
  { id: 'batchNumber', header: '批次号' },
  { id: 'quantity', header: '扣量', meta: R },
  { id: 'unitPrice', header: '单价', meta: R },
  { id: 'totalAmount', header: '总金额', meta: R },
  { id: 'remarks', header: '备注' },
]

// ─── 扣量记录 ────────────────────────────────────────────────
const listLoading = ref(false)
const listData = ref<SaltedDeduction[]>([])

const listFilter = ref({
  warehouseId: '',
  startDate: '',
  endDate: ''
})

function handleListFilterChange() {
  if (!listFilter.value.warehouseId) return
  fetchList()
}

function resetListFilter() {
  listFilter.value = { warehouseId: '', startDate: '', endDate: '' }
  listData.value = []
}

async function fetchList() {
  if (!factoryId.value || !listFilter.value.warehouseId) return
  if (!listFilter.value.startDate || !listFilter.value.endDate) {
    ElMessage({ message: '请选择开始日期和结束日期', type: 'warning', duration: 3000 })
    return
  }
  listLoading.value = true
  try {
    // 后端端点: GET /api/mobile/{factoryId}/factory/salted-deductions
    //   ?warehouseId=&startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
    const res = await get(`/${factoryId.value}/factory/salted-deductions`, {
      warehouseId: listFilter.value.warehouseId,
      startDate: listFilter.value.startDate,
      endDate: listFilter.value.endDate
    })
    if (res.success) {
      listData.value = Array.isArray(res.data) ? res.data : []
    } else {
      ElMessage({ message: res.message || '查询失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '查询失败'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
  } finally {
    listLoading.value = false
  }
}

// ─── 盐化报表 ────────────────────────────────────────────────
const reportLoading = ref(false)
const report = ref<SaltedReport | null>(null)

const reportFilter = ref({
  warehouseId: '',
  startDate: '',
  endDate: ''
})

function resetReportFilter() {
  reportFilter.value = { warehouseId: '', startDate: '', endDate: '' }
  report.value = null
}

async function fetchReport() {
  if (!factoryId.value) return
  if (!reportFilter.value.startDate || !reportFilter.value.endDate) {
    ElMessage({ message: '请选择开始日期和结束日期', type: 'warning', duration: 3000 })
    return
  }
  reportLoading.value = true
  try {
    // 后端端点: GET /api/mobile/{factoryId}/factory/salted-deductions/report
    //   ?warehouseId=&startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
    const params: Record<string, string> = {
      startDate: reportFilter.value.startDate,
      endDate: reportFilter.value.endDate
    }
    if (reportFilter.value.warehouseId) {
      params.warehouseId = reportFilter.value.warehouseId
    }
    const res = await get(`/${factoryId.value}/factory/salted-deductions/report`, params)
    if (res.success) {
      report.value = res.data as SaltedReport
    } else {
      ElMessage({ message: res.message || '报表查询失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '报表查询失败'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
  } finally {
    reportLoading.value = false
  }
}

// ─── 创建扣量 ────────────────────────────────────────────────
const createDialogVisible = ref(false)
const createSubmitting = ref(false)

const createForm = ref({
  warehouseId: '',
  materialBatchId: '',
  orderNumber: '',
  deductionDate: '',
  quantity: undefined as number | string | undefined,
  unitPrice: undefined as number | string | undefined,
  customerName: '',
  remarks: ''
})

// 必填校验（原 el-form rules 的同一组文案）；提交时才显示，改动后清掉对应项
const createErrors = reactive<Record<string, string>>({})
const REQUIRED_MESSAGES: Record<string, string> = {
  warehouseId: '请输入盐化仓 ID',
  materialBatchId: '请输入原料批次 ID',
  orderNumber: '请输入盐化供单号',
  deductionDate: '请选择扣量日期',
  quantity: '请输入扣减数量',
}

function validateCreate(): boolean {
  let ok = true
  for (const key of Object.keys(REQUIRED_MESSAGES)) {
    const v = (createForm.value as Record<string, unknown>)[key]
    const empty = v === undefined || v === null || String(v).trim() === ''
    createErrors[key] = empty ? REQUIRED_MESSAGES[key] : ''
    if (empty) ok = false
  }
  return ok
}

function openCreateDialog() {
  createForm.value = {
    warehouseId: listFilter.value.warehouseId || '',
    materialBatchId: '',
    orderNumber: '',
    deductionDate: '',
    quantity: undefined,
    unitPrice: undefined,
    customerName: '',
    remarks: ''
  }
  for (const key of Object.keys(REQUIRED_MESSAGES)) createErrors[key] = ''
  createDialogVisible.value = true
}

async function doCreate() {
  if (!factoryId.value) return
  if (!validateCreate()) return
  createSubmitting.value = true
  try {
    // 后端端点: POST /api/mobile/{factoryId}/factory/salted-deductions
    const body: Record<string, unknown> = {
      warehouseId: createForm.value.warehouseId,
      materialBatchId: createForm.value.materialBatchId,
      orderNumber: createForm.value.orderNumber,
      deductionDate: createForm.value.deductionDate,
      quantity: Number(createForm.value.quantity)
    }
    const price = createForm.value.unitPrice
    if (price !== undefined && price !== null && String(price) !== '') {
      body.unitPrice = Number(price)
    }
    if (createForm.value.customerName) body.customerName = createForm.value.customerName
    if (createForm.value.remarks) body.remarks = createForm.value.remarks

    const res = await post(`/${factoryId.value}/factory/salted-deductions`, body)
    if (res.success) {
      ElMessage({ message: '盐化扣量录入成功', type: 'success', duration: 3000 })
      createDialogVisible.value = false
      // 自动刷新列表（如仓库 ID 已填且日期已选）
      if (listFilter.value.warehouseId && listFilter.value.startDate && listFilter.value.endDate) {
        fetchList()
      }
    } else {
      ElMessage({ message: res.message || '录入失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '录入失败'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
  } finally {
    createSubmitting.value = false
  }
}

// ─── 工具函数 ────────────────────────────────────────────────
function formatAmount(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.page-container { padding: var(--cs-space-4); min-height: 100%; }
.filters { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.filter-hint { padding: 0 var(--cs-space-5) var(--cs-space-4); color: var(--cs-muted); font-size: var(--cs-font-small); }
.filter-hint.warn { color: var(--cs-warning); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.summary-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cs-space-3); margin: var(--cs-space-3) 0; }
.summary-card { padding: var(--cs-space-3) var(--cs-space-4); }
.cs-card + .summary-row + .cs-card { margin-top: 0; }
.summary-label { font-size: var(--cs-font-small); color: var(--cs-muted); margin-bottom: 6px; }
.summary-value { font-size: var(--cs-font); color: var(--cs-text); font-weight: 500; }
.summary-value--large { font-size: 18px; color: var(--cs-primary); font-weight: 700; }
.price-hidden { color: var(--cs-muted); font-style: italic; }
.muted { color: var(--cs-muted); font-size: var(--cs-font-small); }
.empty-hint { text-align: center; color: var(--cs-muted); }
</style>
