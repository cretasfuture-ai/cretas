<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get, put } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
// 2026-09-07: 原有 `canWrite = permissionStore.canWrite('system')` —— 声明了但**全文件零处使用**,
// 连同 usePermissionStore 一起删。进得了这一页本身就要 system 读权限 (路由 meta.module='system'),
// 写权限的判据在权限矩阵页那一侧。

// 2026-09-07 刀 D: 这里原本是 `allPermissionModules` —— 11 条写死的「模块码 + 中文名」,
// 与后端 RoleController.DISPLAY_MODULES 逐字重复 (形态 D)。两份都已删除。
// 现在模块列、中文名、列序全部由后端 `/{factoryId}/roles/{role}/permissions` 下发,
// 后端那一侧是三层现算 (L2 → L1 → 兜底) + PermissionModuleCatalog。
// ⛔ 不要在这里再抄一份 —— 前面几张同形私有表 (单位别名 #2079、OA MODULE_LABELS #2147、
//    员工档案角色 #2172、白名单角色名) 的修法都一样: 只保留码, 名字委托权威表。

// View permissions dialog
const viewDialogVisible = ref(false);
const viewingRole = ref<TableRow | null>(null);
const rolePermissions = ref<TableRow[]>([]);
const permissionsLoading = ref(false);
/** 取权限失败时的原因。非空 = 这次没读到, ⛔ 与「这个角色真的没有权限」不是同一件事。 */
const permissionsError = ref('');

const viewTab = ref<'function' | 'data'>('function');
type ScopeCode = 'ALL' | 'SELF' | 'SELF_AND_BELOW' | 'DEPT_AND_BELOW';
interface RoleScope { roleCode: string; scope: ScopeCode; configured: boolean; version: number; editable: boolean }
const scopeLabels: Record<ScopeCode, string> = { ALL: '全工厂', SELF: '仅本人创建', SELF_AND_BELOW: '本人及下属创建', DEPT_AND_BELOW: '同部门创建' };
const roleScope = ref<RoleScope | null>(null);
const selectedScope = ref<ScopeCode | null>(null);
const scopeError = ref('');
const scopeLoading = ref(false);
const scopeSaving = ref(false);
let scopeRequest = 0;
let scopeSaveRequest = 0;
function clearScope() {
  ++scopeRequest; ++scopeSaveRequest; roleScope.value = null; selectedScope.value = null; scopeError.value = '';
  scopeLoading.value = false; scopeSaving.value = false;
}
function validScope(value: unknown, role: string): value is RoleScope {
  if (!value || typeof value !== 'object') return false;
  const row = value as Record<string, unknown>;
  return row.roleCode === role && typeof row.scope === 'string' && Object.hasOwn(scopeLabels, row.scope)
    && typeof row.configured === 'boolean' && typeof row.editable === 'boolean'
    && typeof row.version === 'number' && Number.isSafeInteger(row.version) && row.version >= 0;
}
async function loadDataScope(tab: string | number) {
  if (tab !== 'data' || !factoryId.value || !viewingRole.value) return;
  const request = ++scopeRequest;
  const factory = factoryId.value, role = String(viewingRole.value.name);
  const current = () => request === scopeRequest && factory === factoryId.value
    && role === viewingRole.value?.name && viewDialogVisible.value;
  roleScope.value = null; selectedScope.value = null; scopeError.value = ''; scopeLoading.value = true;
  try {
    const response = await get<RoleScope>('/' + factory + '/roles/' + role + '/data-scope');
    if (!current()) return;
    if (!response.success || !validScope(response.data, role)) throw new Error(response.message || '数据范围返回格式无效');
    roleScope.value = response.data; selectedScope.value = response.data.scope;
  } catch (error) {
    if (current()) scopeError.value = error instanceof Error ? error.message : '读取数据范围失败';
  } finally { if (current()) scopeLoading.value = false; }
}
async function saveDataScope() {
  if (!roleScope.value?.editable || !selectedScope.value || scopeSaving.value || !factoryId.value || !viewingRole.value) return;
  const factory = factoryId.value, role = String(viewingRole.value.name), request = scopeRequest;
  const current = () => request === scopeRequest && factory === factoryId.value
    && role === viewingRole.value?.name && viewDialogVisible.value;
  const saveRequest = ++scopeSaveRequest;
  scopeSaving.value = true; scopeError.value = '';
  try {
    const response = await put<RoleScope>('/' + factory + '/roles/' + role + '/data-scope',
      { scope: selectedScope.value, version: roleScope.value.version });
    if (!current()) return;
    if (!response.success || !validScope(response.data, role)) throw new Error(response.message || '数据范围保存失败');
    ElMessage.success('数据范围已保存'); await loadDataScope('data');
  } catch (error) {
    if (current()) scopeError.value = error instanceof Error ? error.message : '数据范围保存失败';
  } finally { if (saveRequest === scopeSaveRequest) scopeSaving.value = false; }
}

// 2026-09-07: 这里原本是「编辑角色」弹窗的全部状态与提交逻辑。它是**不可达的死代码**:
//  · 表格里那个「编辑」按钮写着 `disabled` + tooltip「角色编辑功能开发中」⇒ 打不开;
//  · 它的提交打向 `PUT /api/mobile/{factoryId}/roles/{role}` —— 全仓**没有任何 controller
//    映射这条写路径** (RoleController 零个 @PutMapping/@PatchMapping; 全仓
//    `(Put|Patch|Post)Mapping("…role…")` 只有 5 处, 都是别的路径) ⇒ 就算打开也存不进任何东西。
// ⇒ 整块删除。留着一个「指向不存在端点的权限编辑器」比没有更糟 —— 它对读代码的人
//    声称了一个不存在的能力。真要做角色编辑, 得先有那个端点 (已登记)。

async function handleViewPermissions(row: TableRow) {
  viewingRole.value = row;
  viewDialogVisible.value = true;
  viewTab.value = 'function';  // 默认显功能权限 tab
  clearScope();
  permissionsLoading.value = true;
  permissionsError.value = '';
  try {
    const res = await get(`/${factoryId.value}/roles/${row.name}/permissions`);
    if (res.success && res.data) {
      rolePermissions.value = res.data;
    } else {
      rolePermissions.value = [];
      permissionsError.value = res.message || '接口没有返回权限数据';
    }
  } catch (error) {
    rolePermissions.value = [];
    permissionsError.value = error instanceof Error ? error.message : '请求失败';
  } finally {
    permissionsLoading.value = false;
  }
}

// 2026-09-07 刀 D: 这里原本是 `derivePermissions(role)` —— 取不到接口就**按角色等级和一个
// 字符串匹配现编一张权限表**填进弹窗 (`role.name.includes(m.key.replace('ment',''))`)。
// 那违反核心原则 1「禁止降级处理, 不返回假数据, 明确显示错误」: 编出来的权限与系统实际
// 执行的权限没有任何关系, 而管理员会照着它做判断。⇒ 取不到就说取不到, 并给重试。
async function retryPermissions() {
  if (viewingRole.value) {
    await handleViewPermissions(viewingRole.value);
  }
}

function getAccessTag(access: string) {
  const map: Record<string, { type: string; label: string }> = {
    rw: { type: 'success', label: '读写' },
    r: { type: 'warning', label: '只读' },
    w: { type: 'info', label: '只写' },
    '-': { type: 'danger', label: '无权限' },
  };
  return map[access] || map['-'];
}


/**
 * 角色清单 —— 由后端 `GET /{factoryId}/roles` 下发, ⛔ 不再写死。
 *
 * 2026-09-07 之前这里是一份写死的 17 条数组, 它有两个方向的缺陷 (刀 D 视觉验收实测):
 *  · 5 个码在 `FactoryUserRole` 枚举里**根本不存在** (warehouse_operator / procurement_staff /
 *    sales_staff / hr_manager / equipment_manager) ⇒ 后端 `fromRoleCode` 静默返回 unactivated
 *    ⇒ 权限对话框渲染成 17 行全「无权限」, **在屏幕上与一个真的无权限角色不可区分**;
 *  · 8 个**真有 F006 用户**的角色码不在页面上 (department_admin / dispatcher / equipment_admin /
 *    hr_admin / operator(6 个用户) / viewer / warehouse_worker / yield_operator)
 *    ⇒ 管理员在这一页上看不到它们的权限。
 *
 * ⚠️ 端点返回全部 29 个 active 角色, 含 4 个 department='restaurant' 的。Steve「餐饮不管」:
 *    过滤掉 = 保持现状 (这一页此前也没有餐饮角色), 显示出来 = 新增一个未被要求的产品变更。
 *    ⇒ 过滤并登记。按工厂类型决定显不显示才是最终答案, 那属于餐饮那一侧, 不在本刀。
 */
const roles = ref<TableRow[]>([]);
const rolesLoading = ref(false);
/** 取角色清单失败的原因。非空 = 这次没读到, ⛔ 与「这个工厂没有角色」不是一回事。 */
const rolesError = ref('');
/** 取用户数失败的原因。非空 = 用户数这一列没读到, ⛔ 与「每个角色 0 人」不是一回事。 */
const userCountsError = ref('');

// 2026-09-07: 这里原本把 level 映射成「超级管理 / 主管级 / 主任级 / 员工级」四档,
// 而那套 1-4 的刻度**全仓只有这一页有** —— 权威 `FactoryUserRole.level` 是
// -1 / 0 / 5 / 10 / 15 / 20 / 25 / 28 / 30 / 50。换数据源后那张映射表一格都对不上,
// 「级别」整列会渲染成空。
// ⛔ 没有自己发明档位区间: 枚举只成文定义了两条 (`isManager()` level<=20 /
//    `isWorker()` level==30), 覆盖不了 25/28/50/-1; 在前端重算那两条 = 规则的第二份 (形态 D)。
// ⇒ 直接显示权威值本身。数字越小权限越高 —— 那正是 `FactoryUserRole.canManage`
//    (`this.level < target.level`) 的判据。
function levelText(level: unknown): string {
  return typeof level === 'number' ? String(level) : '—';
}

/**
 * 拉角色清单。⛔ 取不到就说取不到 —— 与权限对话框同一条纪律 (核心原则 1):
 * 「没读到」和「这个工厂没有角色」在页面上必须分得开。
 */
async function loadRoles() {
  if (!factoryId.value) return;
  rolesLoading.value = true;
  rolesError.value = '';
  try {
    const res = await get<Array<Record<string, unknown>>>(`/${factoryId.value}/roles`);
    if (!res.success || !res.data) {
      roles.value = [];
      rolesError.value = res.message || '接口没有返回角色清单';
      return;
    }
    roles.value = res.data
      // Steve「餐饮不管」—— 见 roles 上方的注释。⛔ 别顺手放出来。
      .filter((r) => r.department !== 'restaurant')
      // ⛔ 不种 0: 用户数还没读到时种 0, 会和「这个角色真的没有用户」在页面上长成同一个样子。
      //    null = 还没读到; 数字 = 读到了 (含真实的 0)。
      .map((r) => ({ ...r, userCount: null as number | null })) as unknown as TableRow[];
  } catch (error) {
    roles.value = [];
    rolesError.value = error instanceof Error ? error.message : '请求失败';
  } finally {
    rolesLoading.value = false;
  }
}

// Fetch real user count per role (Apr 21 2026): previously hardcoded
// 2/3/5/8 for every factory which looked like cross-tenant leak to users.
/**
 * 每个角色的用户数。
 *
 * 🔴 2026-09-07: 这里原本**两处吞掉失败** (`if (!resp.success) return` 与
 * `catch { console.warn }`), 而每行的 userCount 种的是 `0` ⇒ 读不到时页面给每个角色
 * 印「0 人」, **与「这个角色真的没有用户」不可区分**。那是本 PR 上面两个函数
 * (`loadRoles` / `handleViewPermissions`) 刚刚做对的同一件事, 这里漏掉了。
 *
 * ⇒ 三态分开: 还没读到 = `null` (渲染「—」+ 页面上说明原因) / 读到了 = 数字 (含真实的 0)。
 * ⚠️ `_silent` 保留 —— 不弹红 toast, 但**必须在页面上留痕**, 否则又变成静默吞。
 */
async function loadRoleUserCounts() {
  if (!factoryId.value) return;
  userCountsError.value = '';
  try {
    // Backend requires page>=1 (1-indexed); using 0 triggers 409 "页码必须大于0"
    // _silent suppresses red toast when API is unavailable for new factories.
    const resp = await get<{ content: Array<{ roleCode?: string }>; totalElements: number }>(
      `/${factoryId.value}/users`,
      { params: { page: 1, size: 500 }, _silent: true } as never
    );
    if (!resp.success || !resp.data) {
      userCountsError.value = resp.message || '接口没有返回用户列表';
      return;
    }
    const list = resp.data.content || [];
    const countMap: Record<string, number> = {};
    for (const u of list) {
      const code = u.roleCode || '';
      if (code) countMap[code] = (countMap[code] || 0) + 1;
    }
    roles.value.forEach(r => {
      r.userCount = countMap[r.name] || 0;   // 这里的 0 是**读到了且确实没有**, 与 null 不同
    });
  } catch (error) {
    userCountsError.value = error instanceof Error ? error.message : '请求失败';
  }
}

onMounted(async () => {
  await loadRoles();
  await loadRoleUserCounts();   // 顺序要紧: 用户数是往已经拉到的行上填的
});
</script>

<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>角色权限</span>
          <!-- 2026-09-07: 这里原本写死「共 14 种工厂角色」, 而那张表当时渲染 17 行 ——
               页面上一个可以直接数出来的假话 (screensMustNotShowFalsehoods 那一族)。
               现在数字由 roles.length 算出, 而 roles 本身来自 GET /{factoryId}/roles。 -->
          <el-tag type="info" data-testid="role-count-badge">共 {{ roles.length }} 种工厂角色</el-tag>
        </div>
      </template>

      <!-- 拉不到角色清单就说拉不到 —— 与权限对话框同一条纪律。
           ⛔ 一张空表看起来像「这个工厂没有角色」, 那与「这次没读到」不是一回事。 -->
      <el-alert
        v-if="rolesError"
        type="error"
        :closable="false"
        title="没有读到角色清单"
        data-testid="roles-error"
        style="margin-bottom: 12px"
      >
        <p>{{ rolesError }}</p>
        <p>这不代表该工厂没有角色，只代表这次没读到。</p>
        <el-button size="small" type="primary" @click="loadRoles">重试</el-button>
      </el-alert>

      <!-- 用户数没读到就说出来 —— ⛔ 不靠一列「—」让人自己猜。 -->
      <el-alert
        v-if="!rolesError && userCountsError"
        type="warning"
        :closable="false"
        title="用户数这一列没有读到"
        data-testid="user-counts-error"
        style="margin-bottom: 12px"
      >
        <p>{{ userCountsError }}</p>
        <p>角色与权限不受影响；「用户数」列显示「—」表示没读到，不是 0 人。</p>
      </el-alert>

      <el-table v-if="!rolesError" v-loading="rolesLoading" :data="roles" stripe>
        <el-table-column prop="displayName" label="角色名称" width="120" />
        <el-table-column prop="name" label="角色标识" width="180">
          <template #default="{ row }">
            <code>{{ row.name }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" />
        <el-table-column prop="level" label="级别" width="120">
          <template #default="{ row }">
            <el-tooltip content="权威值 FactoryUserRole.level，数字越小权限越高" placement="top">
              <el-tag type="info" data-testid="role-level">{{ levelText(row.level) }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="userCount" label="用户数" width="100">
          <template #default="{ row }">
            <!-- null = 还没读到, 渲染「—」而不是「0 人」。0 只在真的读到 0 时出现。 -->
            <el-tag v-if="row.userCount === null" type="info" data-testid="user-count-unknown">—</el-tag>
            <el-tag v-else>{{ row.userCount }} 人</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="handleViewPermissions(row)">查看权限</el-button>
            <!-- ⚠️ 仍然 disabled: 改角色名称/描述需要一个全仓还不存在的写端点 (已登记)。
                 但 tooltip 不再只说「开发中」—— 用户来这一页多半是想改**权限**,
                 而那件事今天就办得到 (权限矩阵页, 写 L1 带审计)。fool-proof Rule 5。 -->
            <el-tooltip placement="top">
              <template #content>
                角色名称/描述的编辑尚未开放。<br />
                要改<b>权限</b>请到「系统管理 → 权限矩阵」，按角色 × 模块逐格修改。
              </template>
              <el-button type="info" link disabled data-testid="role-edit-disabled">编辑</el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 查看权限 Dialog -->
    <el-dialog v-model="viewDialogVisible" @closed="clearScope" :title="`${viewingRole?.displayName} — 权限详情`" width="600px" destroy-on-close>
      <el-tabs v-model="viewTab" type="border-card" @tab-change="loadDataScope">
        <!-- Tab 1: 功能权限 (existing) -->
        <el-tab-pane label="功能权限" name="function">
          <!-- 取不到就说取不到 (核心原则 1)。⛔ 这里以前会现编一张表填进去,
               而「编出来的权限」与「这个角色真的没有权限」在页面上长得一模一样。 -->
          <el-alert
            v-if="permissionsError"
            type="error"
            :closable="false"
            title="没有读到这个角色的权限"
            data-testid="role-permissions-error"
            style="margin-bottom: 12px"
          >
            <p>{{ permissionsError }}</p>
            <p>这不代表该角色没有权限，只代表这次没读到。</p>
            <el-button size="small" type="primary" @click="retryPermissions">重试</el-button>
          </el-alert>
          <el-table v-else :data="rolePermissions" v-loading="permissionsLoading" stripe border>
            <el-table-column prop="label" label="功能模块" width="140" />
            <el-table-column prop="access" label="权限" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="getAccessTag(row.access).type" size="small">
                  {{ getAccessTag(row.access).label }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab 2: 数据权限 (Sprint 5 Track G stub — backend live, frontend Sprint 6 sweep) -->
        <el-tab-pane label="数据权限" name="data">
          <div v-loading="scopeLoading">
            <el-alert title="工厂内角色数据范围" type="info" :closable="false"
              description="按记录创建人限制已接入的销售、采购、客户、原料批次和退货列表/详情。功能权限仍需单独授权；同部门不包含下级部门；上级或部门资料未维护时只显示本人创建记录。关联收发货继承来源订单范围，无订单发货继承客户范围。自定义范围暂不支持。受限账号尚未接入范围筛选的快捷查询、统计、导出和批量操作会明确拒绝，请使用主列表。" />
            <el-alert v-if="scopeError" data-testid="role-data-scope-error" title="无法读取或保存数据范围"
              :description="scopeError" type="error" :closable="false" />
            <div v-if="roleScope" data-testid="role-data-scope-result">
              <p>当前生效：{{ scopeLabels[roleScope.scope] }}</p>
              <p>{{ roleScope.configured ? '本工厂已配置' : '继承角色默认范围' }}</p>
              <el-radio-group v-model="selectedScope" :disabled="!roleScope.editable || scopeSaving">
                <el-radio v-for="(text, code) in scopeLabels" :key="code" :value="code">{{ text }}</el-radio>
              </el-radio-group>
              <el-button v-if="roleScope.editable" type="primary" :loading="scopeSaving"
                :disabled="selectedScope === roleScope.scope" @click="saveDataScope">保存数据范围</el-button>
              <p v-else>当前角色或账号不允许修改数据范围。</p>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="viewDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 2026-09-07: 「编辑角色」弹窗整块删除 —— 见 script 里同日的说明:
         入口按钮 disabled, 且提交打向一个全仓不存在的 PUT 端点。 -->

    <el-card style="margin-top: 20px;">
      <template #header>
        <span>权限说明</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="rw">读写权限 - 可查看和修改</el-descriptions-item>
        <el-descriptions-item label="r">只读权限 - 仅可查看</el-descriptions-item>
        <el-descriptions-item label="w">只写权限 - 仅可创建</el-descriptions-item>
        <el-descriptions-item label="-">无权限 - 不可访问</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
code {
  background-color: #f5f7fa;
  padding: 2px 8px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
}
</style>
