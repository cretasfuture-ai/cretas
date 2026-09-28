<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Box, Close, Delete, FullScreen, Grid, Location, Plus, Search, Upload } from '@element-plus/icons-vue';
import { VueFlow, type Node, type NodeDragEvent } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, put } from '@/api/request';
import {
  getWarehouseLayout, recognizeWarehouseLayout, saveWarehouseLayout,
  type RecognizedWarehouseLayout, type WarehouseAnnotation, type WarehouseFloor, type WarehouseLayout,
  type WarehouseLocation, type WarehouseRack, type WarehouseRectGeometry, type WarehouseZone, type WarehouseZoneShape,
} from '@/api/warehouseLayout';
import { bigCategoryOf } from '@/utils/materialCategory';
import {
  createTrailingDebouncer, daysInStorage, defaultZoneShapeFor, floorAnnotations, floorZoneShapes, formatQuantity,
  maxDaysInStorage, MAX_ANNOTATIONS_PER_FLOOR, nextLocationCode, normalizeWarehouseLayout, parseMapSelection,
  upsertZoneShape, zoneShapeFromPercent, zonesMissingShapeOnFloor, type MapTool,
} from './warehouseLayoutModel';
import WarehouseFloorOverlay from './WarehouseFloorOverlay.vue';
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
  /** 后端没给物料名, 名称退化成了批次号 —— 展示时必须说清, 不能装成物料名 */
  unnamed: boolean;
}

const auth = useAuthStore();
const permissions = usePermissionStore();
const router = useRouter();
const factoryId = computed(() => auth.factoryId);
const canEditLayout = computed(() => ['factory_super_admin', 'warehouse_manager'].includes(auth.currentRole));
const mapFullscreenLabel = computed(() => canEditLayout.value ? '全屏编辑' : '全屏查看');
const mapFullscreenAriaLabel = computed(() => `${mapFullscreenLabel.value}平面图`);
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
// 🔴 2026-08-25 生产实测缺陷: 点「保存」后 150ms 内再点「+」, Element Plus 的
// leave 过渡被打断, `afterLeave` 不再触发, `el-overlay-dialog` 永久停在
// `is-closing` 上 —— 弹窗看着是开的, 但 elementFromPoint 打在遮罩上,
// 保存/取消/× 全部点不动, 用户只能刷新页面。已用「摘掉 is-closing 就恢复可点」
// 的变异对照证明因果。修法: 每次打开都换一个 key, 让 Vue 挂一个全新的
// dialog 元素 —— 全新元素不可能带着上一轮的 is-closing。
const zoneDialogKey = ref(0);
const locationDialogKey = ref(0);
const moveDialogKey = ref(0);
const recognizePreview = ref<RecognizedWarehouseLayout | null>(null);
const recognizePreviewImage = ref('');
const recognizeDialogVisible = ref(false);
const recognizeDialogKey = ref(0);
// 画布连续拖拽的保存调度器。抽成 createTrailingDebouncer 是为了能用假定时器
// **确定性验证**「连续微调只发一次」—— 浏览器自动化驱动不了 Vue Flow 的拖拽
// (合成 PointerEvent 打不进 d3-drag), 在浏览器里怎么试都是「节点没动」,
// 那不是防抖没生效, 是仪器够不着。
// ⚠️ 2026-08-28 改名(原 layoutPersistDebouncer): 现在货架拖拽、区域几何拖拽/缩放、
//    标注拖拽/缩放**共用这一个**调度器。共用是对的 —— persist() PUT 的是整份
//    layout, 三种拖拽各配一个防抖器会在同一个 600ms 窗口里打出三份整份 PUT。
const layoutPersistDebouncer = createTrailingDebouncer(600);
const zoneForm = reactive({ name: '', prefix: '' });
const locationForm = reactive({ code: '', name: '', notes: '' });
const moveLocation = ref('');
// ── 平面图设计器 ──
// 🔴 全屏用的是「同一块画布加一个 class」, ⛔ 不是 el-dialog:
//    2026-08-25 那个 `el-overlay-dialog` 卡在 is-closing 上的生产缺陷正是
//    Element Plus 弹窗过渡被打断造成的。这里根本不引入第二个弹窗过渡,
//    Vue Flow 实例也不会被销毁重建(重建会丢掉当前的平移/缩放)。
const mapFullscreen = ref(false);
const mapTool = ref<MapTool>('select');
/** `zone:<zoneId>` / `annotation:<id>` / 空串 */
const mapSelection = ref('');
const annotationColor = ref('#2563eb');
const showAnnotations = ref(true);
const hasMapDraft = ref(false);
const overlayRef = ref<InstanceType<typeof WarehouseFloorOverlay> | null>(null);
const categoryOptions: ItemCategory[] = ['原料', '成品', '包材', '辅材'];
const navigationTabs = [{ id: 'locations', label: '储存位置' }, { id: 'check', label: '现场盘点' }, { id: 'map', label: '仓库平面图' }] as const;
const zoneColors = ['#dbeafe', '#e0f2fe', '#ede9fe', '#fef3c7', '#dcfce7'];
const annotationColors = ['#2563eb', '#d03050', '#0f9d58', '#b26a00', '#17243a'];
const mapTools: Array<{ id: MapTool; label: string }> = [
  { id: 'select', label: '选择' }, { id: 'zone', label: '区域' }, { id: 'rect', label: '矩形' },
  { id: 'ellipse', label: '圆形' }, { id: 'polygon', label: '多边形' }, { id: 'freehand', label: '自由笔' },
  { id: 'text', label: '文字' },
];
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
    // ⛔ 不用 `?? 0`: 全部批次都没有入库日期时必须是 null(展示 —),
    //    回落成 0 等于谎称「今天入库」。
    maxAge: maxDaysInStorage(entries.map((entry) => entry.inboundDate)),
    // 这一组是不是只有批次号、没有真实物料名(见 normalizeMaterial 的 unnamed)
    unnamed: entries.every((entry) => entry.unnamed),
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

/** 本层还没画上去的区域 —— 「哪些区域在这层图上还没有位置」。 */
const unplacedZones = computed(() => zonesMissingShapeOnFloor(layout.value.zones, activeFloor.value));
/** 只有「有编辑权限 **且** 全屏」时叠加层才接管指针；缩略图状态下画布交互与改动前一致。 */
const canDesignMap = computed(() => canEditLayout.value && mapFullscreen.value);
const mapHint = computed(() => {
  if (!canEditLayout.value) return '只读视图：你的角色不能编辑平面图';
  if (!mapFullscreen.value) return '';
  switch (mapTool.value) {
    case 'zone':
      return activeZone.value
        ? `拖出一块面积，作为「${activeZone.value.name}」在${activeFloor.value?.name ?? '本层'}的位置`
        : '先在上面选一个储存区域，再在图上拖出它的位置';
    case 'polygon': return '逐点单击画多边形，双击或按 Enter 收笔，Esc 放弃';
    case 'freehand': return '按住拖动即可自由涂画';
    case 'text': return '在图上点一下放置文字标注';
    case 'rect':
    case 'ellipse': return '按住拖出一个图形';
    default: return '拖区域的边框或标签牌可移动，选中后拖四角缩放；标注可直接拖动';
  }
});

/** 区域几何落到当前楼层。同一层同一个区域只有一块面积 —— 再画一次是改不是加。 */
function applyZoneShape(shape: WarehouseZoneShape) {
  const floor = activeFloor.value;
  if (!floor || !canEditLayout.value) return;
  floor.zoneShapes = upsertZoneShape(floorZoneShapes(floor), shape);
  layoutPersistDebouncer.schedule(() => { void persist(false); });
}

/** 把一个还没画上去的区域一键放到本层图上（默认三列排开，不重叠）。 */
function placeZoneOnFloor(zone: WarehouseZone) {
  const floor = activeFloor.value;
  if (!floor || !canEditLayout.value) return;
  applyZoneShape(defaultZoneShapeFor(zone.id, floorZoneShapes(floor).length));
  selectedZoneId.value = zone.id;
  mapSelection.value = `zone:${zone.id}`;
}

function addAnnotation(annotation: WarehouseAnnotation) {
  const floor = activeFloor.value;
  if (!floor || !canEditLayout.value) return;
  const current = floorAnnotations(floor);
  // ⛔ 有上限而且说清楚, 不是静默丢弃 —— 静默丢弃的长相是「我画的东西没了」。
  if (current.length >= MAX_ANNOTATIONS_PER_FLOOR) {
    return ElMessage.warning(`本层标注已达 ${MAX_ANNOTATIONS_PER_FLOOR} 条上限，请先删除一些再画`);
  }
  floor.annotations = [...current, annotation];
  mapSelection.value = `annotation:${annotation.id}`;
  layoutPersistDebouncer.schedule(() => { void persist(false); });
}

function updateAnnotation(annotation: WarehouseAnnotation) {
  const floor = activeFloor.value;
  if (!floor || !canEditLayout.value) return;
  floor.annotations = floorAnnotations(floor).map((item) => (item.id === annotation.id ? annotation : item));
  layoutPersistDebouncer.schedule(() => { void persist(false); });
}

/**
 * 文字标注要先问内容再落图。
 *
 * ⚠️ 这里用 ElMessageBox.prompt 而不是再加一个 el-dialog: MessageBox 是命令式
 * 创建、关闭即销毁的，不存在「上一轮残留 is-closing」那条通道（那是 2026-08-25
 * 生产实测的卡死缺陷）。⛔ 也正因为它是命令式的，:key 重建那套修法在这里不适用。
 */
async function requestAnnotationText(rect: WarehouseRectGeometry) {
  if (!canEditLayout.value) return;
  try {
    const result = await ElMessageBox.prompt('输入要标在平面图上的文字', '文字标注', {
      confirmButtonText: '添加',
      cancelButtonText: '取消',
      inputValidator: (input: string) => (input && input.trim() ? true : '请输入文字'),
    }) as { value: string };
    const text = String(result.value ?? '').trim();
    if (!text) return;
    addAnnotation({ id: crypto.randomUUID(), kind: 'text', ...rect, text, color: annotationColor.value, fontSize: 16 });
  } catch {
    // 用户取消，不做任何事
  }
}

/**
 * 删除画布上选中的东西。
 *
 * 🔴 删区域几何 ≠ 删区域: 区域、它的前缀、它下面的储位都保留, 只是不再画在这层图上。
 *    确认文案必须说清这一点, 否则用户以为自己刚刚删掉了整个冷藏区。
 */
async function removeSelectedShape() {
  const floor = activeFloor.value;
  if (!floor || !canEditLayout.value || !mapSelection.value) return;
  const { kind, id } = parseMapSelection(mapSelection.value);
  if (kind === 'zone') {
    const zone = layout.value.zones.find((item) => item.id === id);
    const name = zone ? `${zone.name} · ${zone.prefix}` : '这个孤块';
    if (!await confirmRemove(`把「${name}」从${floor.name}的平面图上移除？区域本身和它下面的储位都保留。`)) return;
    floor.zoneShapes = floorZoneShapes(floor).filter((shape) => shape.zoneId !== id);
  } else if (kind === 'annotation') {
    if (!await confirmRemove('删除这条标注？')) return;
    floor.annotations = floorAnnotations(floor).filter((item) => item.id !== id);
  } else {
    return;
  }
  mapSelection.value = '';
  await persist(false);
}

function selectMapTool(tool: MapTool) {
  if (!canEditLayout.value) return;
  overlayRef.value?.cancelDraft();
  mapTool.value = tool;
  if (tool !== 'select') mapSelection.value = '';
}

function openMapFullscreen() {
  if (!activeFloor.value) return ElMessage.warning('请先添加一个楼层');
  mapFullscreen.value = true;
}

function closeMapFullscreen() {
  overlayRef.value?.cancelDraft();
  mapFullscreen.value = false;
  mapTool.value = 'select';
}

/**
 * 全屏时的键盘操作。
 *
 * ⛔ 输入框里按 Backspace/Delete 不许删图形 —— 那会在用户改文字时静默删掉选中的
 *    标注(ElMessageBox.prompt 的输入框就在同一个 document 上)。
 */
function onMapKeydown(event: KeyboardEvent) {
  if (!mapFullscreen.value) return;
  const target = event.target as HTMLElement | null;
  const typing = !!target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
  if (event.key === 'Escape') {
    if (hasMapDraft.value) overlayRef.value?.cancelDraft();
    else closeMapFullscreen();
    return;
  }
  if (typing) return;
  if (event.key === 'Enter' && mapTool.value === 'polygon') overlayRef.value?.finishPolygon();
  else if ((event.key === 'Delete' || event.key === 'Backspace') && mapSelection.value) void removeSelectedShape();
}

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
  // 🔴 2026-08-25 生产实测: F006 的 139 条原料批次里 22 条 materialName 为 null,
  // 于是名称回落成批次号, 「按物料分组」在这 22 条上退化成「按批次分组」——
  // 每张卡片恒为 1 批次。分组没坏, 是名字不存在; 所以标记出来而不是假装有名字。
  const realName = row.materialName ?? row.materialTypeName ?? row.productName ?? row.productTypeName;
  const unnamed = realName === null || realName === undefined || String(realName).trim() === '';
  return {
    id: String(row.id ?? ''),
    unnamed,
    name: String(unnamed ? (row.batchNumber ?? '未命名') : realName),
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

function openZoneDialog() {
  zoneForm.name = '';
  zoneForm.prefix = '';
  zoneDialogKey.value += 1;
  zoneDialogVisible.value = true;
}

function openLocationDialog() {
  if (!activeZone.value) return ElMessage.warning('请先创建储存区域');
  locationForm.code = nextLocationCode(activeZone.value, layout.value.locations);
  locationForm.name = '';
  locationForm.notes = '';
  locationDialogKey.value += 1;
  locationDialogVisible.value = true;
}

function openMoveDialog(batch: InventoryBatch) {
  selectedBatch.value = batch;
  // 已有值可能是别的模块写进来的来源描述(实测有 "Workflow terminal output warehouse
  // receipt" 这种英文调试串), 不是本厂配置里的位置编码 —— 不要把它预选进下拉。
  moveLocation.value = layout.value.locations.some((item) => item.code === batch.location) ? batch.location : '';
  moveDialogKey.value += 1;
  moveDialogVisible.value = true;
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
  const floor: WarehouseFloor = { id: crypto.randomUUID(), name: `${layout.value.floors.length + 1} 层`, racks: [], zoneShapes: [], annotations: [] };
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

function onRackDrag(event: NodeDragEvent) {
  const rack = activeFloor.value?.racks.find((entry) => entry.id === event.node.id);
  if (!rack) return;
  rack.x = Math.max(0, Math.round(event.node.position.x));
  rack.y = Math.max(0, Math.round(event.node.position.y));
  // persist() PUT 的是**整份** layout, 包含楼层的 base64 底图。每拖停一次就整份重传
  // 一遍, 底图几 MB 时连续微调会打出一串大请求。合并成 600ms 一次。
  layoutPersistDebouncer.schedule(() => { void persist(false); });
}

async function bindLocation(location: WarehouseLocation, level: number) {
  if (!activeRack.value) return;
  location.rackId = activeRack.value.id;
  location.level = level;
  await persist();
}

/**
 * 解绑: 把位置从货架层上摘下来, 让它回到「未绑定」候选池。
 *
 * 🔴 2026-08-25 实测: 绑定后该位置从**所有**下拉里消失, 而那一行只有一个
 * 「绑定位置」按钮 —— 绑错层就没有任何 UI 途径纠正, 只能改数据库。
 */
async function unbindLocation(location: WarehouseLocation) {
  location.rackId = undefined;
  location.level = undefined;
  await persist();
}

async function confirmRemove(message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
    return true;
  } catch {
    return false;
  }
}

/** 删除区域: 区域下还有位置时拒绝, 不做级联删除(级联会静默带走已绑批次的位置)。 */
async function removeZone(zone: WarehouseZone) {
  const owned = layout.value.locations.filter((item) => item.zoneId === zone.id);
  if (owned.length) return ElMessage.warning(`「${zone.name}」下还有 ${owned.length} 个位置, 请先删除位置`);
  const usedByRack = layout.value.floors.some((floor) => floor.racks.some((rack) => rack.zoneId === zone.id));
  if (usedByRack) return ElMessage.warning(`「${zone.name}」仍被平面图上的货架引用, 请先删除货架`);
  if (!await confirmRemove(`删除储存区域「${zone.name} · ${zone.prefix}」?`)) return;
  layout.value.zones = layout.value.zones.filter((item) => item.id !== zone.id);
  // 区域没了, 它在每一层平面图上的那块面积也必须跟着没 —— 留下来就是一个
  // 指向不存在区域的孤块(渲染成「已删除的区域」), 谁也不知道该拿它怎么办。
  for (const floor of layout.value.floors) {
    floor.zoneShapes = floorZoneShapes(floor).filter((shape) => shape.zoneId !== zone.id);
  }
  if (selectedZoneId.value === zone.id) selectedZoneId.value = layout.value.zones[0]?.id ?? '';
  if (parseMapSelection(mapSelection.value).id === zone.id) mapSelection.value = '';
  await persist();
}

/** 删除位置: 位置上还有批次时拒绝 —— 删掉会让那些批次指向一个不存在的编码。 */
async function removeLocation(location: WarehouseLocation) {
  const held = countAt(location.code);
  if (held.length) return ElMessage.warning(`「${location.code}」上还有 ${held.length} 个批次, 请先把批次移走`);
  if (!await confirmRemove(`删除储存位置「${location.code}」?`)) return;
  layout.value.locations = layout.value.locations.filter((item) => item.id !== location.id);
  await persist();
}

/** 删除货架: 先把绑在它上面的位置解绑, 位置本身保留(位置是高频维护对象, 不该被货架带走)。 */
async function removeRack(rack: WarehouseRack) {
  if (!activeFloor.value) return;
  const bound = layout.value.locations.filter((item) => item.rackId === rack.id);
  const suffix = bound.length ? `, 其上 ${bound.length} 个位置会解绑但保留` : '';
  if (!await confirmRemove(`删除「${rack.name}」?${suffix}`)) return;
  for (const item of bound) { item.rackId = undefined; item.level = undefined; }
  activeFloor.value.racks = activeFloor.value.racks.filter((item) => item.id !== rack.id);
  if (selectedRackId.value === rack.id) selectedRackId.value = '';
  await persist();
}

/** 删除楼层: 楼层上还有货架时拒绝。 */
async function removeFloor(floor: WarehouseFloor) {
  if (floor.racks.length) return ElMessage.warning(`「${floor.name}」上还有 ${floor.racks.length} 个货架, 请先删除货架`);
  // 楼层上画的区域面积和标注会一起消失 —— 说出来, ⛔ 不做无声的连带删除。
  const drawn = floorZoneShapes(floor).length;
  const marks = floorAnnotations(floor).length;
  const carried = [drawn ? `${drawn} 块区域面积` : '', marks ? `${marks} 条标注` : ''].filter(Boolean).join('、');
  if (!await confirmRemove(carried ? `删除「${floor.name}」? 图上的 ${carried} 会一并删除(区域和储位本身保留)。` : `删除「${floor.name}」?`)) return;
  layout.value.floors = layout.value.floors.filter((item) => item.id !== floor.id);
  if (selectedFloorId.value === floor.id) selectedFloorId.value = layout.value.floors[0]?.id ?? '';
  await persist();
}

async function moveBatch() {
  const batch = selectedBatch.value;
  if (!batch || !moveLocation.value || !factoryId.value) return;
  // 目标位置必须是本厂已配置的位置。后端当前不校验存在性(实测传
  // ZZ99-NOT-CONFIGURED 返回 200 并写入), 本轮已在后端补上; 前端这一道是同口径的
  // 第二层, 免得再出现 storageLocation 里躺着来源描述的情况。
  if (!layout.value.locations.some((item) => item.code === moveLocation.value)) {
    return ElMessage.warning('请选择本厂已配置的储存位置');
  }
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
    // 🔴 原实现识别完**直接写进配置并保存**, 没有任何预览或确认 —— 模型认错一次
    //    就把工厂的仓储配置改了, 而且没有撤销。改成先出预览, 人工确认才应用。
    recognizePreview.value = response.data;
    recognizePreviewImage.value = image;
    recognizeDialogKey.value += 1;
    recognizeDialogVisible.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 识别失败');
  } finally {
    loading.value = false;
    if (fileInput.value) fileInput.value.value = '';
  }
}

/** 识别结果里真正会被采纳的部分 —— 预览和应用必须用同一个函数, 否则两份会漂。 */
const recognizedZonesToAdd = computed(() => (recognizePreview.value?.zones ?? []).filter((zone) => {
  const prefix = String(zone.prefix ?? '').toUpperCase();
  return /^[A-Z]{2,8}$/.test(prefix) && !layout.value.zones.some((current) => current.prefix === prefix);
}));
const recognizedZonesSkipped = computed(() => (recognizePreview.value?.zones ?? []).length - recognizedZonesToAdd.value.length);
const recognizedRacks = computed(() => recognizePreview.value?.racks ?? []);

async function applyRecognizedLayout() {
  const image = recognizePreviewImage.value;
  if (!recognizePreview.value || !image) return;
  loading.value = true;
  try {
    // 记下「这个识别出来的区域最终用了哪个 id」—— 下面要拿它挂几何。
    const acceptedZoneIds = new Map<typeof recognizedZonesToAdd.value[number], string>();
    for (const zone of recognizedZonesToAdd.value) {
      const prefix = String(zone.prefix ?? '').toUpperCase();
      const id = zone.id || crypto.randomUUID();
      acceptedZoneIds.set(zone, id);
      layout.value.zones.push({ id, name: zone.name, prefix, color: zoneColors[layout.value.zones.length % zoneColors.length] });
    }
    if (!activeFloor.value) {
      const floor: WarehouseFloor = { id: crypto.randomUUID(), name: '1 层', racks: [] as WarehouseRack[], zoneShapes: [], annotations: [] };
      layout.value.floors.push(floor);
      selectedFloorId.value = floor.id;
    }
    if (activeFloor.value) {
      activeFloor.value.background = image;
      // 模型给了 x/y/width/height(0~100 百分比)的区域, 直接落成本层的区域几何。
      // ⛔ 没给几何的**不补默认框** —— 补一个等于在图上画出一块图里没有的区域。
      for (const [zone, id] of acceptedZoneIds) {
        const shape = zoneShapeFromPercent(id, zone);
        if (shape) activeFloor.value.zoneShapes = upsertZoneShape(floorZoneShapes(activeFloor.value), shape);
      }
      for (const rack of recognizedRacks.value) {
        // 模型可能给回一个本厂不存在的 zoneId —— 那样的货架在画布上会渲染成
        // 「 · 名字」(前缀空), 且永远绑不上位置。归到当前选中区域, 不静默留空。
        const zoneId = layout.value.zones.some((zone) => zone.id === rack.zoneId)
          ? rack.zoneId
          : (activeZone.value?.id ?? layout.value.zones[0]?.id ?? '');
        activeFloor.value.racks.push({ ...rack, zoneId, id: rack.id || crypto.randomUUID(), width: Math.max(100, Number(rack.width || 140)), height: Math.max(70, Number(rack.height || 90)), levels: Math.max(1, Number(rack.levels || 4)) });
      }
    }
    await persist(false);
    recognizeDialogVisible.value = false;
    recognizePreview.value = null;
    recognizePreviewImage.value = '';
    ElMessage.success('平面图已应用');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '应用识别结果失败');
  } finally {
    loading.value = false;
    if (fileInput.value) fileInput.value.value = '';
  }
}

function countAt(code: string) { return batches.value.filter((batch) => batch.location === code); }
function isConfiguredLocation(code: string) { return layout.value.locations.some((item) => item.code === code); }
/**
 * 类别卡片上的「N 项」。
 *
 * 原实现用的是**未过滤**的全集, 而下面的列表是按搜索/位置过滤后的 ——
 * 一选位置筛选, 顶上写着 21 项、下面只剩 2 张卡, 两个数说的不是一回事。
 * 这里改成与 `materials` 同一口径(同样的搜索/位置条件), 让计数解释得了列表。
 */
function categoryCount(category: ItemCategory) {
  const names = new Set<string>();
  for (const batch of batches.value) {
    if (batch.category !== category) continue;
    if (locationFilter.value && batch.location !== locationFilter.value) continue;
    if (searchTerm.value && !`${batch.name} ${batch.batchNumber}`.toLowerCase().includes(searchTerm.value.toLowerCase())) continue;
    names.add(batch.name);
  }
  return names.size;
}
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
// 全屏时锁住页面滚动 —— 全屏面板是 position:fixed, 底下的页面还能滚会让人晕。
watch(mapFullscreen, (open) => { document.body.style.overflow = open ? 'hidden' : ''; });
// 换楼层就把选中清掉: 选中标记里存的是上一层的图形 id, 留着会指向一个本层没有的东西。
watch(selectedFloorId, () => { mapSelection.value = ''; overlayRef.value?.cancelDraft(); });
onMounted(() => {
  loadWorkspace();
  window.addEventListener('keydown', onMapKeydown);
});
// 离开页面时把还没落地的那次保存取消掉 —— 否则用户已经走了, 还会再打一次整份 PUT。
// 全屏时直接路由走掉的话, body 的 overflow 也必须还回去, 否则整站滚不动了。
onUnmounted(() => {
  layoutPersistDebouncer.cancel();
  window.removeEventListener('keydown', onMapKeydown);
  document.body.style.overflow = '';
});
</script>

<template>
  <main v-loading="loading" class="storage-workspace">
    <header class="workspace-header">
      <div><h1>仓储空间</h1><span class="muted">{{ layout.zones.length }} 个区域 · {{ layout.locations.length }} 个位置</span></div>
      <el-button :icon="Box" @click="router.push('/warehouse/stocktakes')">盘点任务</el-button>
    </header>

    <nav class="workspace-tabs">
      <button v-for="tab in navigationTabs" :key="tab.id" :data-activity-action="`storage.tab.${tab.id}`" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}</button>
    </nav>

    <section v-if="activeTab === 'locations'" class="workspace-body">
      <aside class="zone-sidebar">
        <div class="section-heading"><h2>储存区域</h2><el-button v-if="canEditLayout" data-activity-action="storage.add-zone" :icon="Plus" circle size="small" aria-label="添加储存区域" title="添加储存区域" @click="openZoneDialog" /></div>
        <div v-for="zone in layout.zones" :key="zone.id" class="zone-row" :class="{ selected: zone.id === selectedZoneId }">
          <button class="zone-main" data-activity-action="storage.select-zone" @click="selectedZoneId = zone.id">
            <span class="zone-prefix" :style="{ background: zone.color }">{{ zone.prefix }}</span>
            <span>{{ zone.name }}</span><small>{{ layout.locations.filter(item => item.zoneId === zone.id).length }}</small>
          </button>
          <el-button v-if="canEditLayout" class="row-remove" data-activity-action="storage.delete-zone" link :icon="Delete" :aria-label="`删除区域 ${zone.name}`" :title="`删除区域 ${zone.name}`" @click.stop="removeZone(zone)" />
        </div>
        <el-empty v-if="!layout.zones.length" description="暂无区域" :image-size="70" />
      </aside>

      <div class="primary-panel">
        <div class="section-heading"><h2>{{ activeZone?.name || '储存位置' }}</h2><div class="toolbar"><el-input v-model="searchTerm" :prefix-icon="Search" placeholder="搜索位置" clearable /><el-button v-if="canEditLayout" type="primary" :icon="Plus" @click="openLocationDialog">添加位置</el-button></div></div>
        <div class="location-grid"><article v-for="location in visibleLocations" :key="location.id" class="location-card"><div class="card-top"><strong>{{ location.code }}</strong><span class="card-top-right"><span>{{ countAt(location.code).length }} 批</span><el-button v-if="canEditLayout" data-activity-action="storage.delete-location" link :icon="Delete" :aria-label="`删除位置 ${location.code}`" :title="`删除位置 ${location.code}`" @click="removeLocation(location)" /></span></div><div>{{ location.name }}</div><small class="muted">{{ location.notes || (countAt(location.code).length ? `${new Set(countAt(location.code).map(item => item.name)).size} 种物料` : '空闲') }}</small></article></div>
        <el-empty v-if="!visibleLocations.length" description="暂无储存位置" :image-size="90" />
      </div>
    </section>

    <section v-else-if="activeTab === 'check'" class="stock-panel">
      <div class="category-grid"><button v-for="category in categoryOptions" :key="category" class="category-card" :class="{ selected: category === selectedCategory }" @click="selectCategory(category)"><strong>{{ category }}</strong><small>{{ categoryCount(category) }} 项</small></button></div>
      <div class="section-heading"><h2>{{ selectedCategory }}</h2><div class="toolbar"><el-input v-model="searchTerm" :prefix-icon="Search" placeholder="搜索名称 / 批次" clearable /><el-select v-model="locationFilter" placeholder="全部位置" clearable><el-option-group v-for="zone in layout.zones" :key="zone.id" :label="`${zone.name} · ${zone.prefix}`"><el-option v-for="location in layout.locations.filter(item => item.zoneId === zone.id)" :key="location.id" :label="location.code" :value="location.code" /></el-option-group></el-select></div></div>
      <div class="material-grid"><button v-for="material in materials" :key="material.name" class="material-card" :class="{ selected: selectedMaterial === material.name }" @click="selectedMaterial = material.name"><strong>{{ material.name }}</strong><small v-if="material.unnamed" class="unnamed-tag">未登记物料名 · 按批次号显示</small><span class="quantity">{{ formatQuantity(material.quantity) }} <small>{{ material.unit }}</small></span><span class="card-foot"><small>{{ material.entries.length }} 批次</small><small>{{ material.maxAge === null ? '在库时长未知' : `最长 ${material.maxAge} 天` }}</small></span></button></div>
      <el-empty v-if="!materials.length" description="暂无库存" :image-size="75" />
      <template v-if="selectedMaterial"><div class="section-heading batch-heading"><h2>{{ selectedMaterial }}</h2><el-button type="primary" plain @click="router.push('/warehouse/stocktakes')">发起盘点</el-button></div><div class="batch-grid"><article v-for="batch in selectedBatches" :key="batch.id" class="batch-card"><div class="card-top"><strong>{{ batch.batchNumber }}</strong><el-button v-if="canMoveInventory" link type="primary" @click="openMoveDialog(batch)">调整位置</el-button></div><div class="quantity">{{ formatQuantity(batch.quantity) }} <small>{{ batch.unit }}</small></div><div class="card-foot"><span :class="{ 'bad-location': batch.location && !isConfiguredLocation(batch.location) }" :title="batch.location">{{ batch.location ? (isConfiguredLocation(batch.location) ? batch.location : '位置无效') : '未分配' }}</span><span>在库 {{ formatAge(batch.inboundDate) }}</span></div></article></div></template>
    </section>

    <section v-else class="map-panel" :class="{ 'is-fullscreen': mapFullscreen }">
      <div class="map-toolbar">
        <div>
          <el-radio-group v-model="selectedFloorId"><el-radio-button v-for="floor in layout.floors" :key="floor.id" :value="floor.id">{{ floor.name }}</el-radio-button></el-radio-group>
          <el-button v-if="canEditLayout" link :icon="Plus" @click="addFloor">楼层</el-button>
          <el-button v-if="canEditLayout && activeFloor" data-activity-action="storage.delete-floor" link :icon="Delete" :aria-label="`删除${activeFloor.name}`" :title="`删除${activeFloor.name}`" @click="removeFloor(activeFloor)" />
        </div>
        <div class="toolbar">
          <el-select v-model="selectedZoneId" placeholder="选择区域"><el-option v-for="zone in layout.zones" :key="zone.id" :label="`${zone.name} · ${zone.prefix}`" :value="zone.id" /></el-select>
          <el-button v-if="canEditLayout" :icon="Grid" @click="addRack">添加货架</el-button>
          <el-button v-if="canEditLayout" :icon="Upload" @click="fileInput?.click()">AI 识别底图</el-button>
          <input ref="fileInput" type="file" accept="image/*" hidden @change="handleImageUpload">
          <el-button v-if="!mapFullscreen" type="primary" :icon="FullScreen" @click="openMapFullscreen">{{ mapFullscreenLabel }}</el-button>
          <el-button v-else :icon="Close" @click="closeMapFullscreen">退出全屏</el-button>
        </div>
      </div>

      <!--
        绘图工具条只在全屏出现。缩略图状态下叠加层完全不吃指针事件,
        画布交互(拖货架/平移/缩放)与本次改动之前逐字节相同。
      -->
      <div v-if="mapFullscreen" class="draw-toolbar">
        <div class="tool-group" role="group" aria-label="平面图绘制工具">
          <button
            v-for="tool in mapTools" :key="tool.id" type="button" class="tool-button"
            :class="{ active: mapTool === tool.id }" :disabled="!canEditLayout"
            :aria-pressed="mapTool === tool.id" @click="selectMapTool(tool.id)"
          >{{ tool.label }}</button>
        </div>
        <div class="tool-group" role="group" aria-label="标注颜色">
          <button
            v-for="color in annotationColors" :key="color" type="button" class="color-swatch"
            :class="{ active: annotationColor === color }" :style="{ background: color }"
            :disabled="!canEditLayout" :aria-label="`标注颜色 ${color}`" :title="`标注颜色 ${color}`"
            @click="annotationColor = color"
          />
        </div>
        <div class="tool-group">
          <el-checkbox v-model="showAnnotations">显示标注</el-checkbox>
          <el-button v-if="canEditLayout && mapTool === 'polygon'" link type="primary" @click="overlayRef?.finishPolygon()">收笔</el-button>
          <el-button v-if="canEditLayout && mapSelection" link type="danger" :icon="Delete" @click="removeSelectedShape">删除选中</el-button>
        </div>
        <span class="muted map-hint">{{ mapHint }}</span>
      </div>

      <div class="map-columns">
        <div
          class="canvas-shell" :class="{ 'is-designing': canDesignMap }"
          :style="activeFloor?.background ? { backgroundImage: `linear-gradient(rgba(255,255,255,.72),rgba(255,255,255,.72)),url(${activeFloor.background})` } : {}"
        >
          <VueFlow
            v-if="activeFloor" :nodes="flowNodes" :edges="[]" :nodes-draggable="canEditLayout" fit-view-on-init
            @node-click="event => selectedRackId = event.node.id" @node-drag-stop="onRackDrag"
          >
            <Background pattern-color="#d8e0ec" :gap="22" />
            <WarehouseFloorOverlay
              ref="overlayRef" :floor="activeFloor" :zones="layout.zones" :tool="mapTool"
              :interactive="canDesignMap" :selection="mapSelection" :active-zone-id="selectedZoneId"
              :stroke-color="annotationColor" :show-annotations="showAnnotations"
              @select="value => mapSelection = value" @zone-shape="applyZoneShape"
              @annotation-add="addAnnotation" @annotation-change="updateAnnotation"
              @text-request="requestAnnotationText" @draft-change="value => hasMapDraft = value"
            />
            <!-- Controls 必须排在叠加层**之后** —— 两者同为 z-index:5, 后写的在上, 否则落笔状态下缩放按钮被盖住点不到 -->
            <Controls position="bottom-left" />
          </VueFlow>
          <el-empty v-else description="暂无楼层" :image-size="100" />
          <button v-if="!mapFullscreen && activeFloor" type="button" class="canvas-expand" :aria-label="mapFullscreenAriaLabel" :title="mapFullscreenAriaLabel" @click="openMapFullscreen">
            <el-icon><FullScreen /></el-icon><span>{{ mapFullscreenLabel }}</span>
          </button>
        </div>

        <aside class="rack-detail">
          <div v-if="canEditLayout && activeFloor && unplacedZones.length" class="unplaced-zones">
            <h3>还没画在{{ activeFloor.name }}的区域</h3>
            <div class="chip-row">
              <button
                v-for="zone in unplacedZones" :key="zone.id" type="button" class="place-zone-chip"
                :style="{ background: zone.color }" :title="`把 ${zone.name} 放到平面图上`"
                @click="placeZoneOnFloor(zone)"
              >{{ zone.prefix }} · {{ zone.name }}</button>
            </div>
            <small class="muted">点一下按默认位置放上去，或全屏后用「区域」笔在图上拖出它的实际位置。</small>
          </div>
          <template v-if="activeRack">
            <div class="section-heading"><h2>{{ activeRack.name }}</h2><el-button v-if="canEditLayout" data-activity-action="storage.delete-rack" link :icon="Delete" :aria-label="`删除${activeRack.name}`" :title="`删除${activeRack.name}`" @click="removeRack(activeRack)" /></div>
            <span class="muted">{{ layout.zones.find(z => z.id === activeRack!.zoneId)?.name || '未归属区域' }} · {{ activeRack.levels }} 层</span>
            <div class="shelf-stack">
              <div v-for="level in Array.from({ length: activeRack.levels }, (_, index) => activeRack!.levels - index)" :key="level" class="shelf-level">
                <span class="level-number">{{ level }}F</span>
                <div class="shelf-content">
                  <div v-for="location in activeRackLocations.filter(item => item.level === level)" :key="location.id" class="shelf-location"><strong>{{ location.code }}</strong><small>{{ countAt(location.code).length }} 批</small><small v-if="countAt(location.code)[0]">{{ formatAge(countAt(location.code)[0]?.inboundDate) }}</small><el-button v-if="canEditLayout" link :icon="Delete" :aria-label="`把 ${location.code} 从本层解绑`" :title="`把 ${location.code} 从本层解绑`" @click="unbindLocation(location)" /></div>
                  <el-dropdown v-if="canEditLayout" trigger="click" @command="(id: string) => { const location = layout.locations.find(item => item.id === id); if (location) bindLocation(location, level); }"><el-button text :icon="Plus">绑定位置</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="location in layout.locations.filter(item => item.zoneId === activeRack!.zoneId && !item.rackId)" :key="location.id" :command="location.id">{{ location.code }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
                </div>
              </div>
            </div>
          </template>
          <el-empty v-else description="选择货架" :image-size="80" />
        </aside>
      </div>
    </section>

    <el-dialog :key="`zone-${zoneDialogKey}`" v-model="zoneDialogVisible" title="添加储存区域" width="420"><el-form label-position="top"><el-form-item label="区域名称"><el-input v-model="zoneForm.name" placeholder="例如：冷藏区" /></el-form-item><el-form-item label="英文前缀"><el-input v-model="zoneForm.prefix" maxlength="8" placeholder="例如：LC" /></el-form-item></el-form><template #footer><el-button @click="zoneDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="addZone">保存</el-button></template></el-dialog>
    <el-dialog :key="`location-${locationDialogKey}`" v-model="locationDialogVisible" title="添加储存位置" width="440"><el-form label-position="top"><el-form-item label="位置编码"><el-input v-model="locationForm.code" /></el-form-item><el-form-item label="位置名称"><el-input v-model="locationForm.name" placeholder="选填" /></el-form-item><el-form-item label="备注"><el-input v-model="locationForm.notes" placeholder="选填" /></el-form-item></el-form><template #footer><el-button @click="locationDialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="addLocation">保存</el-button></template></el-dialog>
    <el-dialog :key="`move-${moveDialogKey}`" v-model="moveDialogVisible" title="调整储存位置" width="420"><el-form label-position="top"><el-form-item label="批次"><span>{{ selectedBatch?.batchNumber }}</span></el-form-item><el-form-item label="储存位置"><el-select v-model="moveLocation" filterable placeholder="选择位置" style="width:100%"><el-option-group v-for="zone in layout.zones" :key="zone.id" :label="`${zone.name} · ${zone.prefix}`"><el-option v-for="location in layout.locations.filter(item => item.zoneId === zone.id)" :key="location.id" :label="`${location.code} · ${location.name}`" :value="location.code" /></el-option-group></el-select></el-form-item></el-form><template #footer><el-button @click="moveDialogVisible = false">取消</el-button><el-button type="primary" @click="moveBatch">保存</el-button></template></el-dialog>
    <el-dialog :key="`recognize-${recognizeDialogKey}`" v-model="recognizeDialogVisible" title="确认 AI 识别结果" width="560">
      <p class="muted recognize-note">识别结果不会自动生效。确认后才会写入本厂仓储配置，底图也一并保存。</p>
      <ul class="recognize-list">
        <li><strong>新增区域 {{ recognizedZonesToAdd.length }} 个</strong><span v-if="recognizedZonesToAdd.length">：{{ recognizedZonesToAdd.map(z => `${z.name} · ${String(z.prefix).toUpperCase()}`).join('、') }}</span></li>
        <li v-if="recognizedZonesSkipped > 0" class="muted">另有 {{ recognizedZonesSkipped }} 个区域被跳过（前缀不合法或已存在）</li>
        <li><strong>新增货架 {{ recognizedRacks.length }} 个</strong></li>
        <li v-if="recognizePreview?.confidence != null" class="muted">模型置信度 {{ Math.round((recognizePreview.confidence || 0) * 100) }}%</li>
      </ul>
      <img v-if="recognizePreviewImage" class="recognize-preview" :src="recognizePreviewImage" alt="待确认的仓库底图">
      <template #footer>
        <el-button @click="recognizeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading" :disabled="!recognizedZonesToAdd.length && !recognizedRacks.length" @click="applyRecognizedLayout">应用到配置</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.storage-workspace{--ink:#17243a;--muted:#72809a;--line:#e5eaf2;--blue:#2563eb;padding:28px 32px;color:var(--ink);font-size:15px;background:#f7f9fc;min-height:calc(100vh - 88px)}.workspace-header,.section-heading,.toolbar,.card-top,.card-foot,.map-toolbar{display:flex;align-items:center;justify-content:space-between}.workspace-header{margin-bottom:22px}.workspace-header h1{font-size:24px;margin:0 0 6px;font-weight:700}.muted,small{color:var(--muted);font-size:13px}.workspace-tabs{display:flex;gap:28px;border-bottom:1px solid var(--line);margin-bottom:22px}.workspace-tabs button{padding:14px 2px;background:none;border:0;color:var(--muted);font-size:15px;cursor:pointer}.workspace-tabs button.active{color:var(--blue);border-bottom:2px solid var(--blue);font-weight:600}.workspace-body{display:grid;grid-template-columns:245px minmax(0,1fr);gap:20px}.zone-sidebar,.primary-panel,.stock-panel,.map-panel,.rack-detail{background:white;border:1px solid var(--line);border-radius:14px;padding:20px}.section-heading{margin-bottom:18px}.section-heading h2,.rack-detail h2{font-size:17px;margin:0}.zone-row{width:100%;display:flex;align-items:center;gap:11px;padding:11px 10px;margin:5px 0;border:1px solid transparent;border-radius:10px;background:white;text-align:left;color:var(--ink);font-size:14px;cursor:pointer}.zone-row.selected,.material-card.selected,.category-card.selected{background:#eff6ff;border-color:#bfd7ff}.zone-row small{margin-left:auto}.zone-prefix{display:grid;place-items:center;width:37px;height:32px;border-radius:8px;font-weight:650}.toolbar{gap:10px}.toolbar .el-input{width:220px}.toolbar .el-select{width:180px}.location-grid,.material-grid,.batch-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:13px}.location-card,.material-card,.batch-card{min-height:112px;padding:15px;border:1px solid var(--line);border-radius:12px;background:white;text-align:left}.location-card .card-top,.batch-card .card-top{margin-bottom:11px}.location-card strong,.batch-card strong{font-size:16px}.location-card>div:nth-child(2){margin-bottom:7px}.category-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:25px}.category-card,.material-card{cursor:pointer}.category-card{padding:15px;border:1px solid var(--line);border-radius:11px;background:white;text-align:left}.category-card strong,.category-card small,.material-card strong,.material-card>.quantity{display:block}.category-card strong{font-size:16px;margin-bottom:6px}.material-card>.quantity,.batch-card>.quantity{font-size:22px;font-weight:700;margin:13px 0}.material-card .quantity small,.batch-card .quantity small{font-weight:400}.card-foot{font-size:13px;color:#526176}.batch-heading{margin-top:27px;padding-top:20px;border-top:1px solid var(--line)}.map-toolbar{gap:12px;margin-bottom:15px}.map-columns{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:15px}.canvas-shell{position:relative;height:340px;border:1px solid var(--line);border-radius:12px;overflow:hidden;background-size:contain;background-position:center;background-repeat:no-repeat}.canvas-shell>.el-empty{height:100%}.rack-detail{padding:17px}.rack-detail h2{margin-bottom:7px}.shelf-stack{margin-top:22px}.shelf-level{display:grid;grid-template-columns:30px minmax(0,1fr);gap:8px;margin:9px 0}.level-number{padding-top:12px;color:var(--muted);font-size:12px}.shelf-content{min-height:74px;padding:9px;background:#f8fafc;border:1px solid #e5eaf2;border-bottom:3px solid #94a3b8;border-radius:8px}.shelf-location{display:flex;justify-content:space-between;align-items:center;margin-bottom:7px}.shelf-location strong{font-size:14px}.zone-row{display:flex;align-items:center;gap:4px;padding:0 4px 0 0;margin:5px 0;border:1px solid transparent;border-radius:10px;background:white}.zone-main{flex:1;min-width:0;display:flex;align-items:center;gap:11px;padding:11px 6px 11px 10px;border:0;border-radius:10px;background:none;text-align:left;color:var(--ink);font-size:14px;cursor:pointer}.zone-main small{margin-left:auto}.zone-row .row-remove,.location-card .el-button.is-link,.shelf-location .el-button.is-link{color:var(--muted);padding:0 2px}.zone-row .row-remove:hover,.location-card .el-button.is-link:hover,.shelf-location .el-button.is-link:hover{color:#d03050}.card-top-right{display:flex;align-items:center;gap:6px}.material-card,.batch-card,.location-card{min-width:0;overflow:hidden}.material-card>.quantity,.batch-card>.quantity,.material-card strong,.batch-card strong,.location-card strong{overflow-wrap:anywhere;word-break:break-word}.unnamed-tag{display:block;margin-top:4px;color:#b26a00;font-size:12px}.bad-location{color:#d03050}.rack-detail .section-heading{margin-bottom:0}.recognize-note{margin:0 0 12px}.recognize-list{margin:0 0 14px;padding-left:18px}.recognize-list li{margin:4px 0}.recognize-preview{display:block;width:100%;max-height:260px;object-fit:contain;border:1px solid var(--line);border-radius:8px;background:#f8fafc}.canvas-expand{position:absolute;top:10px;right:10px;z-index:20;display:flex;align-items:center;gap:6px;padding:7px 12px;border:1px solid var(--line);border-radius:9px;background:rgba(255,255,255,.94);color:var(--blue);font-size:13px;font-weight:600;cursor:pointer;box-shadow:0 1px 4px rgba(23,36,58,.12)}.canvas-expand:hover{background:#eff6ff}.map-panel.is-fullscreen{position:fixed;inset:0;z-index:2400;margin:0;border:0;border-radius:0;padding:16px 22px 20px;background:white;display:flex;flex-direction:column;overflow:auto}.map-panel.is-fullscreen .map-columns{flex:1;min-height:0;grid-template-columns:minmax(0,1fr) 320px}.map-panel.is-fullscreen .canvas-shell{height:100%;min-height:420px}.draw-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:14px;margin-bottom:13px;padding:10px 12px;border:1px solid var(--line);border-radius:11px;background:#f8fafc}.tool-group{display:flex;align-items:center;gap:7px}.tool-button{padding:6px 13px;border:1px solid var(--line);border-radius:8px;background:white;color:var(--ink);font-size:13px;cursor:pointer}.tool-button.active{background:var(--blue);border-color:var(--blue);color:white;font-weight:600}.tool-button:disabled,.color-swatch:disabled{opacity:.5;cursor:not-allowed}.color-swatch{width:22px;height:22px;border:2px solid white;border-radius:50%;outline:1px solid var(--line);cursor:pointer}.color-swatch.active{outline:2px solid var(--blue);outline-offset:1px}.map-hint{margin-left:auto}.unplaced-zones{margin-bottom:16px;padding-bottom:14px;border-bottom:1px solid var(--line)}.unplaced-zones h3{margin:0 0 9px;font-size:14px}.chip-row{display:flex;flex-wrap:wrap;gap:7px;margin-bottom:7px}.place-zone-chip{padding:6px 11px;border:1px solid var(--line);border-radius:8px;color:var(--ink);font-size:13px;cursor:pointer}.place-zone-chip:hover{border-color:#bfd7ff}@media(max-width:1050px){.storage-workspace{padding:20px}.workspace-body{grid-template-columns:205px minmax(0,1fr)}.map-columns{grid-template-columns:1fr}.canvas-shell{height:280px}.map-panel.is-fullscreen .map-columns{grid-template-columns:1fr}.map-panel.is-fullscreen .canvas-shell{height:58vh}.toolbar .el-input{width:160px}.category-grid{grid-template-columns:repeat(2,1fr)}}
</style>
