<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  getFactoryAccess,
  getFactoryAccessUserPermissions,
  listFactoryAccessUsers,
  updateFactoryAccessModule,
  updateFactoryAccessRolePermission,
  updateFactoryAccessUserPermission,
  type FactoryAccessModule,
  type FactoryAccessRole,
  type FactoryAccessState,
  type FactoryAccessUser,
  type FactoryAccessUserPage,
  type FactoryPermissionLevel,
} from '@/api/platformFactoryAccess';

type AccessTab = 'activation' | 'roles' | 'employees';

const props = defineProps<{
  factoryId: string;
  activeTab: AccessTab;
}>();
const emit = defineEmits<{ (event: 'writing-change', value: boolean): void }>();

const accessState = ref<FactoryAccessState | null>(null);
const usersPage = ref<FactoryAccessUserPage | null>(null);
const selectedUserId = ref<number | string>('');
const selectedUser = ref<FactoryAccessUser | null>(null);
const userPermissions = ref<Awaited<ReturnType<typeof getFactoryAccessUserPermissions>>['data'] | null>(null);
const keyword = ref('');
const userPageNumber = ref(0);
const userPageSize = ref(20);
const loadingAccess = ref(false);
const loadingUsers = ref(false);
const loadingUserPermissions = ref(false);
const errorMessage = ref('');
const savingKey = ref<string | null>(null);
const roleDrafts = reactive<Record<string, FactoryPermissionLevel>>({});
const roleDirty = reactive(new Set<string>());
const employeeDrafts = reactive<Record<string, FactoryPermissionLevel>>({});
const employeeDirty = reactive(new Set<string>());
let accessLoadSequence = 0;

watch(savingKey, (value) => emit('writing-change', Boolean(value)), { immediate: true });

const levelOptions: Array<{ value: FactoryPermissionLevel; label: string }> = [
  { value: null, label: '随全局' },
  { value: '-', label: '不可用' },
  { value: 'r', label: '仅查看' },
  { value: 'w', label: '仅操作' },
  { value: 'rw', label: '可查看和操作' },
];
const employeeLevelOptions: Array<{ value: FactoryPermissionLevel; label: string }> = [
  { value: null, label: '随岗位' },
  { value: '-', label: '不可用' },
  { value: 'r', label: '仅查看' },
  { value: 'w', label: '仅操作' },
  { value: 'rw', label: '可查看和操作' },
];

const canManage = computed(() => accessState.value?.canManage === true);
const modules = computed(() => accessState.value?.permissionModules || []);
const activationModules = computed(() => accessState.value?.modules || []);
const users = computed(() => usersPage.value?.content || []);
const selectedUserIsWritable = computed(() => Boolean(
  selectedUser.value && selectedUser.value.active && !selectedUser.value.protectedRole && canManage.value,
));
const userTotal = computed(() => usersPage.value?.totalElements || 0);

function levelLabel(level: FactoryPermissionLevel | undefined): string {
  return levelOptions.find((option) => option.value === (level ?? null))?.label || '未返回';
}

function employeeLevelLabel(level: FactoryPermissionLevel | undefined): string {
  return employeeLevelOptions.find((option) => option.value === (level ?? null))?.label || '未返回';
}

function roleDraftKey(role: FactoryAccessRole, moduleCode: string): string {
  return `${role.code}:${moduleCode}`;
}

function employeeDraftKey(moduleCode: string): string {
  return `${String(selectedUserId.value)}:${moduleCode}`;
}

function roleCurrentLevel(role: FactoryAccessRole, moduleCode: string): FactoryPermissionLevel {
  return role.overrides?.[moduleCode] ?? null;
}

function roleDraftLevel(role: FactoryAccessRole, moduleCode: string): FactoryPermissionLevel {
  const key = roleDraftKey(role, moduleCode);
  return Object.prototype.hasOwnProperty.call(roleDrafts, key)
    ? roleDrafts[key]
    : roleCurrentLevel(role, moduleCode);
}

function setRoleDraft(role: FactoryAccessRole, moduleCode: string, level: FactoryPermissionLevel): void {
  const key = roleDraftKey(role, moduleCode);
  roleDrafts[key] = level;
  if (level === roleCurrentLevel(role, moduleCode)) roleDirty.delete(key);
  else roleDirty.add(key);
}

function employeeCurrentLevel(moduleCode: string): FactoryPermissionLevel {
  return userPermissions.value?.modules.find((module) => module.code === moduleCode)?.overrideLevel ?? null;
}

function employeeDraftLevel(moduleCode: string): FactoryPermissionLevel {
  const key = employeeDraftKey(moduleCode);
  return Object.prototype.hasOwnProperty.call(employeeDrafts, key)
    ? employeeDrafts[key]
    : employeeCurrentLevel(moduleCode);
}

function setEmployeeDraft(moduleCode: string, level: FactoryPermissionLevel): void {
  const key = employeeDraftKey(moduleCode);
  employeeDrafts[key] = level;
  if (level === employeeCurrentLevel(moduleCode)) employeeDirty.delete(key);
  else employeeDirty.add(key);
}

function clearDrafts(): void {
  for (const key of Object.keys(roleDrafts)) delete roleDrafts[key];
  for (const key of Object.keys(employeeDrafts)) delete employeeDrafts[key];
  roleDirty.clear();
  employeeDirty.clear();
}

function applyAccessState(next: FactoryAccessState): void {
  accessState.value = next;
  clearDrafts();
}

async function loadAccess(factoryId = props.factoryId): Promise<void> {
  const sequence = ++accessLoadSequence;
  if (!factoryId) return;
  loadingAccess.value = true;
  errorMessage.value = '';
  try {
    const response = await getFactoryAccess(factoryId);
    if (sequence !== accessLoadSequence || props.factoryId !== factoryId) return;
    applyAccessState(response.data);
  } catch (error) {
    if (sequence !== accessLoadSequence || props.factoryId !== factoryId) return;
    accessState.value = null;
    errorMessage.value = error instanceof Error ? error.message : '工厂授权配置加载失败';
  } finally {
    if (sequence === accessLoadSequence) loadingAccess.value = false;
  }
}

async function confirmScoped(message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, '确认保存授权配置', {
      type: 'warning',
      confirmButtonText: '确认保存',
      cancelButtonText: '取消',
    });
    return true;
  } catch {
    return false;
  }
}

function isWriteAllowed(): boolean {
  if (!canManage.value) {
    ElMessage.error('当前账号没有管理该工厂授权的权限');
    return false;
  }
  if (!props.factoryId || accessState.value?.factoryId !== props.factoryId) {
    ElMessage.error('工厂授权上下文已变化，请刷新后重试');
    return false;
  }
  return !savingKey.value;
}

async function saveModule(module: FactoryAccessModule, enabled: boolean): Promise<void> {
  if (!isWriteAllowed()) return;
  const targetFactoryId = props.factoryId;
  if (!await confirmScoped(`仅修改「${module.name}」在当前工厂的功能开通状态，不会改变菜单显示或其他工厂。`)) return;
  savingKey.value = `module:${module.code}`;
  try {
    const response = await updateFactoryAccessModule(targetFactoryId, module.code, enabled);
    if (props.factoryId !== targetFactoryId) throw new Error('工厂已切换，已丢弃过期授权响应');
    applyAccessState(response.data);
    ElMessage.success('功能开通状态已保存并回读');
  } catch (error) {
    if (props.factoryId === targetFactoryId) ElMessage.error(error instanceof Error ? error.message : '功能开通保存失败');
  } finally {
    if (props.factoryId === targetFactoryId) savingKey.value = null;
  }
}

async function saveRole(role: FactoryAccessRole): Promise<void> {
  if (!isWriteAllowed()) return;
  const changes = modules.value
    .map((module) => ({ moduleCode: module.code, level: roleDraftLevel(role, module.code), key: roleDraftKey(role, module.code) }))
    .filter((change) => roleDirty.has(change.key));
  if (!changes.length) return;
  const targetFactoryId = props.factoryId;
  if (!await confirmScoped(`仅修改当前工厂「${role.name}」的 ${changes.length} 项岗位权限，不改变全局默认或其他工厂。`)) return;
  savingKey.value = `role:${role.code}`;
  try {
    let latest: FactoryAccessState | null = null;
    for (const change of changes) {
      const response = await updateFactoryAccessRolePermission(targetFactoryId, role.code, change.moduleCode, change.level);
      if (props.factoryId !== targetFactoryId) throw new Error('工厂已切换，已丢弃过期授权响应');
      latest = response.data;
    }
    if (latest) applyAccessState(latest);
    ElMessage.success('岗位权限已保存并回读');
  } catch (error) {
    if (props.factoryId === targetFactoryId) ElMessage.error(error instanceof Error ? error.message : '岗位权限保存失败');
  } finally {
    if (props.factoryId === targetFactoryId) savingKey.value = null;
  }
}

async function loadUsers(): Promise<void> {
  const targetFactoryId = props.factoryId;
  if (!targetFactoryId) return;
  loadingUsers.value = true;
  errorMessage.value = '';
  try {
    const response = await listFactoryAccessUsers(targetFactoryId, {
      keyword: keyword.value.trim() || undefined,
      page: userPageNumber.value,
      size: userPageSize.value,
    });
    if (props.factoryId !== targetFactoryId) return;
    usersPage.value = response.data;
    if (!users.value.some((user) => String(user.id) === String(selectedUserId.value))) {
      selectedUserId.value = '';
      selectedUser.value = null;
      userPermissions.value = null;
    }
  } catch (error) {
    if (props.factoryId === targetFactoryId) errorMessage.value = error instanceof Error ? error.message : '员工目录加载失败';
  } finally {
    if (props.factoryId === targetFactoryId) loadingUsers.value = false;
  }
}

async function selectUser(user: FactoryAccessUser): Promise<void> {
  if (savingKey.value) return;
  selectedUserId.value = user.id;
  selectedUser.value = user;
  userPermissions.value = null;
  const targetFactoryId = props.factoryId;
  loadingUserPermissions.value = true;
  try {
    const response = await getFactoryAccessUserPermissions(targetFactoryId, user.id);
    if (props.factoryId !== targetFactoryId || String(selectedUserId.value) !== String(user.id)) return;
    userPermissions.value = response.data;
  } catch (error) {
    if (props.factoryId === targetFactoryId && String(selectedUserId.value) === String(user.id)) {
      errorMessage.value = error instanceof Error ? error.message : '员工授权加载失败';
    }
  } finally {
    if (props.factoryId === targetFactoryId && String(selectedUserId.value) === String(user.id)) loadingUserPermissions.value = false;
  }
}

async function saveEmployeePermission(moduleCode: string): Promise<void> {
  if (!isWriteAllowed() || !selectedUser.value || !selectedUserIsWritable.value) return;
  const key = employeeDraftKey(moduleCode);
  if (!employeeDirty.has(key)) return;
  const targetFactoryId = props.factoryId;
  const targetUserId = selectedUser.value.id;
  const level = employeeDraftLevel(moduleCode);
  const employeeName = selectedUser.value.displayName || selectedUser.value.username;
  const scope = level === null
    ? '清除该员工该模块现有例外并恢复随岗位生效；如存在同模块旧入口例外也会一并清理'
    : '设置该员工的模块例外，不改变其岗位默认';
  if (!await confirmScoped(`仅修改员工「${employeeName}」在当前工厂的「${moduleCode}」权限：${scope}。`)) return;
  savingKey.value = `employee:${String(targetUserId)}:${moduleCode}`;
  try {
    const response = await updateFactoryAccessUserPermission(targetFactoryId, targetUserId, moduleCode, level);
    if (props.factoryId !== targetFactoryId || String(selectedUserId.value) !== String(targetUserId)) {
      throw new Error('工厂或员工已切换，已丢弃过期授权响应');
    }
    userPermissions.value = response.data;
    employeeDirty.delete(key);
    delete employeeDrafts[key];
    ElMessage.success('员工例外权限已保存并回读');
  } catch (error) {
    if (props.factoryId === targetFactoryId && String(selectedUserId.value) === String(targetUserId)) {
      ElMessage.error(error instanceof Error ? error.message : '员工授权保存失败');
    }
  } finally {
    if (props.factoryId === targetFactoryId && String(selectedUserId.value) === String(targetUserId)) savingKey.value = null;
  }
}

function onUserPageChange(page: number): void {
  userPageNumber.value = Math.max(0, page - 1);
  void loadUsers();
}

watch(() => props.factoryId, (factoryId) => {
  accessLoadSequence += 1;
  accessState.value = null;
  usersPage.value = null;
  selectedUserId.value = '';
  selectedUser.value = null;
  userPermissions.value = null;
  userPageNumber.value = 0;
  clearDrafts();
  savingKey.value = null;
  if (factoryId) void loadAccess(factoryId);
  if (factoryId && props.activeTab === 'employees') void loadUsers();
});

watch(() => props.activeTab, (tab) => {
  if (!props.factoryId) return;
  if (!accessState.value) void loadAccess(props.factoryId);
  if (tab === 'employees' && !usersPage.value) void loadUsers();
}, { immediate: true });
</script>

<template>
  <div class="factory-access-panel">
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" class="access-error" />
    <el-skeleton v-if="loadingAccess" :rows="7" animated />
    <template v-else-if="accessState">
      <div class="access-summary">
        <div>
          <strong>{{ accessState.factoryName }}</strong>
          <span v-if="accessState.configurationVersion != null">配置版本 {{ accessState.configurationVersion }}</span>
          <span v-else>默认配置</span>
          <el-tag v-if="accessState.pendingConfiguration" type="warning" size="small">有待发布配置</el-tag>
        </div>
        <el-tag :type="canManage ? 'success' : 'info'">{{ canManage ? '可管理' : '只读查看' }}</el-tag>
      </div>
      <el-alert v-if="!canManage" title="当前账号只能查看授权状态，保存按钮已禁用。" type="info" :closable="false" />

      <section v-if="activeTab === 'activation'" class="access-section">
        <div class="section-heading"><div><h3>功能开通</h3><p>为当前工厂开通或停用业务功能；菜单显示在另一页单独设置。</p></div></div>
        <div class="module-activation-list">
          <div v-for="module in activationModules" :key="module.code" class="activation-row">
            <div><strong>{{ module.name }}</strong><small>{{ module.category }}</small></div>
            <el-switch :model-value="module.enabled" :disabled="!canManage || !!savingKey" :loading="savingKey === `module:${module.code}`" @change="(value: boolean) => saveModule(module, value)" />
          </div>
        </div>
      </section>

      <section v-else-if="activeTab === 'roles'" class="access-section">
        <div class="section-heading"><div><h3>岗位权限</h3><p>显示全局默认、当前工厂覆盖和最终生效值；保存只作用于当前工厂。</p></div></div>
        <div class="table-scroll">
          <table class="permission-table">
            <thead><tr><th>岗位</th><th v-for="module in modules" :key="module.code">{{ module.name }}</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="role in accessState.roles" :key="role.code">
                <td><strong>{{ role.name }}</strong><small>{{ role.code }}<template v-if="role.protectedRole"> · 受保护</template></small></td>
                <td v-for="module in modules" :key="module.code" class="level-cell">
                  <div class="level-summary">全局 {{ levelLabel(role.globalLevels?.[module.code]) }} · 当前厂 {{ levelLabel(roleCurrentLevel(role, module.code)) }} · 生效 {{ levelLabel(role.effectiveLevels?.[module.code]) }}</div>
                  <el-select :model-value="roleDraftLevel(role, module.code)" size="small" :disabled="!canManage || role.protectedRole || !!savingKey" @change="(value: FactoryPermissionLevel) => setRoleDraft(role, module.code, value)">
                    <el-option v-for="option in levelOptions" :key="`${module.code}-${String(option.value)}`" :label="option.label" :value="option.value" />
                  </el-select>
                </td>
                <td><el-button size="small" type="primary" plain :disabled="!canManage || role.protectedRole || !!savingKey || ![...roleDirty].some(key => key.startsWith(`${role.code}:`))" :loading="savingKey === `role:${role.code}`" @click="saveRole(role)">保存本行</el-button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-else class="access-section employees-section">
        <div class="section-heading"><div><h3>员工授权</h3><p>员工例外仅覆盖当前工厂；“随岗位”会清除该模块的员工级覆盖。</p></div></div>
        <div class="employee-toolbar"><el-input v-model="keyword" placeholder="搜索用户名或姓名" clearable @keyup.enter="userPageNumber = 0; loadUsers()" /><el-button type="primary" plain :loading="loadingUsers" @click="userPageNumber = 0; loadUsers()">搜索</el-button></div>
        <div class="employee-grid">
          <div class="employee-list">
            <el-skeleton v-if="loadingUsers" :rows="5" animated />
            <el-empty v-else-if="!users.length" description="没有匹配员工" />
            <template v-else>
              <button v-for="user in users" :key="String(user.id)" type="button" class="employee-row" :class="{ active: String(selectedUserId) === String(user.id) }" :disabled="!!savingKey" @click="selectUser(user)">
                <span><strong>{{ user.displayName || user.username }}</strong><small>{{ user.username }} · {{ user.roleCode }}</small></span>
                <el-tag v-if="user.protectedRole" type="warning" size="small">受保护</el-tag><el-tag v-else-if="!user.active" type="info" size="small">停用</el-tag>
              </button>
            </template>
            <el-pagination v-if="userTotal" small layout="prev, pager, next" :current-page="userPageNumber + 1" :page-size="userPageSize" :total="userTotal" @current-change="onUserPageChange" />
          </div>
          <div class="employee-permissions">
            <el-empty v-if="!selectedUser" description="请选择员工" />
            <el-skeleton v-else-if="loadingUserPermissions" :rows="5" animated />
            <template v-else-if="userPermissions">
              <div class="employee-detail-heading"><strong>{{ userPermissions.user.displayName || userPermissions.user.username }}</strong><span>{{ userPermissions.user.roleCode }} · 基础岗位权限 / 当前员工覆盖 / 最终生效</span></div>
              <div v-for="module in userPermissions.modules" :key="module.code" class="employee-permission-row">
                <div><strong>{{ module.name }}</strong><small>岗位 {{ levelLabel(module.roleLevel) }} · 当前员工 {{ employeeLevelLabel(module.overrideLevel) }} · 生效 {{ levelLabel(module.effectiveLevel) }}</small></div>
                <el-select :model-value="employeeDraftLevel(module.code)" size="small" :disabled="!selectedUserIsWritable || !!savingKey" @change="(value: FactoryPermissionLevel) => setEmployeeDraft(module.code, value)">
                  <el-option v-for="option in employeeLevelOptions" :key="`${module.code}-${String(option.value)}`" :label="option.label" :value="option.value" />
                </el-select>
                <el-button size="small" type="primary" plain :disabled="!selectedUserIsWritable || !!savingKey || !employeeDirty.has(employeeDraftKey(module.code))" :loading="savingKey === `employee:${String(selectedUserId)}:${module.code}`" @click="saveEmployeePermission(module.code)">保存</el-button>
              </div>
            </template>
          </div>
        </div>
      </section>
    </template>
    <el-empty v-else description="请选择工厂后查看授权" />
  </div>
</template>

<style lang="scss" scoped>
.factory-access-panel { display: flex; flex-direction: column; gap: 14px; }
.access-error { margin-bottom: 4px; }
.access-summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 14px; background: #f7faff; border: 1px solid #e7eff8; border-radius: 8px; }
.access-summary div { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.access-summary span, .section-heading p, .employee-detail-heading span { color: #7A8599; font-size: 12px; }
.access-section { display: flex; flex-direction: column; gap: 14px; }
.section-heading h3 { margin: 0 0 5px; color: #1A2332; }
.section-heading p { margin: 0; }
.module-activation-list { display: grid; gap: 8px; }
.activation-row, .employee-permission-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px; border: 1px solid #EDF2F7; border-radius: 8px; }
.activation-row div, .employee-permission-row div, .employee-row span { display: flex; flex-direction: column; gap: 4px; }
.activation-row small, .employee-permission-row small, .employee-row small, .permission-table small { color: #7A8599; font-size: 12px; }
.table-scroll { overflow-x: auto; }
.permission-table { width: 100%; min-width: 880px; border-collapse: collapse; }
.permission-table th, .permission-table td { padding: 10px; border-bottom: 1px solid #EDF2F7; text-align: left; vertical-align: top; }
.permission-table th { color: #7A8599; font-size: 12px; font-weight: 600; }
.permission-table td:first-child { min-width: 135px; }
.permission-table td:last-child { white-space: nowrap; }
.permission-table td > strong, .permission-table td > small { display: block; }
.level-cell { min-width: 180px; }
.level-summary { margin-bottom: 6px; color: #7A8599; font-size: 11px; line-height: 1.45; }
.employee-toolbar { display: flex; gap: 8px; max-width: 460px; }
.employee-grid { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 16px; align-items: start; }
.employee-list { display: flex; flex-direction: column; gap: 6px; }
.employee-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; padding: 10px; border: 1px solid #EDF2F7; border-radius: 8px; background: #fff; color: #1A2332; text-align: left; cursor: pointer; }
.employee-row:hover, .employee-row.active { border-color: #9fc7e9; background: #f7faff; }
.employee-row:disabled { cursor: wait; opacity: .7; }
.employee-permissions { min-height: 180px; }
.employee-detail-heading { display: flex; flex-direction: column; gap: 5px; margin-bottom: 12px; }
.employee-permission-row { margin-bottom: 8px; }
@media (max-width: 900px) { .employee-grid { grid-template-columns: 1fr; } .permission-table { min-width: 700px; } }
</style>
