<script setup lang="ts">
/**
 * P-NUCLEAR-1 (28-Backlog #30) — 核价单详情 (PC).
 *
 * 三阶段操作:
 *   1. DRAFT → INQUIRING: 提交询价 (submit button)
 *   2. INQUIRING/QUOTED: 添加供应商报价 (loops; UPDATE if same supplier)
 *   3. QUOTED → CONVERTED: 选定中标供应商 → 回填发起比价的采购草稿, 或生成采购单 (idempotent)
 *
 * L3 报价可比 (S5-01 / S5-02 / S5-04):
 *   - 报价表来自后端比价接口: 只有同币种 / 同单位 / 同规格 / 税率已知且一致的报价有名次;
 *     不可比的报价逐条显示原因, 不参与排序, 不能选定。
 *   - 过期 / 未生效 / 低于起订量 / 供应关系缺失的报价不能选定 (单选框禁用 + 原因)。
 *   - 税率不填 = 未知, ⛔ 不当 0%。
 *   - 每条报价可查看「价格变化对 BOM 的影响」估算 (只读)。
 *
 * 防呆 R2: 所有 toast 含 inquiryNumber + supplierName context.
 * 防呆 R4: select-and-convert 已转化拒绝重复 — 后端 409 原文由全局 toast 展示.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import { buildSupplierPricePayload, type SupplierPriceForm } from './quotePayload';
import { Refresh } from '@element-plus/icons-vue';
import {
  getInquiry,
  submitInquiry,
  cancelInquiry,
  addSupplierPrice,
  getQuoteComparison,
  getQuotePriceImpact,
  selectAndConvert,
  type InquiryQuote,
  type QuoteComparison,
  type QuoteComparisonRow,
  type QuotePriceImpact,
} from '@/api/inquiryQuote';
import { listActiveSuppliers, type SupplierRecord } from '@/api/supplierManagement';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const inquiryId = computed(() => String(route.params.id));
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const quote = ref<InquiryQuote | null>(null);
const comparison = ref<QuoteComparison | null>(null);
const rows = computed<QuoteComparisonRow[]>(() => comparison.value?.rows ?? []);
const suppliers = ref<SupplierRecord[]>([]);

function emptyPriceForm(): SupplierPriceForm {
  return {
    supplierId: '',
    unitPrice: 0,
    taxRate: null,
    currency: 'CNY',
    quotedUnit: quote.value?.unit ?? '',
    specification: quote.value?.specification ?? '',
    minOrderQuantity: null,
    priceEffectiveDate: '',
    validUntil: '',
    deliveryDays: null,
    remark: '',
  };
}

const addPriceForm = ref<SupplierPriceForm>(emptyPriceForm());
const addPriceSubmitting = ref(false);

// Select & convert
const selectedSupplierId = ref<string>('');
const convertSubmitting = ref(false);

// BOM impact
const impactVisible = ref(false);
const impactLoading = ref(false);
const impact = ref<QuotePriceImpact | null>(null);

const isEditable = computed(() => {
  if (!quote.value) return false;
  return ['INQUIRING', 'QUOTED', 'SELECTED'].includes(quote.value.status);
});

const canConvert = computed(() => {
  if (!quote.value) return false;
  return (
    (quote.value.status === 'QUOTED' || quote.value.status === 'SELECTED') &&
    rows.value.length > 0
  );
});

const isConverted = computed(() => quote.value?.status === 'CONVERTED');
const fillsExistingDraft = computed(() => Boolean(quote.value?.sourcePurchaseOrderId));
const selectedRow = computed(() =>
  rows.value.find((r) => r.supplierId === selectedSupplierId.value) ?? null,
);

async function load() {
  if (!factoryId.value || !inquiryId.value) return;
  loading.value = true;
  try {
    const qRes = await getInquiry(factoryId.value, inquiryId.value);
    if (qRes.success && qRes.data) {
      quote.value = qRes.data;
    }
    const cRes = await getQuoteComparison(factoryId.value, inquiryId.value);
    if (cRes.success && cRes.data) {
      comparison.value = cRes.data;
      if (selectedRow.value && !selectedRow.value.selectable) {
        selectedSupplierId.value = '';
      }
    }
  } finally {
    loading.value = false;
  }
}

async function loadSuppliers() {
  if (!factoryId.value) return;
  suppliers.value = await listActiveSuppliers(factoryId.value);
}

async function doSubmit() {
  if (!factoryId.value || !quote.value) return;
  try {
    await ElMessageBox.confirm(
      `确认提交核价单 ${quote.value.inquiryNumber} 询价?`,
      '提交询价',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  const res = await submitInquiry(factoryId.value, inquiryId.value);
  if (res.success) {
    ElMessage.success(res.message || '已提交询价');
    load();
  }
}

async function doCancel() {
  if (!factoryId.value || !quote.value) return;
  try {
    await ElMessageBox.confirm(
      `确认取消核价单 ${quote.value.inquiryNumber}? 取消后不可恢复.`,
      '取消核价单',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  const res = await cancelInquiry(factoryId.value, inquiryId.value);
  if (res.success) {
    ElMessage.success(res.message || '已取消');
    load();
  }
}

function openAddPrice() {
  addPriceForm.value = emptyPriceForm();
}

async function submitAddPrice() {
  if (!factoryId.value || !quote.value) return;
  if (!addPriceForm.value.supplierId) {
    ElMessage.warning('请选择供应商');
    return;
  }
  if (!addPriceForm.value.unitPrice || addPriceForm.value.unitPrice <= 0) {
    ElMessage.warning('未税单价必须 > 0');
    return;
  }
  addPriceSubmitting.value = true;
  try {
    const res = await addSupplierPrice(
      factoryId.value,
      inquiryId.value,
      buildSupplierPricePayload(addPriceForm.value),
    );
    if (res.success) {
      ElMessage.success(res.message || '报价提交成功');
      openAddPrice();
      load();
    }
  } finally {
    addPriceSubmitting.value = false;
  }
}

async function doSelectAndConvert() {
  if (!factoryId.value || !quote.value) return;
  const selected = selectedRow.value;
  if (!selected) {
    ElMessage.warning('请先勾选一条可选定的报价');
    return;
  }
  const target = fillsExistingDraft.value
    ? '回填发起比价的采购草稿 (不新建采购单)'
    : '生成采购单';
  try {
    await ElMessageBox.confirm(
      `核价单 ${quote.value.inquiryNumber}: 选定「${selected.supplierName || selected.supplierId}」` +
        ` 未税单价 ${formatPrice(selected.unitPrice)}/${selected.quotedUnit}、税率 ${selected.taxRate}%，` +
        `${target}? 重复点击会被服务端拒绝。`,
      '选定报价',
      { type: 'warning', confirmButtonText: '确认' },
    );
  } catch {
    return;
  }

  convertSubmitting.value = true;
  try {
    const res = await selectAndConvert(factoryId.value, inquiryId.value, {
      selectedSupplierId: selected.supplierId,
    });
    if (res.success && res.data) {
      ElMessage.success(res.message || `已写入采购单 ${res.data.orderNumber}`);
    }
  } finally {
    convertSubmitting.value = false;
    load();
  }
}

async function openImpact(row: QuoteComparisonRow) {
  if (!factoryId.value) return;
  impactVisible.value = true;
  impactLoading.value = true;
  impact.value = null;
  try {
    const res = await getQuotePriceImpact(factoryId.value, inquiryId.value, row.priceId);
    if (res.success && res.data) {
      impact.value = res.data;
    }
  } finally {
    impactLoading.value = false;
  }
}

function goPurchaseOrder(id?: string | null) {
  if (!id) return;
  router.push({ name: 'ProcurementOrderDetail', params: { id } });
}

function formatPrice(v: number | null | undefined): string {
  if (v == null) return '-';
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`;
}

function sourceLabel(source: string | null | undefined): string {
  if (source === 'QUOTE') return '报价';
  if (source === 'SUPPLIER_MATERIAL') return '供应关系';
  return '';
}

onMounted(async () => {
  await load();
  openAddPrice();
  loadSuppliers();
});
</script>

<template>
  <div class="inquiry-quote-detail" v-loading="loading">
    <div class="page-header">
      <div>
        <el-button text @click="router.back()">‹ 返回</el-button>
        <h2 v-if="quote" class="title">
          {{ quote.inquiryNumber }}
          <el-tag class="status-tag" effect="light" :type="isConverted ? 'success' : 'primary'">
            {{ quote.status }}
          </el-tag>
        </h2>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button
          v-if="quote && quote.status === 'DRAFT'"
          type="primary"
          @click="doSubmit"
        >
          提交询价
        </el-button>
        <el-button
          v-if="quote && !isConverted && quote.status !== 'CANCELLED'"
          type="danger"
          plain
          @click="doCancel"
        >
          取消核价单
        </el-button>
        <el-button
          v-if="isConverted && quote?.purchaseOrderId"
          type="success"
          @click="goPurchaseOrder(quote?.purchaseOrderId)"
        >
          查看采购单 {{ quote.purchaseOrderNumber }}
        </el-button>
      </div>
    </div>

    <el-card v-if="quote" class="info-card" shadow="never">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="标题">{{ quote.title || '-' }}</el-descriptions-item>
        <el-descriptions-item label="物料">{{ quote.materialName || quote.materialTypeId }}</el-descriptions-item>
        <el-descriptions-item label="规格">{{ quote.specification || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ quote.quantity }} {{ quote.unit }}</el-descriptions-item>
        <el-descriptions-item label="询价日期">{{ quote.inquiryDate }}</el-descriptions-item>
        <el-descriptions-item label="期望到货">{{ quote.requiredDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="采购草稿" :span="2">
          <template v-if="quote.sourcePurchaseOrderId">
            <el-link type="primary" @click="goPurchaseOrder(quote.sourcePurchaseOrderId)">
              查看发起比价的草稿
            </el-link>
            <span class="hint">（选定报价会回填这张草稿，不另建采购单）</span>
          </template>
          <template v-else>未挂采购草稿（选定报价会生成新的采购单）</template>
        </el-descriptions-item>
        <el-descriptions-item label="中标供应商" :span="2">
          {{ quote.selectedSupplierName || quote.selectedSupplierId || '尚未选定' }}
        </el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ quote.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="prices-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            供应商报价 ({{ rows.length }}) · 可比 {{ comparison?.comparableCount ?? 0 }} · 可选定
            {{ comparison?.selectableCount ?? 0 }}
          </span>
        </div>
      </template>

      <el-alert v-if="comparison" type="info" :closable="false" show-icon class="basis">
        <template #title>
          比较基准：{{ comparison.referenceCurrency }} · 计价单位「{{ comparison.referenceUnit }}」
          <template v-if="comparison.referenceSpecification">
            · 规格「{{ comparison.referenceSpecification }}」
          </template>
          · 税率
          {{ comparison.referenceTaxRate != null ? `${comparison.referenceTaxRate}%` : '未定' }}
          （{{ comparison.referenceTaxRateSource }}）
        </template>
        {{ comparison.rankingBasis }}
      </el-alert>

      <el-table :data="rows" stripe empty-text="尚无报价" row-key="priceId">
        <el-table-column v-if="canConvert" label="选定" width="60" align="center">
          <template #default="{ row }">
            <el-radio
              v-model="selectedSupplierId"
              :value="row.supplierId"
              :disabled="!row.selectable"
            />
          </template>
        </el-table-column>
        <el-table-column label="可比 / 名次" min-width="230">
          <template #default="{ row }">
            <el-tag v-if="row.rank" type="success" effect="light">第 {{ row.rank }} 低</el-tag>
            <el-tag v-else-if="!row.comparable" type="danger" effect="light">不可比</el-tag>
            <el-tag v-else type="info" effect="light">不排序</el-tag>
            <ul v-if="row.blockingReasons.length" class="issues blocking">
              <li v-for="issue in row.blockingReasons" :key="issue.code">{{ issue.message }}</li>
            </ul>
            <ul v-if="row.warnings.length" class="issues warning">
              <li v-for="issue in row.warnings" :key="issue.code">{{ issue.message }}</li>
            </ul>
          </template>
        </el-table-column>
        <el-table-column label="供应商" min-width="150">
          <template #default="{ row }">{{ row.supplierName || row.supplierId }}</template>
        </el-table-column>
        <el-table-column label="未税单价" min-width="120" align="right">
          <template #default="{ row }">
            {{ formatPrice(row.unitPrice) }}<span v-if="row.quotedUnit">/{{ row.quotedUnit }}</span>
          </template>
        </el-table-column>
        <el-table-column label="税率" min-width="80" align="right">
          <template #default="{ row }">
            <span v-if="row.taxRate != null">{{ row.taxRate }}%</span>
            <el-tag v-else type="warning" size="small">未知</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="含税单价" min-width="110" align="right">
          <template #default="{ row }">{{ formatPrice(row.taxInclusiveUnitPrice) }}</template>
        </el-table-column>
        <el-table-column label="币种 / 规格" min-width="120">
          <template #default="{ row }">
            {{ row.currency || '币种未注明' }} · {{ row.specification || '规格未注明' }}
          </template>
        </el-table-column>
        <el-table-column label="起订量" min-width="120">
          <template #default="{ row }">
            <template v-if="row.minOrderQuantity != null">
              {{ row.minOrderQuantity }} {{ row.minOrderQuantityUnit || '' }}
              <span class="hint">({{ sourceLabel(row.minOrderQuantitySource) }})</span>
            </template>
            <template v-else>-</template>
          </template>
        </el-table-column>
        <el-table-column label="交期" min-width="140">
          <template #default="{ row }">
            <template v-if="row.leadTimeDays != null">
              {{ row.leadTimeDays }} 天 <span class="hint">({{ sourceLabel(row.leadTimeSource) }})</span>
              <div class="hint">预计 {{ row.expectedArrivalDate }} 到</div>
            </template>
            <template v-else>-</template>
          </template>
        </el-table-column>
        <el-table-column label="有效期 / 生效日" min-width="140">
          <template #default="{ row }">
            <div>至 {{ row.validUntil || '未注明' }}</div>
            <div class="hint">{{ row.priceEffectiveDate ? `${row.priceEffectiveDate} 起生效` : '生效日未注明' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openImpact(row)">BOM 影响</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="canConvert" class="convert-bar">
        <el-button
          type="primary"
          :loading="convertSubmitting"
          :disabled="!selectedRow || !selectedRow.selectable"
          @click="doSelectAndConvert"
        >
          {{ fillsExistingDraft ? '选定并回填采购草稿' : '选定并生成采购单' }}
        </el-button>
        <span class="hint">
          只有「可选定」的报价能勾选；重复提交不会写出第二张采购单
        </span>
      </div>
    </el-card>

    <el-card v-if="isEditable" class="add-price-card" shadow="never">
      <template #header>
        <span>添加 / 更新供应商报价</span>
      </template>
      <el-form :model="addPriceForm" label-width="96px" class="price-form">
        <el-form-item label="供应商" required>
          <el-select
            v-model="addPriceForm.supplierId"
            filterable
            placeholder="选择供应商"
            style="width: 280px"
          >
            <el-option
              v-for="s in suppliers"
              :key="s.id"
              :label="s.displayName || s.name"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="未税单价" required>
          <el-input-number v-model="addPriceForm.unitPrice" :min="0" :precision="4" />
          <el-input v-model="addPriceForm.currency" maxlength="8" style="width: 90px; margin-left: 8px">
            <template #prepend>币种</template>
          </el-input>
          <el-input v-model="addPriceForm.quotedUnit" maxlength="20" style="width: 140px; margin-left: 8px">
            <template #prepend>每</template>
          </el-input>
        </el-form-item>
        <el-form-item label="税率(%)">
          <el-input-number
            v-model="addPriceForm.taxRate"
            :min="0"
            :max="100"
            :precision="2"
            :value-on-clear="null"
            placeholder="不知道就留空"
          />
          <span class="hint">留空 = 税率未知，这条报价会标为「不可比」，不会按 0% 算</span>
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="addPriceForm.specification" maxlength="200" style="width: 280px" />
        </el-form-item>
        <el-form-item label="起订量">
          <el-input-number v-model="addPriceForm.minOrderQuantity" :min="0" :value-on-clear="null" />
          <span class="hint">按上面的计价单位；不填则读供应关系里维护的起订量</span>
        </el-form-item>
        <el-form-item label="交货天数">
          <el-input-number v-model="addPriceForm.deliveryDays" :min="0" :value-on-clear="null" />
        </el-form-item>
        <el-form-item label="价格生效日">
          <el-date-picker v-model="addPriceForm.priceEffectiveDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="报价有效期至">
          <el-date-picker v-model="addPriceForm.validUntil" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addPriceForm.remark" maxlength="500" style="width: 280px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="addPriceSubmitting" @click="submitAddPrice">
            提交报价
          </el-button>
          <span class="hint">同供应商已报价则更新</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-dialog
      v-model="impactVisible"
      :title="impact ? `价格变化对 BOM 的影响 — ${impact.materialName || ''} (${impact.supplierName || ''})` : '价格变化对 BOM 的影响'"
      width="860px"
    >
      <div v-loading="impactLoading">
        <template v-if="impact">
          <el-alert type="warning" :closable="false" show-icon class="basis">
            <template #title>
              估算：未税单价 {{ formatPrice(impact.quotedUnitPrice) }}/{{ impact.quotedUnit }}，
              {{ impact.priceEffectiveDate }} 起生效
            </template>
            {{ impact.note }}
            <span v-if="impact.hiddenRecipeCount">另有 {{ impact.hiddenRecipeCount }} 个配方因工艺保密未显示。</span>
          </el-alert>
          <el-table :data="impact.rows" stripe empty-text="没有 BOM 用到这个物料">
            <el-table-column label="配方" min-width="180">
              <template #default="{ row }">
                {{ row.productName || row.recipeCode }}
                <span class="hint">v{{ row.recipeVersion }} · {{ row.recipeStatus }}</span>
              </template>
            </el-table-column>
            <el-table-column label="用量" min-width="90" align="right">
              <template #default="{ row }">{{ row.standardQuantity ?? '-' }} {{ row.priceUnit || '' }}</template>
            </el-table-column>
            <el-table-column label="现行单价" min-width="100" align="right">
              <template #default="{ row }">{{ formatPrice(row.currentUnitPrice) }}</template>
            </el-table-column>
            <el-table-column label="现行行成本" min-width="100" align="right">
              <template #default="{ row }">{{ formatPrice(row.currentItemCost) }}</template>
            </el-table-column>
            <el-table-column label="估算行成本" min-width="100" align="right">
              <template #default="{ row }">
                <template v-if="row.computable">{{ formatPrice(row.estimatedItemCost) }}</template>
                <span v-else class="hint">{{ row.notComputableReason }}</span>
              </template>
            </el-table-column>
            <el-table-column label="变化" min-width="90" align="right">
              <template #default="{ row }">{{ formatPrice(row.deltaItemCost) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.inquiry-quote-detail {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 4px 0 0;
  color: #303133;
}
.status-tag {
  margin-left: 8px;
  vertical-align: middle;
}
.info-card,
.prices-card,
.add-price-card {
  margin-bottom: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.basis {
  margin-bottom: 12px;
}
.hint {
  font-size: 12px;
  color: #909399;
  margin-left: 6px;
}
.issues {
  margin: 4px 0 0;
  padding-left: 16px;
  font-size: 12px;
  line-height: 1.5;
}
.issues.blocking {
  color: #c45656;
}
.issues.warning {
  color: #b88230;
}
.convert-bar {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
