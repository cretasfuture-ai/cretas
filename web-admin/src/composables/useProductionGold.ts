/**
 * useProductionGold — 工厂生产 gold 数据加载器
 *
 * 封装三个 /api/smartbi/factory/* Python gold 端点:
 *   - cost-structure  → 按日成本汇总 + summary
 *   - yield-trend     → 按日出成率趋势
 *   - product-cost-compare → 按产品聚合成本/出成率
 *
 * ProductionAnalysis.vue 和 FactoryCostAnalysis.vue 都可复用此 composable,
 * A4 拥有修改权; A3 只读不改。
 *
 * RBAC: 成本字段 (totalMaterialCost 等) 后端按 canViewPrice 角色脱敏返 null,
 *       调用方自行决定是否渲染成本区。
 */
import { ref, shallowRef, computed, onScopeDispose, type Ref, type ComputedRef } from 'vue';
import {
  getExternalProduction,
  getFactoryCostStructure,
  getFactoryYieldTrend,
  getFactoryProductCostCompare,
  type CostStructureData,
  type YieldTrendData,
  type ProductCostCompareData,
  type ExternalProductionData,
} from '@/api/smartbi/factoryGold';
import type { PythonServiceError } from '@/api/smartbi/common';

export interface ProductionGoldDateQuery {
  /** YYYY-MM-DD. undefined = 全部历史. */
  startDate?: string;
  endDate?: string;
}

export function useExternalProduction(factoryId: Ref<string>, summaryOnly = false) {
  // The snapshot is replaced as a whole. Preserve its null-only yieldRate and cost fields.
  const data = shallowRef<ExternalProductionData | null>(null);
  const error = ref('');
  let revision = 0;
  let loadedFactory = '';
  onScopeDispose(() => { revision++; });

  async function load(query: ProductionGoldDateQuery): Promise<'external' | 'native' | 'failed' | 'obsolete'> {
    const factory = factoryId.value;
    const requestRevision = ++revision;
    const current = () => revision === requestRevision && factoryId.value === factory;
    if (factory !== loadedFactory) data.value = null;
    error.value = '';
    if (!factory) return 'native';
    try {
      const result = await getExternalProduction({ factoryId: factory, ...query }, summaryOnly);
      if (!current()) return 'obsolete';
      if (!result.success) {
        data.value = null;
        error.value = result.message || '黑湖生产数据读取失败，请重试';
        return 'failed';
      }
      loadedFactory = factory;
      data.value = result.data;
      return result.data ? 'external' : 'native';
    } catch (cause: unknown) {
      if (!current()) return 'obsolete';
      data.value = null;
      // The existing production pages can be available to roles outside the
      // external analytics permission. Their native reads keep their own gates.
      if ((cause as PythonServiceError)?.status !== 403) {
        error.value = '黑湖生产数据读取失败，请重试';
      }
      return 'failed';
    }
  }

  return { data, error, load };
}

export interface UseProductionGoldReturn {
  loading: Ref<boolean>;
  loadError: Ref<string>;
  costData: Ref<CostStructureData | null>;
  yieldData: Ref<YieldTrendData | null>;
  productData: Ref<ProductCostCompareData | null>;

  /** True when at least one endpoint returned rows */
  hasAnyData: ComputedRef<boolean>;
  hasYieldTrend: ComputedRef<boolean>;
  hasProducts: ComputedRef<boolean>;
  hasCostRows: ComputedRef<boolean>;

  reset: () => void;
  reload: (query: ProductionGoldDateQuery) => Promise<void>;
}

export function useProductionGold(factoryId: Ref<string>): UseProductionGoldReturn {
  const loading = ref(false);
  const loadError = ref('');

  const costData = ref<CostStructureData | null>(null);
  const yieldData = ref<YieldTrendData | null>(null);
  const productData = ref<ProductCostCompareData | null>(null);
  let revision = 0;
  onScopeDispose(() => { revision++; });

  function reset() {
    revision++;
    loading.value = false;
    loadError.value = '';
    costData.value = null;
    yieldData.value = null;
    productData.value = null;
  }

  // ── Derived empty checks ──────────────────────────────────────────────────────
  const hasCostRows = computed(() => (costData.value?.rows?.length ?? 0) > 0);
  const hasYieldTrend = computed(() => (yieldData.value?.trend?.length ?? 0) >= 2);
  const hasProducts = computed(() => (productData.value?.products?.length ?? 0) > 0);
  const hasAnyData = computed(() => hasCostRows.value || hasYieldTrend.value || hasProducts.value);

  async function reload(query: ProductionGoldDateQuery = {}): Promise<void> {
    const fid = factoryId.value;
    const requestRevision = ++revision;
    const current = () => requestRevision === revision && factoryId.value === fid;
    if (!fid) {
      loadError.value = '无法获取工厂 ID，请重新登录';
      return;
    }

    loading.value = true;
    loadError.value = '';

    const args = { factoryId: fid, startDate: query.startDate, endDate: query.endDate };

    try {
      const [costRes, yieldRes, productRes] = await Promise.all([
        getFactoryCostStructure(args),
        getFactoryYieldTrend(args),
        getFactoryProductCostCompare(args),
      ]);
      if (!current()) return;

      if (costRes.success) {
        costData.value = costRes.data;
      } else {
        console.warn('[useProductionGold] cost-structure error:', costRes.message);
      }
      if (yieldRes.success) {
        yieldData.value = yieldRes.data;
      } else {
        console.warn('[useProductionGold] yield-trend error:', yieldRes.message);
      }
      if (productRes.success) {
        productData.value = productRes.data;
      } else {
        console.warn('[useProductionGold] product-cost-compare error:', productRes.message);
      }

      if (!costRes.success && !yieldRes.success && !productRes.success) {
        loadError.value = costRes.message || '加载成本数据失败';
      }
    } catch (e: unknown) {
      if (!current()) return;
      const msg = e instanceof Error ? e.message : '加载生产 gold 数据失败';
      loadError.value = msg;
    } finally {
      if (current()) loading.value = false;
    }
  }

  return {
    loading,
    loadError,
    costData,
    yieldData,
    productData,
    hasAnyData,
    hasYieldTrend,
    hasProducts,
    hasCostRows,
    reset,
    reload,
  };
}
