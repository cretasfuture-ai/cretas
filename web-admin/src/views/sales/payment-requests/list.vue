<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">{{ t('sales.payment_requests_list.text_3a05ab16ab6a') }}</h2>
      <p class="page-subtitle">{{ t('sales.payment_requests_list.text_92e1c7738fd7') }}</p>
    </div>

    <!-- 过滤栏 -->
    <UCard class="filter-card" >
      <el-row :gutter="16" align="middle">
        <el-col :span="5">
          <CompatSelect
            v-model="filterStatus"
            :placeholder="t('sales.payment_requests_list.placeholder_6320b4a8722a')"
            clearable
            style="width: 100%"
            @change="handleFilterChange"
          >
            <CompatOption :label="t('sales.payment_requests_list.label_5c55a67935af')" value="" />
            <!-- 由 PAYMENT_REQUEST_STATUS_META 生成 —— 手写第二份就是 CANCELLED
                 从筛选器里漏掉的原因(被撤回的单子筛不出来)。 -->
            <CompatOption
              v-for="meta in PAYMENT_REQUEST_STATUS_META"
              :key="meta.value"
              :label="meta.label"
              :value="meta.value"
            />
          </CompatSelect>
        </el-col>
        <el-col :span="6">
          <el-input
            v-model="filterKeyword"
            :placeholder="t('sales.payment_requests_list.placeholder_6bdf887a22cb')"
            clearable
            prefix-icon="Search"
            @clear="handleFilterChange"
            @keyup.enter="handleFilterChange"
          />
        </el-col>
        <el-col :span="4">
          <UButton color="primary" @click="handleFilterChange">{{ t('sales.payment_requests_list.action_bcd6771e08ec') }}</UButton>
          <UButton color="neutral" variant="outline" @click="resetFilter">{{ t('sales.payment_requests_list.action_cb5d682bac3d') }}</UButton>
        </el-col>
        <el-col :span="9" style="text-align: right">
          <!-- Rule 4: 仅销售员可发起 sales:read_write -->
          <UButton
            v-if="canSalesWrite"
            color="success"
            @click="openCreateDialog"
          >
            {{ t('sales.payment_requests_list.action_c5ebe78b7f14') }}
          </UButton>
        </el-col>
      </el-row>
    </UCard>

    <!-- 数据表格 -->
    <UCard class="data-card" >
      <CompatTable
        v-loading="loading"
        :data="tableData"
        stripe
        row-key="id"
        style="width: 100%"
      >
        <template #empty><ListEmptyExplain empty-title="还没有销售付款申请" empty-hint="客户需要预付或分期付款时，点右上角新建付款申请并关联销售订单；财务审批后进入收款。" /></template>
        <CompatTableColumn :label="t('sales.payment_requests_list.label_ef8e791a45ca')" prop="requestNumber" width="170" />
        <!-- 销售方向: 显示销售订单号和客户名 -->
        <CompatTableColumn :label="t('sales.payment_requests_list.label_68bd4b5a1bf2')" prop="salesOrderId" width="160">
          <template #default="{ row }">
            {{ row.salesOrderId || '—' }}
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.payment_requests_list.label_ad4af312dbc2')" prop="customerName" min-width="120">
          <template #default="{ row }">
            {{ row.customerName || row.customerId || '—' }}
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.payment_requests_list.label_ec4b5671e3af')">
          <template #default>
            <UBadge variant="soft" color="warning" size="sm">{{ t('sales.payment_requests_list.text_c949b27ea438') }}</UBadge>
          </template>
        </CompatTableColumn>
        <!-- @PriceSensitive: amount 字段后端对无权限角色返回 null -->
        <CompatTableColumn :label="t('sales.payment_requests_list.label_10d91e07c599')" prop="amount" width="120">
          <template #default="{ row }">
            <span v-if="row.amount !== null && row.amount !== undefined">
              ¥{{ formatAmount(row.amount) }}
            </span>
            <span v-else class="price-hidden">—</span>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.payment_requests_list.label_4e3c13589f98')" prop="paymentMethod" width="100">
          <template #default="{ row }">
            {{ row.paymentMethod || '—' }}
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.payment_requests_list.label_6320b4a8722a')" prop="status" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.payment_requests_list.label_07ec86e0f1d4')" prop="createdAt" width="155">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.payment_requests_list.label_ed31fbb483ee')" width="260" fixed="right">
          <template #default="{ row }">
            <!-- 提交审核：PENDING → FINANCE_REVIEW (sales:read_write) -->
            <UButton
              v-if="row.status === 'PENDING' && canSalesWrite"
              color="primary"
              variant="link"
              size="sm"
              @click="openSubmitDialog(row)"
            >
              {{ t('sales.payment_requests_list.action_034829aca81d') }}
            </UButton>
            <!-- 财务审批：FINANCE_REVIEW (finance:read_write) -->
            <UButton
              v-if="row.status === 'FINANCE_REVIEW' && isFinanceManager"
              color="success"
              variant="link"
              size="sm"
              @click="openApproveDialog(row)"
            >
              {{ t('sales.payment_requests_list.action_64d021080a5f') }}
            </UButton>
            <!-- 出纳付款：APPROVED → PAID (finance:read_write / cashier) -->
            <UButton
              v-if="row.status === 'APPROVED' && isCashier"
              color="warning"
              variant="link"
              size="sm"
              @click="openMarkPaidDialog(row)"
            >
              {{ t('sales.payment_requests_list.action_92f779b599b4') }}
            </UButton>
            <UButton color="info" variant="link" size="sm" @click="openDetailDialog(row)">
              {{ t('sales.payment_requests_list.action_979a332955c8') }}
            </UButton>
          </template>
        </CompatTableColumn>
      </CompatTable>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <CompatPagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="fetchData"
          @size-change="fetchData"
        />
      </div>
    </UCard>

    <!-- ===== 新建销售付款申请 Dialog ===== -->
    <!-- Rule 2: 含销售订单号、客户、金额上下文 -->
    <CompatDialog
      v-model="createDialogVisible"
      :title="t('sales.payment_requests_list.title_c16f7e88833a')"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom: 16px"
      >
        {{ t('sales.payment_requests_list.text_3d688e907233') }}
      </el-alert>

      <CompatForm
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="110px"
      >
        <CompatFormItem :label="t('sales.payment_requests_list.label_ad4af312dbc2')" prop="customerId">
          <el-select
            v-model="createForm.customerId"
            filterable
            :placeholder="t('sales.payment_requests_list.placeholder_a38c27a62e70')"
            style="width: 100%"
          >
            <el-option v-for="c in customerOptions" :key="c.id" :label="c.name" :value="c.id" />
            <template #empty>
              <div style="padding:8px 12px;color:#909399">{{ t('sales.payment_requests_list.text_cac2886c1596') }}</div>
            </template>
          </el-select>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.payment_requests_list.label_68bd4b5a1bf2')" prop="salesOrderId">
          <el-select
            v-model="createForm.salesOrderId"
            filterable
            clearable
            :placeholder="t('sales.payment_requests_list.placeholder_e1c8e50bfd61')"
            style="width: 100%"
          >
            <el-option
              v-for="o in filteredSalesOrderOptions"
              :key="o.id"
              :value="o.id"
              :label="o.customerName ? `${o.orderNumber} · ${o.customerName}` : o.orderNumber"
            />
            <template #empty>
              <div style="padding:8px 12px;color:#909399">{{ t('sales.payment_requests_list.text_6d169952d4a7') }}</div>
            </template>
          </el-select>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.payment_requests_list.label_10d91e07c599')" prop="amount">
          <el-input-number
            v-model="createForm.amount"
            :min="0.01"
            :precision="2"
            :step="100"
            style="width: 100%"
            :placeholder="t('sales.payment_requests_list.placeholder_2125b861d6c3')"
          />
        </CompatFormItem>
        <!-- Rule 3: 付款方式 dropdown -->
        <CompatFormItem :label="t('sales.payment_requests_list.label_4e3c13589f98')" prop="paymentMethod">
          <CompatSelect
            v-model="createForm.paymentMethod"
            :placeholder="t('sales.payment_requests_list.placeholder_c8a85d23db36')"
            style="width: 100%"
          >
            <CompatOption :label="t('sales.payment_requests_list.label_597b930d5c89')" value="BANK_TRANSFER" />
            <CompatOption :label="t('sales.payment_requests_list.label_118f18e68405')" value="CASH" />
            <CompatOption :label="t('sales.payment_requests_list.label_81b430b69643')" value="WECHAT" />
            <CompatOption :label="t('sales.payment_requests_list.label_276a8bc45803')" value="ALIPAY" />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.payment_requests_list.label_daede9881787')" prop="remark">
          <el-input
            v-model="createForm.remark"
            type="textarea"
            :rows="2"
            :placeholder="t('sales.payment_requests_list.placeholder_be74ee4b74d8')"
          />
        </CompatFormItem>
      </CompatForm>

      <template #footer>
        <UButton color="neutral" variant="outline" @click="createDialogVisible = false">{{ t('sales.payment_requests_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="createSubmitting" @click="doCreate">
          {{ t('sales.payment_requests_list.action_9cc5e4d81ec2') }}
        </UButton>
      </template>
    </CompatDialog>

    <!-- ===== 提交审核 Dialog ===== -->
    <CompatDialog
      v-model="submitDialogVisible"
      :title="`提交审核 — ${currentRow?.customerName || currentRow?.customerId || ''} (${currentRow?.requestNumber || ''})`"
      width="440px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-alert type="info" :closable="false" style="margin-bottom: 16px">
          {{ t('sales.payment_requests_list.text_6e5b87eed291') }}<strong>{{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}</strong>
          {{ t('sales.payment_requests_list.text_77827f2b8b31') }}{{ currentRow.salesOrderId || t('sales.payment_requests_list.text_2ae6e10bd9f9') }}
        </el-alert>
        <p style="color: #606266; font-size: 14px; margin: 0">{{ t('sales.payment_requests_list.text_a01b9b0bd187') }}</p>
      </div>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="submitDialogVisible = false">{{ t('sales.payment_requests_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="submitting" @click="doSubmit">{{ t('sales.payment_requests_list.action_9ebe36f8cf23') }}</UButton>
      </template>
    </CompatDialog>

    <!-- ===== 财务审批 Dialog ===== -->
    <!-- Rule 2: 含客户+金额+单号; Rule 3: 拒绝原因 dropdown -->
    <CompatDialog
      v-model="approveDialogVisible"
      :title="`财务审批 — ${currentRow?.customerName || currentRow?.customerId || ''} (${currentRow?.requestNumber || ''})`"
      width="520px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <CompatDescriptions :column="2" border size="small" style="margin-bottom: 16px">
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_ad4af312dbc2')">{{ currentRow.customerName || currentRow.customerId }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_68bd4b5a1bf2')">{{ currentRow.salesOrderId || t('sales.payment_requests_list.text_2ae6e10bd9f9') }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_10d91e07c599')">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_4e3c13589f98')">{{ currentRow.paymentMethod || '—' }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_fe1f2b577fda')" :span="2">{{ currentRow.remark || '—' }}</CompatDescriptionsItem>
        </CompatDescriptions>
      </div>

      <CompatForm ref="approveFormRef" :model="approveForm" :rules="approveRules" label-width="90px">
        <CompatFormItem :label="t('sales.payment_requests_list.label_b9625f9948e3')" prop="action" required>
          <el-radio-group v-model="approveForm.action">
            <el-radio value="approve">{{ t('sales.payment_requests_list.text_8cbe697b1573') }}</el-radio>
            <el-radio value="reject">{{ t('sales.payment_requests_list.text_136de7a8c46f') }}</el-radio>
          </el-radio-group>
        </CompatFormItem>
        <!-- Rule 3: 拒绝原因 dropdown -->
        <CompatFormItem
          v-if="approveForm.action === 'reject'"
          :label="t('sales.payment_requests_list.label_570043ae3396')"
          prop="rejectReason"
        >
          <CompatSelect v-model="approveForm.rejectReason" :placeholder="t('sales.payment_requests_list.placeholder_4cb2089d2209')" style="width: 100%">
            <CompatOption :label="t('sales.payment_requests_list.label_a213ba14e7dd')" value="金额不符" />
            <CompatOption :label="t('sales.payment_requests_list.label_b42f41b2c46a')" value="客户信息有误" />
            <CompatOption :label="t('sales.payment_requests_list.label_c9cf52bd1516')" value="缺少凭证" />
            <CompatOption :label="t('sales.payment_requests_list.label_59d2bc7827c1')" value="超出预算" />
            <CompatOption :label="t('sales.payment_requests_list.label_8e56c595ca14')" value="不符合付款条件" />
            <CompatOption :label="t('sales.payment_requests_list.label_e1144b690ef5')" value="_other" />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem
          v-if="approveForm.action === 'reject' && approveForm.rejectReason === '_other'"
          :label="t('sales.payment_requests_list.label_6f3ebd2d3e87')"
          prop="rejectNote"
        >
          <el-input v-model="approveForm.rejectNote" type="textarea" :rows="2" :placeholder="t('sales.payment_requests_list.placeholder_05a4801b1dbd')" />
        </CompatFormItem>
        <CompatFormItem :label="t('sales.payment_requests_list.label_97d012369eab')" prop="reviewNote">
          <el-input v-model="approveForm.reviewNote" type="textarea" :rows="2" :placeholder="t('sales.payment_requests_list.placeholder_641f9e32d738')" />
        </CompatFormItem>
      </CompatForm>

      <template #footer>
        <UButton color="neutral" variant="outline" @click="approveDialogVisible = false">{{ t('sales.payment_requests_list.action_2cd0f3be8738') }}</UButton>
        <el-button
          :type="approveForm.action === 'reject' ? 'danger' : 'success'"
          :loading="submitting"
          :disabled="!approveForm.action"
          @click="doApprove"
        >
          {{ approveForm.action === 'reject' ? t('sales.payment_requests_list.text_de45b8ac34f3') : t('sales.payment_requests_list.text_38034be43042') }}
        </el-button>
      </template>
    </CompatDialog>

    <!-- ===== 确认付款 Dialog ===== -->
    <!-- Rule 1: 金额上限显示; Rule 2: 含客户+金额+单号 -->
    <CompatDialog
      v-model="markPaidDialogVisible"
      :title="`确认付款 — ${currentRow?.customerName || currentRow?.customerId || ''} (${currentRow?.requestNumber || ''})`"
      width="480px"
      :close-on-click-modal="false"
    >
      <div v-if="currentRow" class="dialog-context">
        <el-alert type="warning" :closable="false" style="margin-bottom: 16px">
          <strong>{{ t('sales.payment_requests_list.text_6b89860dca8c') }}{{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}</strong>
          {{ t('sales.payment_requests_list.text_5a630b25fe4f') }}{{ currentRow.customerName || currentRow.customerId }}
        </el-alert>
        <el-alert type="info" :closable="false" style="margin-bottom: 16px">
          {{ t('sales.payment_requests_list.text_2ade8d5926dd') }}
        </el-alert>
      </div>

      <CompatForm ref="markPaidFormRef" :model="markPaidForm" :rules="markPaidRules" label-width="90px">
        <CompatFormItem :label="t('sales.payment_requests_list.label_d88919a0b082')" prop="evidence">
          <UInput v-model="markPaidForm.evidence" :placeholder="t('sales.payment_requests_list.placeholder_017440a55d12')" />
        </CompatFormItem>
      </CompatForm>

      <template #footer>
        <UButton color="neutral" variant="outline" @click="markPaidDialogVisible = false">{{ t('sales.payment_requests_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="warning" :loading="submitting" @click="doMarkPaid">
          {{ t('sales.payment_requests_list.action_9a01377d202a') }}
        </UButton>
      </template>
    </CompatDialog>

    <!-- ===== 详情 Dialog ===== -->
    <CompatDialog
      v-model="detailDialogVisible"
      :title="`销售付款申请详情 — ${currentRow?.requestNumber || ''}`"
      width="620px"
    >
      <div v-if="currentRow">
        <CompatDescriptions :column="2" border>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_ef8e791a45ca')">{{ currentRow.requestNumber }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_3ebcf74d8bd4')">
            <UBadge variant="soft" color="warning" size="sm">{{ t('sales.payment_requests_list.text_c949b27ea438') }}</UBadge>
          </CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_ad4af312dbc2')">{{ currentRow.customerName || currentRow.customerId }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_68bd4b5a1bf2')">{{ currentRow.salesOrderId || t('sales.payment_requests_list.text_2ae6e10bd9f9') }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_10d91e07c599')">
            {{ currentRow.amount !== null && currentRow.amount !== undefined ? `¥${formatAmount(currentRow.amount)}` : '—' }}
          </CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_4e3c13589f98')">{{ currentRow.paymentMethod || '—' }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_6320b4a8722a')">
            <el-tag :type="statusTag(currentRow.status)" size="small">{{ statusLabel(currentRow.status) }}</el-tag>
          </CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_07ec86e0f1d4')">{{ formatDate(currentRow.createdAt) }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_fe1f2b577fda')" :span="2">{{ currentRow.remark || '—' }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_97d012369eab')" :span="2">{{ currentRow.financeReviewNote || '—' }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_570043ae3396')" :span="2">{{ currentRow.rejectReason || '—' }}</CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('sales.payment_requests_list.label_d88919a0b082')" :span="2">{{ currentRow.arapTransactionId || '—' }}</CompatDescriptionsItem>
        </CompatDescriptions>
      </div>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="detailDialogVisible = false">{{ t('sales.payment_requests_list.action_3fd47edce45b') }}</UButton>
      </template>
    </CompatDialog>
  </div>
</template>

<script setup lang="ts">
import { CompatDescriptions, CompatDescriptionsItem, CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
import { ref, computed, onMounted } from 'vue'

import type { FormInstance, FormRules } from 'element-plus'
import { get, post, put } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'
import { enumLabel } from '@/utils/enumDisplay'

// ─── 类型定义 ────────────────────────────────────────────────
/** 从 PAYMENT_REQUEST_STATUS_META 推导, 不再手写第二份(手写那份漏了 CANCELLED)。 */
type RequestStatus = (typeof PAYMENT_REQUEST_STATUS_META)[number]['value']

interface SalesPaymentRequestRow {
  id: string
  requestNumber: string
  sourceType: 'SALES' | 'PURCHASE'
  salesOrderId: string | null
  customerId: string | null
  customerName: string | null   // enriched by backend (if available)
  amount: number | null          // @PriceSensitive — null when role lacks price:view
  paymentMethod: string | null
  status: RequestStatus
  remark: string | null
  financeReviewNote: string | null
  rejectReason: string | null
  arapTransactionId: string | null
  createdAt: string | null
}

// ─── Store ────────────────────────────────────────────────────
const authStore = useAuthStore()
const permStore = usePermissionStore()
const factoryId = computed(() => authStore.factoryId)
// 销售付款由 sales:read_write 发起
const canSalesWrite = computed(() => permStore.canWrite('sales'))

const userRole = computed(() => authStore.currentRole)
const isFinanceManager = computed(() =>
  ['factory_super_admin', 'platform_admin', 'finance_manager'].includes(userRole.value || '')
)
const isCashier = computed(() =>
  ['factory_super_admin', 'platform_admin', 'finance_manager', 'cashier'].includes(userRole.value || '')
)

// ─── 列表状态 ────────────────────────────────────────────────
const loading = ref(false)
const submitting = ref(false)
const tableData = ref<SalesPaymentRequestRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const filterStatus = ref('')
const filterKeyword = ref('')

// ─── Dialog 状态 ─────────────────────────────────────────────
const createDialogVisible = ref(false)
const createSubmitting = ref(false)
const submitDialogVisible = ref(false)
const approveDialogVisible = ref(false)
const markPaidDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const currentRow = ref<SalesPaymentRequestRow | null>(null)

// ─── 表单定义 ────────────────────────────────────────────────
const createFormRef = ref<FormInstance>()
const createForm = ref({
  customerId: '',
  salesOrderId: '',
  amount: undefined as number | undefined,
  paymentMethod: '',
  remark: ''
})
const createRules: FormRules = {
  customerId: [{ required: true, message: t('sales.payment_requests_list.message_4dbaecc55e14'), trigger: 'change' }],
  amount: [{ required: true, message: t('sales.payment_requests_list.message_71369f107fc6'), trigger: 'blur' }],
  paymentMethod: [{ required: true, message: t('sales.payment_requests_list.message_c8a85d23db36'), trigger: 'change' }]
}

// fool-proof-design Rule 2 (身份而非裸 UUID): 客户 / 销售订单 由裸 UUID 输入改为按名称/单号
// 搜索的 el-select — mirror views/sales/shipments/list.vue (customerOptions) 和
// views/finance/payments/list.vue (salesOrderOptions) 已有的同款 pattern。
interface CustomerOption { id: string; name: string }
interface SalesOrderOption { id: string; orderNumber: string; customerId?: string; customerName?: string }
const customerOptions = ref<CustomerOption[]>([])
const salesOrderOptions = ref<SalesOrderOption[]>([])

async function loadCustomerOptions() {
  if (!factoryId.value) return
  try {
    const res = await get(`/${factoryId.value}/customers`, { params: { page: 1, size: 200 } })
    if (res.success && res.data) {
      const list = (res.data.content || []) as Array<{ id?: string; name?: string }>
      customerOptions.value = list
        .filter((c) => c.id && c.name)
        .map((c) => ({ id: String(c.id), name: String(c.name) }))
    }
  } catch { /* fall back to empty select */ }
}

async function loadSalesOrderOptions() {
  if (!factoryId.value) return
  try {
    // 端点为 /sales/orders (SalesController), 不是 /sales-orders.
    const res = await get(`/${factoryId.value}/sales/orders`, { params: { page: 1, size: 200 } })
    if (res.success && res.data) {
      const list = (res.data.content || []) as Array<{
        id?: string; orderNumber?: string; customerId?: string; customerName?: string
      }>
      salesOrderOptions.value = list
        .filter((o) => o.id && o.orderNumber)
        .map((o) => ({
          id: String(o.id),
          orderNumber: String(o.orderNumber),
          customerId: o.customerId ? String(o.customerId) : undefined,
          customerName: o.customerName ? String(o.customerName) : undefined
        }))
    }
  } catch { /* fall back to empty select */ }
}

// 选客户后, 销售订单下拉只显示该客户名下的订单 (仍保留全量作为 fallback, 防止 customerId
// 关联缺失时误藏订单).
const filteredSalesOrderOptions = computed(() => {
  if (!createForm.value.customerId) return salesOrderOptions.value
  const matched = salesOrderOptions.value.filter((o) => o.customerId === createForm.value.customerId)
  return matched.length > 0 ? matched : salesOrderOptions.value
})

const approveFormRef = ref<FormInstance>()
const approveForm = ref({ action: '', rejectReason: '', rejectNote: '', reviewNote: '' })
const approveRules: FormRules = {
  action: [{ required: true, message: t('sales.payment_requests_list.message_16bcc3d17a2d'), trigger: 'change' }]
}

const markPaidFormRef = ref<FormInstance>()
const markPaidForm = ref({ evidence: '' })
const markPaidRules: FormRules = {
  evidence: [{ required: true, message: t('sales.payment_requests_list.message_5dbeef6d973e'), trigger: 'blur' }]
}

// ─── 标签映射 ────────────────────────────────────────────────
/**
 * 付款申请状态 —— 本页的**唯一**一份清单。
 *
 * 客户 2026-07-17 (复测 07-20 / 07-21 未解决):「未经翻译直接引用后端英文」,
 * 点名了销售付款申请。这个页面之前把同一份状态清单**抄了 4 份**
 * (筛选下拉 / RequestStatus 类型 / statusLabel / statusTag), 4 份**都漏了**
 * 后端 `PaymentRequestStatus` 里的 `CANCELLED`, 而兜底又写成 `map[s] ?? s`
 * —— 于是一张被撤回的单子在列表里显示成英文原码 `CANCELLED`。
 *
 * 收敛成一份之后, 漏一个码会在**类型层**就炸, 而不是等用户看到英文。
 * 措辞刻意保留本页语境(销售视角 PENDING = 还没提交), 与
 * `PAYMENT_REQUEST_STATUS_LABELS`(财务视角「待财务初审」)不同 —— 这是
 * enumDisplay.ts 分层规则里的「同码不同义必须分域」, 不是漂移。
 */
const PAYMENT_REQUEST_STATUS_META = [
  { value: 'PENDING', label: t('sales.payment_requests_list.label_bb6b135bd5c1'), tag: 'info' },
  { value: 'FINANCE_REVIEW', label: t('sales.payment_requests_list.label_43f7b35f5b99'), tag: 'warning' },
  { value: 'APPROVED', label: t('sales.payment_requests_list.label_b42cb548c1d4'), tag: '' },
  { value: 'PAID', label: t('sales.payment_requests_list.label_73dd345ef1c8'), tag: 'success' },
  { value: 'REJECTED', label: t('sales.payment_requests_list.label_f098218a3032'), tag: 'danger' },
  { value: 'CANCELLED', label: t('sales.payment_requests_list.label_0462a8aa7036'), tag: 'info' },
] as const satisfies ReadonlyArray<{
  value: string
  label: string
  tag: '' | 'success' | 'warning' | 'danger' | 'info'
}>

function statusLabel(s: string): string {
  const hit = PAYMENT_REQUEST_STATUS_META.find((m) => m.value === s)
  // 兜底走全局枚举表, 不再把英文原码吐给用户。
  // 全局表也没有时它会返回「未知状态（X）」—— 那是**显式**的缺口信号,
  // 比一个看起来像正常内容的英文码好。
  return hit ? hit.label : enumLabel(s)
}

function statusTag(s: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  return PAYMENT_REQUEST_STATUS_META.find((m) => m.value === s)?.tag ?? 'info'
}

function formatAmount(v: number | null): string {
  if (v === null || v === undefined) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatDate(dt: string | null): string {
  if (!dt) return '—'
  return new Date(dt).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

// ─── 数据加载 ────────────────────────────────────────────────
async function fetchData() {
  if (!factoryId.value) return
  loading.value = true
  try {
    // 后端端点: GET /api/mobile/{factoryId}/payment-requests
    //   ?status=&keyword= (全状态列表，前端再用 sourceType=SALES 过滤)
    //   后端 listByFactory 返回当前工厂全部 PaymentRequest，我们在前端按 sourceType 过滤
    const params: Record<string, unknown> = {}
    if (filterStatus.value) params.status = filterStatus.value
    if (filterKeyword.value) params.keyword = filterKeyword.value
    // 明确告诉后端只取 SALES 方向 (若后端支持; 否则前端过滤)
    params.sourceType = 'SALES'

    const res = await get(`/${factoryId.value}/payment-requests`, params)
    if (res.success) {
      const raw = Array.isArray(res.data)
        ? res.data
        : (res.data as { content?: SalesPaymentRequestRow[] })?.content ?? []
      // 前端二次过滤确保只展示 SALES 方向（防止后端未支持 sourceType 参数）
      tableData.value = (raw as SalesPaymentRequestRow[]).filter(
        (r) => r.sourceType === 'SALES'
      )
      total.value = tableData.value.length
    }
  } catch (err) {
    console.error('获取销售付款申请列表失败', err)
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  page.value = 1
  fetchData()
}

function resetFilter() {
  filterStatus.value = ''
  filterKeyword.value = ''
  handleFilterChange()
}

// ─── 新建销售付款申请 ─────────────────────────────────────────
function openCreateDialog() {
  createForm.value = {
    customerId: '',
    salesOrderId: '',
    amount: undefined,
    paymentMethod: '',
    remark: ''
  }
  createFormRef.value?.resetFields()
  createDialogVisible.value = true
  if (customerOptions.value.length === 0) loadCustomerOptions()
  if (salesOrderOptions.value.length === 0) loadSalesOrderOptions()
}

async function doCreate() {
  if (!createFormRef.value || !factoryId.value) return
  await createFormRef.value.validate(async (valid) => {
    if (!valid) return
    createSubmitting.value = true
    try {
      // 后端端点: POST /api/mobile/{factoryId}/payment-requests/sales
      const body: Record<string, unknown> = {
        customerId: createForm.value.customerId,
        amount: createForm.value.amount,
        paymentMethod: createForm.value.paymentMethod
      }
      if (createForm.value.salesOrderId) body.salesOrderId = createForm.value.salesOrderId
      if (createForm.value.remark) body.remark = createForm.value.remark

      const res = await post(`/${factoryId.value}/payment-requests/sales`, body)
      if (res.success) {
        ElMessage({ message: t('sales.payment_requests_list.message_3f23513934d2'), type: 'success', duration: 3000 })
        createDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '创建失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '创建失败'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      createSubmitting.value = false
    }
  })
}

// ─── 提交审核 ────────────────────────────────────────────────
function openSubmitDialog(row: SalesPaymentRequestRow) {
  currentRow.value = row
  submitDialogVisible.value = true
}

async function doSubmit() {
  if (!currentRow.value || !factoryId.value) return
  submitting.value = true
  try {
    // 后端端点: PUT /api/mobile/{factoryId}/payment-requests/{id}/submit
    const res = await put(`/${factoryId.value}/payment-requests/${currentRow.value.id}/submit`, {})
    if (res.success) {
      ElMessage({ message: t('sales.payment_requests_list.message_feebf68541dd'), type: 'success', duration: 3000 })
      submitDialogVisible.value = false
      fetchData()
    } else {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true })
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '提交失败'
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
  } finally {
    submitting.value = false
  }
}

// ─── 财务审批 ────────────────────────────────────────────────
function openApproveDialog(row: SalesPaymentRequestRow) {
  currentRow.value = row
  approveForm.value = { action: '', rejectReason: '', rejectNote: '', reviewNote: '' }
  approveFormRef.value?.resetFields()
  approveDialogVisible.value = true
}

async function doApprove() {
  if (!approveFormRef.value || !currentRow.value || !factoryId.value) return
  await approveFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      let res
      if (approveForm.value.action === 'approve') {
        // 后端端点: PUT /api/mobile/{factoryId}/payment-requests/{id}/finance-approve
        res = await put(
          `/${factoryId.value}/payment-requests/${currentRow.value!.id}/finance-approve`,
          { reviewNote: approveForm.value.reviewNote || null }
        )
      } else {
        const reason = approveForm.value.rejectReason === '_other'
          ? approveForm.value.rejectNote
          : approveForm.value.rejectReason
        // 后端端点: PUT /api/mobile/{factoryId}/payment-requests/{id}/reject
        res = await put(
          `/${factoryId.value}/payment-requests/${currentRow.value!.id}/reject`,
          { rejectReason: reason || null }
        )
      }
      if (res.success) {
        ElMessage({
          message: approveForm.value.action === 'approve' ? '已批准销售付款申请' : '已拒绝销售付款申请',
          type: 'success',
          duration: 3000
        })
        approveDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '操作失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '操作失败'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 确认付款 ────────────────────────────────────────────────
function openMarkPaidDialog(row: SalesPaymentRequestRow) {
  currentRow.value = row
  markPaidForm.value = { evidence: '' }
  markPaidFormRef.value?.resetFields()
  markPaidDialogVisible.value = true
}

async function doMarkPaid() {
  if (!markPaidFormRef.value || !currentRow.value || !factoryId.value) return
  await markPaidFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      // 后端端点: PUT /api/mobile/{factoryId}/payment-requests/{id}/mark-paid
      const res = await put(
        `/${factoryId.value}/payment-requests/${currentRow.value!.id}/mark-paid`,
        { evidence: markPaidForm.value.evidence }
      )
      if (res.success) {
        ElMessage({ message: t('sales.payment_requests_list.message_93db8147f29e'), type: 'success', duration: 3000 })
        markPaidDialogVisible.value = false
        fetchData()
      } else {
        ElMessage({ message: res.message || '付款确认失败', type: 'error', duration: 0, showClose: true })
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '付款确认失败'
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    } finally {
      submitting.value = false
    }
  })
}

// ─── 详情 ────────────────────────────────────────────────────
function openDetailDialog(row: SalesPaymentRequestRow) {
  currentRow.value = row
  detailDialogVisible.value = true
}

// ─── 生命周期 ────────────────────────────────────────────────
onMounted(fetchData)
</script>

<style scoped>
.page-container {
  padding: 20px;
  background: #F4F6F9;
  min-height: 100%;
}

.page-header {
  margin-bottom: 16px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #1B65A8;
  margin: 0 0 4px;
}

.page-subtitle {
  font-size: 13px;
  color: #909399;
  margin: 0;
}

.filter-card {
  border-radius: 10px;
  margin-bottom: 16px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.data-card {
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
}

.price-hidden {
  color: #C0C4CC;
  font-style: italic;
}

.dialog-context {
  margin-bottom: 4px;
}
</style>
