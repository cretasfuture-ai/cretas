<script setup lang="ts">
/**
 * 分仓库存查询 (PR #309 B2=B, 2026-05-11)
 *
 * 客户需求: 工厂多仓库 + 跨工厂可见 — 用户切换 (factory × warehouse) 查询
 * 当前快照库存 (原料 / 成品)。
 *
 * Multi-tenancy 保护 (backend-enforced):
 * - factoryId dropdown 数据源: GET /api/mobile/{path-factoryId}/factories/network
 *   (PR #314 C2) — 只返回当前用户可见的工厂网络。
 * - 查询时 backend 校验 targetFactoryId ∈ network, 拒绝越权访问。
 *
 * NOTE: 这里不是 A5 跨工厂 sales aggregation (那个 feature-flag 灰度)。
 * 这是 read-only 单工厂查询的 dropdown — 一次只看一个工厂的库存。
 */
import { ref, computed, watch, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Search, Refresh } from '@element-plus/icons-vue';
// 2026-07-02 fix: warehouseDisplayName now shows the warehouse's real DB
// `name` (customer-configured) instead of a hardcoded WH-WKS/WH-LOG label —
// see utils/warehouse.ts for the LIUSHANMEN "同仓库多名字" incident.
import { warehouseDisplayName } from '@/utils/warehouse';
import { displayUnit } from '@/utils/unitPricing';
import { resolveWarehouseInventoryEmpty } from './warehouseInventoryEmptyState';
import { aggregateInventory, isCompleteInventory, numericQuantity } from './inventoryAggregation';
import { usePermissionStore } from '@/store/modules/permission';
import { formatAmount, fmtQty } from '@/utils/tableFormatters';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const ownFactoryId = computed(() => authStore.factoryId);

// ==================== 工厂下拉 ====================

interface FactoryNetworkEntry {
  factoryId: string;
  factoryName: string;
}

const factoryOptions = ref<FactoryNetworkEntry[]>([]);
const selectedFactoryId = ref('');
const factoryLoading = ref(false);

async function loadFactoryNetwork() {
  if (!ownFactoryId.value) return;
  factoryLoading.value = true;
  try {
    const resp = await get<FactoryNetworkEntry[]>(
      `/${ownFactoryId.value}/factories/network`
    );
    if (resp.success && Array.isArray(resp.data)) {
      factoryOptions.value = resp.data;
      // Default: user's own factory
      if (!selectedFactoryId.value) {
        selectedFactoryId.value = ownFactoryId.value;
      }
    } else if (resp.success === false) {
      ElMessage.error(resp.message || '加载工厂列表失败');
    }
  } catch (e) {
    console.error('加载工厂网络失败:', e);
  } finally {
    factoryLoading.value = false;
  }
}

// ==================== 仓库下拉 ====================

interface FactoryWarehouse {
  id: string;
  factoryId: string;
  code: string;
  name: string;
  type: string;
  isActive: boolean;
}

const warehouseOptions = ref<FactoryWarehouse[]>([]);
const selectedWarehouseId = ref('');
const warehouseLoading = ref(false);

async function loadWarehouses() {
  if (!ownFactoryId.value || !selectedFactoryId.value) return;
  warehouseLoading.value = true;
  try {
    const isCrossFactory = selectedFactoryId.value !== ownFactoryId.value;
    const url = isCrossFactory
      ? `/${ownFactoryId.value}/inventory/warehouses?targetFactoryId=${encodeURIComponent(selectedFactoryId.value)}`
      : `/${ownFactoryId.value}/factory/warehouses`;
    const resp = await get<FactoryWarehouse[]>(url);
    if (resp.success && Array.isArray(resp.data)) {
      warehouseOptions.value = resp.data.filter((w) => w.isActive !== false);
      // Default: WH-LOG (总仓) if available, else first
      const defaultWh =
        warehouseOptions.value.find((w) => w.code === 'WH-LOG') ||
        warehouseOptions.value[0];
      selectedWarehouseId.value = defaultWh ? defaultWh.id : '';
    } else if (resp.success === false) {
      ElMessage.error(resp.message || '加载仓库列表失败');
      warehouseOptions.value = [];
      selectedWarehouseId.value = '';
    }
  } catch (e) {
    console.error('加载仓库失败:', e);
    warehouseOptions.value = [];
    selectedWarehouseId.value = '';
  } finally {
    warehouseLoading.value = false;
  }
}

// ==================== 库存数据 ====================

interface MaterialBatchRow {
  ownership?: string;
  ownerCustomerId?: string;
  id: string;
  batchNumber: string;
  materialCode?: string;
  materialName?: string;
  materialTypeId?: string;
  currentQuantity?: number;
  /**
   * 报工在途、尚未结单的预扣消耗量 (缺陷 A, 2026-09-02)。仅本页请求的
   * WarehouseInventoryController#queryByWarehouse 填充; undefined/null = 未知,
   * 按 0 处理(即 可用 == 账面)。⛔ 不要拿它覆盖 currentQuantity —— 两个数各自
   * 都对(账面 vs 可投料), 合并会丢信息量, 必须并排显示且各自标清口径。
   */
  unsettledConsumption?: number | null;
  receiptQuantity?: number;
  quantityUnit?: string;
  unit?: string;
  warehouseCode?: string;
  warehouseName?: string;
  warehouseId?: string;
  expireDate?: string;
  expirationDate?: string;
  expiryDate?: string;
  storageLocation?: string;
  status?: string;
  supplierName?: string;
  unitPrice?: number;
}

interface FinishedGoodsRow {
  ownership?: string;
  ownerCustomerId?: string;
  unitCost?: number;
  id: string;
  batchNumber: string;
  productName?: string;
  productTypeId?: string;
  producedQuantity?: number;
  shippedQuantity?: number;
  reservedQuantity?: number;
  unit?: string;
  warehouseId?: string;
  expireDate?: string;
  productionDate?: string;
  storageLocation?: string;
  status?: string;
  unitPrice?: number;
}

interface InventoryByWarehouseData {
  factoryId: string;
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  warehouseType?: string;
  materials: MaterialBatchRow[];
  products: FinishedGoodsRow[];
  materialCount: number;
  productCount: number;
}

const loading = ref(false);
const data = ref<InventoryByWarehouseData | null>(null);
const activeTab = ref('materials');
const keywordFilter = ref('');
const viewMode = ref('summary');
const inventoryError = ref('');

const aggregateRows = computed(() => {
  const snapshot = data.value;
  if (!snapshot) return [];
  const scope = { factoryId: snapshot.factoryId, warehouseId: snapshot.warehouseId };
  return activeTab.value === 'products'
    ? aggregateInventory(filteredProducts.value.map((row) => {
      const quantity = numericQuantity(row.producedQuantity);
      const shipped = numericQuantity(row.shippedQuantity);
      const reserved = numericQuantity(row.reservedQuantity);
      const ledger = quantity == null || shipped == null ? null : quantity - shipped;
      const price = numericQuantity(row.unitCost);
      return { ...scope, id: row.id, itemId: row.productTypeId, name: row.productName,
        unit: row.unit, status: row.status, ownership: row.ownership, ownerCustomerId: row.ownerCustomerId, quantity: ledger,
        available: ledger == null || reserved == null ? null : ledger - reserved,
        value: ledger == null || price == null ? null : ledger * price };
    }))
    : aggregateInventory(filteredMaterials.value.map((row) => {
      const quantity = numericQuantity(row.currentQuantity);
      const price = numericQuantity(row.unitPrice);
      return { ...scope, id: row.id, itemId: row.materialTypeId, name: row.materialName, code: row.materialCode,
        unit: row.quantityUnit || row.unit, status: row.status, ownership: row.ownership, ownerCustomerId: row.ownerCustomerId, quantity,
        available: quantity == null ? null : Math.max(0, quantity - (numericQuantity(row.unsettledConsumption) ?? 0)),
        value: quantity == null || price == null ? null : quantity * price };
    }));
});

let inventoryRequest = 0;
async function loadInventory() {
  const requestId = ++inventoryRequest;
  if (!ownFactoryId.value || !selectedFactoryId.value || !selectedWarehouseId.value) {
    return;
  }
  loading.value = true;
  inventoryError.value = '';
  data.value = null;
  try {
    const url =
      `/${ownFactoryId.value}/inventory/by-warehouse?` +
      `targetFactoryId=${encodeURIComponent(selectedFactoryId.value)}` +
      `&warehouseId=${encodeURIComponent(selectedWarehouseId.value)}`;
    const resp = await get<InventoryByWarehouseData>(url);
    if (requestId !== inventoryRequest) return;
    if (resp.success && resp.data) {
      if (resp.data.factoryId !== selectedFactoryId.value || resp.data.warehouseId !== selectedWarehouseId.value
        || !Array.isArray(resp.data.materials) || !Array.isArray(resp.data.products)
        || !isCompleteInventory(resp.data.materials, resp.data.materialCount)
        || !isCompleteInventory(resp.data.products, resp.data.productCount)) {
        throw new Error('库存响应范围不一致或批次数据不完整，无法显示仓库汇总，请刷新重试');
      }
      data.value = resp.data;
    } else if (resp.success === false) {
      inventoryError.value = resp.message || '查询库存失败';
      ElMessage.error(resp.message || '查询库存失败');
      data.value = null;
    }
  } catch (e) {
    if (requestId !== inventoryRequest) return;
    inventoryError.value = e instanceof Error ? e.message : '查询库存失败';
    console.error('查询库存失败:', e);
    data.value = null;
  } finally {
    if (requestId === inventoryRequest) loading.value = false;
  }
}

// ==================== 过滤后的展示数据 ====================

const filteredMaterials = computed<MaterialBatchRow[]>(() => {
  if (!data.value) return [];
  const kw = keywordFilter.value.trim().toLowerCase();
  if (!kw) return data.value.materials;
  return data.value.materials.filter((r) => {
    const name = (r.materialName || '').toLowerCase();
    const code = (r.materialCode || '').toLowerCase();
    const bn = (r.batchNumber || '').toLowerCase();
    return name.includes(kw) || code.includes(kw) || bn.includes(kw);
  });
});

const filteredProducts = computed<FinishedGoodsRow[]>(() => {
  if (!data.value) return [];
  const kw = keywordFilter.value.trim().toLowerCase();
  if (!kw) return data.value.products;
  return data.value.products.filter((r) => {
    const name = (r.productName || '').toLowerCase();
    const bn = (r.batchNumber || '').toLowerCase();
    return name.includes(kw) || bn.includes(kw);
  });
});

// 过期日期高亮 (与 /warehouse/inventory 风格一致)
function getExpireDateClass(row: { expireDate?: string; expirationDate?: string; expiryDate?: string }): string {
  const dateStr = row.expireDate || row.expirationDate || row.expiryDate;
  if (!dateStr) return '';
  const expDate = new Date(dateStr);
  const now = new Date();
  const diffDays = Math.ceil((expDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
  if (diffDays < 0) return 'expire-expired';
  if (diffDays <= 30) return 'expire-danger';
  if (diffDays <= 60) return 'expire-warning';
  return '';
}

const STATUS_MAP: Record<string, { text: string; type: '' | 'success' | 'info' | 'warning' | 'danger' }> = {
  AVAILABLE: { text: '可用', type: 'success' },
  IN_STOCK: { text: '库存中', type: 'success' },
  FRESH: { text: '鲜品', type: '' },
  FROZEN: { text: '冻品', type: 'info' },
  RESERVED: { text: '已预留', type: 'warning' },
  INSPECTING: { text: '质检中', type: 'warning' },
  DEPLETED: { text: '已耗尽', type: 'info' },
  USED_UP: { text: '已用完', type: 'info' },
  EXPIRED: { text: '已过期', type: 'danger' },
  SCRAPPED: { text: '已报废', type: 'danger' },
};

function getStatusType(status?: string) {
  if (!status) return 'info' as const;
  return STATUS_MAP[status]?.type ?? 'info';
}

function getStatusText(status?: string) {
  if (!status) return '-';
  return STATUS_MAP[status]?.text ?? status;
}

// 可用数量 (成品: produced - shipped - reserved)
function getProductAvailable(row: FinishedGoodsRow): number {
  const produced = Number(row.producedQuantity) || 0;
  const shipped = Number(row.shippedQuantity) || 0;
  const reserved = Number(row.reservedQuantity) || 0;
  return produced - shipped - reserved;
}

// ==================== 原料「账面 / 可用」两个数 (缺陷 A, 2026-09-02) ====================
//
// LIUSHANMEN 生产实测: 同一批次 1399, 分仓库存查询显示「当前数量」5900kg,
// 报工投料区「生产仓可用」显示 5800kg —— 两个数都对: 5900 是账面(未结单),
// 5800 是可投料量(已减报工在途预扣)。旧版列头只写「当前数量」不说口径,
// 工人拿账面数去投料会短 100kg。⛔ 不把一个数改成另一个 —— 那会丢信息量,
// 这里改成并排显示两个数, 各自标清口径 (账面 / 可用)。
//
// currentQuantity(账面) 由后端 currentQuantity 直接下发 (= receipt-used-reserved,
// reserved 目前恒 0, 报工不写这一列)。unsettledConsumption(报工在途未结单预扣)
// 只有 WarehouseInventoryController#queryByWarehouse 这个端点会填充 (读 material_
// consumptions, interim_settled_at IS NULL), 与报损/调拨弹窗同一口径, 未知时按 0
// 处理 —— 此时账面==可用, 而不是隐藏可用列 (阳性对照要求两者都显示且相等)。

/** 账面数量: 后端 currentQuantity, 缺失时返回 null (不猜)。 */
function getMaterialLedgerQty(row: MaterialBatchRow): number | null {
  return row.currentQuantity == null ? null : Number(row.currentQuantity);
}

/** 可用数量 = 账面 − 报工在途未结单预扣, clamp ≥ 0 (与 wastage-reports「货架实物」同一口径)。 */
function getMaterialAvailableQty(row: MaterialBatchRow): number | null {
  const ledger = getMaterialLedgerQty(row);
  if (ledger == null) return null;
  const unsettled = Number(row.unsettledConsumption) || 0;
  const available = ledger - unsettled;
  return available < 0 ? 0 : available;
}

// ==================== 空态文案 (缺陷 B, 2026-09-02) ====================
//
// 「搜不到」≠「仓库是空的」——纯函数判定见 warehouseInventoryEmptyState.ts,
// 与 procurement/orders/materialDropdownEmptyState.ts (#459) 同一判定顺序:
// 先看这一类批次总数(与搜索词无关), 再看有没有搜索词。

const materialEmptyText = computed<string>(() =>
  resolveWarehouseInventoryEmpty({
    totalCount: data.value?.materialCount ?? 0,
    query: keywordFilter.value,
    kind: 'materials',
  }).text,
);

const productEmptyText = computed<string>(() =>
  resolveWarehouseInventoryEmpty({
    totalCount: data.value?.productCount ?? 0,
    query: keywordFilter.value,
    kind: 'products',
  }).text,
);

// ==================== 联动 watch ====================

watch(selectedFactoryId, async (v) => {
  if (!v) return;
  await loadWarehouses();
  if (selectedWarehouseId.value) {
    await loadInventory();
  } else {
    data.value = null;
  }
});

watch(selectedWarehouseId, async (v) => {
  if (!v) {
    data.value = null;
    return;
  }
  await loadInventory();
});

function handleRefresh() {
  keywordFilter.value = '';
  loadInventory();
}

onMounted(async () => {
  await loadFactoryNetwork();
});
</script>

<template>
  <div class="page-wrapper">
    <el-card class="filter-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">分仓库存查询</span>
          <span class="hint">按工厂 × 仓库查看当前快照</span>
        </div>
      </template>
      <div class="filter-row">
        <div class="filter-item">
          <label>工厂:</label>
          <el-select
            v-model="selectedFactoryId"
            :loading="factoryLoading"
            placeholder="选择工厂"
            style="width: 220px"
            filterable
          >
            <el-option
              v-for="opt in factoryOptions"
              :key="opt.factoryId"
              :label="opt.factoryName"
              :value="opt.factoryId"
            >
              <span style="float: left">{{ opt.factoryName }}</span>
              <span style="float: right; color: #909399; font-size: 12px">{{ opt.factoryId }}</span>
            </el-option>
          </el-select>
        </div>

        <div class="filter-item">
          <label>仓库:</label>
          <el-select
            v-model="selectedWarehouseId"
            :loading="warehouseLoading"
            :disabled="!selectedFactoryId"
            placeholder="选择仓库"
            style="width: 220px"
          >
            <el-option
              v-for="w in warehouseOptions"
              :key="w.id"
              :label="`${warehouseDisplayName(w.name, w.code)} (${w.code})`"
              :value="w.id"
            />
          </el-select>
        </div>

        <div class="filter-item">
          <label>搜索:</label>
          <el-input
            v-model="keywordFilter"
            placeholder="批次号 / 物料编码 / 物料名称"
            :prefix-icon="Search"
            clearable
            style="width: 240px"
          />
        </div>

        <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
      </div>
    </el-card>

    <el-card v-if="data" class="summary-card" shadow="never">
      <div class="summary-row">
        <div class="summary-item">
          <div class="summary-label">当前选择</div>
          <div class="summary-value">
            <strong>{{ data.factoryId }}</strong>
            <span class="separator">/</span>
            <strong>{{ warehouseDisplayName(data.warehouseName, data.warehouseCode) }}</strong>
            <span class="badge">{{ data.warehouseCode }}</span>
          </div>
        </div>
        <div class="summary-item">
          <div class="summary-label">原料批次</div>
          <div class="summary-value strong-blue">{{ data.materialCount }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-label">成品批次</div>
          <div class="summary-value strong-green">{{ data.productCount }}</div>
        </div>
      </div>
    </el-card>

    <el-card class="result-card" shadow="never">
      <el-alert v-if="inventoryError" :title="inventoryError" type="error" :closable="false" show-icon />
      <el-radio-group v-if="activeTab !== 'overview'" v-model="viewMode" aria-label="库存展示方式" style="margin-bottom: 12px">
        <el-radio-button value="summary">按物品汇总</el-radio-button>
        <el-radio-button value="batches">批次明细</el-radio-button>
      </el-radio-group>
      <template v-if="viewMode === 'summary' && activeTab !== 'overview' && data">
        <p class="hint">当前仓库全部批次，按筛选结果汇总；不同物品、单位与状态分别列出。原料余量扣除在途消耗，成品余量扣除预留；余量不代表质量放行。未提供的成本估值显示“—”。</p>
        <el-table :data="aggregateRows" :empty-text="activeTab === 'products' ? productEmptyText : materialEmptyText" border stripe data-testid="inventory-aggregate">
          <el-table-column prop="code" label="物料编码" width="140" />
          <el-table-column prop="name" label="物品名称" min-width="160" />
          <el-table-column prop="itemId" label="物品ID" min-width="140" />
          <el-table-column label="归属" min-width="150"><template #default="{ row }">{{ row.ownerCustomerId ? `客户 ${row.ownerCustomerId}` : row.ownership || '未提供' }}</template></el-table-column>
          <el-table-column prop="batchCount" label="批次数" width="90" />
          <el-table-column label="账面合计" width="130"><template #default="{ row }">{{ row.quantity == null ? '—' : fmtQty(row.quantity) }}</template></el-table-column>
          <el-table-column label="扣减后余量" width="130"><template #default="{ row }">{{ row.available == null ? '—' : fmtQty(row.available) }}</template></el-table-column>
          <el-table-column label="单位" width="80"><template #default="{ row }">{{ displayUnit(row.unit) || '—' }}</template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="{ row }">{{ getStatusText(row.status) }}</template></el-table-column>
          <el-table-column v-if="permissionStore.canViewPrice" label="库存成本估值" width="140"><template #default="{ row }">{{ row.value == null ? '—' : formatAmount(row.value) }}</template></el-table-column>
        </el-table>
      </template>
      <el-tabs v-model="activeTab">
        <el-tab-pane name="materials">
          <template #label>
            原料库存
            <span v-if="data" class="tab-count">({{ filteredMaterials.length }})</span>
          </template>

          <el-table
            v-if="viewMode === 'batches'"
            :data="filteredMaterials"
            v-loading="loading"
            :empty-text="materialEmptyText"
            stripe
            border
            style="width: 100%"
          >
            <el-table-column prop="batchNumber" label="批次号" width="180" show-overflow-tooltip />
            <el-table-column prop="materialCode" label="物料编码" width="150" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.materialCode || '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="materialName" label="原料名称" min-width="160" show-overflow-tooltip />
            <!-- 缺陷 A (2026-09-02): 「当前数量」拆成账面/可用两列, 各自标清口径,
                 ⛔ 不互相覆盖 —— 见脚本区 getMaterialLedgerQty / getMaterialAvailableQty 注释。 -->
            <el-table-column label="账面数量" width="110" align="right">
              <template #header>
                <el-tooltip content="收货量 − 已用 − 预留（不含报工在途尚未结单的预扣量）" placement="top">
                  <span>账面数量</span>
                </el-tooltip>
              </template>
              <template #default="{ row }">
                {{ getMaterialLedgerQty(row) ?? '-' }}
              </template>
            </el-table-column>
            <el-table-column label="可用数量" width="110" align="right">
              <template #header>
                <el-tooltip content="账面数量 − 报工在途未结单预扣量，即当前可投料量" placement="top">
                  <span>可用数量</span>
                </el-tooltip>
              </template>
              <template #default="{ row }">
                <strong>{{ getMaterialAvailableQty(row) ?? '-' }}</strong>
              </template>
            </el-table-column>
            <el-table-column label="单位" width="80" align="center">
              <template #default="{ row }">
                {{ displayUnit(row.quantityUnit || row.unit) || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="仓库" width="150">
              <template #default="{ row }">
                {{ warehouseDisplayName(row.warehouseName, row.warehouseCode) }}
                <span class="muted" v-if="row.warehouseCode">({{ row.warehouseCode }})</span>
              </template>
            </el-table-column>
            <el-table-column prop="storageLocation" label="存储位置" width="130" show-overflow-tooltip />
            <el-table-column prop="supplierName" label="供应商" width="140" show-overflow-tooltip />
            <el-table-column label="过期日期" width="120">
              <template #default="{ row }">
                <span :class="getExpireDateClass(row)">
                  {{ row.expireDate || row.expirationDate || row.expiryDate || '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="getStatusType(row.status)" size="small">{{ getStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane name="products">
          <template #label>
            成品库存
            <span v-if="data" class="tab-count">({{ filteredProducts.length }})</span>
          </template>

          <el-table
            v-if="viewMode === 'batches'"
            :data="filteredProducts"
            v-loading="loading"
            :empty-text="productEmptyText"
            stripe
            border
            style="width: 100%"
          >
            <el-table-column prop="batchNumber" label="批次号" width="180" show-overflow-tooltip />
            <el-table-column prop="productName" label="产品名称" min-width="160" show-overflow-tooltip />
            <el-table-column label="生产数量" width="110" align="right">
              <template #default="{ row }">
                {{ row.producedQuantity ?? '-' }}
              </template>
            </el-table-column>
            <el-table-column label="已发货" width="100" align="right">
              <template #default="{ row }">
                {{ row.shippedQuantity ?? 0 }}
              </template>
            </el-table-column>
            <el-table-column label="预留" width="100" align="right">
              <template #default="{ row }">
                {{ row.reservedQuantity ?? 0 }}
              </template>
            </el-table-column>
            <el-table-column label="可用数量" width="110" align="right">
              <template #default="{ row }">
                <strong>{{ getProductAvailable(row) }}</strong>
              </template>
            </el-table-column>
            <el-table-column label="单位" width="80" align="center">
              <template #default="{ row }">
                {{ displayUnit(row.unit) || '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="storageLocation" label="存储位置" width="130" show-overflow-tooltip />
            <el-table-column label="生产日期" width="110">
              <template #default="{ row }">
                {{ row.productionDate || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="过期日期" width="120">
              <template #default="{ row }">
                <span :class="getExpireDateClass(row)">{{ row.expireDate || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="getStatusType(row.status)" size="small">{{ getStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane name="overview">
          <template #label>
            总览
          </template>
          <div v-if="!data" class="empty-overview">请先选择工厂和仓库</div>
          <div v-else class="overview-grid">
            <div class="overview-block">
              <div class="overview-title">仓库信息</div>
              <div class="overview-row"><span class="overview-label">工厂ID</span><span>{{ data.factoryId }}</span></div>
              <div class="overview-row"><span class="overview-label">仓库</span><span>{{ warehouseDisplayName(data.warehouseName, data.warehouseCode) }} ({{ data.warehouseCode }})</span></div>
              <div class="overview-row" v-if="data.warehouseType"><span class="overview-label">类型</span><span>{{ data.warehouseType }}</span></div>
              <div class="overview-row"><span class="overview-label">仓库UUID</span><span class="muted small">{{ data.warehouseId }}</span></div>
            </div>
            <div class="overview-block">
              <div class="overview-title">汇总</div>
              <div class="overview-row"><span class="overview-label">原料批次数</span><strong class="strong-blue">{{ data.materialCount }}</strong></div>
              <div class="overview-row"><span class="overview-label">成品批次数</span><strong class="strong-green">{{ data.productCount }}</strong></div>
              <div class="overview-row"><span class="overview-label">查询时间</span><span>{{ new Date().toLocaleString() }}</span></div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-header {
  display: flex;
  align-items: baseline;
  gap: 12px;

  .page-title {
    font-size: 16px;
    font-weight: 600;
    color: #303133;
  }

  .hint {
    font-size: 13px;
    color: #909399;
  }
}

.filter-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: center;
}

.filter-item {
  display: flex;
  gap: 8px;
  align-items: center;

  label {
    font-size: 14px;
    color: #606266;
  }
}

.summary-card {
  :deep(.el-card__body) {
    padding: 16px 20px;
  }
}

.summary-row {
  display: flex;
  gap: 32px;
  align-items: center;
  flex-wrap: wrap;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-label {
  font-size: 12px;
  color: #909399;
}

.summary-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 8px;

  .separator {
    color: #c0c4cc;
  }

  .badge {
    font-size: 12px;
    background: #ecf5ff;
    color: #409eff;
    padding: 2px 8px;
    border-radius: 3px;
    font-weight: normal;
  }
}

.strong-blue {
  color: #409eff;
}

.strong-green {
  color: #67c23a;
}

.tab-count {
  color: #909399;
  font-size: 12px;
  margin-left: 4px;
}

.muted {
  color: #909399;
  font-size: 12px;
  margin-left: 4px;

  &.small {
    font-size: 11px;
    font-family: monospace;
  }
}

.empty-overview {
  text-align: center;
  padding: 40px;
  color: #909399;
}

.overview-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  padding: 8px 4px;
}

.overview-block {
  background: #fafafa;
  border-radius: 4px;
  padding: 16px;
}

.overview-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #303133;
}

.overview-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  font-size: 14px;
  border-bottom: 1px dashed #ebeef5;

  &:last-child {
    border-bottom: none;
  }

  .overview-label {
    color: #909399;
  }
}

// 过期日期高亮
.expire-expired {
  color: #f56c6c;
  font-weight: 600;
}

.expire-danger {
  color: #e6a23c;
  font-weight: 500;
}

.expire-warning {
  color: #909399;
}

.result-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 12px 16px;
  }
}
</style>
