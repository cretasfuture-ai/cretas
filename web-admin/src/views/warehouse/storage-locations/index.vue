<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Box, Grid, Location, Plus, Search, Upload } from '@element-plus/icons-vue';
import { VueFlow, type Node, type NodeDragEvent } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, put } from '@/api/request';
import {
  getWarehouseLayout, recognizeWarehouseLayout, saveWarehouseLayout,
  type WarehouseFloor, type WarehouseLayout, type WarehouseLocation, type WarehouseRack, type WarehouseZone,
} from '@/api/warehouseLayout';
import { bigCategoryOf } from '@/utils/materialCategory';
import { daysInStorage, nextLocationCode, normalizeWarehouseLayout } from './warehouseLayoutModel';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';

type ItemCategory = '原料' | '成品' | '包材' | '辅材';
interface InventoryBatch {
  id: string;
  name: string;
  batchNumber: string;
  quantity: number;
  unit: string;
  category: ItemCategory;
  location: string;
  inboundDate?: string;
  finished: boolean;
}

const auth = useAuthStore();
const permissions = usePermissionStore();
const router = useRouter();
const factoryId = computed(() => auth.factoryId);
const canEditLayout = computed(() => ['factory_super_admin', 'warehouse_manager'].includes(auth.currentRole));
const canMoveInventory = computed(() => permissions.canWrite('warehouse'));
const loading = ref(false);
const saving = ref(false);
const activeTab = ref<'locations' | 'check' | 'map'>('locations');
const layout = ref<WarehouseLayout>(normalizeWarehouseLayout(null));
const batches = ref<InventoryBatch[]>([]);
const selectedZoneId = ref('');
const selectedFloorId = ref('');
const selectedRackId = ref('');
const selectedCategory = ref<ItemCategory>('原料');
const selectedMaterial = ref('');
const searchTerm = ref('');
const locationFilter = ref('');
const zoneDialogVisible = ref(false);
const locationDialogVisible = ref(false);
const moveDialogVisible = ref(false);
const selectedBatch = ref<InventoryBatch | null>(null);
const fileInput = ref<HTMLInputElement>();
const zoneForm = reactive({ name: '', prefix: '' });
const locationForm = reactive({ code: '', name: '', notes: '' });
const moveLocation = ref('');
const categoryOptions: ItemCategory[] = ['原料', '成品', '包材', '辅材'];
const navigationTabs = [{ id: 'locations', label: '储存位置' }, { id: 'check', label: '现场盘点' }, { id: 'map', label: '仓库平面图' }] as const;
const zoneColors = ['#dbeafe', '#e0f2fe', '#ede9fe', '#fef3c7', '#dcfce7'];
let materialSearchTimer: ReturnType<typeof setTimeout> | undefined;

const activeZone = computed(() => layout.value.zones.find((zone) => zone.id === selectedZoneId.value));
const activeFloor = computed(() => layout.value.floors.find((floor) => floor.id === selectedFloorId.value));
const activeRack = computed(() => activeFloor.value?.racks.find((rack) => rack.id === selectedRackId.value));
const visibleLocations = computed(() => layout.value.locations.filter((location) =>
  (!selectedZoneId.value || location.zoneId === selectedZoneId.value)
  && (!searchTerm.value || `${location.code} ${location.name}`.toLowerCase().includes(searchTerm.value.toLowerCase()))));

const categoryBatches = computed(() => batches.value.filter((batch) => batch.category === selectedCategory.value));
const materials = computed(() => {
  const groups = new Map<string, InventoryBatch[]>();
  for (const batch of categoryBatches.value) {
    if (locationFilter.value && batch.location !== locationFilter.value) continue;
    if (searchTerm.value && !`${batch.name} ${batch.batchNumber}`.toLowerCase().includes(searchTerm.value.toLowerCase())) continue;
    const current = groups.get(batch.name) ?? [];
    current.push(batch);
    groups.set(batch.name, current);
  }
  return [...groups.entries()].map(([name, entries]) => ({
    name,
    entries,
    quantity: entries.reduce((total, entry) => total + entry.quantity, 0),
    unit: entries[0]?.unit ?? '',
    maxAge: Math.max(...entries.map((entry) => daysInStorage(entry.inboundDate) ?? 0)),
  })).slice(0, 36);
});
const selectedBatches = computed(() => materials.value.find((material) => material.name === selectedMaterial.value)?.entries ?? []);
const activeRackLocations = computed(() => layout.value.locations.filter((location) => location.rackId === selectedRackId.value));
const flowNodes = computed<Node[]>(() => (activeFloor.value?.racks ?? []).map((rack) => {
  const zone = layout.value.zones.find((entry) => entry.id === rack.zoneId);
  const count = layout.value.locations.filter((entry) => entry.rackId === rack.id).length;
  return {
    id: rack.id,
    position: { x: rack.x, y: rack.y },
    data: { label: `${zone?.prefix ?? ''} · ${rack.name}\n${rack.levels} 层 · ${count} 位置` },
    style: {
      width: `${rack.width}px`, minHeight: `${rack.height}px`, whiteSpace: 'pre-line',
      border: rack.id === selectedRackId.value ? '2px solid #2563eb' : '1px solid #cbd5e1',
      borderRadius: '12px', background: zone?.color ?? '#eff6ff', color: '#0f172a',
      fontSize: '14px', fontWeight: '600', padding: '14px',
    },
    draggable: canEditLayout.value,
  };
}));

function pickArray(value: unknown): Record<string, unknown>[] {
  if (Array.isArray(value)) return value as Record<string, unknown>[];
  if (value && typeof value === 'object') {
    const result = value as Record<string, unknown>;
    return Array.isArray(result.content) ? result.content as Record<string, unknown>[]
      : Array.isArray(result.list) ? result.list as Record<string, unknown>[] : [];
  }
  return [];
}

function normalizeMaterial(row: Record<string, unknown>, finished: boolean): InventoryBatch {
  const family = bigCategoryOf(String(row.category ?? row.materialCategory ?? row.materialTypeCategory ?? ''));
  const category: ItemCategory = finished ? '成品' : family === '包材' ? '包材' : family === '辅料' || family === '调料' ? '辅材' : '原料';
  return {
    id: String(row.id ?? ''),
    name: String(row.materialName ?? row.materialTypeName ?? row.productName ?? row.productTypeName ?? row.batchNumber ?? '未命名'),
    batchNumber: String(row.batchNumber ?? row.id ?? ''),
    quantity: finished
      ? Number(row.producedQuantity ?? 0) - Number(row.shippedQuantity ?? 0) - Number(row.reservedQuantity ?? 0)
      : Number(row.remainingQuantity ?? row.currentQuantity ?? row.quantity ?? 0),
    unit: String(row.unit ?? row.quantityUnit ?? 'kg'),
    category,
    location: String(row.storageLocation ?? ''),
    inboundDate: String(row.receiptDate ?? row.inboundDate ?? row.createdAt ?? row.productionDate ?? '') || undefined,
    finished,
  };
}

async function loadWorkspace() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const [stored, materialsResult, finishedResult] = await Promise.allSettled([
      getWarehouseLayout(factoryId.value),
      get(`/${factoryId.value}/material-batches`, { params: { page: 1, size: 200 } }),
      get(`/${factoryId.value}/sales/finished-goods`, { params: { page: 1, size: 500 } }),
    ]);
    if (stored.status === 'fulfilled') layout.value = normalizeWarehouseLayout(stored.value.data);
    else throw stored.reason;
    batches.value = [
      ...(materialsResult.status === 'fulfilled' ? pickArray(materialsResult.value.data).map((row) => normalizeMaterial(row, false)) : []),
      ...(finishedResult.status === 'fulfilled' ? pickArray(finishedResult.value.data).map((row) => normalizeMaterial(row, true)) : []),
    ].filter((batch) => batch.quantity > 0);
    if (!selectedZoneId.value && layout.value.zones[0]) selectedZoneId.value = layout.value.zones[0].id;
    if (!selectedFloorId.value && layout.value.floors[0]) selectedFloorId.value = layout.value.floors[0].id;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '仓储配置加载失败');
  } finally {
    loading.value = false;
  }
}

async function persist(showMessage = true) {
  if (!canEditLayout.value || !factoryId.value) return;
  saving.value = true;
  try {
    await saveWarehouseLayout(factoryId.value, layout.value);
    if (showMessage) ElMessage.success('已保存');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
    throw error;
  } finally {
    saving.value = false;
  }
}

async function addZone() {
  const name = zoneForm.name.trim();
  const prefix = zoneForm.prefix.trim().toUpperCase();
  if (!name || !/^[A-Z]{2,8}$/.test(prefix)) return ElMessage.warning('请填写名称和 2 至 8 位英文前缀');
  if (layout.value.zones.some((zone) => zone.prefix === prefix)) return ElMessage.warning('英文前缀已存在');
  const zone: WarehouseZone = { id: crypto.randomUUID(), name, prefix, color: zoneColors[layout.value.zones.length % zoneColors.length] };
  layout.value.zones.push(zone);
  selectedZoneId.value = zone.id;
  zoneDialogVisible.value = false;
  zoneForm.name = ''; zoneForm.prefix = '';
  await persist();
}

function openLocationDialog() {
  if (!activeZone.value) return ElMessage.warning('请先创建储存区域');
  locationForm.code = nextLocationCode(activeZone.value, layout.value.locations);
  locationForm.name = '';
  locationForm.notes = '';
  locationDialogVisible.value = true;
}

async function addLocation() {
  if (!activeZone.value) return;
  const code = locationForm.code.trim().toUpperCase();
  if (!code.startsWith(activeZone.value.prefix)) return ElMessage.warning(`位置编码须以 ${activeZone.value.prefix} 开头`);
  if (layout.value.locations.some((location) => location.code === code)) return ElMessage.warning('位置编码已存在');
  layout.value.locations.push({ id: crypto.randomUUID(), zoneId: activeZone.value.id, code, name: locationForm.name.trim() || code, notes: locationForm.notes.trim() });
  locationDialogVisible.value = false;
  await persist();
}

async function addFloor() {
  const floor: WarehouseFloor = { id: crypto.randomUUID(), name: `${layout.value.floors.length + 1} 层`, racks: [] };
  layout.value.floors.push(floor);
  selectedFloorId.value = floor.id;
  await persist();
}

async function addRack() {
  if (!activeFloor.value || !activeZone.value) return ElMessage.warning('请先选择楼层和区域');
  const index = activeFloor.value.racks.length;
  const rack: WarehouseRack = {
    id: crypto.randomUUID(), zoneId: activeZone.value.id, name: `${index + 1} 号货架`,
    x: 70 + (index % 3) * 190, y: 70 + Math.floor(index / 3) * 140, width: 155, height: 84, levels: 4,
  };
  activeFloor.value.racks.push(rack);
  selectedRackId.value = rack.id;
  await persist();
}

async function onRackDrag(event: NodeDragEvent) {
  const rack = activeFloor.value?.racks.find((entry) => entry.id === event.node.id);
  if (!rack) return;
  rack.x = Math.max(0, Math.round(event.node.position.x));
  rack.y = Math.max(0, Math.round(event.node.position.y));
  await persist(false);
}

async function bindLocation(location: WarehouseLocation, level: number) {
  if (!activeRack.value) return;
  location.rackId = activeRack.value.id;
  location.level = level;
  await persist();
}

async function moveBatch() {
  const batch = selectedBatch.value;
  if (!batch || !moveLocation.value || !factoryId.value) return;
  try {
    const path = batch.finished
      ? `/${factoryId.value}/warehouse/layout/finished-goods/${batch.id}/location`
      : `/${factoryId.value}/material-batches/${batch.id}`;
    await put(path, { storageLocation: moveLocation.value });
    batch.location = moveLocation.value;
    moveDialogVisible.value = false;
    ElMessage.success('位置已更新');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '位置调整失败');
  }
}

async function handleImageUpload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file || !factoryId.value) return;
  if (file.size > 8_000_000) return ElMessage.warning('图片不能超过 8MB');
  const image = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('图片读取失败'));
    reader.readAsDataURL(file);
  });
  loading.value = true;
  try {
    const response = await recognizeWarehouseLayout(factoryId.value, image, file.type || 'image/jpeg');
    const recognized = response.data;
    for (const zone of recognized.zones ?? []) {
      const prefix = String(zone.prefix ?? '').toUpperCase();
      if (/^[A-Z]{2,8}$/.test(prefix) && !layout.value.zones.some((current) => current.prefix === prefix)) {
        layout.value.zones.push({ id: zone.id || crypto.randomUUID(), name: zone.name, prefix, color: zoneColors[layout.value.zones.length % zoneColors.length] });
      }
    }
    if (!activeFloor.value) {
      const floor = { id: crypto.randomUUID(), name: '1 层', racks: [] as WarehouseRack[] };
      layout.value.floors.push(floor);
      selectedFloorId.value = floor.id;
    }
    if (activeFloor.value) {
      activeFloor.value.background = image;
      for (const rack of recognized.racks ?? []) {
        activeFloor.value.racks.push({ ...rack, id: rack.id || crypto.randomUUID(), width: Math.max(100, Number(rack.width || 140)), height: Math.max(70, Number(rack.height || 90)), levels: Math.max(1, Number(rack.levels || 4)) });
      }
    }
    await persist(false);
    ElMessage.success('平面图识别完成');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 识别失败');
  } finally {
    loading.value = false;
    if (fileInput.value) fileInput.value.value = '';
  }
}

function countAt(code: string) { return batches.value.filter((batch) => batch.location === code); }
function formatAge(value?: string) { const age = daysInStorage(value); return age === null ? '—' : `${age} 天`; }
function selectCategory(category: ItemCategory) { selectedCategory.value = category; selectedMaterial.value = ''; searchTerm.value = ''; }
watch(searchTerm, (keyword) => {
  if (activeTab.value !== 'check' || !factoryId.value || selectedCategory.value === '成品') return;
  if (materialSearchTimer) clearTimeout(materialSearchTimer);
  materialSearchTimer = setTimeout(async () => {
    try {
      const response = await get(`/${factoryId.value}/material-batches`, {
        params: { page: 1, size: 200, ...(keyword.trim() ? { keyword: keyword.trim() } : {}) },
      });
      const materialRows = pickArray(response.data).map((row) => normalizeMaterial(row, false)).filter((batch) => batch.quantity > 0);
      batches.value = [...materialRows, ...batches.value.filter((batch) => batch.finished)];
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '物料搜索失败');
    }
  }, 250);
});
onMounted(loadWorkspace);
</script>

<template>
  <main v-loading="loading" class="storage-workspace">
    <header class="workspace-header">
      <div><h1>仓储空间</h1><span class="muted">{{ layout.zones.length }} 个区域 · {{ layout.locations.length }} 个位置</span></div>
      <el-button :icon="Box" @click="router.push('/warehouse/stocktakes')">盘点任务</el-button>
    </header>

    <nav class="workspace-tabs">
      <button v-for="tab in navigationTabs" :key="tab.id" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}</button>
    </nav>

    <section v-if="activeTab === 'locations'" class="workspace-body">
      <aside class="zone-sidebar">
        <div class="section-heading"><h2>储存区域</h2><el-button v-if="canEditLayout" :icon="Plus" circle size="small" @click="zoneDialogVisible = true" /></div>
        <button v-for="zone in layout.zones" :key="zone.id" class="zone-row" :class="{ selected: zone.id === selectedZoneId }" @click="selectedZoneId = zone.id">
          <span class="zone-prefix" :style="{ background: zone.color }">{{ zone.prefix }}</span>
          <span>{{ zone.name }}</span><small>{{ layout.locations.filter(item => item.zoneId === zone.id).length }}</small>
        </button>
        <el-empty v-if="!layout.zones.length" description="暂无区域" :image-size="70" />
      </aside>

      <div class="primary-panel">
        <div class="section-heading"><h2>{{ activeZone?.name || '储存位置' }}</h2><div class="toolbar"><el-input v-model="searchTerm" :prefix-icon="Search" placeholder="搜索位置" clearable /><el-button v-if="canEditLayout" type="primary" :icon="Plus" @click="openLocationDialog">添加位置</el-button></div></div>
        <div class="location-grid"><article v-for="location in visibleLocations" :key="location.id" class="location-card"><div class="card-top"><strong>{{ location.code }}</strong><span>{{ countAt(location.code).length }} 批</span></div><div>{{ location.name }}</div><small class="muted">{{ location.notes || (countAt(location.code).length ? `${new Set(countAt(location.code).map(item => item.name)).size} 种物料` : '空闲') }}</small></article></div>
        <el-empty v-if="!visibleLocations.length" description="暂无储存位置" :image-size="90" />
      </div>
    </section>

    <section v-else-if="activeTab === 'check'" class="stock-panel">
      <div class="category-grid"><button v-for="category in categoryOptions" :key="category" class="category-card" :class="{ selected: category === selectedCategory }" @click="selectCategory(category)"><strong>{{ category }}</strong><small>{{ new Set(batches.filter(item => item.category === category).map(item => item.name)).size }} 项</small></button></div>
      <div class="section-heading"><h2>{{ selectedCategory }}</h2><div class="toolbar"><el-input v-model="searchTerm" :prefix-icon="Search" placeholder="搜索名称 / 批次" clearable /><el-select v-model="locationFilter" placeholder="全部位置" clearable><el-option-group v-for="zone in layout.zones" :key="zone.id" :label="`${zone.name} · ${zone.prefix}`"><el-option v-for="location in layout.locations.filter(item => item.zoneId === zone.id)" :key="location.id" :label="location.code" :value="location.code" /></el-option-group></el-select></div></div>
      <div class="material-grid"><button v-for="material in materials" :key="material.name" class="material-card" :class="{ selected: selectedMaterial === material.name }" @click="selectedMaterial = material.name"><strong>{{ material.name }}</strong><span class="quantity">{{ material.quantity }} <small>{{ material.unit }}</small></span><span class="card-foot"><small>{{ material.entries.length }} 批次</small><small>最长 {{ material.maxAge }} 天</small></span></button></div>
      <el-empty v-if="!materials.length" description="暂无库存" :image-size="75" />
      <template v-if="selectedMaterial"><div class="section-heading batch-heading"><h2>{{ selectedMaterial }}</h2><el-button type="primary" plain @click="router.push('/warehouse/stocktakes')">发起盘点</el-button></div><div class="batch-grid"><article v-for="batch in selectedBatches" :key="batch.id" class="batch-card"><div class="card-top"><strong>{{ batch.batchNumber }}</strong><el-button v-if="canMoveInventory" link type="primary" @click="selectedBatch = batch; moveLocation = batch.location; moveDialogVisible = true">调整位置</el-button></div><div class="quantity">{{ batch.quantity }} <small>{{ batch.unit }}</small></div><div class="card-foot"><span>{{ batch.location || '未分配' }}</span><span>在库 {{ formatAge(batch.inboundDate) }}</span></div></article></div></template>
    </section>

    <section v-else class="map-panel"><div class="map-toolbar"><div><el-radio-group v-model="selectedFloorId"><el-radio-button v-for="floor in layout.floors" :key="floor.id" :value="floor.id">{{ floor.name }}</el-radio-button></el-radio-group><el-button v-if="canEditLayout" link :icon="Plus" @click="addFloor">楼层</el-button></div><div class="toolbar"><el-select v-model="selectedZoneId" placeholder="选择区域"><el-option v-for="zone in layout.zones" :key="zone.id" :label="`${zone.name} · ${zone.prefix}`" :value="zone.id" /></el-select><el-button v-if="canEditLayout" :icon="Grid" @click="addRack">添加货架</el-button><el-button v-if="canEditLayout" :icon="Upload" @click="fileInput?.click()">AI 识别底图</el-button><input ref="fileInput" type="file" accept="image/*" hidden @change="handleImageUpload"></div></div>
      <div class="map-columns"><div class="canvas-shell" :style="activeFloor?.background ? { backgroundImage: `linear-gradient(rgba(255,255,255,.72),rgba(255,255,255,.72)),url(${activeFloor.background})` } : {}"><VueFlow v-if="activeFloor" :nodes="flowNodes" :edges="[]" :nodes-draggable="canEditLayout" fit-view-on-init @node-click="event => selectedRackId = event.node.id" @node-drag-stop="onRackDrag"><Background pattern-color="#d8e0ec" :gap="22" /><Controls position="bottom-left" /></VueFlow><el-empty v-else description="暂无楼层" :image-size="100" /></div>
      <aside class="rack-detail"><template v-if="activeRack"><h2>{{ activeRack.name }}</h2><span class="muted">{{ activeZone?.name }} · {{ activeRack.levels }} 层</span><div class="shelf-stack"><div v-for="level in Array.from({ length: activeRack.levels }, (_, index) => activeRack!.levels - index)" :key="level" class="shelf-level"><span class="level-number">{{ level }}F</span><div class="shelf-content"><div v-for="location in activeRackLocations.filter(item => item.level === level)" :key="location.id" class="shelf-location"><strong>{{ location.code }}</strong><small>{{ countAt(location.code).length }} 批</small><small v-if="countAt(location.code)[0]">{{ formatAge(countAt(location.code)[0]?.inboundDate) }}</small></div><el-dropdown v-if="canEditLayout" trigger="click" @command="(id: string) => { const location = layout.locations.find(item => item.id === id); if (location) bindLocation(location, level); }"><el-button text :icon="Plus">绑定位置</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="location in layout.locations.filter(item => item.zoneId === activeRack!.zoneId && !item.rackId)" :key="location.id" :command="location.id">{{ location.code }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></div></div></template><el-empty v-else description="选择货架" :image-size="80" /></aside></div>
    </section>

    <el-dialog v-model="zoneDialogVisible" title="添加储存区域" width="420"><el-form label-position="top"><el-form-item label="区域名称"><el-input v-model="zoneForm.name" placeholder="例如：冷藏区" /></el-form-item><el-form-item label="英文前缀"><el-input v-model="zoneForm.prefix" maxlength="8" placeholder="例如：LC" /></el-form-item></el-form><template #footer><el-button @click="zoneDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="addZone">保存</el-button></template></el-dialog>
    <el-dialog v-model="locationDialogVisible" title="添加储存位置" width="440"><el-form label-position="top"><el-form-item label="位置编码"><el-input v-model="locationForm.code" /></el-form-item><el-form-item label="位置名称"><el-input v-model="locationForm.name" placeholder="选填" /></el-form-item><el-form-item label="备注"><el-input v-model="locationForm.notes" placeholder="选填" /></el-form-item></el-form><template #footer><el-button @click="locationDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="addLocation">保存</el-button></template></el-dialog>
    <el-dialog v-model="moveDialogVisible" title="调整储存位置" width="420"><el-form label-position="top"><el-form-item label="批次"><span>{{ selectedBatch?.batchNumber }}</span></el-form-item><el-form-item label="储存位置"><el-select v-model="moveLocation" filterable placeholder="选择位置" style="width:100%"><el-option-group v-for="zone in layout.zones" :key="zone.id" :label="`${zone.name} · ${zone.prefix}`"><el-option v-for="location in layout.locations.filter(item => item.zoneId === zone.id)" :key="location.id" :label="`${location.code} · ${location.name}`" :value="location.code" /></el-option-group></el-select></el-form-item></el-form><template #footer><el-button @click="moveDialogVisible = false">取消</el-button><el-button type="primary" @click="moveBatch">保存</el-button></template></el-dialog>
  </main>
</template>

<style scoped>
.storage-workspace{--ink:#17243a;--muted:#72809a;--line:#e5eaf2;--blue:#2563eb;padding:28px 32px;color:var(--ink);font-size:15px;background:#f7f9fc;min-height:calc(100vh - 88px)}.workspace-header,.section-heading,.toolbar,.card-top,.card-foot,.map-toolbar{display:flex;align-items:center;justify-content:space-between}.workspace-header{margin-bottom:22px}.workspace-header h1{font-size:24px;margin:0 0 6px;font-weight:700}.muted,small{color:var(--muted);font-size:13px}.workspace-tabs{display:flex;gap:28px;border-bottom:1px solid var(--line);margin-bottom:22px}.workspace-tabs button{padding:14px 2px;background:none;border:0;color:var(--muted);font-size:15px;cursor:pointer}.workspace-tabs button.active{color:var(--blue);border-bottom:2px solid var(--blue);font-weight:600}.workspace-body{display:grid;grid-template-columns:245px minmax(0,1fr);gap:20px}.zone-sidebar,.primary-panel,.stock-panel,.map-panel,.rack-detail{background:white;border:1px solid var(--line);border-radius:14px;padding:20px}.section-heading{margin-bottom:18px}.section-heading h2,.rack-detail h2{font-size:17px;margin:0}.zone-row{width:100%;display:flex;align-items:center;gap:11px;padding:11px 10px;margin:5px 0;border:1px solid transparent;border-radius:10px;background:white;text-align:left;color:var(--ink);font-size:14px;cursor:pointer}.zone-row.selected,.material-card.selected,.category-card.selected{background:#eff6ff;border-color:#bfd7ff}.zone-row small{margin-left:auto}.zone-prefix{display:grid;place-items:center;width:37px;height:32px;border-radius:8px;font-weight:650}.toolbar{gap:10px}.toolbar .el-input{width:220px}.toolbar .el-select{width:180px}.location-grid,.material-grid,.batch-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:13px}.location-card,.material-card,.batch-card{min-height:112px;padding:15px;border:1px solid var(--line);border-radius:12px;background:white;text-align:left}.location-card .card-top,.batch-card .card-top{margin-bottom:11px}.location-card strong,.batch-card strong{font-size:16px}.location-card>div:nth-child(2){margin-bottom:7px}.category-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:25px}.category-card,.material-card{cursor:pointer}.category-card{padding:15px;border:1px solid var(--line);border-radius:11px;background:white;text-align:left}.category-card strong,.category-card small,.material-card strong,.material-card>.quantity{display:block}.category-card strong{font-size:16px;margin-bottom:6px}.material-card>.quantity,.batch-card>.quantity{font-size:22px;font-weight:700;margin:13px 0}.material-card .quantity small,.batch-card .quantity small{font-weight:400}.card-foot{font-size:13px;color:#526176}.batch-heading{margin-top:27px;padding-top:20px;border-top:1px solid var(--line)}.map-toolbar{gap:12px;margin-bottom:15px}.map-columns{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:15px}.canvas-shell{height:590px;border:1px solid var(--line);border-radius:12px;overflow:hidden;background-size:contain;background-position:center;background-repeat:no-repeat}.canvas-shell>.el-empty{height:100%}.rack-detail{padding:17px}.rack-detail h2{margin-bottom:7px}.shelf-stack{margin-top:22px}.shelf-level{display:grid;grid-template-columns:30px minmax(0,1fr);gap:8px;margin:9px 0}.level-number{padding-top:12px;color:var(--muted);font-size:12px}.shelf-content{min-height:74px;padding:9px;background:#f8fafc;border:1px solid #e5eaf2;border-bottom:3px solid #94a3b8;border-radius:8px}.shelf-location{display:flex;justify-content:space-between;align-items:center;margin-bottom:7px}.shelf-location strong{font-size:14px}@media(max-width:1050px){.storage-workspace{padding:20px}.workspace-body{grid-template-columns:205px minmax(0,1fr)}.map-columns{grid-template-columns:1fr}.canvas-shell{height:450px}.toolbar .el-input{width:160px}.category-grid{grid-template-columns:repeat(2,1fr)}}
</style>
