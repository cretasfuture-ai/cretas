<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
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

// Sprint 5 Track G: 数据权限 tab (read-only stub, edit defer Sprint 6)
const viewTab = ref<'function' | 'data'>('function');
// 默认 stub 值: 一线员工 SELF, 其他 ALL — backend V20260519_05 实际写入 role_definitions
function deriveDataScope(roleName: string): string {
  if (['operator', 'warehouse_worker', 'warehouse_operator', 'quality_inspector'].includes(roleName)) {
    return 'SELF';
  }
  return 'ALL';
}
// 用普通 ref + watch viewingRole 更新, 而非 computed (radio v-model 需 writable)
const dataScopeStub = ref<string>('ALL');

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
  dataScopeStub.value = deriveDataScope(row.name);  // Sprint 5 Track G stub
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
      .map((r) => ({ ...r, userCount: 0 })) as unknown as TableRow[];
  } catch (error) {
    roles.value = [];
    rolesError.value = error instanceof Error ? error.message : '请求失败';
  } finally {
    rolesLoading.value = false;
  }
}

// Fetch real user count per role (Apr 21 2026): previously hardcoded
// 2/3/5/8 for every factory which looked like cross-tenant leak to users.
async function loadRoleUserCounts() {
  if (!factoryId.value) return;
  try {
    // Backend requires page>=1 (1-indexed); using 0 triggers 409 "页码必须大于0"
    // _silent suppresses red toast when API is unavailable for new factories.
    const resp = await get<{ content: Array<{ roleCode?: string }>; totalElements: number }>(
      `/${factoryId.value}/users`,
      { params: { page: 1, size: 500 }, _silent: true } as never
    );
    if (!resp.success || !resp.data) return;
    const list = resp.data.content || [];
    const countMap: Record<string, number> = {};
    for (const u of list) {
      const code = u.roleCode || '';
      if (code) countMap[code] = (countMap[code] || 0) + 1;
    }
    roles.value.forEach(r => {
      r.userCount = countMap[r.name] || 0;
    });
  } catch (e) {
    console.warn('loadRoleUserCounts failed', e);
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
          <!-- 2026-09-07: 这里原本写死「共 14 种工厂角色」, 而下面那张表实际渲染 17 行 ——
               页面上一个可以直接数出来的假话 (screensMustNotShowFalsehoods 那一族)。
               ⚠️ 数字算出来了, 但 roles 数组本身仍是写死的 17 条; 真正的修法是拉
               GET /{factoryId}/roles (端点早就有, RoleControllerListRolesTest 钉着) —— 单独一刀。 -->
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

      <el-table v-else v-loading="rolesLoading" :data="roles" stripe>
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
            <el-tag>{{ row.userCount }} 人</el-tag>
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
    <el-dialog v-model="viewDialogVisible" :title="`${viewingRole?.displayName} — 权限详情`" width="600px" destroy-on-close>
      <el-tabs v-model="viewTab" type="border-card">
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
          <el-alert
            title="数据权限维度"
            type="info"
            :closable="false"
            description="后端 framework 已上线 (5 级 scope: ALL/DEPT_AND_BELOW/SELF_AND_BELOW/SELF/CUSTOM). 当前角色的默认范围见下表; 自定义编辑功能尚未开放."
            style="margin-bottom: 16px"
          />
          <el-form label-width="100px" disabled>
            <el-form-item label="数据范围">
              <el-radio-group v-model="dataScopeStub">
                <el-radio value="ALL">全工厂 (默认)</el-radio>
                <el-radio value="DEPT_AND_BELOW">本部门 + 下属部门</el-radio>
                <el-radio value="SELF_AND_BELOW">自己 + 下属</el-radio>
                <el-radio value="SELF">仅自己创建</el-radio>
                <el-radio value="CUSTOM">自定义 (尚未开放)</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="生效说明">
              <el-text type="info" size="small">
                销售单 list 已实施 POC (SELF scope 仅看自己创建的订单).
                
              </el-text>
            </el-form-item>
          </el-form>
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
