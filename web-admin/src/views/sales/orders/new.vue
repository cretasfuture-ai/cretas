<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { MATERIAL_SUPPLY_MODE_OPTIONS, SALES_PROCESSING_MODE_OPTIONS, type MaterialSupplyMode, type SalesProcessingMode } from './salesOrderSupplyContract';
import { filterSalesProducts, isSalesOrderProduct, type SalesProductOption } from './salesOrderProductSearch';
import type { TableRow } from '@/types/api';
import { canonicalUnitCodeKeepingCount, displayUnit, storageUnitCode } from '@/utils/unitPricing';
import {
  ambiguousPackagingUnit,
  initialTaxIncludedUnitPrice,
  packagingSpecIdForUnit,
  productTaxRatePercent,
  salesOrderUnitOptions,
  untaxedUnitPrice,
  type SalesOrderPackagingSpec,
} from './salesOrderNewLineContract';

type Warehouse = TableRow & { id: string; code: string; name: string; type?: string; isActive?: boolean };
type Customer = TableRow & { id: string; name: string };
type Product = SalesProductOption & {
  unit?: string;
  measurementUnit?: string;
  defaultUnit?: string;
  level1Unit?: string;
  boxConversionCoefficient?: number;
  unitPrice?: number;
  taxIncludedUnitPrice?: number;
  taxRate?: number | string;
  packagingSpecs?: SalesOrderPackagingSpec[];
};
type SalesLine = {
  product: Product;
  quantity: number;
  unit: string;
  taxIncludedUnitPrice: number;
  taxRate: number;
  /** 随含税单价/税点实时反算，提交只发送当前快照。 */
  unitPrice: number;
  sourceWarehouseCode: string;
};
type SuppliedMaterial = { materialTypeId: string; materialName: string; expectedQuantity: number; unit: string; expectedArrivalAt: string; targetWarehouseId: string };

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('sales'));
const loading = ref(false);
const loadingOptions = ref(false);
const customers = ref<Customer[]>([]);
const products = ref<Product[]>([]);
const suppliedMaterialOptions = ref<TableRow[]>([]);
const warehouses = ref<Warehouse[]>([]);
const productQuery = ref('');
const showProductPicker = ref(false);
const dirty = ref(false);

const form = ref({
  customerId: '',
  processingMode: 'STANDARD_SALE' as SalesProcessingMode,
  materialSupplyMode: 'FACTORY_SUPPLIED' as MaterialSupplyMode,
  requiredDeliveryDate: '',
  deliveryAddress: '',
  salesperson: '',
  remark: '',
  items: [] as SalesLine[],
  suppliedMaterials: [] as SuppliedMaterial[],
});

const allowedWarehouseTypes = new Set(['RAW', 'WORKSHOP', 'WIP', 'LINESIDE', 'LOGISTICS', 'FINISHED']);
const sourceWarehouses = computed(() => warehouses.value.filter((warehouse) =>
  warehouse.isActive !== false && allowedWarehouseTypes.has(String(warehouse.type || '').toUpperCase()),
));
const warehouseGroups = computed(() => [
  { label: '生产相关仓（RAW / WORKSHOP / WIP / LINESIDE）', types: ['RAW', 'WORKSHOP', 'WIP', 'LINESIDE'] },
  { label: '物流仓（LOGISTICS）', types: ['LOGISTICS'] },
  { label: '成品仓（FINISHED）', types: ['FINISHED'] },
].map((group) => ({
  ...group,
  warehouses: sourceWarehouses.value.filter((warehouse) => group.types.includes(String(warehouse.type || '').toUpperCase())),
})).filter((group) => group.warehouses.length > 0));
const filteredProducts = computed(() => filterSalesProducts(products.value, productQuery.value));
const selectedCustomer = computed(() => customers.value.find((customer) => String(customer.id) === form.value.customerId));
const requiresSuppliedMaterials = computed(() => form.value.processingMode === 'TOLL_PROCESSING' && form.value.materialSupplyMode === 'CUSTOMER_SUPPLIED');

function unwrap<T>(data: unknown): T[] {
  if (Array.isArray(data)) return data as T[];
  const content = (data as { content?: unknown[] } | null)?.content;
  return Array.isArray(content) ? content as T[] : [];
}

async function loadOptions(): Promise<void> {
  if (!factoryId.value) return;
  loadingOptions.value = true;
  try {
    const [customerResponse, productResponse, materialResponse, warehouseResponse] = await Promise.all([
      get(`/${factoryId.value}/customers`, { params: { page: 1, size: 100 }, _silent: true } as never),
      get(`/${factoryId.value}/product-types/sellable`, { _silent: true } as never),
      get(`/${factoryId.value}/raw-material-types/active`, { _silent: true } as never),
      get(`/${factoryId.value}/factory/warehouses`, { _silent: true } as never),
    ]);
    customers.value = unwrap<Customer>(customerResponse?.data);
    products.value = unwrap<Product>(productResponse?.data).filter(isSalesOrderProduct);
    suppliedMaterialOptions.value = unwrap<TableRow>(materialResponse?.data);
    warehouses.value = unwrap<Warehouse>(warehouseResponse?.data).filter((warehouse) => warehouse.isActive !== false);
  } catch {
    ElMessage.error('客户、产品或仓库选项加载失败，请刷新后重试');
  } finally {
    loadingOptions.value = false;
  }
}

function warehouseLabel(warehouse: Warehouse): string {
  return `${warehouse.name || warehouse.code} (${warehouse.code})`;
}

function addProduct(product: Product): void {
  if (!isSalesOrderProduct(product)) return;
  if (form.value.items.some((line) => String(line.product.id) === String(product.id))) {
    ElMessage.warning('同一订单不能重复添加产品');
    return;
  }
  const unit = salesOrderUnitOptions(product)[0]
    || canonicalUnitCodeKeepingCount(String(product.unit || product.measurementUnit || product.defaultUnit || '份'));
  const taxIncludedPrice = initialTaxIncludedUnitPrice(product);
  const taxRate = productTaxRatePercent(product);
  form.value.items.push({
    product,
    quantity: 1,
    unit,
    taxIncludedUnitPrice: taxIncludedPrice,
    taxRate,
    unitPrice: untaxedUnitPrice(taxIncludedPrice, taxRate),
    sourceWarehouseCode: '',
  });
  dirty.value = true;
  showProductPicker.value = false;
  productQuery.value = '';
}

function recalculateSalesUnitPrice(line: SalesLine): void {
  line.unitPrice = untaxedUnitPrice(line.taxIncludedUnitPrice, line.taxRate);
  dirty.value = true;
}

function removeProduct(index: number): void {
  form.value.items.splice(index, 1);
  dirty.value = true;
}

function addSuppliedMaterial(): void {
  form.value.suppliedMaterials.push({ materialTypeId: '', materialName: '', expectedQuantity: 0, unit: '', expectedArrivalAt: '', targetWarehouseId: '' });
  dirty.value = true;
}

function onSuppliedMaterialChange(row: SuppliedMaterial): void {
  const material = suppliedMaterialOptions.value.find((candidate) => String(candidate.id) === row.materialTypeId);
  row.materialName = String(material?.name || '');
  row.unit = canonicalUnitCodeKeepingCount(String(material?.unit || material?.measurementUnit || ''));
  dirty.value = true;
}

function onProcessingModeChange(): void {
  if (form.value.processingMode === 'STANDARD_SALE' && form.value.materialSupplyMode === 'CUSTOMER_SUPPLIED') {
    form.value.materialSupplyMode = 'FACTORY_SUPPLIED';
    form.value.suppliedMaterials = [];
  }
}

function onMaterialSupplyModeChange(): void {
  if (form.value.materialSupplyMode !== 'CUSTOMER_SUPPLIED') form.value.suppliedMaterials = [];
  else if (!form.value.suppliedMaterials.length) addSuppliedMaterial();
}

function validate(): string | null {
  if (!form.value.customerId) return '请选择客户';
  if (!form.value.processingMode || !form.value.materialSupplyMode) return '请选择加工方式和物料供应方式';
  if (!form.value.items.length) return '请至少添加一个成品或半成品';
  if (form.value.items.some((line) => line.quantity <= 0 || !line.unit || line.taxIncludedUnitPrice <= 0)) return '请完善产品数量、单位和含税销售单价';
  if (form.value.items.some((line) => line.taxRate < 0 || line.taxRate > 100)) return '税点必须在 0% 到 100% 之间';
  if (form.value.items.some((line) => ambiguousPackagingUnit(line.product, line.unit))) return '所选单位存在多个包装规格，请先在产品档案设置默认规格';
  if (form.value.items.some((line) => !line.sourceWarehouseCode)) return '请为每个产品选择来源仓库';
  if (form.value.items.some((line) => !sourceWarehouses.value.some((warehouse) => warehouse.code === line.sourceWarehouseCode))) return '来源仓库无效或不属于销售允许的仓库类型';
  if (requiresSuppliedMaterials.value && form.value.suppliedMaterials.some((row) => !row.materialTypeId || row.expectedQuantity <= 0 || !row.unit || !row.expectedArrivalAt || !row.targetWarehouseId)) return '请完整填写客户自带原料明细';
  if (requiresSuppliedMaterials.value && !form.value.suppliedMaterials.length) return '客户供料模式至少需要一条客供原料';
  if (sourceWarehouses.value.length === 0) return '当前工厂没有可用的 RAW/WORKSHOP/WIP/LINESIDE/LOGISTICS/FINISHED 仓库，请先配置仓库';
  return null;
}

function productCategory(product: Product): string {
  const value = String(product.productCategory || product.category || '').toUpperCase();
  return value.includes('SEMI') || value === 'WIP' ? '半成品' : '成品';
}

async function submit(): Promise<void> {
  const error = validate();
  if (error) { ElMessage.warning(error); return; }
  if (!canWrite.value) { ElMessage.warning('当前账号没有销售订单写权限'); return; }
  loading.value = true;
  try {
    const response = await post(`/${factoryId.value}/sales/orders`, {
      customerId: form.value.customerId,
      orderDate: new Date().toISOString().slice(0, 10),
      requiredDeliveryDate: form.value.requiredDeliveryDate || undefined,
      deliveryAddress: form.value.deliveryAddress || undefined,
      salesperson: form.value.salesperson || undefined,
      remark: form.value.remark || undefined,
      processingMode: form.value.processingMode,
      materialSupplyMode: form.value.materialSupplyMode,
      suppliedMaterials: requiresSuppliedMaterials.value ? form.value.suppliedMaterials.map((row) => ({ ...row, unit: storageUnitCode(row.unit), expectedArrivalAt: row.expectedArrivalAt.length === 10 ? `${row.expectedArrivalAt}T00:00:00` : row.expectedArrivalAt })) : [],
      items: form.value.items.map((line) => ({
        productTypeId: String(line.product.id),
        productName: String(line.product.name || line.product.productName || ''),
        quantity: line.quantity,
        unit: storageUnitCode(line.unit),
        unitPrice: line.unitPrice,
        taxRate: line.taxRate,
        specification: line.product.specification || line.product.packageSpec || undefined,
        packagingSpecId: packagingSpecIdForUnit(line.product, line.unit),
        sourceWarehouseCode: line.sourceWarehouseCode,
      })),
    });
    const orderId = String(response?.data?.id || '');
    dirty.value = false;
    ElMessage.success(`已创建${label('salesOrder')}，请在订单详情中选择一次性全量或分批发运方式`);
    await router.push(orderId ? `/sales/orders/${orderId}` : '/sales/orders');
  } catch {
    // request interceptor surfaces the server reason; values remain in the form for retry.
  } finally {
    loading.value = false;
  }
}

async function leave(): Promise<void> {
  if (!dirty.value) { await router.push('/sales/orders'); return; }
  try {
    await ElMessageBox.confirm('当前页面有未保存内容，确定离开吗？', '离开建单', { type: 'warning', confirmButtonText: '离开', cancelButtonText: '继续编辑' });
    dirty.value = false;
    await router.push('/sales/orders');
  } catch { /* keep editing */ }
}

onMounted(loadOptions);
</script>

<template>
  <div class="sales-order-new-page">
    <div class="page-header">
      <div>
        <el-button link @click="leave">返回销售订单</el-button>
        <h2>新建{{ label('salesOrder') }}</h2>
        <p>只选择可销售的成品/半成品；客户自带原料请登记在客供原料区。</p>
      </div>
      <div class="page-actions">
        <el-button @click="leave">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submit">保存草稿</el-button>
      </div>
    </div>

    <el-alert v-if="!sourceWarehouses.length && !loadingOptions" type="warning" :closable="false" show-icon title="当前没有合法来源仓库，保存会被阻止。请先配置 RAW / WORKSHOP / WIP / LINESIDE / LOGISTICS / FINISHED 仓库。" />
    <el-skeleton v-if="loadingOptions" :rows="8" animated />
    <el-form v-else :model="form" label-width="120px" class="order-form">
      <el-card shadow="never" class="content-card">
        <template #header><span class="section-title">客户与订单信息</span></template>
        <el-row :gutter="20">
          <el-col :md="12" :xs="24"><el-form-item label="客户" required><el-select v-model="form.customerId" filterable placeholder="请选择客户" style="width:100%" @change="dirty = true"><el-option v-for="customer in customers" :key="customer.id" :label="customer.name" :value="String(customer.id)" /></el-select><small v-if="selectedCustomer">{{ selectedCustomer.name }}</small></el-form-item></el-col>
          <el-col :md="6" :xs="24"><el-form-item label="交货日期"><el-date-picker v-model="form.requiredDeliveryDate" value-format="YYYY-MM-DD" type="date" style="width:100%" @change="dirty = true" /></el-form-item></el-col>
          <el-col :md="6" :xs="24"><el-form-item label="业务员"><el-input v-model="form.salesperson" @input="dirty = true" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="交货地址"><el-input v-model="form.deliveryAddress" @input="dirty = true" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" @input="dirty = true" /></el-form-item>
      </el-card>

      <el-card shadow="never" class="content-card">
        <template #header><span class="section-title">加工与物料供应</span></template>
        <el-row :gutter="20">
          <el-col :md="12" :xs="24"><el-form-item label="加工方式" required><el-select v-model="form.processingMode" style="width:100%" @change="onProcessingModeChange"><el-option v-for="option in SALES_PROCESSING_MODE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item></el-col>
          <el-col :md="12" :xs="24"><el-form-item label="物料供应方式" required><el-select v-model="form.materialSupplyMode" style="width:100%" @change="onMaterialSupplyModeChange"><el-option v-for="option in MATERIAL_SUPPLY_MODE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" :disabled="option.value === 'CUSTOMER_SUPPLIED' && form.processingMode === 'STANDARD_SALE'" /></el-select></el-form-item></el-col>
        </el-row>
        <div v-if="requiresSuppliedMaterials" class="supplied-materials">
          <el-alert type="info" :closable="false" title="客户自带原料只登记预计到货，不会混入销售产品，也不会在销售侧直接增加库存。" />
          <div v-for="(row, index) in form.suppliedMaterials" :key="index" class="supplied-row">
            <el-select v-model="row.materialTypeId" filterable placeholder="选择客供原料" @change="onSuppliedMaterialChange(row)"><el-option v-for="material in suppliedMaterialOptions" :key="material.id" :label="`${material.name} (${material.code || '无编码'})`" :value="String(material.id)" /></el-select>
            <el-input-number v-model="row.expectedQuantity" :min="0" :precision="3" @change="dirty = true" /><span>{{ displayUnit(row.unit) || '单位' }}</span>
            <el-date-picker v-model="row.expectedArrivalAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="预计到货" @change="dirty = true" />
            <el-select v-model="row.targetWarehouseId" placeholder="目标仓库" @change="dirty = true"><el-option v-for="warehouse in warehouses" :key="warehouse.id" :label="warehouseLabel(warehouse)" :value="warehouse.id" /></el-select>
            <el-button type="danger" link @click="form.suppliedMaterials.splice(index, 1); dirty = true">移除</el-button>
          </div>
          <el-button link type="primary" @click="addSuppliedMaterial">+ 添加客供原料</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="content-card">
        <template #header><div class="section-header"><span class="section-title">销售产品</span><el-button type="primary" @click="showProductPicker = true">添加产品</el-button></div></template>
        <el-alert type="success" :closable="false" title="可搜索中文名、产品编码、全拼或拼音首字母；这里只显示真实可销售成品/半成品。" />
        <el-table v-if="form.items.length" :data="form.items" border class="line-table">
          <el-table-column label="产品" min-width="240"><template #default="{ row }"><strong>{{ row.product.name || row.product.productName }}</strong><div class="muted">{{ row.product.code || '无编码' }} · {{ row.product.specification || row.product.packageSpec || '无规格' }}</div></template></el-table-column>
          <el-table-column label="数量" width="150"><template #default="{ row }"><el-input-number v-model="row.quantity" :min="0.001" :precision="3" @change="dirty = true" /></template></el-table-column>
          <el-table-column label="单位" width="150"><template #default="{ row }"><el-select v-model="row.unit" placeholder="选择规格单位" @change="dirty = true"><el-option v-for="unit in salesOrderUnitOptions(row.product)" :key="unit" :label="displayUnit(unit)" :value="unit" /></el-select></template></el-table-column>
          <el-table-column label="含税单价" width="170"><template #default="{ row }"><el-input-number v-model="row.taxIncludedUnitPrice" :min="0.01" :precision="2" @update:model-value="recalculateSalesUnitPrice(row)" /></template></el-table-column>
          <el-table-column label="税点(%)" width="150"><template #default="{ row }"><el-input-number v-model="row.taxRate" :min="0" :max="100" :precision="2" @update:model-value="recalculateSalesUnitPrice(row)" /></template></el-table-column>
          <el-table-column label="来源仓库" min-width="220"><template #default="{ row }"><el-select v-model="row.sourceWarehouseCode" filterable placeholder="请选择合法来源仓库" @change="dirty = true"><el-option-group v-for="group in warehouseGroups" :key="group.label" :label="group.label"><el-option v-for="warehouse in group.warehouses" :key="warehouse.code" :label="warehouseLabel(warehouse)" :value="warehouse.code" /></el-option-group></el-select></template></el-table-column>
          <el-table-column label="操作" width="80"><template #default="{ $index }"><el-button type="danger" link @click="removeProduct($index)">移除</el-button></template></el-table-column>
        </el-table>
        <el-empty v-else description="尚未添加销售产品" />
      </el-card>

    </el-form>

    <el-dialog v-model="showProductPicker" title="选择销售产品" width="760px" destroy-on-close>
      <el-input v-model="productQuery" clearable autofocus placeholder="搜索中文名 / 编码 / 全拼 / 首字母" />
      <div class="product-picker-list" role="listbox" aria-label="可销售成品和半成品">
        <button v-for="product in filteredProducts" :key="String(product.id)" type="button" class="product-picker-row" @click="addProduct(product)">
          <span><strong>{{ product.name || product.productName }}</strong><small>{{ product.code || '无编码' }} · {{ product.specification || product.packageSpec || '无规格' }}</small></span><span>{{ productCategory(product) }}</span>
        </button>
        <el-empty v-if="!filteredProducts.length" description="没有匹配的可销售成品/半成品" />
      </div>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.sales-order-new-page { min-height: 100%; padding: 20px; background: #f4f6f9; }
.page-header, .section-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.page-header { margin-bottom: 16px; h2 { margin: 6px 0 4px; color: #1a2332; } p { margin: 0; color: #7a8599; } }
.page-actions { display: flex; gap: 8px; }
.content-card { margin-bottom: 16px; border-radius: 10px; box-shadow: 0 2px 12px rgba(27,101,168,.06); }
.section-title { font-weight: 600; color: #1a2332; }
.muted, small { color: #7a8599; font-size: 12px; }
.supplied-row { display: flex; align-items: center; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
.supplied-row .el-select { width: 220px; }
.line-table { margin-top: 12px; }
.product-picker-list { max-height: 480px; overflow-y: auto; margin-top: 12px; }
.product-picker-row { width: 100%; display: flex; justify-content: space-between; text-align: left; padding: 12px; border: 0; border-bottom: 1px solid #edf2f7; background: #fff; cursor: pointer; color: #1a2332; }
.product-picker-row:hover, .product-picker-row:focus-visible { background: #f0f7ff; outline: 2px solid #1b65a8; outline-offset: -2px; }
.product-picker-row span:first-child { display: flex; flex-direction: column; gap: 4px; }
@media (max-width: 760px) { .sales-order-new-page { padding: 12px; } .page-header { align-items: flex-start; flex-direction: column; } .page-actions { width: 100%; justify-content: flex-end; } }
</style>
