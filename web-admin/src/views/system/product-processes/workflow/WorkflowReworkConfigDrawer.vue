<template>
  <el-drawer
    :model-value="visible"
    :title="`回料配置 · ${displayName || '未命名回料'}`"
    direction="rtl"
    size="480px"
    append-to-body
    :before-close="handleBeforeClose"
    data-testid="rework-config-drawer"
    @update:model-value="(value: boolean) => !value && handleBeforeClose(() => emit('update:visible', false))"
  >
    <div class="rework-drawer">
      <!-- 基本信息在前, 目标工序在后 —— 与已确认原型的结构一致(§8)。 -->
      <section class="rework-drawer__section">
        <h4 class="rework-drawer__title">基本信息</h4>
        <el-form label-width="88px" label-position="left" size="small">
          <el-form-item label="显示名称">
            <el-input
              :model-value="draftName"
              :disabled="!canWrite"
              maxlength="40"
              data-testid="rework-name"
              @update:model-value="(value: string) => draftName = value"
            />
          </el-form-item>
          <el-form-item label="产出 SKU">
            <WorkflowSkuPicker
              test-id="rework-sku"
              :model-value="draftSkuId"
              :semi-options="semiOptions"
              :finished-options="finishedOptions"
              :disabled="!canWrite"
              placeholder="选择或现场创建回料 SKU"
              @change="(value: string) => draftSkuId = value"
            />
          </el-form-item>
          <el-form-item label="计量单位">
            <el-select
              :model-value="draftUnit"
              :disabled="!canWrite"
              filterable
              allow-create
              :teleported="false"
              data-testid="rework-unit"
              @change="(value: string) => draftUnit = value"
            >
              <el-option v-for="unit in unitOptions" :key="unit" :label="unit" :value="unit" />
            </el-select>
          </el-form-item>
        </el-form>
      </section>

      <section class="rework-drawer__section rework-drawer__section--targets">
        <h4 class="rework-drawer__title">
          允许回投的目标工序
          <span class="rework-drawer__total" data-testid="rework-total-selected">
            已选 {{ draftTargets.size }} / {{ context.totalCount }}
          </span>
        </h4>
        <p class="rework-drawer__scope">
          比例是<strong>配方值</strong>：这块回料就按它投进那道工序，由车间纪律照此执行，
          实际投多少不必登记，后续核算直接按这个比例算。
          系统当前<strong>不会</strong>在报工时按它自动配料 —— 后端只读画布上的回料标记。
        </p>
        <p v-if="draftTargets.size === 0" class="rework-drawer__hint" data-testid="rework-no-target">
          还没配回投目标。回料是可选项，<strong>不影响保存与发布</strong>；先留空也可以，回头再补。
        </p>
        <el-input
          v-if="context.totalCount > 8"
          v-model="keyword"
          size="small"
          clearable
          placeholder="按工序名筛选（分组仍然保留）"
          data-testid="rework-target-search"
        />

        <div v-for="group in filteredGroups" :key="group.routeKey" class="route-group">
          <div class="route-group__head">
            <span class="route-group__name" :title="group.routeName">{{ group.routeName }}</span>
            <span v-if="group.isSourceRoute" class="route-group__tag">来源线路</span>
            <span class="route-group__count" :data-testid="`route-count-${group.routeKey}`">
              已选 {{ selectedInGroup(group) }} / {{ group.options.length }}
            </span>
          </div>
          <div class="route-group__grid">
            <div
              v-for="option in group.options"
              :key="option.processNodeId"
              class="target-card"
              :class="{ 'is-selected': draftTargets.has(option.processNodeId) }"
              :data-testid="`rework-target-${option.processNodeId}`"
              @mouseenter="emit('hover-target', option.processNodeId)"
              @mouseleave="emit('hover-target', null)"
            >
              <!--
                ⛔ 勾选与定位必须严格拆开(§8): 勾 checkbox **只**改允许关系,
                   一次都不许顺手缩放/平移画布。两件事串在一起, 用户每勾一项画布就跳一次。
              -->
              <el-checkbox
                class="target-card__check"
                :model-value="draftTargets.has(option.processNodeId)"
                :disabled="!canWrite"
                :data-testid="`rework-check-${option.processNodeId}`"
                @change="(checked: boolean) => toggleTarget(option.processNodeId, checked)"
              >
                <span class="target-card__name" :title="option.fullLabel" :aria-label="option.fullLabel">
                  {{ option.processName }}
                </span>
              </el-checkbox>
              <button
                type="button"
                class="target-card__locate"
                :class="{ 'is-locked': lockedTargetId === option.processNodeId }"
                :aria-pressed="lockedTargetId === option.processNodeId"
                :aria-label="`定位到 ${option.fullLabel}`"
                :title="`定位到 ${option.fullLabel}`"
                :data-testid="`rework-locate-${option.processNodeId}`"
                @click.stop="emit('toggle-locate', option.processNodeId)"
                @keydown.enter.prevent.stop="emit('toggle-locate', option.processNodeId)"
                @keydown.space.prevent.stop="emit('toggle-locate', option.processNodeId)"
              >{{ lockedTargetId === option.processNodeId ? '定位中' : '定位' }}</button>
              <label
                v-if="draftTargets.has(option.processNodeId)"
                class="target-card__ratio"
                :title="`${option.processName} 的回料投入比例（配方值）`"
              >
                <span class="target-card__ratio-label">回投比例</span>
                <input
                  type="number"
                  min="0"
                  max="100"
                  step="0.1"
                  class="target-card__ratio-input"
                  :value="draftTargets.get(option.processNodeId)"
                  :disabled="!canWrite"
                  :aria-label="`${option.fullLabel} 的回料投入比例，百分比`"
                  :data-testid="`rework-ratio-${option.processNodeId}`"
                  @change="(event: Event) => setRatio(option.processNodeId, (event.target as HTMLInputElement).value)"
                  @click.stop
                />
                <span aria-hidden="true">%</span>
              </label>
            </div>
          </div>
        </div>
        <el-empty
          v-if="filteredGroups.length === 0"
          description="没有匹配的工序"
          :image-size="70"
        />
      </section>
    </div>

    <template #footer>
      <el-button data-testid="rework-cancel" @click="handleBeforeClose(() => emit('update:visible', false))">
        取消
      </el-button>
      <el-button
        type="primary"
        :disabled="!canWrite"
        data-testid="rework-save"
        @click="save"
      >保存</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import WorkflowSkuPicker, { type WorkflowSkuPickerOption } from './WorkflowSkuPicker.vue';
import type { ReworkRouteGroup, ReworkTargetContext } from './workflowReworkTargets';

const props = defineProps<{
  visible: boolean;
  canWrite: boolean;
  context: ReworkTargetContext;
  name: string;
  skuId: string;
  unit: string;
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
  unitOptions: string[];
  /** 当前被锁定聚焦的目标工序（由画布层持有，抽屉只显示状态）。 */
  lockedTargetId: string | null;
}>();

const emit = defineEmits<{
  'update:visible': [visible: boolean];
  /** 悬停目标行 → 临时聚焦；null = 移出恢复。 */
  'hover-target': [processNodeId: string | null];
  /** 点击「定位」→ 锁定/取消锁定聚焦，并把目标平滑移进可视区。 */
  'toggle-locate': [processNodeId: string];
  save: [payload: {
    name: string; skuId: string; unit: string;
    targets: Array<{ processNodeId: string; inputRatioPercent: number }>;
  }];
}>();

const draftName = ref('');
const draftSkuId = ref('');
const draftUnit = ref('');
/** processNodeId → 投入比例（%）。⛔ 用 Map 不用 Set：比例是这一对的属性，不是工序的属性。 */
const draftTargets = ref<Map<string, number>>(new Map());
const keyword = ref('');
const initialSnapshot = ref('');

function snapshot(): string {
  return JSON.stringify({
    n: draftName.value, s: draftSkuId.value, u: draftUnit.value,
    t: [...draftTargets.value.entries()].sort((a, b) => a[0].localeCompare(b[0])),
  });
}

watch(() => [props.visible, props.context.reworkNodeId], ([visible]) => {
  if (!visible) return;
  draftName.value = props.name;
  draftSkuId.value = props.skuId;
  draftUnit.value = props.unit;
  draftTargets.value = new Map(
    props.context.groups.flatMap((group) => group.options)
      .filter((option) => option.selected)
      .map((option) => [option.processNodeId, option.inputRatioPercent ?? 0] as const),
  );
  keyword.value = '';
  initialSnapshot.value = snapshot();
}, { immediate: true });

const displayName = computed(() => draftName.value || props.name);

const filteredGroups = computed<ReworkRouteGroup[]>(() => {
  const query = keyword.value.trim();
  if (!query) return props.context.groups;
  // 搜索在**分组内**过滤，⛔ 不把分组压平成一条长列表（§8）。
  return props.context.groups
    .map((group) => ({
      ...group,
      options: group.options.filter((option) => option.fullLabel.includes(query)),
    }))
    .filter((group) => group.options.length > 0);
});

function selectedInGroup(group: ReworkRouteGroup): number {
  return group.options.filter((option) => draftTargets.value.has(option.processNodeId)).length;
}

function toggleTarget(processNodeId: string, checked: boolean): void {
  const next = new Map(draftTargets.value);
  if (checked) next.set(processNodeId, next.get(processNodeId) ?? 0);
  else next.delete(processNodeId);
  draftTargets.value = next;
}

/** 比例是配方值，允许 0（「登记了这条去向，比例待定」），⛔ 但不接受负数/超 100/非数。 */
function setRatio(processNodeId: string, raw: string): void {
  const numeric = Number(raw);
  const clamped = Number.isFinite(numeric) ? Math.max(0, Math.min(100, numeric)) : 0;
  const next = new Map(draftTargets.value);
  next.set(processNodeId, clamped);
  draftTargets.value = next;
}

function save(): void {
  // ⛔ 不再 fail closed：回料是可选项，允许一个目标都不配就保存（owner 2026-09-01 裁定）。
  emit('save', {
    name: draftName.value.trim() || props.name,
    skuId: draftSkuId.value,
    unit: draftUnit.value,
    targets: [...draftTargets.value.entries()]
      .map(([processNodeId, inputRatioPercent]) => ({ processNodeId, inputRatioPercent })),
  });
  initialSnapshot.value = snapshot();
  emit('update:visible', false);
}

/** 有未保存修改时按现有离开保护提示，不静默丢失（§8）。 */
function handleBeforeClose(done: () => void): void {
  if (snapshot() === initialSnapshot.value) { done(); return; }
  ElMessageBox.confirm('回料配置有未保存的修改，确认放弃？', '放弃修改', {
    type: 'warning', confirmButtonText: '放弃', cancelButtonText: '继续编辑',
  }).then(() => done()).catch(() => { /* 继续编辑 */ });
}
</script>

<style scoped>
/* 抽屉 body 用剩余高度统一滚动 —— ⛔ 列表内部不再套一个只能看 5 项的小滚动盒(§8)。 */
.rework-drawer { display: flex; flex-direction: column; gap: 18px; }
.rework-drawer__section { display: flex; flex-direction: column; gap: 8px; }
.rework-drawer__title {
  display: flex; align-items: baseline; justify-content: space-between; gap: 8px;
  margin: 0; color: #1f2d3d; font-size: 14px; font-weight: 700;
}
.rework-drawer__total { color: #1b65a8; font-size: 12px; font-weight: 700; }
.rework-drawer__scope { margin: 0; color: #667085; font-size: 12px; line-height: 1.6; }
.rework-drawer__warn { margin: 0; color: #b5590a; font-size: 12px; font-weight: 650; }

.route-group { border: 1px solid #e6ebf2; border-radius: 8px; overflow: hidden; }
.route-group__head {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; background: #f5f7fa;
}
.route-group__name {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: #1f2d3d; font-size: 13px; font-weight: 700;
}
.route-group__tag {
  flex: none; padding: 1px 7px; border-radius: 999px;
  background: #e8f2ff; color: #1b65a8; font-size: 11px; font-weight: 650;
}
.route-group__count { flex: none; color: #667085; font-size: 11px; font-variant-numeric: tabular-nums; }
/* 两列紧凑卡片；窄屏降一列(§8)。 */
.route-group__grid {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; padding: 8px;
}
@media (max-width: 560px) { .route-group__grid { grid-template-columns: minmax(0, 1fr); } }

/* 两行：第一行 勾选 + 工序名 + 定位；第二行（仅已选）投入比例。
   ⛔ 不把比例挤进第一行 —— 本机实测工序名会被省略成「熟…」，
      而工序名正是这张卡片唯一要传达的信息。 */
.target-card {
  display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center;
  gap: 4px 6px; min-width: 0;
  padding: 5px 7px; border: 1px solid #e6ebf2; border-radius: 7px; background: #fff;
}
/* 已选状态不只靠颜色：加粗边框 + 左侧色条 + checkbox 勾选本身(§8)。 */
.target-card.is-selected {
  border-color: #409eff; border-left-width: 4px; background: #f2f8ff; font-weight: 650;
}
.target-card__check { flex: 1; min-width: 0; }
.target-card__check :deep(.el-checkbox__label) { min-width: 0; overflow: hidden; }
.target-card__name {
  display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px;
}
.target-card__ratio {
  grid-column: 1 / -1;
  display: flex; align-items: center; gap: 4px;
  padding-top: 2px; border-top: 1px dashed #e6ebf2;
  color: #1b65a8; font-size: 11px; font-variant-numeric: tabular-nums;
}
.target-card__ratio-label { flex: 1; min-width: 0; color: #667085; }
.target-card__ratio-input {
  width: 46px; padding: 1px 4px; border: 1px solid #d8e4ef; border-radius: 5px;
  font-size: 11px; text-align: right; font-variant-numeric: tabular-nums;
}
.target-card__ratio-input:focus-visible { outline: 2px solid #1b65a8; outline-offset: 1px; }
.rework-drawer__hint { margin: 0; color: #667085; font-size: 12px; line-height: 1.6; }
.target-card__locate {
  flex: none; padding: 1px 7px; border: 1px solid #d8e4ef; border-radius: 5px;
  background: #fff; color: #1b65a8; font-size: 11px; cursor: pointer;
}
.target-card__locate:hover { border-color: #409eff; background: #ecf5ff; }
.target-card__locate:focus-visible { outline: 2px solid #1b65a8; outline-offset: 1px; }
.target-card__locate.is-locked { border-color: #409eff; background: #409eff; color: #fff; }
</style>
