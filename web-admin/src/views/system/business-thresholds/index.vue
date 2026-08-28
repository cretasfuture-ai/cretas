<!--
  业务阈值配置 (System Business Thresholds) — 2026-08-28.

  Owner 要求原话:「得有一个模块专门去设置这个东西」。

  背景: 阈值参数中心后端 (CanvasThresholdsController) 与前端 API/编辑器组件
  (thresholdsHub.ts / thresholds-hub-editor/index.vue) 早就存在，但编辑器组件此前
  只内嵌在 canvas-editor 页面里，没有独立路由 / 菜单入口 —— 工厂管理员点不到。
  本页把同一套能力做成一个真正能从菜单点进来的工厂侧独立页面。

  Backend: GET/POST/PUT/DELETE /api/mobile/{factoryId}/canvas-thresholds
           POST .../canvas-thresholds/reset-to-default?category=

  🔴 不做百分比换算 — 阈值中心的值按 valueType 原样存字符串, unit 只是显示后缀,
     不是 factory_settings 那套"存小数/显示百分比"的约定。照原值显示和提交。

  RBAC: 读写均需 system:read_write — 非管理员时保存/重置按钮禁用 (canWrite('system'))。
  防呆设计: 批量重置带 ElMessageBox.confirm 二次确认 (分类名 + 后果说明)，
            照 finance/gross-margin-redline/index.vue 的既有 pattern。
-->
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  listThresholds,
  updateThreshold,
  resetCategoryToDefault,
  ThresholdCategoryLabels,
  type FactoryThreshold,
  type ThresholdCategory,
  type ThresholdValueType,
} from '@/api/thresholdsHub';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);

/** 仅 system:read_write (工厂超管等) 可写。控制保存/重置按钮显隐/禁用。 */
const canWrite = computed(() => permissionStore.canWrite('system'));

/** 15 大类 — 与后端 ThresholdCategory 枚举同步 (2026-08-28 由 6 扩到 15)。 */
const CATEGORY_ORDER: ThresholdCategory[] = [
  'INVENTORY', 'IOT', 'CREDIT', 'BOM', 'SHORTAGE', 'AI',
  'SALES', 'PURCHASE', 'PRICING', 'PRODUCTION', 'PROCUREMENT',
  'QUALITY', 'FINANCE', 'PROCESSING', 'EFFICIENCY',
];

/** 表格行的可编辑草稿字段 — 与原始数据分开，避免用户输入中途覆盖服务端字段。 */
interface EditableRow extends FactoryThreshold {
  _editValue: string;
  _editEnabled: boolean;
}

const activeCategory = ref<ThresholdCategory>('INVENTORY');
const rows = ref<EditableRow[]>([]);
const loading = ref(false);
const savingId = ref<string | null>(null);
const resetting = ref(false);

/** 字符串 -> number|undefined, 供 el-input-number 使用 (空串/非数字一律 undefined). */
function numOrUndef(v: string): number | undefined {
  if (v === '' || v === null || v === undefined) return undefined;
  const n = Number(v);
  return Number.isNaN(n) ? undefined : n;
}

function boundOrUndef(v: string | null | undefined): number | undefined {
  if (v === null || v === undefined || v === '') return undefined;
  const n = Number(v);
  return Number.isNaN(n) ? undefined : n;
}

function valueTypeTagType(t: ThresholdValueType): 'success' | 'warning' | 'info' | 'primary' {
  switch (t) {
    case 'INTEGER': return 'primary';
    case 'DECIMAL': return 'success';
    case 'DOUBLE': return 'warning';
    default: return 'info';
  }
}

async function loadCurrent() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const data = await listThresholds(factoryId.value, activeCategory.value);
    rows.value = data.map((r) => ({
      ...r,
      _editValue: r.thresholdValue,
      _editEnabled: r.enabled,
    }));
  } catch (e) {
    // request 拦截器已经弹过 sticky error toast (见 request.ts response interceptor);
    // 这里只记录，不重复弹一遍。
    console.error('[BusinessThresholds] load failed:', e);
  } finally {
    loading.value = false;
  }
}

async function onSaveRow(row: EditableRow) {
  if (!factoryId.value || !canWrite.value) return;
  savingId.value = row.id;
  try {
    await updateThreshold(factoryId.value, row.id, {
      thresholdValue: row._editValue,
      enabled: row._editEnabled,
      version: row.version,
    });
    ElMessage.success(`「${row.thresholdKey}」已保存`);
    await loadCurrent();
  } catch (e) {
    console.error('[BusinessThresholds] save row failed:', e);
  } finally {
    savingId.value = null;
  }
}

async function onResetCategory() {
  if (!factoryId.value || !canWrite.value) return;
  const label = ThresholdCategoryLabels[activeCategory.value] ?? activeCategory.value;
  try {
    await ElMessageBox.confirm(
      `确认将「${label}」分类下所有阈值恢复为默认值？此操作会覆盖已自定义的配置，且不可撤销。`,
      '恢复默认值确认',
      { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  resetting.value = true;
  try {
    const updated = await resetCategoryToDefault(factoryId.value, activeCategory.value);
    ElMessage.success(`已恢复 ${updated} 项阈值为默认值`);
    await loadCurrent();
  } catch (e) {
    console.error('[BusinessThresholds] reset category failed:', e);
  } finally {
    resetting.value = false;
  }
}

function onTabChange() {
  loadCurrent();
}

onMounted(() => {
  loadCurrent();
});
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">业务阈值配置</span>
          <span class="page-subtitle">工厂各业务模块的可配置阈值参数中心 — 按分类查看/编辑/恢复默认</span>
        </div>
      </template>

      <el-tabs v-model="activeCategory" class="threshold-tabs" @tab-change="onTabChange">
        <el-tab-pane
          v-for="cat in CATEGORY_ORDER"
          :key="cat"
          :label="ThresholdCategoryLabels[cat] ?? cat"
          :name="cat"
        >
          <div class="tab-actions">
            <el-button
              type="warning"
              size="small"
              :loading="resetting"
              :disabled="!canWrite || loading"
              @click="onResetCategory"
            >
              恢复默认值
            </el-button>
            <el-button size="small" :disabled="loading" @click="loadCurrent">刷新</el-button>
          </div>

          <el-table
            v-loading="loading"
            :data="rows"
            stripe
            style="width: 100%"
            empty-text="此分类下暂无阈值配置"
          >
            <el-table-column prop="thresholdKey" label="键" min-width="200">
              <template #default="{ row }">
                <code class="key-cell">{{ row.thresholdKey }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="220" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="valueTypeTagType(row.valueType)">{{ row.valueType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="当前值" min-width="200">
              <template #default="{ row }: { row: EditableRow }">
                <el-input-number
                  v-if="row.valueType !== 'STRING'"
                  :model-value="numOrUndef(row._editValue)"
                  :min="boundOrUndef(row.minValue)"
                  :max="boundOrUndef(row.maxValue)"
                  :precision="row.valueType === 'INTEGER' ? 0 : undefined"
                  :step="row.valueType === 'INTEGER' ? 1 : 0.1"
                  :disabled="!canWrite"
                  size="small"
                  controls-position="right"
                  style="width: 140px"
                  @update:model-value="(v: number | undefined) => (row._editValue = v === undefined || v === null ? '' : String(v))"
                />
                <el-input
                  v-else
                  v-model="row._editValue"
                  :disabled="!canWrite"
                  size="small"
                  style="width: 160px"
                />
                <span v-if="row.unit" class="unit-text"> {{ row.unit }}</span>
              </template>
            </el-table-column>
            <el-table-column label="默认值" min-width="110">
              <template #default="{ row }">
                <span class="default-text">{{ row.defaultValue }}{{ row.unit ? ' ' + row.unit : '' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="范围" min-width="140">
              <template #default="{ row }">
                <span v-if="row.minValue || row.maxValue" class="range-text">
                  <span v-if="row.minValue">≥ {{ row.minValue }}</span>
                  <span v-if="row.minValue && row.maxValue">, </span>
                  <span v-if="row.maxValue">≤ {{ row.maxValue }}</span>
                </span>
                <span v-else class="range-text">无限制</span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="80">
              <template #default="{ row }: { row: EditableRow }">
                <el-switch v-model="row._editEnabled" :disabled="!canWrite" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }: { row: EditableRow }">
                <el-button
                  size="small"
                  type="primary"
                  link
                  :loading="savingId === row.id"
                  :disabled="!canWrite"
                  @click="onSaveRow(row)"
                >
                  保存
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
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
    padding: 16px 20px;
  }
}

.card-header {
  display: flex;
  flex-direction: column;
  gap: 4px;

  .page-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-color-primary, #303133);
  }

  .page-subtitle {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.threshold-tabs {
  flex: 1;
}

.tab-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.key-cell {
  font-family: var(--el-font-family-mono, ui-monospace, monospace);
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
}

.default-text,
.range-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.unit-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: 4px;
}
</style>
