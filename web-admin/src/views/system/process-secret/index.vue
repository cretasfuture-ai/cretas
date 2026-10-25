<template>
  <div class="process-secret-page">
    <div class="page-header">
      <h2>工艺保密</h2>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon class="intro">
      <template #title>
        把产品设为工艺保密后，在配方、BOM、工艺页面上只有负责人和负责人授权的人能看到完整的配比和用量，其他人（包括平台工作人员）只看到物料和本单总量。
        首次设置由工厂总监操作并指定负责人，之后由负责人管理授权和查看访问记录。
      </template>
    </el-alert>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索产品名称" clearable class="search" />
        <el-radio-group v-model="statusFilter">
          <el-radio-button value="all">全部产品</el-radio-button>
          <el-radio-button value="protected">已保密</el-radio-button>
          <el-radio-button value="open">未保密</el-radio-button>
        </el-radio-group>
      </div>

      <el-table v-loading="loading" :data="filteredRows" stripe border>
        <el-table-column label="产品" min-width="200">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <span v-if="row.code" class="muted"> {{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="保密状态" width="170">
          <template #default="{ row }">
            <el-tag v-if="!row.policy" type="info" size="small">未保密</el-tag>
            <el-tag v-else :type="row.policy.storageMode === 'CUSTOMER_HELD' ? 'danger' : 'warning'" size="small">
              {{ modeLabel(row.policy.storageMode) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="负责人" width="150">
          <template #default="{ row }">
            <span v-if="row.policy">{{ userName(row.policy.ownerUserId) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最后修改" width="170">
          <template #default="{ row }">{{ row.policy ? formatTime(row.policy.updatedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <template v-if="!row.policy">
              <el-button v-if="isFactoryDirector" link type="primary" @click="openPolicyDialog(row)">设为保密</el-button>
              <span v-else class="muted">仅工厂总监可以设置</span>
            </template>
            <div v-else-if="row.policy.currentUserIsOwner" class="row-actions">
              <el-button link type="primary" @click="openGrantDrawer(row)">授权管理</el-button>
              <el-button link type="primary" @click="openAuditDrawer(row)">访问记录</el-button>
              <el-dropdown trigger="click" @command="(cmd: string) => onMoreCommand(cmd, row)">
                <el-button link type="primary">更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">修改保密设置</el-dropdown-item>
                    <el-dropdown-item command="disable" class="danger-item">关闭保密</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <span v-else class="muted">由 {{ userName(row.policy.ownerUserId) }} 负责管理</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">本厂还没有启用中的产品，先在产品管理里添加产品。</div>
        </template>
      </el-table>
    </el-card>

    <!-- 设置 / 修改保密 -->
    <el-dialog v-model="policyDialog.open" :title="policyDialogTitle" width="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="保密方式">
          <el-radio-group v-model="policyDialog.storageMode" class="mode-group">
            <el-radio value="CLOUD_RESTRICTED" class="mode-option">
              <div class="mode-title">工艺保密访问</div>
              <div class="mode-desc">完整配方和工艺仍保存在系统里，只有负责人和获授权的人能看、能导出、能修改。</div>
            </el-radio>
            <el-radio value="CUSTOMER_HELD" class="mode-option">
              <div class="mode-title">核心工艺不入云</div>
              <div class="mode-desc">系统里不保存、不返回完整工艺，核心工艺由贵方自己的执行端保存；系统只接收执行端签名的批次结果。</div>
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert v-if="modeChangeWarning" type="warning" :closable="false" show-icon :title="modeChangeWarning" class="warn" />
        <el-form-item label="负责人">
          <el-select v-model="policyDialog.ownerUserId" filterable placeholder="选择负责人" class="full">
            <el-option v-for="u in users" :key="u.id" :label="userLabel(u)" :value="u.id" />
          </el-select>
          <div class="hint">负责人管理授权、查看访问记录、修改或关闭保密；其他人（包括工厂总监）不能代替。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="policyDialog.saving" :disabled="!policyDialog.ownerUserId"
          @click="savePolicy">保存</el-button>
      </template>
    </el-dialog>

    <!-- 授权管理 -->
    <el-drawer v-model="grantDrawer.open" :title="`授权管理 — ${grantDrawer.product?.name || ''}`" :size="drawerSize" destroy-on-close>
      <el-alert v-if="grantDrawer.product?.policy?.storageMode === 'CUSTOMER_HELD'" type="info" :closable="false" show-icon
        title="该产品核心工艺不入云，系统里没有可授予的完整工艺，需要授权请在贵方执行端处理。" class="warn" />
      <template v-else>
        <el-card shadow="never" class="grant-form">
          <template #header><strong>新增授权</strong></template>
          <el-form label-width="90px">
            <el-form-item label="被授权人">
              <el-select v-model="grantForm.granteeId" filterable placeholder="选择本厂员工" class="full">
                <el-option v-for="u in grantableUsers" :key="u.id" :label="userLabel(u)" :value="u.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="可以">
              <el-checkbox v-model="grantForm.canViewFull">查看完整配方和工艺</el-checkbox>
              <el-checkbox v-model="grantForm.canExport">导出</el-checkbox>
              <el-checkbox v-model="grantForm.canEdit">修改</el-checkbox>
            </el-form-item>
            <el-form-item label="用途">
              <el-select v-model="grantForm.purposeType" placeholder="选择用途" class="full">
                <el-option v-for="p in PURPOSES" :key="p" :label="p" :value="p" />
              </el-select>
              <el-input v-if="grantForm.purposeType === '其他'" v-model="grantForm.purposeOther" type="textarea"
                :rows="2" maxlength="200" show-word-limit placeholder="写清楚为什么需要" class="other" />
            </el-form-item>
            <el-form-item label="到期时间">
              <el-date-picker v-model="grantForm.expiresAt" type="datetime" placeholder="选择到期时间"
                value-format="YYYY-MM-DDTHH:mm:ss" :disabled-date="isPastDate" :shortcuts="EXPIRY_SHORTCUTS" />
              <div class="hint">到期后自动失效；也可以随时手动撤销。</div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="grantForm.saving" :disabled="!grantValid" @click="saveGrant">授权</el-button>
              <span v-if="grantInvalidReason" class="muted reason">{{ grantInvalidReason }}</span>
            </el-form-item>
          </el-form>
        </el-card>
      </template>

      <el-table v-loading="grantDrawer.loading" :data="grantDrawer.grants" stripe border class="grant-table">
        <el-table-column label="被授权人" min-width="110">
          <template #default="{ row }">{{ userName(row.granteeId) }}</template>
        </el-table-column>
        <el-table-column label="权限" min-width="120">
          <template #default="{ row }">
            <el-tag v-if="row.canViewFull" size="small">查看</el-tag>
            <el-tag v-if="row.canExport" size="small" type="warning">导出</el-tag>
            <el-tag v-if="row.canEdit" size="small" type="danger">修改</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="purpose" label="用途" min-width="120" />
        <el-table-column label="到期" min-width="140">
          <template #default="{ row }">{{ formatTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130" fixed="right">
          <template #default="{ row }">
            <el-tag v-if="row.revokedAt" size="small" type="info">已撤销</el-tag>
            <el-tag v-else-if="isExpired(row.expiresAt)" size="small" type="info">已到期</el-tag>
            <template v-else>
              <el-tag size="small" type="success">生效中</el-tag>
              <el-button link type="danger" @click="confirmRevoke(row)">撤销</el-button>
            </template>
          </template>
        </el-table-column>
        <template #empty><div class="empty">还没有授权记录。</div></template>
      </el-table>
    </el-drawer>

    <!-- 访问记录 -->
    <el-drawer v-model="auditDrawer.open" :title="`访问记录 — ${auditDrawer.product?.name || ''}`" :size="drawerSize" destroy-on-close>
      <div class="hint audit-hint">记录谁在什么时候查看、导出、修改了该产品的工艺，以及授权的变更。记录里不含工艺内容。</div>
      <el-table v-loading="auditDrawer.loading" :data="auditRows" stripe border>
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ formatTime(row.event.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" min-width="100">
          <template #default="{ row }">
            {{ row.event.actorType === 'PLATFORM_ADMIN' ? '平台人员' : userName(row.event.actorId) }}
          </template>
        </el-table-column>
        <el-table-column label="动作" min-width="110">
          <template #default="{ row }">{{ row.actionLabel }}</template>
        </el-table-column>
        <el-table-column label="结果" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.event.decision === 'ALLOW' ? 'success' : 'danger'">
              {{ row.event.decision === 'ALLOW' ? '允许' : '拒绝' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">{{ row.description }}</template>
        </el-table-column>
        <template #empty><div class="empty">还没有访问记录。</div></template>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowDown, Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { describeAuditReason } from './auditLabels';
import {
  createProcessSecretGrant,
  disableProcessSecretPolicy,
  listActiveProductTypes,
  listProcessSecretAuditEvents,
  listProcessSecretGrants,
  listProcessSecretPolicies,
  listUserDirectory,
  revokeProcessSecretGrant,
  upsertProcessSecretPolicy,
  type DirectoryUser,
  type ProcessSecretAccessEvent,
  type ProcessSecretGrant,
  type ProcessSecretPolicy,
  type ProcessSecretStorageMode,
} from '@/api/processSecret';

interface ProductRow {
  id: string;
  name: string;
  code?: string;
  policy: ProcessSecretPolicy | null;
}

const MODE_LABELS: Record<ProcessSecretStorageMode, string> = {
  CLOUD_RESTRICTED: '工艺保密访问',
  CUSTOMER_HELD: '核心工艺不入云',
};
const ACTION_LABELS: Record<string, string> = {
  VIEW_FULL: '查看完整工艺',
  EXPORT: '导出',
  EDIT: '修改工艺',
  MANAGE_POLICY: '修改保密设置',
  MANAGE_GRANT: '授权变更',
  READ_AUDIT: '查看访问记录',
  REGISTER_PACKAGE: '登记工艺包',
  RETIRE_PACKAGE: '停用工艺包',
};
const PURPOSES = ['新品试制', '生产异常排查', '质量审核', '配方调整', '设备调试', '其他'];
const DAY = 24 * 60 * 60 * 1000;
const EXPIRY_SHORTCUTS = [
  { text: '1 天后', value: () => new Date(Date.now() + DAY) },
  { text: '7 天后', value: () => new Date(Date.now() + 7 * DAY) },
  { text: '30 天后', value: () => new Date(Date.now() + 30 * DAY) },
];

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');
const currentUserId = computed(() => authStore.user?.id ?? null);
const isFactoryDirector = computed(() => authStore.currentRole === 'factory_super_admin');

const loading = ref(false);
const products = ref<{ id: string; name: string; code?: string }[]>([]);
const policies = ref<ProcessSecretPolicy[]>([]);
const users = ref<DirectoryUser[]>([]);
const keyword = ref('');
const statusFilter = ref<'all' | 'protected' | 'open'>('all');

const rows = computed<ProductRow[]>(() => {
  const byProduct = new Map(policies.value.map((p) => [p.productTypeId, p]));
  return products.value.map((p) => ({ ...p, policy: byProduct.get(p.id) || null }));
});
const filteredRows = computed(() => rows.value.filter((r) => {
  if (keyword.value && !r.name.includes(keyword.value.trim())) return false;
  if (statusFilter.value === 'protected') return !!r.policy;
  if (statusFilter.value === 'open') return !r.policy;
  return true;
}));

const userById = computed(() => new Map(users.value.map((u) => [u.id, u])));
function userLabel(u: DirectoryUser): string {
  const name = u.fullName || u.username || `#${u.id}`;
  return u.department ? `${name}（${u.department}）` : name;
}
function userName(id?: number | null): string {
  if (id == null) return '—';
  const u = userById.value.get(id);
  return u ? (u.fullName || u.username || `#${id}`) : `#${id}`;
}
function modeLabel(mode: string): string {
  return MODE_LABELS[mode as ProcessSecretStorageMode] || mode;
}
function formatTime(s?: string | null): string {
  return s ? s.replace('T', ' ').substring(0, 16) : '—';
}
function isExpired(s?: string | null): boolean {
  return !!s && new Date(s.replace(' ', 'T')).getTime() <= Date.now();
}
function isPastDate(d: Date): boolean {
  return d.getTime() < Date.now() - DAY;
}

// 窄屏 (< 760px) 抽屉占满宽度, 否则 720px 的抽屉会把表格挤出横向滚动条
const viewportWidth = ref(window.innerWidth);
function onResize() { viewportWidth.value = window.innerWidth; }
window.addEventListener('resize', onResize);
onBeforeUnmount(() => window.removeEventListener('resize', onResize));
const drawerSize = computed(() => (viewportWidth.value < 760 ? '100%' : '720px'));

function onMoreCommand(cmd: string, row: ProductRow) {
  if (cmd === 'edit') openPolicyDialog(row);
  else if (cmd === 'disable') void confirmDisable(row);
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const [p, pol, u] = await Promise.all([
      listActiveProductTypes(factoryId.value),
      listProcessSecretPolicies(factoryId.value),
      listUserDirectory(factoryId.value),
    ]);
    products.value = p.success && Array.isArray(p.data) ? p.data : [];
    policies.value = pol.success && Array.isArray(pol.data) ? pol.data : [];
    users.value = u.success && Array.isArray(u.data) ? u.data : [];
  } finally {
    loading.value = false;
  }
}

// ---------------------------------------------------------------- 设置 / 修改
const policyDialog = reactive({
  open: false,
  saving: false,
  product: null as ProductRow | null,
  storageMode: 'CLOUD_RESTRICTED' as ProcessSecretStorageMode,
  ownerUserId: null as number | null,
});
const policyDialogTitle = computed(() =>
  `${policyDialog.product?.policy ? '修改工艺保密' : '设为工艺保密'} — ${policyDialog.product?.name || ''}`);
const modeChangeWarning = computed(() => {
  const before = policyDialog.product?.policy?.storageMode;
  if (before === 'CLOUD_RESTRICTED' && policyDialog.storageMode === 'CUSTOMER_HELD') {
    return '改为「核心工艺不入云」后，该产品现有的全部授权会立即失效。';
  }
  if (before === 'CUSTOMER_HELD' && policyDialog.storageMode === 'CLOUD_RESTRICTED') {
    return '改回「工艺保密访问」后，已登记的客户侧工艺包会全部停用。';
  }
  return '';
});

function openPolicyDialog(row: ProductRow) {
  policyDialog.product = row;
  policyDialog.storageMode = row.policy?.storageMode || 'CLOUD_RESTRICTED';
  policyDialog.ownerUserId = row.policy?.ownerUserId ?? currentUserId.value;
  policyDialog.open = true;
}

async function savePolicy() {
  const row = policyDialog.product;
  if (!row || !policyDialog.ownerUserId) return;
  policyDialog.saving = true;
  try {
    const res = await upsertProcessSecretPolicy(factoryId.value, row.id, {
      storageMode: policyDialog.storageMode,
      ownerUserId: policyDialog.ownerUserId,
      expectedPolicyVersion: row.policy?.policyVersion ?? null,
    });
    if (res.success) {
      ElMessage.success(`「${row.name}」已设为${MODE_LABELS[policyDialog.storageMode]}`);
      policyDialog.open = false;
      await load();
    }
  } finally {
    policyDialog.saving = false;
  }
}

async function confirmDisable(row: ProductRow) {
  if (!row.policy) return;
  try {
    await ElMessageBox.confirm(
      `关闭后「${row.name}」的配方和工艺对有产品权限的人恢复可见，该产品的全部授权同时撤销。`,
      `关闭工艺保密 — ${row.name}`,
      { type: 'warning', confirmButtonText: '确认关闭', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  const res = await disableProcessSecretPolicy(factoryId.value, row.id, row.policy.policyVersion);
  if (res.success) {
    ElMessage.success(`已关闭「${row.name}」的工艺保密`);
    await load();
  }
}

// ---------------------------------------------------------------- 授权
const grantDrawer = reactive({
  open: false,
  loading: false,
  product: null as ProductRow | null,
  grants: [] as ProcessSecretGrant[],
});
const grantForm = reactive({
  granteeId: null as number | null,
  canViewFull: true,
  canExport: false,
  canEdit: false,
  purposeType: '' as string,
  purposeOther: '',
  expiresAt: '' as string,
  saving: false,
});
const grantableUsers = computed(() =>
  users.value.filter((u) => u.id !== grantDrawer.product?.policy?.ownerUserId));
const grantPurpose = computed(() =>
  grantForm.purposeType === '其他' ? grantForm.purposeOther.trim() : grantForm.purposeType);
const grantInvalidReason = computed(() => {
  if (!grantForm.granteeId) return '请选择被授权人';
  if (!grantForm.canViewFull && !grantForm.canExport && !grantForm.canEdit) return '至少勾选一项权限';
  if (!grantPurpose.value) return '请选择用途';
  if (!grantForm.expiresAt) return '请选择到期时间';
  if (new Date(grantForm.expiresAt).getTime() <= Date.now()) return '到期时间必须晚于现在';
  return '';
});
const grantValid = computed(() => !grantInvalidReason.value);

function resetGrantForm() {
  Object.assign(grantForm, {
    granteeId: null, canViewFull: true, canExport: false, canEdit: false,
    purposeType: '', purposeOther: '', expiresAt: '', saving: false,
  });
}

async function loadGrants() {
  if (!grantDrawer.product) return;
  grantDrawer.loading = true;
  try {
    const res = await listProcessSecretGrants(factoryId.value, grantDrawer.product.id);
    grantDrawer.grants = res.success && Array.isArray(res.data) ? res.data : [];
  } finally {
    grantDrawer.loading = false;
  }
}

function openGrantDrawer(row: ProductRow) {
  grantDrawer.product = row;
  grantDrawer.grants = [];
  resetGrantForm();
  grantDrawer.open = true;
  void loadGrants();
}

async function saveGrant() {
  const row = grantDrawer.product;
  if (!row || !grantValid.value || !grantForm.granteeId) return;
  grantForm.saving = true;
  try {
    const res = await createProcessSecretGrant(factoryId.value, row.id, {
      granteeType: 'FACTORY_USER',
      granteeId: grantForm.granteeId,
      canViewFull: grantForm.canViewFull,
      canExport: grantForm.canExport,
      canEdit: grantForm.canEdit,
      purpose: grantPurpose.value,
      expiresAt: `${grantForm.expiresAt}+08:00`,
    });
    if (res.success) {
      ElMessage.success(`已授权 ${userName(grantForm.granteeId)}`);
      resetGrantForm();
      await loadGrants();
    }
  } finally {
    grantForm.saving = false;
  }
}

async function confirmRevoke(grant: ProcessSecretGrant) {
  try {
    await ElMessageBox.confirm(
      `撤销后 ${userName(grant.granteeId)} 立即不能再查看「${grantDrawer.product?.name || ''}」的完整工艺。`,
      '撤销授权',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  const res = await revokeProcessSecretGrant(factoryId.value, grant.id);
  if (res.success) {
    ElMessage.success('已撤销');
    await loadGrants();
  }
}

// ---------------------------------------------------------------- 访问记录
const auditDrawer = reactive({
  open: false,
  loading: false,
  product: null as ProductRow | null,
  events: [] as ProcessSecretAccessEvent[],
  grants: [] as ProcessSecretGrant[],
});
const auditRows = computed(() => {
  const grantById = new Map(auditDrawer.grants.map((g) => [g.id, g]));
  const ctx = { userName, modeLabel, formatTime, grantById: (id: number) => grantById.get(id) };
  return auditDrawer.events.map((event) => {
    const d = describeAuditReason(event.reason, ctx);
    return { event, description: d.text, actionLabel: d.action || ACTION_LABELS[event.action] || event.action };
  });
});

async function openAuditDrawer(row: ProductRow) {
  auditDrawer.product = row;
  auditDrawer.events = [];
  auditDrawer.grants = [];
  auditDrawer.open = true;
  auditDrawer.loading = true;
  try {
    // 授权列表用来把「GRANT #3」换成被授权人、权限、用途和到期时间; 拿不到时说明里只少这些细节
    const [events, grants] = await Promise.all([
      listProcessSecretAuditEvents(factoryId.value, row.id),
      row.policy?.storageMode === 'CLOUD_RESTRICTED'
        ? listProcessSecretGrants(factoryId.value, row.id).catch((): null => null)
        : Promise.resolve(null),
    ]);
    auditDrawer.events = events.success && Array.isArray(events.data) ? events.data : [];
    auditDrawer.grants = grants?.success && Array.isArray(grants.data) ? grants.data : [];
  } finally {
    auditDrawer.loading = false;
  }
}

onMounted(load);
</script>

<style scoped>
.process-secret-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-header h2 { margin: 0; font-size: 18px; }
.intro { margin-bottom: 12px; }
.toolbar { display: flex; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.search { width: 240px; }
.muted { color: #909399; }
.empty { padding: 24px 0; color: #909399; }
.full { width: 100%; }
.hint { font-size: 12px; color: #909399; line-height: 1.6; margin-top: 4px; }
.mode-group { display: flex; flex-direction: column; align-items: stretch; gap: 10px; }
.mode-option { height: auto; align-items: flex-start; white-space: normal; margin-right: 0; }
.mode-title { font-weight: 600; }
.mode-desc { font-size: 12px; color: #606266; line-height: 1.6; }
.warn { margin-bottom: 12px; }
.grant-form { margin-bottom: 16px; }
.grant-table { margin-top: 8px; }
.other { margin-top: 8px; }
.reason { margin-left: 12px; font-size: 12px; }
.audit-hint { margin: 0 0 12px; }
.row-actions { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.row-actions .el-button + .el-button { margin-left: 8px; }
:deep(.danger-item) { color: var(--el-color-danger); }
</style>
