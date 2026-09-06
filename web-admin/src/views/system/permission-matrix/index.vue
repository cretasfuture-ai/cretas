<script setup lang="ts">
/**
 * 「权限矩阵」只读页 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906 step 1)。
 *
 * owner 2026-09-06 裁定: **先看清真相, 不做编辑器**。所以本页:
 * - ⛔ 没有任何编辑控件 (无输入框 / 无下拉 / 无保存按钮), 也不许加;
 * - 把 L1 (`platform_role_permissions`, 权威) 与硬编码兜底矩阵**并排**摆出来,
 *   不一致的格子高亮 + tooltip 写出兜底值;
 * - L2 关掉的模块列置灰。
 *
 * <h3>门禁只有一道, 就在路由 meta 上</h3>
 * 路由 `meta.module = 'system'` ⇒ `guards.ts` 用 `permissionStore.canAccess('system')`
 * 拦; 菜单项也是 `module: 'system'`; 后端是
 * `@RequirePermission({"system:read","system:read_write"})` —— 三处同一个权限码,
 * 由 `permissionGateMatchesBackend.spec.ts` 钉住。
 *
 * ⛔ 页面内**不再**自己判一次 `canAccess('system')`: 能走到这个组件的人必然已经
 * 过了路由那道闸 ⇒ 那个判断对**全部可达主体恒为真**, 是一条空转分支
 * (`branchReachability` 闸实测把它揪出来, 预算 18→19)。多写一道看起来更安全的闸,
 * 实际只是给自己一个「守住了」的错觉 (形态 B: 闸在验收环境里恒真)。
 *
 * ⚠️ 真正拦得住的是后端那道 —— 403 会被原样显示, ⛔ 不降级成空表格
 * (空表格与「这个工厂没配权限」长得一样)。
 */
import { computed, onMounted, ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  getPermissionMatrix,
  type MatrixDiff,
  type PermissionMatrixView,
} from '@/api/permissionMatrixApi';
import {
  axisModulesMissingFromData,
  offAxisModules,
  sidebarModuleAxis,
} from './moduleAxis';
import {
  buildCellContext,
  cellOf,
  diffTooltip,
  diffsOnAxis,
  type MatrixCell,
} from './matrixCells';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const loadError = ref<string | null>(null);
const view = ref<PermissionMatrixView | null>(null);

const axis = sidebarModuleAxis();
const ctx = computed(() => (view.value ? buildCellContext(view.value) : null));

const GROUP_LABELS: Record<string, string> = {
  platform: '平台角色',
  factory: '工厂角色',
  restaurant: '餐饮角色',
  unknown: '枚举里没有的历史角色',
};
const GROUP_ORDER = ['platform', 'factory', 'restaurant', 'unknown'];

const roleGroups = computed(() => {
  if (!view.value) return [];
  return GROUP_ORDER
    .map((group) => ({
      group,
      label: GROUP_LABELS[group] || group,
      roles: view.value!.roles.filter((r) => r.group === group),
    }))
    .filter((g) => g.roles.length > 0);
});

const offAxis = computed(() =>
  (view.value ? offAxisModules(view.value.modules, axis) : []));
const axisGaps = computed(() =>
  (view.value ? axisModulesMissingFromData(axis, view.value.modules) : []));

const onAxisDiffs = computed<MatrixDiff[]>(() =>
  (view.value ? diffsOnAxis(view.value.diffs, axis) : []));
const offAxisDiffCount = computed(() =>
  (view.value ? view.value.diffs.length - onAxisDiffs.value.length : 0));

function cell(role: string, module: string): MatrixCell | null {
  if (!view.value || !ctx.value) return null;
  return cellOf(view.value, ctx.value, role, module);
}

function cellClass(c: MatrixCell | null): string[] {
  if (!c) return [];
  const classes = ['pm-cell'];
  if (c.diff) classes.push('pm-cell--diff', `pm-cell--${c.diff.kind}`);
  if (c.l2Disabled) classes.push('pm-cell--l2-off');
  if (c.l2Override !== null) classes.push('pm-cell--l2-override');
  return classes;
}

function cellTitle(c: MatrixCell | null): string {
  if (!c) return '';
  const parts: string[] = [];
  if (c.diff) parts.push(diffTooltip(c));
  if (c.l2Disabled) parts.push('L2: 本工厂已关闭该模块');
  if (c.l2Override !== null) parts.push(`L2 工厂覆盖: ${c.l2Override}`);
  return parts.join(' / ');
}

async function load(): Promise<void> {
  if (!factoryId.value) {
    loadError.value = '当前账号没有 factoryId, 无法读取工厂级 (L2) 表头';
    return;
  }
  loading.value = true;
  loadError.value = null;
  try {
    view.value = await getPermissionMatrix(factoryId.value);
  } catch (e) {
    // ⛔ 不吞: 403 / 500 都原样显示。空表格会被读成「没有分歧」。
    view.value = null;
    loadError.value = (e as Error)?.message || '读取权限矩阵失败';
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <div class="permission-matrix-page">
      <header class="pm-header">
        <h2>权限矩阵 (只读)</h2>
        <p class="pm-sub">
          L1 <code>platform_role_permissions</code> 是权威; 硬编码兜底矩阵只在 L1 没有那一行时生效。
          <b>本页不提供任何编辑</b> —— owner 2026-09-06 裁定 step 1 只看不改。
        </p>
        <p class="pm-sub">
          模块轴 = <b>侧边栏实际渲染用的那份清单</b> (共 {{ axis.length }} 个, 从
          <code>menuConfig</code> 现算)。全仓另有 6 份互不一致的模块清单, 见 PR 说明。
        </p>
      </header>

      <div v-if="loading" class="pm-loading" data-testid="pm-loading">加载中…</div>
      <div v-else-if="loadError" class="pm-error" data-testid="pm-error">
        读取失败: {{ loadError }}
      </div>

      <template v-else-if="view">
        <section class="pm-summary" data-testid="pm-summary">
          <span class="pm-stat">
            轴上分歧 <b data-testid="pm-diff-count">{{ onAxisDiffs.length }}</b> 格
          </span>
          <span class="pm-stat">
            轴外分歧 <b data-testid="pm-offaxis-diff-count">{{ offAxisDiffCount }}</b> 格
          </span>
          <span class="pm-stat">角色 {{ view.roles.length }} 个</span>
          <span class="pm-stat">工厂 {{ view.factoryId }}</span>
        </section>

        <p v-if="offAxis.length" class="pm-note" data-testid="pm-offaxis">
          ⚠️ 有 L1/L2/兜底数据、但侧边栏<b>没有任何入口</b>的模块 (不在下表列里):
          <code v-for="m in offAxis" :key="m" class="pm-chip-off">{{ m }}</code>
        </p>
        <p v-if="axisGaps.length" class="pm-note" data-testid="pm-axis-gaps">
          🔴 侧边栏挂着入口、而三层权限数据里<b>一行都没有</b>的模块:
          <code v-for="m in axisGaps" :key="m" class="pm-chip-off">{{ m }}</code>
          —— DB 读不到时这些菜单项对所有角色消失。
        </p>
        <p v-if="view.modulesWithoutL1.length" class="pm-note" data-testid="pm-no-l1">
          L1 表里一行都没有的模块 (兜底/L2 独自说了算):
          <code v-for="m in view.modulesWithoutL1" :key="m" class="pm-chip-off">{{ m }}</code>
        </p>

        <div class="pm-table-wrap">
          <table class="pm-table" data-testid="pm-table">
            <thead>
              <tr>
                <th class="pm-role-col">角色</th>
                <th
                  v-for="m in axis"
                  :key="m"
                  :class="['pm-module-col', { 'pm-module-col--off': ctx?.disabledModules.has(m) }]"
                  :title="ctx?.disabledModules.has(m) ? 'L2: 本工厂已关闭该模块' : ''"
                >
                  {{ m }}
                </th>
              </tr>
            </thead>
            <tbody>
              <template v-for="g in roleGroups" :key="g.group">
                <tr class="pm-group-row">
                  <td :colspan="axis.length + 1">{{ g.label }} ({{ g.roles.length }})</td>
                </tr>
                <tr v-for="role in g.roles" :key="role.code" :data-role="role.code">
                  <td class="pm-role-col">
                    <span class="pm-role-name">{{ role.displayName }}</span>
                    <span class="pm-role-code">{{ role.code }}</span>
                  </td>
                  <td
                    v-for="m in axis"
                    :key="m"
                    :class="cellClass(cell(role.code, m))"
                    :title="cellTitle(cell(role.code, m))"
                    :data-cell="`${role.code}/${m}`"
                  >
                    <span class="pm-chip">{{ cell(role.code, m)?.display }}</span>
                    <span v-if="cell(role.code, m)?.fallback" class="pm-fallback-mark">兜底</span>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </template>
  </div>
</template>

<style scoped>
.permission-matrix-page { padding: 16px; }
.pm-header h2 { margin: 0 0 4px; font-size: 18px; }
.pm-sub { margin: 2px 0; color: #606266; font-size: 12px; }
.pm-summary { display: flex; gap: 16px; margin: 12px 0; font-size: 13px; }
.pm-note { font-size: 12px; color: #606266; margin: 4px 0; }
.pm-chip-off { margin-right: 6px; background: #f4f4f5; padding: 0 4px; border-radius: 3px; }
.pm-error { color: #f56c6c; padding: 12px 0; }
.pm-denied { color: #909399; padding: 24px 0; }
.pm-table-wrap { overflow: auto; max-height: 70vh; }
.pm-table { border-collapse: collapse; font-size: 12px; }
.pm-table th, .pm-table td { border: 1px solid #ebeef5; padding: 4px 6px; text-align: center; white-space: nowrap; }
.pm-table thead th { position: sticky; top: 0; background: #fafafa; z-index: 1; }
.pm-role-col { text-align: left; position: sticky; left: 0; background: #fff; }
.pm-role-code { display: block; color: #909399; font-size: 10px; }
.pm-module-col--off { color: #c0c4cc; text-decoration: line-through; }
.pm-group-row td { background: #f5f7fa; text-align: left; font-weight: 600; }
.pm-cell--diff { background: #fdf6ec; }
.pm-cell--fallback { outline: 1px dashed #e6a23c; }
.pm-cell--override { background: #fef0f0; }
.pm-cell--l2-off { color: #c0c4cc; }
.pm-cell--l2-override { border-bottom: 2px solid #409eff; }
.pm-fallback-mark { display: block; font-size: 9px; color: #e6a23c; }
</style>
