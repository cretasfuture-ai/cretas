<script setup lang="ts">
/**
 * S4 产线产能与设备: 每条产线 (可细到产品) 每小时能做多少、按什么单位; 每个产品要用哪些设备。
 * 紧急插单判断「产能够不够 / 设备在不在」只读这里的配置: 没配或单位对不上会显示「缺配置」, 不会套默认值。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import {
  getProductionLines,
  createProductionLine,
  getLineCapacityConfigs,
  setLineCapacity,
  deleteLineCapacity,
  getProductEquipmentRequirements,
  setProductEquipment,
  type ProductionLine,
  type LineCapacityConfig,
  type ProductEquipmentRequirement,
  type CapacityUnit,
} from '@/api/scheduling';

interface ProductOption { id: string; name: string; code?: string; unit?: string }
interface EquipmentOption { id: number; name: string; equipmentCode?: string; status?: string }

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const canWrite = computed(() => permissionStore.canWrite('scheduling'));

const UNIT_OPTIONS: { value: CapacityUnit; label: string }[] = [
  { value: 'PIECE', label: '件' },
  { value: 'KG', label: '千克 (kg)' },
  { value: 'BATCH', label: '批' },
];
const unitLabel = (u: CapacityUnit) => ({ PIECE: '件', KG: 'kg', BATCH: '批' }[u]);
const sourceLabel = (s: string) => (s === 'CONVERSATIONAL_BUILD' ? '对话建厂' : '工厂配置');

const loading = ref(false);
const lines = ref<ProductionLine[]>([]);
const products = ref<ProductOption[]>([]);
const equipmentList = ref<EquipmentOption[]>([]);
const capacityRows = ref<LineCapacityConfig[]>([]);
const requirementRows = ref<ProductEquipmentRequirement[]>([]);

const lineName = (id: string) => lines.value.find(l => l.id === id)?.name || `已删除的产线 (${id})`;
const productName = (id: string | null) =>
  id == null ? '该线所有产品 (通用)' : products.value.find(p => p.id === id)?.name || id;
const equipmentName = (id: number) => {
  const e = equipmentList.value.find(x => x.id === id);
  return e ? `${e.name}${e.equipmentCode ? ` (${e.equipmentCode})` : ''}` : `#${id}`;
};

async function loadAll() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const [lineRes, capRes, reqRes, productRes, equipmentRes] = await Promise.all([
      getProductionLines(factoryId.value),
      getLineCapacityConfigs(factoryId.value),
      getProductEquipmentRequirements(factoryId.value),
      get<ProductOption[]>(`/${factoryId.value}/product-types/active`),
      get<{ content: EquipmentOption[] }>(`/${factoryId.value}/equipment`, { params: { page: 1, size: 500 } }),
    ]);
    const failed = [lineRes, capRes, reqRes, productRes, equipmentRes].find(r => !r.success);
    if (failed) {
      ElMessage.error(failed.message || '加载失败');
      return;
    }
    lines.value = lineRes.data || [];
    capacityRows.value = capRes.data || [];
    requirementRows.value = reqRes.data || [];
    products.value = productRes.data || [];
    equipmentList.value = equipmentRes.data?.content || [];
  } catch (error) {
    console.error('加载产线产能配置失败:', error);
    ElMessage.error('加载失败，请刷新重试');
  } finally {
    loading.value = false;
  }
}

onMounted(loadAll);

// ---------------------------------------------------------------- 新增产线

const lineDialog = ref(false);
const lineForm = ref({ name: '', lineCode: '' });
const savingLine = ref(false);

function openLineDialog() {
  lineForm.value = { name: '', lineCode: '' };
  lineDialog.value = true;
}

async function saveLine() {
  const name = lineForm.value.name.trim();
  if (!name) return;
  savingLine.value = true;
  try {
    const res = await createProductionLine(factoryId.value, { name, lineCode: lineForm.value.lineCode.trim() || undefined } as Partial<ProductionLine>);
    if (!res.success) {
      ElMessage.error(res.message || '新增产线失败');
      return;
    }
    ElMessage.success(`已新增产线「${name}」，接着给它设定每小时产能`);
    lineDialog.value = false;
    await loadAll();
  } finally {
    savingLine.value = false;
  }
}

// ---------------------------------------------------------------- 设定产能

const capacityDialog = ref(false);
const capacityForm = ref<{ productionLineId: string; productTypeId: string | null; capacityPerHour: number | undefined; capacityUnit: CapacityUnit | '' }>({
  productionLineId: '',
  productTypeId: null,
  capacityPerHour: undefined,
  capacityUnit: '',
});
const savingCapacity = ref(false);

/** 同线同产品已有的在用配置: 保存会替换它, 打开对话框就告诉用户 (防呆 Rule 1)。 */
const existingCapacity = computed(() =>
  capacityRows.value.find(
    r => r.productionLineId === capacityForm.value.productionLineId
      && (r.productTypeId ?? null) === (capacityForm.value.productTypeId || null),
  ),
);
const capacityValid = computed(() =>
  !!capacityForm.value.productionLineId
  && !!capacityForm.value.capacityUnit
  && (capacityForm.value.capacityPerHour ?? 0) > 0,
);

function openCapacityDialog(row?: LineCapacityConfig) {
  capacityForm.value = row
    ? { productionLineId: row.productionLineId, productTypeId: row.productTypeId, capacityPerHour: row.capacityPerHour, capacityUnit: row.capacityUnit }
    : { productionLineId: lines.value[0]?.id || '', productTypeId: null, capacityPerHour: undefined, capacityUnit: '' };
  capacityDialog.value = true;
}

async function saveCapacity() {
  if (!capacityValid.value) return;
  savingCapacity.value = true;
  try {
    const f = capacityForm.value;
    const res = await setLineCapacity(factoryId.value, {
      productionLineId: f.productionLineId,
      productTypeId: f.productTypeId || null,
      capacityPerHour: f.capacityPerHour as number,
      capacityUnit: f.capacityUnit as CapacityUnit,
    });
    if (!res.success) {
      ElMessage.error(res.message || '保存失败');
      return;
    }
    ElMessage.success(`已保存：${lineName(f.productionLineId)} · ${productName(f.productTypeId || null)} 每小时 ${f.capacityPerHour} ${unitLabel(f.capacityUnit as CapacityUnit)}`);
    capacityDialog.value = false;
    await loadAll();
  } finally {
    savingCapacity.value = false;
  }
}

async function removeCapacity(row: LineCapacityConfig) {
  try {
    await ElMessageBox.confirm(
      `删除后，${lineName(row.productionLineId)} 做「${productName(row.productTypeId)}」的产能将变为「缺配置」，插单无法核对产能。`,
      `删除产能配置 — ${lineName(row.productionLineId)}`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  const res = await deleteLineCapacity(factoryId.value, row.id);
  if (!res.success) {
    ElMessage.error(res.message || '删除失败');
    return;
  }
  ElMessage.success('已删除');
  await loadAll();
}

// ---------------------------------------------------------------- 产品所需设备

const equipmentProductId = ref('');
const equipmentSelection = ref<number[]>([]);
const savingEquipment = ref(false);

const currentEquipmentIds = computed(() =>
  requirementRows.value.filter(r => r.productTypeId === equipmentProductId.value).map(r => r.equipmentId),
);
const productsWithEquipment = computed(() => {
  const byProduct = new Map<string, number[]>();
  requirementRows.value.forEach(r => byProduct.set(r.productTypeId, [...(byProduct.get(r.productTypeId) || []), r.equipmentId]));
  return [...byProduct.entries()].map(([productTypeId, ids]) => ({ productTypeId, ids }));
});

function pickEquipmentProduct(id: string) {
  equipmentProductId.value = id;
  equipmentSelection.value = [...currentEquipmentIds.value];
}

const equipmentChanged = computed(() => {
  const a = [...equipmentSelection.value].sort().join(',');
  const b = [...currentEquipmentIds.value].sort().join(',');
  return a !== b;
});

async function saveEquipment() {
  if (!equipmentProductId.value || !equipmentChanged.value) return;
  savingEquipment.value = true;
  try {
    const res = await setProductEquipment(factoryId.value, equipmentProductId.value, equipmentSelection.value);
    if (!res.success) {
      ElMessage.error(res.message || '保存失败');
      return;
    }
    ElMessage.success(`已保存「${productName(equipmentProductId.value)}」所需设备 ${equipmentSelection.value.length} 台`);
    await loadAll();
    equipmentSelection.value = [...currentEquipmentIds.value];
  } finally {
    savingEquipment.value = false;
  }
}
</script>

<template>
  <div class="page-container" v-loading="loading">
    <div class="page-header">
      <h2>产线产能与设备</h2>
      <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
    </div>
    <el-alert type="info" :closable="false" show-icon class="intro">
      紧急插单判断「产能够不够、设备在不在」只看这里的配置。某条线没配、或配的单位和订单不一样（比如订单按件、产线只配了 kg/小时），插单会显示「缺配置」，系统不会自己换算或套默认值。
    </el-alert>

    <!-- 产线 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>产线（{{ lines.length }} 条）</span>
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openLineDialog">新增产线</el-button>
        </div>
      </template>
      <el-empty v-if="!lines.length" description="还没有产线。先新增产线，再给它设定每小时产能。" />
      <el-table v-else :data="lines" size="small">
        <el-table-column prop="name" label="产线" min-width="160" />
        <el-table-column prop="lineCode" label="编号" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'warning'" size="small">
              {{ row.status === 'active' ? '运行' : row.status === 'maintenance' ? '检修' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已配产能" min-width="200">
          <template #default="{ row }">
            <span v-if="!capacityRows.some(c => c.productionLineId === row.id)" class="missing">缺配置</span>
            <span v-else>{{ capacityRows.filter(c => c.productionLineId === row.id).length }} 条</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 产能 -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>每小时产能</span>
          <el-button v-if="canWrite" type="primary" :icon="Plus" :disabled="!lines.length" @click="openCapacityDialog()">设定产能</el-button>
        </div>
      </template>
      <el-empty v-if="!capacityRows.length" description="还没有配置产能，插单的产能会显示「缺配置」。" />
      <el-table v-else :data="capacityRows" size="small">
        <el-table-column label="产线" min-width="140">
          <template #default="{ row }">{{ lineName(row.productionLineId) }}</template>
        </el-table-column>
        <el-table-column label="适用产品" min-width="180">
          <template #default="{ row }">{{ productName(row.productTypeId) }}</template>
        </el-table-column>
        <el-table-column label="每小时产能" width="160">
          <template #default="{ row }">{{ row.capacityPerHour }} {{ unitLabel(row.capacityUnit) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
        </el-table-column>
        <el-table-column prop="effectiveFrom" label="生效时间" width="170" />
        <el-table-column v-if="canWrite" label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCapacityDialog(row)">修改</el-button>
            <el-button link type="danger" @click="removeCapacity(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 产品所需设备 -->
    <el-card shadow="never" class="section">
      <template #header><span>产品所需设备</span></template>
      <div class="equipment-editor">
        <el-select
          :model-value="equipmentProductId"
          placeholder="选择产品"
          filterable
          style="width: 280px"
          @update:model-value="pickEquipmentProduct"
        >
          <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <template v-if="equipmentProductId">
          <el-select
            v-model="equipmentSelection"
            multiple
            filterable
            :disabled="!canWrite"
            placeholder="选择做这个产品必须用到的设备"
            style="flex: 1; min-width: 320px"
          >
            <el-option
              v-for="e in equipmentList"
              :key="e.id"
              :label="equipmentName(e.id)"
              :value="e.id"
            />
          </el-select>
          <el-button
            v-if="canWrite"
            type="primary"
            :loading="savingEquipment"
            :disabled="!equipmentChanged"
            @click="saveEquipment"
          >保存</el-button>
        </template>
      </div>
      <p v-if="equipmentProductId && !equipmentSelection.length" class="hint missing">
        这个产品还没配所需设备，插单的设备会显示「缺配置」。
      </p>
      <el-empty v-if="!productsWithEquipment.length" description="还没有产品配置所需设备。" :image-size="60" />
      <el-table v-else :data="productsWithEquipment" size="small" class="equipment-table">
        <el-table-column label="产品" min-width="180">
          <template #default="{ row }">{{ productName(row.productTypeId) }}</template>
        </el-table-column>
        <el-table-column label="所需设备" min-width="320">
          <template #default="{ row }">{{ row.ids.map(equipmentName).join('、') }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click="pickEquipmentProduct(row.productTypeId)">修改</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增产线 -->
    <el-dialog v-model="lineDialog" title="新增产线" width="440px">
      <el-form label-width="80px">
        <el-form-item label="产线名称" required>
          <el-input v-model="lineForm.name" maxlength="100" placeholder="例如：卤制一线" />
        </el-form-item>
        <el-form-item label="编号">
          <el-input v-model="lineForm.lineCode" maxlength="50" placeholder="可不填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lineDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingLine" :disabled="!lineForm.name.trim()" @click="saveLine">新增</el-button>
      </template>
    </el-dialog>

    <!-- 设定产能 -->
    <el-dialog
      v-model="capacityDialog"
      :title="`设定产能 — ${capacityForm.productionLineId ? lineName(capacityForm.productionLineId) : '选择产线'}`"
      width="520px"
    >
      <el-form label-width="100px">
        <el-form-item label="产线" required>
          <el-select v-model="capacityForm.productionLineId" style="width: 100%">
            <el-option v-for="l in lines" :key="l.id" :label="l.name" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用产品">
          <el-select v-model="capacityForm.productTypeId" clearable filterable placeholder="不选 = 该线所有产品通用" style="width: 100%">
            <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="产能单位" required>
          <el-radio-group v-model="capacityForm.capacityUnit">
            <el-radio-button v-for="u in UNIT_OPTIONS" :key="u.value" :value="u.value">{{ u.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="每小时产能" required>
          <el-input-number v-model="capacityForm.capacityPerHour" :min="0.001" :precision="3" :step="10" controls-position="right" />
          <span class="unit-suffix">{{ capacityForm.capacityUnit ? `${unitLabel(capacityForm.capacityUnit)}/小时` : '先选单位' }}</span>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="existingCapacity"
        type="warning"
        :closable="false"
        show-icon
        :title="`当前已配：每小时 ${existingCapacity.capacityPerHour} ${unitLabel(existingCapacity.capacityUnit)}（${sourceLabel(existingCapacity.source)}）。保存后用新值替换，旧值留在记录里。`"
      />
      <template #footer>
        <el-button @click="capacityDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingCapacity" :disabled="!capacityValid" @click="saveCapacity">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container { padding: 20px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.page-header h2 { margin: 0; font-size: 20px; }
.intro { margin-bottom: 16px; }
.section { margin-bottom: 16px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.missing { color: var(--el-color-warning); }
.equipment-editor { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 8px; }
.equipment-table { margin-top: 12px; }
.hint { margin: 4px 0 0; font-size: 13px; }
.unit-suffix { margin-left: 8px; color: var(--el-text-color-secondary); }
</style>
