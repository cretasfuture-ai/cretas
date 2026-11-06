<template>
  <el-dialog
    :model-value="modelValue"
    :title="`影响分析 · ${targetName}`"
    width="1040px"
    destroy-on-close
    data-test="supply-impact-dialog"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-radio-group v-model="event" data-test="supply-impact-event" @change="run">
      <el-radio-button value="disruption">断供</el-radio-button>
      <el-radio-button value="quality">质量问题</el-radio-button>
      <el-radio-button value="price" :disabled="!target.materialTypeId">涨价</el-radio-button>
    </el-radio-group>

    <div class="params">
      <template v-if="event === 'disruption'">
        向后看
        <el-input-number v-model="horizonDays" :min="1" :max="180" size="small" />
        天
      </template>
      <template v-else-if="event === 'quality'">
        入库日期
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" size="small"
                        range-separator="至" start-placeholder="开始" end-placeholder="结束" />
      </template>
      <template v-else>
        涨幅
        <el-input-number v-model="percent" :min="-100" :max="1000" size="small" />
        %
      </template>
      <el-button type="primary" size="small" :loading="loading" data-test="supply-impact-run" @click="run">查询</el-button>
    </div>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <div v-loading="loading" style="min-height: 120px">
      <template v-if="disruption && event === 'disruption'">
        <h4>涉及原料与其它供应商</h4>
        <el-table :data="disruption.materials" size="small" border>
          <el-table-column prop="name" label="原料" />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column label="其它在供供应商">
            <template #default="{ row }">{{ row.otherSuppliers.length ? row.otherSuppliers.join('、') : '无(没有登记备选来源)' }}</template>
          </el-table-column>
        </el-table>
        <h4>依赖它的配方</h4>
        <el-table :data="disruption.recipes" size="small" border empty-text="没有当前生效配方用到" data-test="impact-recipes">
          <el-table-column prop="productName" label="产品" />
          <el-table-column prop="version" label="版本" width="70" />
          <el-table-column prop="materialName" label="原料" />
          <el-table-column label="替代" width="70">
            <template #default="{ row }">{{ row.hasAlternative ? '有' : '无' }}</template>
          </el-table-column>
          <el-table-column label="选择组其它料 / 登记替代料">
            <template #default="{ row }">{{ recipeAlt(row) }}</template>
          </el-table-column>
        </el-table>
        <h4>未来 {{ disruption.horizonDays }} 天内的计划</h4>
        <el-table :data="disruption.plans" size="small" border data-test="impact-plans" empty-text="窗口内没有用到的计划">
          <el-table-column prop="planNumber" label="计划" width="170" />
          <el-table-column prop="plannedDate" label="计划日" width="110" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="materialName" label="原料" />
          <el-table-column label="标准用量" width="220">
            <template #default="{ row }">
              <span v-if="row.requiredQuantity != null">{{ row.requiredQuantity }} {{ row.quantityUnit }}</span>
              <el-text v-else type="warning" size="small">{{ row.quantityReason }}</el-text>
            </template>
          </el-table-column>
        </el-table>
        <h4>库存与在途能撑几天</h4>
        <el-table :data="disruption.stock" size="small" border data-test="impact-stock">
          <el-table-column prop="materialName" label="原料" />
          <el-table-column prop="onHand" label="在库" width="90" />
          <el-table-column prop="inTransitFromSubject" label="在途(受影响方)" width="130" />
          <el-table-column prop="inTransitFromOthers" label="在途(其它来源)" width="130" />
          <el-table-column label="能撑" width="90">
            <template #default="{ row }">{{ row.coverDays != null ? row.coverDays + ' 天' : '算不出' }}</template>
          </el-table-column>
          <el-table-column prop="note" label="说明" />
        </el-table>
        <h4>受影响订单与客户</h4>
        <OrderRows :rows="disruption.orders" />
        <GapList :gaps="disruption.gaps" />
      </template>

      <template v-if="quality && event === 'quality'">
        <h4>范围内入库批次 ({{ quality.sourceBatches.length }})</h4>
        <el-table :data="quality.sourceBatches" size="small" border data-test="impact-batches">
          <el-table-column prop="batchNumber" label="批次" />
          <el-table-column prop="materialName" label="原料" />
          <el-table-column prop="supplierName" label="供应商" />
          <el-table-column prop="receiptDate" label="入库日" width="110" />
          <el-table-column prop="remainingQuantity" label="剩余" width="90" />
          <el-table-column prop="unit" label="单位" width="70" />
        </el-table>
        <p>
          下游：在制品 {{ quality.productionBatches.length }} 批，成品 {{ quality.finishedBatches.length }} 批，
          发货 {{ quality.shipments.length }} 单
        </p>
        <h4>已发 · 客户</h4>
        <el-table :data="quality.customers" size="small" border data-test="impact-customers" empty-text="没有发货记录">
          <el-table-column prop="customerName" label="客户" />
          <el-table-column prop="shipmentCount" label="发货单数" width="100" />
          <el-table-column label="数量">
            <template #default="{ row }">{{ row.quantities.join('、') || '—' }}</template>
          </el-table-column>
        </el-table>
        <h4>成品批</h4>
        <el-table :data="quality.finishedBatches" size="small" border empty-text="无">
          <el-table-column prop="batchNumber" label="批次" />
          <el-table-column prop="name" label="名称" />
        </el-table>
        <SupplyImpactBoxExposure :exposure="quality.boxExposure" />
        <h4>未发订单(同产品)</h4>
        <OrderRows :rows="quality.unshippedOrders" />
        <GapList :gaps="quality.gaps" />
      </template>

      <template v-if="price && event === 'price'">
        <p>档案单价：{{ price.currentUnitPrice ?? '未填' }}；涨幅 {{ price.percent }}%</p>
        <h4>各产品单位成本变化（同开计划标准成本快照口径）</h4>
        <el-table :data="price.products" size="small" border data-test="impact-products">
          <el-table-column prop="planNumber" label="计划" width="170" />
          <el-table-column label="单位成本变化">
            <template #default="{ row }">
              <span v-if="row.unitCostDelta != null">
                +{{ row.unitCostDelta }} 元/{{ row.plannedUnit }}（原料部分 {{ row.unitCostBefore }}）
              </span>
              <el-text v-else type="warning" size="small">{{ row.reason }}</el-text>
            </template>
          </el-table-column>
          <el-table-column prop="planCostDelta" label="整张计划增加(元)" width="140" />
        </el-table>
        <h4>用到该原料的配方</h4>
        <el-table :data="price.recipes" size="small" border empty-text="无" data-test="impact-recipes">
          <el-table-column prop="productName" label="产品" />
          <el-table-column prop="version" label="版本" width="70" />
          <el-table-column label="替代">
            <template #default="{ row }">{{ recipeAlt(row) }}</template>
          </el-table-column>
        </el-table>
        <h4>受影响的未完成订单</h4>
        <OrderRows :rows="price.orders" />
        <GapList :gaps="price.gaps" />
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, ref, watch } from 'vue';
import { ElAlert, ElTable, ElTableColumn } from 'element-plus';
import {
  fetchDisruption, fetchPrice, fetchQuality,
  type SupplyImpactDisruption, type SupplyImpactGap, type SupplyImpactOrder,
  type SupplyImpactPrice, type SupplyImpactQuality, type SupplyImpactRecipe, type SupplyImpactTarget,
} from '@/api/supplyImpact';
import SupplyImpactBoxExposure from './SupplyImpactBoxExposure.vue';

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  target: SupplyImpactTarget;
  targetName: string;
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>();

type EventKind = 'disruption' | 'quality' | 'price';
const event = ref<EventKind>('disruption');
const horizonDays = ref(14);
const range = ref<[string, string] | null>(null);
const percent = ref(10);
const loading = ref(false);
const error = ref('');
const disruption = ref<SupplyImpactDisruption | null>(null);
const quality = ref<SupplyImpactQuality | null>(null);
const price = ref<SupplyImpactPrice | null>(null);
let requestEpoch = 0;

function recipeAlt(row: SupplyImpactRecipe): string {
  const alts = [...row.groupAlternatives, ...row.registeredSubstitutes].join('、');
  return alts || (row.optional ? '可选料(可不放)' : '无');
}

const OrderRows = defineComponent({
  props: { rows: { type: Array as () => SupplyImpactOrder[], required: true } },
  setup: (p) => () => h(ElTable, { data: p.rows, size: 'small', border: true, emptyText: '没有未发订单', 'data-test': 'impact-orders' }, () => [
    h(ElTableColumn, { label: '订单', prop: 'orderNumber', width: 170 }),
    h(ElTableColumn, { label: '客户', prop: 'customerName' }),
    h(ElTableColumn, { label: '产品', prop: 'productName' }),
    h(ElTableColumn, { label: '未发', width: 120 }, { default: ({ row }: { row: SupplyImpactOrder }) => `${row.undeliveredQuantity} ${row.unit ?? ''}` }),
    h(ElTableColumn, { label: '交期', prop: 'requiredDeliveryDate', width: 110 }),
    h(ElTableColumn, { label: '关联', width: 130 }, { default: ({ row }: { row: SupplyImpactOrder }) => (row.linkedBy === 'PLAN' ? '经计划关联' : '同产品(尚无计划)') }),
  ]),
});
const GapList = defineComponent({
  props: { gaps: { type: Array as () => SupplyImpactGap[], required: true } },
  setup: (p) => () => (p.gaps.length
    ? h(ElAlert, { type: 'warning', closable: false, title: '数据缺口（如实标出，未猜测）', 'data-test': 'impact-gaps' },
      { default: () => h('ul', p.gaps.map((g) => h('li', g.message))) })
    : null),
});

const canRunPrice = computed(() => !!props.target.materialTypeId);

async function run(): Promise<void> {
  const epoch = ++requestEpoch;
  const signature = requestSignature();
  loading.value = true;
  error.value = '';
  disruption.value = null;
  quality.value = null;
  price.value = null;
  try {
    if (event.value === 'disruption') {
      const value = await fetchDisruption(props.factoryId, props.target, horizonDays.value);
      if (isCurrentRequest(epoch, signature)) disruption.value = value;
    } else if (event.value === 'quality') {
      const value = await fetchQuality(props.factoryId, props.target, range.value?.[0], range.value?.[1]);
      if (isCurrentRequest(epoch, signature)) quality.value = value;
    } else if (canRunPrice.value) {
      const value = await fetchPrice(props.factoryId, props.target.materialTypeId as string, percent.value);
      if (isCurrentRequest(epoch, signature)) price.value = value;
    }
  } catch (e) {
    if (isCurrentRequest(epoch, signature)) error.value = e instanceof Error ? e.message : '查询失败';
  } finally {
    if (isCurrentRequest(epoch, signature)) loading.value = false;
  }
}

function requestSignature(): string {
  return JSON.stringify({
    factoryId: props.factoryId,
    supplierId: props.target.supplierId || null,
    materialTypeId: props.target.materialTypeId || null,
    event: event.value,
    horizonDays: horizonDays.value,
    range: range.value,
    percent: percent.value,
  });
}

function isCurrentRequest(epoch: number, signature: string): boolean {
  return epoch === requestEpoch && signature === requestSignature();
}

function clearResults(): void {
  disruption.value = null;
  quality.value = null;
  price.value = null;
  error.value = '';
  loading.value = false;
}

watch(() => props.modelValue, (open) => {
  requestEpoch += 1;
  clearResults();
  if (open) {
    event.value = 'disruption';
    void run();
  }
}, { immediate: true });

watch(() => [props.factoryId, props.target.supplierId, props.target.materialTypeId], () => {
  requestEpoch += 1;
  clearResults();
  if (props.modelValue) void run();
});

watch([event, range, horizonDays, percent], () => {
  if (requestEpoch > 0) {
    requestEpoch += 1;
    clearResults();
  }
}, { flush: 'sync' });
</script>

<style scoped>
.params { display: flex; align-items: center; gap: 8px; margin: 12px 0; }
h4 { margin: 14px 0 6px; }
</style>
