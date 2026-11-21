<script setup lang="ts">
import { CompatDatePicker, CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

import { ref, computed, nextTick, onMounted, reactive } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';

import { Plus, Search, Refresh } from '@element-plus/icons-vue';
import type { FormInstance } from 'element-plus';
import DynamicEntityForm from '@/components/DynamicEntityForm.vue';
import type { FieldConfig } from '@/config/entityFieldConfigs';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import CreditPanel from './components/CreditPanel.vue';
import type { TableRow } from '@/types/api';
import {
  CUSTOMER_ADVANCED_FIELDS,
  CUSTOMER_TYPE_OPTIONS,
  countFilledCustomerAdvancedFields,
  customerTypeLabel,
  isCustomerAdvancedField,
  isSupportedCustomerPhone,
  normalizeCustomerPayload,
} from './customerFormModel';

// P1 #23 S-CREDIT-1 — 信用状态映射
type CreditStatusValue = 'NORMAL' | 'WARNING' | 'SUSPENDED';
const CREDIT_STATUS_OPTIONS: Array<{ value: CreditStatusValue; label: string; tagType: 'success' | 'warning' | 'danger' }> = [
  { value: 'NORMAL', label: t('sales.customers_list.label_296de0e31f8c'), tagType: 'success' },
  { value: 'WARNING', label: t('sales.customers_list.label_75d5781e08db'), tagType: 'warning' },
  { value: 'SUSPENDED', label: t('sales.customers_list.label_0eafcb149bcd'), tagType: 'danger' },
];
function getCreditStatusOption(v: string | undefined | null) {
  return CREDIT_STATUS_OPTIONS.find(o => o.value === v);
}
// Sprint 4 W2 S-CRM-FULL-1 — 防呆 R3: dropdown options 来自 single source of truth
import {
  CUSTOMER_STATUS_OPTIONS,
  CUSTOMER_IMPORTANCE_OPTIONS,
  CUSTOMER_SOURCE_OPTIONS,
  INVOICE_TYPE_OPTIONS,
  getCustomerStatusOption,
  getCustomerImportanceOption,
  getCustomerSourceOption,
  getInvoiceTypeOption,
  type CustomerStatusValue,
  type CustomerImportanceValue,
  type CustomerSourceValue,
  type InvoiceTypeValue,
} from '@/constants/customerEnums';

// 客户扩展字段 — 添加新字段只需在此数组加一行
const customerExtendedFields: FieldConfig[] = [
  { key: 'taxNumber', label: t('sales.customers_list.label_d83963e77003'), type: 'text', group: '开票信息', order: 1 },
  { key: 'billingAddress', label: t('sales.customers_list.label_75bd433efe79'), type: 'text', group: '开票信息', order: 2 },
  { key: 'bankName', label: t('sales.customers_list.label_c74687bbb415'), type: 'text', group: '开票信息', order: 3 },
  { key: 'bankAccount', label: t('sales.customers_list.label_0cc3f24453f3'), type: 'text', group: '开票信息', order: 4 },
  { key: 'creditLimit', label: t('sales.customers_list.label_fbf10f214232'), type: 'decimal', group: '信用管理', precision: 2, suffix: '元', order: 5 },
  { key: 'paymentTerms', label: t('sales.customers_list.label_7f5aeeddec63'), type: 'text', group: '信用管理', order: 6, placeholder: t('sales.customers_list.placeholder_f09b5c3d167e') },
  // P1 #23 S-CREDIT-1: 信用账期天数 + 信用状态
  { key: 'creditPeriodDays', label: t('sales.customers_list.label_a1c8e9f8f90b'), type: 'decimal', group: '信用管理', precision: 0, suffix: '天', order: 7, placeholder: t('sales.customers_list.placeholder_1f86e08ac77b') },
];

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('sales'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: Math.max(1, Number(route.query.page) || 1), size: Math.max(1, Number(route.query.size) || 10), total: 0 });
const searchKeyword = ref(String(route.query.keyword || ''));
// Sprint 4 W2 S-CRM-FULL-1 — list filters (R3 dropdown, 任一为空 = 不过滤)
const filterCustomerStatus = ref<CustomerStatusValue | ''>((route.query.customerStatus as CustomerStatusValue) || '');
const filterImportance = ref<CustomerImportanceValue | ''>((route.query.importance as CustomerImportanceValue) || '');
const filterSource = ref<CustomerSourceValue | ''>((route.query.source as CustomerSourceValue) || '');
const filterCustomerType = ref(String(route.query.customerType || ''));
const advancedFiltersVisible = ref(Boolean(filterCustomerStatus.value || filterImportance.value || filterSource.value));

onMounted(async () => {
  await loadData();
  const scrollY = Math.max(0, Number(route.query.scrollY) || 0);
  if (scrollY) {
    await nextTick();
    window.scrollTo({ top: scrollY });
  }
});

function listRouteQuery(includeScroll = false): Record<string, string> {
  const query: Record<string, string> = {};
  if (pagination.value.page !== 1) query.page = String(pagination.value.page);
  if (pagination.value.size !== 10) query.size = String(pagination.value.size);
  if (searchKeyword.value.trim()) query.keyword = searchKeyword.value.trim();
  if (filterCustomerType.value) query.customerType = filterCustomerType.value;
  if (filterCustomerStatus.value) query.customerStatus = filterCustomerStatus.value;
  if (filterImportance.value) query.importance = filterImportance.value;
  if (filterSource.value) query.source = filterSource.value;
  if (includeScroll && window.scrollY > 0) query.scrollY = String(Math.round(window.scrollY));
  return query;
}

async function syncListRoute(includeScroll = false): Promise<void> {
  await router.replace({ path: '/sales/customers', query: listRouteQuery(includeScroll) });
}

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/customers`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined,
        type: filterCustomerType.value || undefined,
        // Sprint 4 W2 S-CRM-FULL-1 — 3 个 CRM 过滤器 (空字符串 → undefined → 后端 IS NULL 分支)
        customerStatus: filterCustomerStatus.value || undefined,
        importance: filterImportance.value || undefined,
        source: filterSource.value || undefined,
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function handleSearch() {
  pagination.value.page = 1;
  await syncListRoute();
  await loadData();
}

async function handleRefresh() {
  searchKeyword.value = '';
  filterCustomerStatus.value = '';
  filterImportance.value = '';
  filterSource.value = '';
  filterCustomerType.value = '';
  advancedFiltersVisible.value = false;
  pagination.value.page = 1;
  await syncListRoute();
  await loadData();
}

async function handlePageChange(page: number) {
  pagination.value.page = page;
  await syncListRoute();
  await loadData();
}

async function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  await syncListRoute();
  await loadData();
}

// Dialog state
const dialogVisible = ref(false);
const dialogMode = ref<'add' | 'edit' | 'view'>('add');
const formRef = ref<FormInstance>();
const submitting = ref(false);
const advancedSections = ref<string[]>([]);

const defaultForm = {
  id: '',
  customerCode: '',  // S-CRM-FULL-1 R2: 显示 dialog context, read-only
  name: '',
  contactPerson: '',
  phone: '',
  shippingAddress: '',
  email: '',
  type: '',
  industry: '',
  notes: '',
  status: 'ACTIVE',  // T10: status field default
  // Sprint 4 W2 S-CRM-FULL-1 — 4 CRM 字段 (新增/编辑 dialog 内)
  customerStatus: '' as CustomerStatusValue | '',
  importance: '' as CustomerImportanceValue | '',
  source: '' as CustomerSourceValue | '',
  lastContactedAt: null as string | null,
  // Sprint 4 W2 S-INVOICE-CLIENT-1 — 客户级开票默认 (Option 3 三层链第 1 层)
  defaultTaxRate: null as number | null,
  defaultInvoiceType: '' as InvoiceTypeValue | '',
  // P1 #23 S-CREDIT-1 — 信用状态 (creditLimit/creditPeriodDays 走 extendedFields)
  creditStatus: '' as CreditStatusValue | '',
  version: null as number | null,  // optimistic lock — echoed on PUT, server returns 409 on mismatch
};
const formData = reactive({ ...defaultForm });

function requiredTextRule(label: string) {
  return {
    validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
      if (!String(value ?? '').trim()) callback(new Error(`请输入${label}`));
      else callback();
    },
    trigger: ['blur', 'change'],
  };
}

// 2026-08-12 (Steve): contactPerson / phone / shippingAddress 从必填改为选填。
// 后端与库本来就允许为空 —— CreateCustomerRequest 上这三个字段只有 @Size 没有
// @NotBlank, public.customers 三列实测 nullable=YES(只有 name 是 NOT NULL)。
// 也就是说这三个必填是前端单方面加严的, 全仓只有这一处载体。
// ⚠️ phone 的【格式】校验保留: 它本来就写成「空值放行, 非空才验格式」, 正好是选填要的行为。
const formRules = {
  name: [requiredTextRule('客户名称')],
  phone: [
    {
      validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
        if (!String(value ?? '').trim() || isSupportedCustomerPhone(value)) callback();
        else callback(new Error('支持手机号、企业座机/分机或国际电话号码'));
      },
      trigger: ['blur', 'change'],
    },
  ],
  status: [{ required: true, message: t('sales.customers_list.message_50d023002e83'), trigger: 'change' }],
  email: [{ type: 'email', message: t('sales.customers_list.message_ae5e16f4d62c'), trigger: ['blur', 'change'] }],
};

const advancedFieldKeys = Array.from(new Set([
  ...CUSTOMER_ADVANCED_FIELDS,
  ...customerExtendedFields.map((field) => field.key),
]));
const advancedFilledCount = computed(() => countFilledCustomerAdvancedFields(formData as TableRow, advancedFieldKeys));

const dialogTitle = computed(() => {
  // S-CRM-FULL-1 防呆 R2: edit/view dialog title 必带客户身份信息 (name + code)
  if (dialogMode.value === 'add') return '新增客户';
  const ctx = formData.name
    ? ` — ${formData.name}${formData.customerCode ? ` (${formData.customerCode})` : ''}`
    : '';
  return (dialogMode.value === 'edit' ? '编辑客户' : '查看客户') + ctx;
});

const isViewMode = computed(() => dialogMode.value === 'view');

function handleAdd() {
  dialogMode.value = 'add';
  Object.assign(
    formData,
    defaultForm,
    Object.fromEntries(customerExtendedFields.map((field): [string, null] => [field.key, null])),
  );
  advancedSections.value = [];
  dialogVisible.value = true;
  void nextTick(() => formRef.value?.clearValidate());
}

async function handleView(row: TableRow) {
  // Bug #6 fix: 跳转到 21-tab 客户 360° 详情页, 而非打开 basic dialog
  await syncListRoute(true);
  const returnTarget = router.resolve({ path: '/sales/customers', query: listRouteQuery(true) }).fullPath;
  await router.push({
    name: 'SalesCustomerDetail',
    params: { id: row.id as string },
    query: { from: returnTarget },
  });
}

function handleEdit(row: TableRow) {
  dialogMode.value = 'edit';
  Object.assign(formData, {
    id: row.id,
    customerCode: (row.customerCode as string) || '',
    name: row.name || '',
    contactPerson: row.contactPerson || '',
    phone: row.phone || '',
    version: typeof row.version === 'number' ? row.version : null,
    shippingAddress: row.shippingAddress || row.address || '',
    email: row.email || '',
    type: row.type || '',
    industry: row.industry || '',
    notes: row.notes || '',
    status: (row.status as string) || 'ACTIVE',  // T10
    // Sprint 4 W2 S-CRM-FULL-1
    customerStatus: (row.customerStatus as CustomerStatusValue) || '',
    importance: (row.importance as CustomerImportanceValue) || '',
    source: (row.source as CustomerSourceValue) || '',
    lastContactedAt: (row.lastContactedAt as string) || null,
    // Sprint 4 W2 S-INVOICE-CLIENT-1
    defaultTaxRate: (row.defaultTaxRate as number | null) ?? null,
    defaultInvoiceType: (row.defaultInvoiceType as InvoiceTypeValue) || '',
    // P1 #23 S-CREDIT-1
    creditStatus: (row.creditStatus as CreditStatusValue) || '',
    ...Object.fromEntries(customerExtendedFields.map((field) => [field.key, row[field.key] ?? null] as const)),
  });
  advancedSections.value = [];
  dialogVisible.value = true;
  void nextTick(() => formRef.value?.clearValidate());
}

async function handleSubmit() {
  if (!formRef.value || submitting.value) return;
  try {
    await formRef.value.validate();
  } catch (fields) {
    const invalidFields = Object.keys((fields as Record<string, unknown>) || {});
    const advancedInvalid = invalidFields.find(isCustomerAdvancedField);
    if (advancedInvalid) {
      advancedSections.value = ['advanced'];
      await nextTick();
      formRef.value?.scrollToField(advancedInvalid);
    }
    return;
  }
  submitting.value = true;
  try {
    const payload: TableRow = normalizeCustomerPayload(
      formData as TableRow,
      customerExtendedFields.map((field) => field.key),
    );
    let res;
    if (dialogMode.value === 'add') {
      res = await post(`/${factoryId.value}/customers`, payload);
    } else {
      // Optimistic lock: include version snapshot so BE can detect concurrent edit.
      if (formData.version !== null && formData.version !== undefined) {
        payload.version = formData.version;
      }
      res = await put(`/${factoryId.value}/customers/${formData.id}`, payload);
    }
    if (res.success) {
      ElMessage.success(dialogMode.value === 'add' ? '新增成功' : '编辑成功');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '操作失败');
    }
  } catch (error) {
    // R24 P2 follow-up: 409 with actionHint = business invariant (already rich-toasted by
    // interceptor). 409 without actionHint = vanilla optimistic-lock — show refresh dialog.
    const err = error as { status?: number; actionHint?: string | null };
    if (err?.status === 409 && !err.actionHint) {
      try {
        await ElMessageBox.confirm(
          t('sales.customers_list.message_323efa39f1f8'),
          t('sales.customers_list.message_f38a7d8a0c52'),
          { type: 'warning', confirmButtonText: t('sales.customers_list.confirmbuttontext_e28be7098db1'), cancelButtonText: t('sales.customers_list.cancelbuttontext_2cd0f3be8738') }
        );
        dialogVisible.value = false;
        loadData();
      } catch {
        // user cancelled — keep dialog open so they can copy their changes elsewhere
      }
    }
    // Interceptor (request.ts) already shows specific sticky toast for ApiError.
    // Firing a fallback here creates double-toast ("操作失败" BEFORE "客户名称已存在").
    console.error('提交失败:', error);
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(row: TableRow) {
  try {
    await ElMessageBox.confirm(`确定删除客户「${row.name}」吗？`, t('sales.customers_list.message_fe43bc43c294'), {
      type: 'warning',
      confirmButtonText: t('sales.customers_list.confirmbuttontext_fac2a67ad878'),
      cancelButtonText: t('sales.customers_list.cancelbuttontext_2cd0f3be8738'),
    });
    const res = await del(`/${factoryId.value}/customers/${row.id}`);
    if (res.success) {
      ElMessage.success(t('sales.customers_list.message_5223f91b9670'));
      loadData();
    } else {
      ElMessage.error(res.message || '删除失败');
    }
  } catch (e) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (e !== 'cancel') console.error('Delete customer failed:', e);
  }
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="客户"
      here="向我们采购的下游买家（如「华东冷链商贸」「南方食品批发」「康乐超市」），用于销售订单 / 应收账款"
      other-name="采购管理 → 供应商"
      other="向我们供货的上游卖家（如肉联厂、包材厂），用于采购订单 / 应付账款"
      other-path="/procurement/suppliers"
    />
    <UCard class="page-card" >
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ t('sales.customers_list.text_38840bfead29') }}</span>
            <span class="data-count">{{ t('sales.customers_list.text_76e547a8fa54') }} {{ pagination.total }} {{ t('sales.customers_list.text_6016bf63b0ff') }}</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">{{ t('sales.customers_list.action_c65d63c6a637') }}</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          :placeholder="t('sales.customers_list.placeholder_fd971c03d230')"
          :prefix-icon="Search"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
        />
        <CompatSelect
          v-model="filterCustomerType"
          :placeholder="t('sales.customers_list.placeholder_5d9575908d1d')"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <CompatOption v-for="opt in CUSTOMER_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </CompatSelect>
        <UButton color="neutral" variant="outline" @click="advancedFiltersVisible = !advancedFiltersVisible">
          {{ advancedFiltersVisible ? t('sales.customers_list.text_8e96288e1868') : t('sales.customers_list.text_97a1d7b98ba6') }}
        </UButton>
        <!-- CRM 字段降级为高级筛选，避免列表默认界面混淆客户类型与生命周期。 -->
        <CompatSelect
          v-if="advancedFiltersVisible"
          v-model="filterCustomerStatus"
          :placeholder="t('sales.customers_list.placeholder_052373d23b89')"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <CompatOption
            v-for="opt in CUSTOMER_STATUS_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </CompatSelect>
        <CompatSelect
          v-if="advancedFiltersVisible"
          v-model="filterImportance"
          :placeholder="t('sales.customers_list.placeholder_4e1342a9d68d')"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <CompatOption
            v-for="opt in CUSTOMER_IMPORTANCE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </CompatSelect>
        <CompatSelect
          v-if="advancedFiltersVisible"
          v-model="filterSource"
          :placeholder="t('sales.customers_list.placeholder_9f357adb6100')"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <CompatOption
            v-for="opt in CUSTOMER_SOURCE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </CompatSelect>
        <el-button type="primary" :icon="Search" @click="handleSearch">{{ t('sales.customers_list.action_44ce7ae909bb') }}</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">{{ t('sales.customers_list.action_cb5d682bac3d') }}</el-button>
      </div>

      <CompatTable :data="tableData" v-loading="loading" :empty-text="t('sales.customers_list.empty_text_497c85690c4c')" stripe border style="width: 100%">
        <CompatTableColumn prop="customerCode" :label="t('sales.customers_list.label_99a48cba26c1')" width="140" />
        <CompatTableColumn prop="name" :label="t('sales.customers_list.label_27375b325d9d')" min-width="180" show-overflow-tooltip />
        <CompatTableColumn prop="contactPerson" :label="t('sales.customers_list.label_3303b56982a0')" width="120" />
        <CompatTableColumn prop="phone" :label="t('sales.customers_list.label_d6981829142a')" width="140" />
        <CompatTableColumn :label="t('sales.customers_list.label_445edb72726b')" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.shippingAddress || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="status" :label="t('sales.customers_list.label_f4f0ead1116b')" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="(row.status === 'ACTIVE' || row.isActive === true) ? 'success' : 'info'" size="small">
              {{ (row.status === 'ACTIVE' || row.isActive === true) ? t('sales.customers_list.text_0149d010d8ea') : t('sales.customers_list.text_a8c3698b5b8c') }}
            </el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.customers_list.label_2d89604d7481')" width="110" align="center">
          <template #default="{ row }">{{ customerTypeLabel(row.type) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.customers_list.label_86bd937524c6')" width="80" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="getCustomerImportanceOption(row.importance as string)"
              :type="getCustomerImportanceOption(row.importance as string)?.tagType"
              size="small"
            >
              {{ getCustomerImportanceOption(row.importance as string)?.label }}
            </el-tag>
            <span v-else class="cell-empty">-</span>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.customers_list.label_a488e93d69cc')" width="100" align="center">
          <template #default="{ row }">
            <span>{{ getCustomerSourceOption(row.source as string)?.label || '-' }}</span>
          </template>
        </CompatTableColumn>
        <!-- P1 #23 S-CREDIT-1 — 信用状态列 -->
        <CompatTableColumn :label="t('sales.customers_list.label_e385e5596087')" width="80" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="getCreditStatusOption(row.creditStatus as string)"
              :type="getCreditStatusOption(row.creditStatus as string)?.tagType"
              size="small"
            >
              {{ getCreditStatusOption(row.creditStatus as string)?.label }}
            </el-tag>
            <span v-else class="cell-empty">-</span>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.customers_list.label_ed31fbb483ee')" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <UButton color="primary" variant="link" size="sm" @click="handleView(row)">{{ t('sales.customers_list.action_db8db0530432') }}</UButton>
            <UButton v-if="canWrite" color="primary" variant="link" size="sm" @click="handleEdit(row)">{{ t('sales.customers_list.action_051836569928') }}</UButton>
            <UButton v-if="canWrite" color="error" variant="link" size="sm" @click="handleDelete(row)">{{ t('sales.customers_list.action_2f9daa828907') }}</UButton>
          </template>
        </CompatTableColumn>
      </CompatTable>

      <div class="pagination-wrapper">
        <CompatPagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </UCard>

    <CompatDialog v-model="dialogVisible" :title="dialogTitle" width="min(720px, calc(100vw - 24px))" destroy-on-close>
      <!-- P1 #23 S-CREDIT-1: view/edit 模式下 mount 信用面板 (在 context bar 之上) -->
      <CreditPanel
        v-if="dialogMode !== 'add' && formData.id"
        :customer-id="formData.id"
      />

      <!-- S-CRM-FULL-1 防呆 R2: edit/view 模式下 dialog 顶部 context bar (品名 + 编号 + 状态 + 重要度 + 最近接洽) -->
      <div v-if="dialogMode !== 'add' && formData.name" class="dialog-context-bar">
        <div class="ctx-row">
          <span class="ctx-label">{{ t('sales.customers_list.text_18fb065286e7') }}</span>
          <strong>{{ formData.name }}</strong>
          <span v-if="formData.customerCode" class="ctx-code">{{ formData.customerCode }}</span>
        </div>
        <div class="ctx-row ctx-tags">
          <el-tag
            v-if="getCustomerStatusOption(formData.customerStatus)"
            :type="getCustomerStatusOption(formData.customerStatus)?.tagType"
            size="small"
          >{{ t('sales.customers_list.text_4923b2f37e48') }} {{ getCustomerStatusOption(formData.customerStatus)?.label }}</el-tag>
          <el-tag
            v-if="getCustomerImportanceOption(formData.importance)"
            :type="getCustomerImportanceOption(formData.importance)?.tagType"
            size="small"
          >{{ t('sales.customers_list.text_856764a78b26') }} {{ getCustomerImportanceOption(formData.importance)?.label }}</el-tag>
          <UBadge variant="soft" v-if="formData.source" size="sm" color="info">{{ t('sales.customers_list.text_15701b7e8888') }} {{ getCustomerSourceOption(formData.source)?.label }}</UBadge>
          <span class="ctx-last-contact">
            {{ t('sales.customers_list.text_b078682fe745') }} {{ formData.lastContactedAt ? new Date(formData.lastContactedAt).toLocaleString('zh-CN') : t('sales.customers_list.text_7450cc5b16e2') }}
          </span>
        </div>
      </div>

      <CompatForm ref="formRef" :model="formData" :rules="formRules" label-width="90px" :disabled="isViewMode">
        <CompatFormItem :label="t('sales.customers_list.label_27375b325d9d')" prop="name" required>
          <UInput v-model="formData.name" :placeholder="t('sales.customers_list.placeholder_2f0439d74af2')" />
        </CompatFormItem>
        <!-- 2026-08-12 (Steve): 联系人/联系电话/收货地址改为选填。后端与库本来就允许为空
             (CreateCustomerRequest 上只有 @Size 没有 @NotBlank; public.customers 三列
             nullable=YES) —— 这三个必填只存在于本文件, 是前端单方面加严的。
             它们仍留在基础区(不进折叠), 只是不再拦提交。 -->
        <CompatFormItem :label="t('sales.customers_list.label_3303b56982a0')" prop="contactPerson">
          <UInput v-model="formData.contactPerson" :placeholder="t('sales.customers_list.placeholder_096f113e6489')" />
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_d6981829142a')" prop="phone">
          <UInput v-model="formData.phone" :placeholder="t('sales.customers_list.placeholder_ba2975d1794a')" />
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_445edb72726b')" prop="shippingAddress">
          <el-input v-model="formData.shippingAddress" :placeholder="t('sales.customers_list.placeholder_031f9979d713')" type="textarea" :rows="2" />
        </CompatFormItem>
        <!-- T10 P2.2: status field editable in form (was view-only via list column badge) -->
        <CompatFormItem :label="t('sales.customers_list.label_6320b4a8722a')" prop="status" required>
          <CompatSelect v-model="formData.status" :disabled="isViewMode" style="width: 100%">
            <CompatOption :label="t('sales.customers_list.label_0149d010d8ea')" value="ACTIVE" />
            <CompatOption :label="t('sales.customers_list.label_a8c3698b5b8c')" value="INACTIVE" />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_2d89604d7481')" prop="type">
          <CompatSelect v-model="formData.type" :placeholder="t('sales.customers_list.placeholder_b2b2379dfee3')" clearable style="width: 100%">
            <CompatOption v-for="opt in CUSTOMER_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_daede9881787')" prop="notes">
          <el-input v-model="formData.notes" :placeholder="t('sales.customers_list.placeholder_1004c72456f8')" type="textarea" :rows="2" />
        </CompatFormItem>

        <el-collapse v-model="advancedSections" class="advanced-collapse">
          <el-collapse-item name="advanced">
            <template #title>
              <span class="advanced-title">{{ t('sales.customers_list.text_1311fec5ed1e') }}</span>
              <UBadge variant="soft" v-if="advancedFilledCount" size="sm" color="info">{{ t('sales.customers_list.text_693875585c47') }} {{ advancedFilledCount }} {{ t('sales.customers_list.text_49ccde43a154') }}</UBadge>
              <span v-else class="advanced-empty">{{ t('sales.customers_list.text_ebdcbdcf9c99') }}</span>
            </template>
        <CompatFormItem :label="t('sales.customers_list.label_73075237fd0f')" prop="email">
          <UInput v-model="formData.email" :placeholder="t('sales.customers_list.placeholder_59c9ed95eb06')" />
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_2ec94bbf93f3')" prop="industry">
          <UInput v-model="formData.industry" :placeholder="t('sales.customers_list.placeholder_2dc8d7fee7d8')" />
        </CompatFormItem>

        <el-divider content-position="left">{{ t('sales.customers_list.text_6c78a119cf0b') }}</el-divider>
        <CompatFormItem :label="t('sales.customers_list.label_9ebe1ab0d17f')" prop="customerStatus">
          <CompatSelect v-model="formData.customerStatus" :placeholder="t('sales.customers_list.placeholder_d9b44d079200')" style="width: 100%">
            <CompatOption
              v-for="opt in CUSTOMER_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_d9f32d1ae63d')" prop="importance">
          <CompatSelect v-model="formData.importance" :placeholder="t('sales.customers_list.placeholder_a8b7b1c5f833')" style="width: 100%">
            <CompatOption
              v-for="opt in CUSTOMER_IMPORTANCE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_b6e60a6bd9eb')" prop="source">
          <CompatSelect v-model="formData.source" :placeholder="t('sales.customers_list.placeholder_687cf876998e')" clearable style="width: 100%">
            <CompatOption
              v-for="opt in CUSTOMER_SOURCE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_5f6352bba039')" prop="lastContactedAt">
          <CompatDatePicker
            v-model="formData.lastContactedAt"
            type="datetime"
            :placeholder="t('sales.customers_list.placeholder_f1a23f4b4eb6')"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </CompatFormItem>

        <!-- Sprint 4 W2 S-INVOICE-CLIENT-1 — 客户级开票默认 (Option 3 三层链第 1 层, R3 dropdown) -->
        <el-divider content-position="left">{{ t('sales.customers_list.text_530cefad1504') }}</el-divider>
        <CompatFormItem :label="t('sales.customers_list.label_21d358aaf9ff')" prop="defaultTaxRate">
          <el-input-number
            v-model="formData.defaultTaxRate"
            :precision="2"
            :step="1"
            :min="0"
            :max="100"
            :placeholder="t('sales.customers_list.placeholder_6471228cbbdf')"
            style="width: 100%"
          >
            <template #suffix>%</template>
          </el-input-number>
          <div class="form-hint">{{ t('sales.customers_list.text_553b162c41d4') }}</div>
        </CompatFormItem>
        <CompatFormItem :label="t('sales.customers_list.label_d5a6d85f98f8')" prop="defaultInvoiceType">
          <el-select v-model="formData.defaultInvoiceType" :placeholder="t('sales.customers_list.placeholder_3b8f65dc8d45')" clearable style="width: 100%">
            <el-option
              v-for="opt in INVOICE_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            >
              <span style="float: left">{{ opt.label }}</span>
              <span style="float: right; color: var(--el-text-color-secondary); font-size: 12px">
                {{ opt.description }}
              </span>
            </el-option>
          </el-select>
        </CompatFormItem>

        <!-- P1 #23 S-CREDIT-1: 信用状态 dropdown (creditLimit / creditPeriodDays 走扩展字段) -->
        <el-divider content-position="left">{{ t('sales.customers_list.text_f96fd253ca27') }}</el-divider>
        <CompatFormItem :label="t('sales.customers_list.label_014d60ecff6f')" prop="creditStatus">
          <CompatSelect v-model="formData.creditStatus" :placeholder="t('sales.customers_list.placeholder_1987dfae1d6a')" style="width: 100%">
            <CompatOption
              v-for="opt in CREDIT_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
          <div class="form-hint">
            {{ t('sales.customers_list.text_43d07648406d') }}
          </div>
        </CompatFormItem>

        <!-- 扩展字段 (动态渲染) -->
        <el-divider content-position="left">{{ t('sales.customers_list.text_0e91274c2843') }}</el-divider>
        <DynamicEntityForm
          :fields="customerExtendedFields"
          :model-value="formData as TableRow"
          @update:model-value="Object.assign(formData, $event)"
          :readonly="isViewMode"
          :columns="2"
          label-width="110px"
        />
          </el-collapse-item>
        </el-collapse>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">{{ isViewMode ? t('sales.customers_list.text_3fd47edce45b') : t('sales.customers_list.text_2cd0f3be8738') }}</UButton>
        <UButton v-if="!isViewMode" color="primary" :loading="submitting" @click="handleSubmit">{{ t('sales.customers_list.action_fac2a67ad878') }}</UButton>
      </template>
    </CompatDialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}

/* Sprint 4 W2 S-CRM-FULL-1 — 防呆 R2 dialog context bar */
.dialog-context-bar {
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 6px;
  padding: 12px 14px;
  margin-bottom: 16px;
  font-size: 13px;
}

.ctx-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ctx-row + .ctx-row {
  margin-top: 8px;
}

.ctx-label {
  color: var(--text-color-secondary, #909399);
}

.ctx-code {
  color: var(--text-color-secondary, #909399);
  font-family: monospace;
}

.ctx-last-contact {
  color: var(--text-color-secondary, #909399);
  margin-left: auto;
}

.ctx-tags {
  gap: 6px;
}

.cell-empty {
  color: var(--text-color-disabled, #c0c4cc);
  font-size: 12px;
}

.advanced-collapse { margin-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.advanced-title { margin-right: 8px; font-weight: 600; color: var(--el-text-color-primary); }
.advanced-empty { color: var(--el-text-color-secondary); font-size: 12px; }
.advanced-collapse :deep(.el-collapse-item__content) { padding: 8px 4px 2px; }

/* Sprint 4 W2 S-INVOICE-CLIENT-1 — form hint under input */
.form-hint {
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
  line-height: 1.4;
  margin-top: 4px;
}
</style>
