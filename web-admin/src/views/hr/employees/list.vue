<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import type { FormInstance } from 'element-plus';
import type { TableRow } from '@/types/api';
import { enumLabel } from '@/utils/enumDisplay';
import DepartmentSwitcherRow, { type DeptOption } from '@/components/layout/DepartmentSwitcherRow.vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('hr'));

/**
 * R-1 / R-2②: 改用户名、删除账号 —— 比普通编辑更重的两个动作, 单独收口。
 *
 * 用户名是**登录凭证**, 删除会影响历史归属, 因此只给 HR 主管和工厂超管,
 * ⛔ 不要顺手放开给 department_admin(他有 hr 写权限, 但那是管本部门员工资料的)。
 */
const HR_PRIVILEGED_ROLES = ['hr_admin', 'factory_super_admin', 'platform_admin'];
const canManageAccount = computed(() => canWrite.value
  && HR_PRIVILEGED_ROLES.includes(String(authStore.user?.roleCode || '')));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

// Apr 20 Bug BR-10 fix: 员工管理之前无检索, 用户报告"未见检索功能"
// 加 3 个 filter: keyword (姓名/用户名/手机) / roleCode / isActive
const searchForm = reactive({ keyword: '', roleCode: '', isActive: null as boolean | null });

// Sprint 4 W2 #63 U-DEPT-1 integration: 部门切换 button row
// 防呆 R2: button text 部门名称 (不是 ID) — DepartmentSwitcherRow 已用 dept.name
// 防呆 R5: "全部" button 为默认 (清空 filter, dept.id = null)
const activeDeptId = ref<string | null>(null);
const departments = ref<DeptOption[]>([]);

async function loadDepartments() {
  if (!factoryId.value) return;
  try {
    const response = await get<TableRow[]>(`/${factoryId.value}/departments/active`);
    if (response.success && response.data) {
      departments.value = (response.data as TableRow[]).map((d) => ({
        id: String(d.id),
        name: d.name as string,
      }));
    }
  } catch (error) {
    console.error('加载部门列表失败:', error);
  }
}

/**
 * 部门显示名。后端可能只给英文码(如 `production`)，页面已加载的部门列表能把它翻成中文。
 * 翻不出时**回落原值而不是编造** —— 用户看到码至少知道要去部门管理里查。
 */
function resolveDepartmentText(row: TableRow): string {
  const display = row.departmentDisplayName || row.departmentName;
  if (display) return String(display);
  const raw = row.department;
  if (raw === null || raw === undefined || String(raw).trim() === '') return '-';
  const code = String(raw);
  const matched = departments.value.find((d) => d.id === code || d.name === code);
  return matched ? matched.name : code;
}

function handleDeptChange(_id: string | null) {
  pagination.value.page = 1;
  loadData();
}

onMounted(() => {
  loadDepartments();
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    // 有 keyword 用 /users/search, 否则 /users; roleCode/isActive 前端过滤 (后端无参数)
    const basePath = searchForm.keyword.trim()
      ? `/${factoryId.value}/users/search`
      : `/${factoryId.value}/users`;
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    if (searchForm.keyword.trim()) params.keyword = searchForm.keyword.trim();
    const response = await get(basePath, { params });
    if (response.success && response.data) {
      let rows = response.data.content || [];
      // Apr 20 BR-10: 前端 filter roleCode + isActive (后端无参数)
      if (searchForm.roleCode) {
        rows = rows.filter((u: TableRow) => (u.roleCode || u.role) === searchForm.roleCode);
      }
      if (searchForm.isActive !== null) {
        rows = rows.filter((u: TableRow) => {
          const active = u.isActive === true || u.status === 'ACTIVE';
          return active === searchForm.isActive;
        });
      }
      // Sprint 4 W2 #63 U-DEPT-1: filter by selected department (name-match)
      // User records expose dept as departmentName/department/departmentDisplayName,
      // not deptId — match dept name to the active chip's name.
      if (activeDeptId.value !== null) {
        const dept = departments.value.find((d) => d.id === activeDeptId.value);
        const deptName = dept?.name;
        if (deptName) {
          rows = rows.filter((u: TableRow) => {
            const userDept = u.departmentDisplayName || u.departmentName || u.department || '';
            return String(userDept) === deptName;
          });
        }
      }
      tableData.value = rows;
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载员工数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

// Apr 20 BR-10: 检索操作
function handleSearch() {
  pagination.value.page = 1;
  loadData();
}
function handleReset() {
  searchForm.keyword = '';
  searchForm.roleCode = '';
  searchForm.isActive = null;
  handleSearch();
}

// Dialog state
const dialogVisible = ref(false);
const dialogMode = ref<'add' | 'edit' | 'view'>('add');
const formRef = ref<FormInstance>();
const submitting = ref(false);

const defaultForm = {
  id: '',
  fullName: '',
  username: '',
  phone: '',
  role: '',
  employeeCode: '',
  department: '',
  isActive: true,
};
const formData = reactive({ ...defaultForm });

/**
 * 部门下拉的**建议项** —— ⛔ 不是权威清单。
 *
 * 三个来源合并去重：
 *   ① `departments` 表（部门管理里建的）—— 2026-08-20 实测**全库 0 行**，
 *      所以它今天贡献不了任何东西；留着是为了这张表将来被填起来时自动接上。
 *   ② 当前这一页员工**已经在用**的部门（prod 上是自由文本：sales / finance / ...）。
 *   ③ 本人的现值 —— ⚠️ 这条最容易漏：他的部门若不在前两者里（比如他在第 2 页），
 *      下拉里就没有这一项，一保存就把人家的部门**清空**了。
 *
 * 下拉是 `allow-create` 的，所以选不到时可以直接输入。
 */
const departmentOptions = computed<string[]>(() => {
  const names = new Set<string>();
  departments.value.forEach((d) => d.name && names.add(d.name));
  tableData.value.forEach((row) => {
    const text = row.departmentDisplayName || row.departmentName || row.department;
    if (text && String(text).trim()) names.add(String(text).trim());
  });
  if (formData.department) names.add(formData.department);
  return Array.from(names).sort((a, b) => a.localeCompare(b, 'zh-CN'));
});

const roleOptions = [
  { value: 'factory_super_admin', label: '工厂总监' },
  { value: 'hr_admin', label: 'HR管理员' },
  { value: 'procurement_manager', label: '采购主管' },
  { value: 'sales_manager', label: '销售主管' },
  { value: 'operations_coordinator', label: '运营协调员' },
  { value: 'dispatcher', label: '调度' },
  { value: 'warehouse_manager', label: '仓储主管' },
  { value: 'equipment_admin', label: '设备管理员' },
  { value: 'quality_manager', label: '质量经理' },
  { value: 'finance_manager', label: '财务主管' },
  { value: 'workshop_supervisor', label: '车间主任' },
  { value: 'quality_inspector', label: '质检员' },
  { value: 'operator', label: '操作员' },
  { value: 'warehouse_worker', label: '仓库员' },
];

const formRules = {
  fullName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
};

const dialogTitle = computed(() => {
  if (dialogMode.value === 'add') return '添加员工';
  if (dialogMode.value === 'edit') return '编辑员工';
  return '查看员工';
});

const isViewMode = computed(() => dialogMode.value === 'view');

function handleAdd() {
  dialogMode.value = 'add';
  Object.assign(formData, defaultForm);
  dialogVisible.value = true;
}

function handleView(row: TableRow) {
  dialogMode.value = 'view';
  Object.assign(formData, {
    id: row.id,
    fullName: row.fullName || '',
    username: row.username || '',
    phone: row.phone || '',
    role: row.roleCode || row.role || '',
    employeeCode: row.employeeCode || '',
    // ⚠️ 顺序要紧: 后端 UserMapper.toDTO 里 department **恒为 null**,
    // 真实值放在 departmentDisplayName。原来第一个读的是不存在的 departmentName,
    // 第二个读的是恒 null 的 department ⇒ 编辑框里的部门**永远是空的**,
    // 无论那个人实际在哪个部门。(用户报的「没法编辑部门」有一半是这个。)
    department: String(row.departmentDisplayName || row.departmentName || row.department || ''),
    isActive: row.isActive === true || row.status === 'ACTIVE',
  });
  dialogVisible.value = true;
}

function handleEdit(row: TableRow) {
  dialogMode.value = 'edit';
  Object.assign(formData, {
    id: row.id,
    fullName: row.fullName || '',
    username: row.username || '',
    phone: row.phone || '',
    role: row.roleCode || row.role || '',
    employeeCode: row.employeeCode || '',
    // ⚠️ 顺序要紧: 后端 UserMapper.toDTO 里 department **恒为 null**,
    // 真实值放在 departmentDisplayName。原来第一个读的是不存在的 departmentName,
    // 第二个读的是恒 null 的 department ⇒ 编辑框里的部门**永远是空的**,
    // 无论那个人实际在哪个部门。(用户报的「没法编辑部门」有一半是这个。)
    department: String(row.departmentDisplayName || row.departmentName || row.department || ''),
    isActive: row.isActive === true || row.status === 'ACTIVE',
  });
  dialogVisible.value = true;
}

/**
 * R-2②: 删除账号 —— **仅软删**，不提供硬删入口。
 *
 * ⚠️ 二次确认里**明说历史归属会保留**，这不是客套话，是这个仓真吃过的亏:
 * `created_by=0` 那一族的根因就是「写死的 user id 有保质期」，而
 * `id=1 / factory_admin1` 正是**被租户清理删掉**之后才开始报外键。
 * ⛔ 若哪天有人把后端改成硬删，这段提示就变成了假话 —— 那时该改的是实现，不是提示。
 *
 * ⚠️ 删除会造出软删行，而软删行**照样占着用户名**（全量唯一索引）。
 *    用户名查重因此必须走 `countByUsernameIncludingDeleted`，见 UserRepository 上的说明。
 */
// R-2① (2026-08-20 手动测试台账): 软删账号 —— 仅软删, 不提供硬删入口。
// 删除前拉一下「至少建过 N 张采购单」的提示 (只统计采购单一种单据类型, 不是全量单据数),
// 让操作者知道这不是一个无痕动作; 真正的软删逻辑在后端 (deleted_at), 历史单据不受影响。
const deleting = ref(false);
async function handleDelete(row: TableRow) {
  if (!factoryId.value || !row.id) return;
  let impactHint = '';
  try {
    const impact = await get(`/${factoryId.value}/users/${row.id}/deletion-impact`);
    const count = Number(impact?.data?.purchaseOrderCount ?? 0);
    if (count > 0) {
      impactHint = `该账号至少建过 ${count} 张采购单（其它类型单据未计入此数字）。`;
    }
  } catch (error) {
    // 拿不到影响预览不阻塞删除流程 —— 退化成不带具体条数的确认, 而不是让整个操作失败。
    console.error('查询删除影响失败:', error);
  }
  try {
    await ElMessageBox.confirm(
      `${impactHint}删除后「${row.fullName || row.username}」不再出现在员工列表中，`
        + '但他过去创建/申请/审批过的历史单据仍会保留他的姓名归属，不受影响。此操作不可逆（软删除，非物理删除）。',
      '确认删除员工账号',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  deleting.value = true;
  try {
    const res = await del(`/${factoryId.value}/users/${row.id}`);
    if (res.success) {
      ElMessage.success('员工账号已删除');
      await loadData();
    } else {
      ElMessage.error(res.message || '删除失败');
    }
  } catch (error) {
    console.error('删除员工失败:', error);
  } finally {
    deleting.value = false;
  }
}

async function handleSubmit() {
  if (!formRef.value) return;
  await formRef.value.validate();
  submitting.value = true;
  try {
    const payload: Record<string, any> = {
      fullName: formData.fullName,
      username: formData.username,
      phone: formData.phone,
      roleCode: formData.role,
      employeeCode: formData.employeeCode,
      department: formData.department || undefined,
      // Apr 18 2026 bug #47: 后端字段兼容 — 有的接口读 isActive, 有的读 status
      // 一起送避免在职/离职 切换后列表显示不一致。
      isActive: formData.isActive,
      status: formData.isActive ? 'ACTIVE' : 'INACTIVE',
    };
    if (dialogMode.value === 'add') {
      payload.password = '123456';
      payload.email = (formData.username || 'user') + '@cretas.com';
    }
    let res;
    if (dialogMode.value === 'add') {
      res = await post(`/${factoryId.value}/users`, payload);
    } else {
      res = await put(`/${factoryId.value}/users/${formData.id}`, payload);
    }
    if (res.success) {
      ElMessage.success(dialogMode.value === 'add' ? '添加成功' : '编辑成功');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '操作失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast; this is debug-only log.
    console.error('提交失败:', error);
  } finally {
    submitting.value = false;
  }
}

/**
 * 角色中文名 —— 委托权威表 `enumDisplay.ROLE_LABELS`，**不要在这里另抄一份**。
 *
 * 🔴 原来这里是一张私有映射表，硬抄了 18 个角色码，而权威表有 30 个。
 * 漏掉的码直接以英文原样示人 —— prod 实测六膳门员工档案里 5 个人的角色显示成
 * `yield_operator`，旁边的人却显示「质检员」。同一列一半中文一半英文码。
 *
 * ⚠️ 顺带发现同一个码在仓里有**两个不同中文名**：权威表叫「出成率录入员」，
 * `hr/whitelist/index.vue` 的私有列表叫「报工员」。那处未在本次改动范围内，
 * 但同样该收敛到权威表 —— 两个名字指同一个角色，用户没法确认是不是同一件事。
 */
function getRoleText(role: string) {
  return enumLabel(role);
}
</script>

<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>员工档案</span>
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">添加员工</el-button>
        </div>
      </template>

      <!-- Sprint 4 W2 #63 U-DEPT-1: 部门切换 button row (1 个部门时自动隐藏) -->
      <DepartmentSwitcherRow
        v-model="activeDeptId"
        :departments="departments"
        all-label="全部"
        @change="handleDeptChange"
      />

      <!-- Apr 20 Bug BR-10 fix: 加检索栏 (keyword + 角色 + 状态) -->
      <div class="search-bar" style="display:flex; gap:8px; margin-bottom:16px; flex-wrap:wrap;">
        <el-input v-model="searchForm.keyword" placeholder="搜索 姓名/用户名/手机号" clearable style="width:240px" @keyup.enter="handleSearch" />
        <el-select v-model="searchForm.roleCode" placeholder="角色" clearable style="width:160px" @change="handleSearch">
          <el-option v-for="opt in roleOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select v-model="searchForm.isActive" placeholder="状态" clearable style="width:110px" @change="handleSearch">
          <el-option :value="true" label="在职" />
          <el-option :value="false" label="离职" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border>
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="fullName" label="姓名" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column label="角色">
          <template #default="{ row }">
            <el-tag>{{ getRoleText(row.roleCode || row.role) }}</el-tag>
          </template>
        </el-table-column>
        <!--
          原来直接回落到 row.department 这个**英文码**(prod 实测显示 `production`)。
          页面本来就加载了 /departments/active, 只是没拿来翻译。翻不出时仍回落原值 ——
          不编造名字, 但至少已知的码会显示中文。
        -->
        <el-table-column label="部门">
          <template #default="{ row }">{{ resolveDepartmentText(row) }}</template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row.isActive === true || row.status === 'ACTIVE') ? 'success' : 'danger'">
              {{ (row.isActive === true || row.status === 'ACTIVE') ? '在职' : '离职' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="handleView(row)">查看</el-button>
            <el-button v-if="canWrite" type="primary" link @click="handleEdit(row)">编辑</el-button>
            <!--
              R-2② (2026-08-20): 原来只有「查看/编辑」, 人走了只能改成停用, 账号一直挂在列表里。
              后端 DELETE /users/{userId} 一直存在(hr:read_write), 缺的只是入口。
              ⛔ 用 canManageAccount 而不是 canWrite: 只给 HR 主管 / 工厂超管,
                 不给部门管理员 —— 他有 hr 写权限, 但那是管本部门员工资料的。
            -->
            <el-button
              v-if="canManageAccount"
              type="danger"
              link
              :loading="deleting"
              @click="handleDelete(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
        class="pagination"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="80px" :disabled="isViewMode">
        <el-form-item label="姓名" prop="fullName">
          <el-input v-model="formData.fullName" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <!--
            R-1 (2026-08-20): 原来是 `:disabled="dialogMode === 'edit'"` —— 编辑态一律禁用,
            HR 主管和工厂超管也改不了。Steve: 「人事管理里, 专门管人事的或者工厂管理员,
            应该可以去修改用户名。」
            ⇒ 编辑态按**角色**决定能不能改, ⛔ 不是一刀切禁用。
            查看态(view)始终只读。
          -->
          <el-input
            v-model="formData.username"
            placeholder="请输入用户名"
            :disabled="dialogMode === 'view' || (dialogMode === 'edit' && !canManageAccount)"
          />
          <div v-if="dialogMode === 'edit' && canManageAccount" class="field-hint">
            改完之后本人要用<strong>新用户名</strong>登录；历史单据仍挂在他名下。
          </div>
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="formData.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="formData.role" placeholder="请选择角色" style="width: 100%">
            <el-option v-for="opt in roleOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="工号" prop="employeeCode">
          <el-input v-model="formData.employeeCode" placeholder="请输入工号" />
        </el-form-item>
        <el-form-item label="部门" prop="department">
          <!--
            🔴 R-2① (2026-08-20): 这里原来写死了 5 个 option
            (PROCESSING / QUALITY / LOGISTICS / MANAGEMENT / FARMING) —— 后端 Department
            枚举的 5 个值。选了保存**没有任何反应**(后端 updateEntity 压根不复制 department,
            与 roleCode/position 是同一个「新建写了、更新漏了」的形状, 已在后端修)。

            ⚠️ 我的第一版把它改成 `v-for="dept in departments"`(接 /departments/active),
               理由是「前端手写清单和真实数据源两张皮」。**那个判断是错的** ——
               实测 `departments` 表全库 **0 行 / 0 个工厂**, 接上去等于换成一个空下拉,
               比 5 个硬编码更糟。⇒ 判「哪一份是真的」之前先去数据里看一眼。

            真实情况: users.department 是自由文本, prod 已有 15 种取值
            (sales / finance / production / warehouse / hr / quality / procurement / ...),
            **只有 PROCESSING 一个在那个枚举里**。所以枚举才是过期的那份, 已放宽为 String。

            ⚠️ 建议项用部门**名称**而不是 code: 「部门管理」里编码是可选字段、名称必填,
               用 code 会有取不到值的部门; 且 name 与后端
               countByFactoryIdAndDepartment(factoryId, department.getName())
               以及本文件 resolveDepartmentText() 的匹配口径一致。

            ⇒ 这里给的是**建议项**(该工厂当前在用的部门 + 本人现值), 并允许直接输入新部门。
          -->
          <el-select
            v-model="formData.department"
            placeholder="请选择或直接输入部门"
            clearable
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option v-for="name in departmentOptions" :key="name" :label="name" :value="name" />
          </el-select>
          <div class="field-hint">
            没有合适的？直接输入新部门名即可。
          </div>
        </el-form-item>
        <el-form-item label="状态" prop="isActive">
          <el-switch v-model="formData.isActive" active-text="在职" inactive-text="离职" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ isViewMode ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!isViewMode" type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}
/* R-1/R-2: 字段下方的说明 —— 「改完要用新用户名登录」这类话要看得见, 不能藏在 tooltip 里 */
.field-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
