<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { useWorkspaceStore } from '@/store/modules/workspace';
import { oaInvalidatedModules } from '@/utils/workspaceInvalidation';
import { enumLabel } from '@/utils/enumDisplay';

/**
 * 业务类型中文名 —— 筛选下拉和表格列共用同一份。
 * 之前下拉里写死了四个中文名, 表格列却走 enumLabel 的通用字典(只有 PO/SO),
 * 于是调拨待办在列表里显示成「未知状态（INVENTORY_TRANSFER）」——
 * 同一个页面对同一个编码给出两种说法。
 */
/**
 * ⚠️ 这不是事实来源, 只是后端没下发 moduleLabel 时的离线兜底。
 *
 * 权威表在后端 `DecisionTypeMetadataRegistry`(30+ 个 moduleCode, 各带 chineseName),
 * 已通过待办 DTO 的 `moduleLabel` 字段下发。**不要在这里加新码** ——
 * 这份表历史上只覆盖了权威表的 4 个, 另外 20 多个码全部渲染成「未知状态（X）」,
 * 客户截图里的「未知状态（BUDGET）」就是这么来的。加在这里只会再漂一次。
 */
const MODULE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '采购订单',
  SALES_ORDER: '销售订单',
  INVENTORY_TRANSFER: '库存调拨',
  INVENTORY_ADJUSTMENT: '库存盘点',
};
import { handleCatchError } from '@/utils/errorToast';
import { formatDateTime } from '@/utils/dateFormat';

interface PendingApproval {
  instanceId: string;
  moduleCode: string;
  businessEntityId: string;
  /** 拼好的一句话, 作**兜底** —— 未下发结构化字段的模块仍靠它显示。 */
  businessSummary?: string;
  /** M-12 结构化字段: 单号 / 金额 / 往来单位分开下发, ⛔ 不要从 businessSummary 反解。 */
  businessCode?: string;
  businessAmount?: number | string | null;
  counterpartyName?: string | null;
  currentNodeId?: string;
  currentNodeLabel?: string;
  approverRoles?: string[];
  initiatedAt?: string;
  initiatedByUsername?: string;
  /** 业务类型中文名, 后端按权威表下发; 解析不出时为空, 由 MODULE_LABELS 兜底。 */
  moduleLabel?: string;
  /** 由定时任务等系统流程发起, 没有人类申请人 (后端判据: initiatedBy == null)。 */
  systemInitiated?: boolean;
}

const authStore = useAuthStore();
const workspace = useWorkspaceStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);
const loading = ref(false);
const operatingId = ref('');
const rows = ref<PendingApproval[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
// 只列后端 executeDomainAction 里真有分支的域 —— 这里加一个码不会让它变得可操作,
// 只会让用户点了之后撞 422「该业务域尚未迁移到统一 OA 动作适配器」。
// 📌 BUDGET(会计期间结账) 刻意不在此列: 它的通过 = 执行月度关账, 已拆到独立 PR 等终审。
const ACTIONABLE_MODULE_CODES = new Set([
  'PURCHASE_ORDER', 'SALES_ORDER', 'INVENTORY_TRANSFER', 'INVENTORY_ADJUSTMENT',
]);
const moduleCode = ref('');
const focusedInstanceId = ref('');
let mounted = false;

function queryValue(value: unknown): string {
  const normalized = Array.isArray(value) ? value[0] : value;
  return typeof normalized === 'string' ? normalized : '';
}

function canAct(row: PendingApproval): boolean {
  return ACTIONABLE_MODULE_CODES.has(row.moduleCode);
}

async function loadPending() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/workflow/instances/pending`, {
      params: {
        page: page.value,
        size: size.value,
        moduleCode: moduleCode.value || undefined,
      },
    });
    const data = res.data || {};
    rows.value = data.items || data.content || data.records || [];
    total.value = Number(data.total ?? data.totalElements ?? rows.value.length);
  } catch (error) {
    handleCatchError(error, '待审批任务加载失败');
  } finally {
    loading.value = false;
  }
}

watch(
  () => [route.query.moduleCode, route.query.instanceId],
  async ([moduleCodeQuery, instanceIdQuery]) => {
    const requestedModuleCode = queryValue(moduleCodeQuery);
    const nextModuleCode = ACTIONABLE_MODULE_CODES.has(requestedModuleCode)
      ? requestedModuleCode
      : '';
    const moduleChanged = nextModuleCode !== moduleCode.value;
    moduleCode.value = nextModuleCode;
    focusedInstanceId.value = queryValue(instanceIdQuery);
    if (mounted && moduleChanged) {
      page.value = 1;
      await loadPending();
    }
  },
  { immediate: true },
);

function rowClassName({ row }: { row: PendingApproval }): string {
  return row.instanceId === focusedInstanceId.value ? 'deep-linked-approval-row' : '';
}

async function act(row: PendingApproval, action: 'APPROVE' | 'REJECT') {
  if (operatingId.value) return;
  let notes = '';
  try {
    if (action === 'REJECT') {
      const result = await ElMessageBox.prompt('请填写驳回原因', '驳回审批', {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputValidator: value => Boolean(value?.trim()) || '驳回原因不能为空',
      });
      notes = result.value.trim();
    } else {
      // M-13 (2026-08-20, owner 原话:「点击通过的时候必须要浏览一下它整体的内容才可以点击
      // 通过」): 不能是一句空洞的"确定吗?" —— 财务在批几十万到几百万的采购单, 这一步的
      // 目的是让金额等关键信息在按下去之前真的被看到一次。
      await ElMessageBox.confirm(
        approveConfirmMessage(row),
        '通过前请确认单据信息',
        {
          confirmButtonText: '审批通过',
          cancelButtonText: '取消',
          dangerouslyUseHTMLString: true,
        },
      );
    }
  } catch {
    return;
  }
  operatingId.value = row.instanceId;
  try {
    const res = await post(
      `/${factoryId.value}/workflow/instances/${row.instanceId}/actions`,
      {
        action,
        notes,
        idempotencyKey: `oa-${action.toLowerCase()}-${row.instanceId}-${row.currentNodeId}`,
        expectedNodeId: row.currentNodeId,
      },
    );
    if (!res.success) throw new Error(res.message || '审批操作失败');
    ElMessage.success(action === 'APPROVE' ? '审批已通过' : '审批已驳回');
    /*
     * 🔴 2026-08-20 owner 实测:「点击通过以后, 再点到财务管理里面还是待审批; 到仓储管理的
     *    入库任务与批次点进去没有任何东西。刷新一下网页就更新好了。」
     *
     * 本页自己有 loadPending() 会刷新, 而**别的标签**停在 <keep-alive> 缓存里 ——
     * 切回去只触发 activated, ⛔ 不会二次 onMounted, 于是显示的是批之前的数据。
     * 这里把受影响业务域的其它标签标记为失效, 它们下次被切回来时才真正重新取数
     * (惰性: 这一行不发任何请求)。
     *
     * ⚠️ 放在 res.success 之后: 审批失败时数据没变, 让别的标签白取一次数是纯浪费。
     * ⛔ 不要改成在每个页面各写一个 onActivated 重新取数 —— 那是同一件事修 353 份。
     */
    workspace.invalidateModules(oaInvalidatedModules(row.moduleCode));
    await loadPending();
    // 客户 2026-07-30「审核后 库存没有过来」: 调拨审批通过 ≠ 库存过账, 还要回单据点一次
    // 「确认调拨入库」。批完人停在 OA 列表, 这里不导航就是 dead-end (防呆 Rule 5) ——
    // 实测客户 3 张单卡在 APPROVED, 最早一张卡了 6 周。
    if (action === 'APPROVE' && row.moduleCode === 'INVENTORY_TRANSFER' && row.businessEntityId) {
      try {
        await ElMessageBox.confirm(
          '审批通过后库存还没有过账。需要回到调拨单点一次「确认调拨入库」，调出仓才会扣减、调入仓才会收到货。',
          '还差最后一步：确认入库',
          { confirmButtonText: '去确认入库', cancelButtonText: '稍后处理', type: 'warning' },
        );
        router.push(`/transfer/${row.businessEntityId}`);
      } catch {
        // 选择"稍后处理": 不跳转; 单据列表与首页待办仍会持续提示。
      }
    }
  } catch (error) {
    handleCatchError(error, '审批操作失败');
  } finally {
    operatingId.value = '';
  }
}

/* ───────────────── M-12: 单号 / 金额 / 往来单位 各归各位 ───────────────── */

/** 单号 —— 后端结构化字段优先; 没有就回落那句拼好的摘要(⛔ 不能变空白)。 */
function businessCodeOf(row: PendingApproval): string {
  return row.businessCode || row.businessSummary || '查看单据';
}

function counterpartyOf(row: PendingApproval): string {
  return row.counterpartyName || '';
}

/**
 * 金额 —— 返回 number 或 null。
 * ⛔ 取不到时返 null 而不是 0: 「这单没有金额概念」(如库存调拨) 与「这单 0 元」
 * 是两件事, 显示成 0 会让审批人以为是一张零元单。
 */
function amountOf(row: PendingApproval): number | null {
  const raw = row.businessAmount;
  if (raw === null || raw === undefined || raw === '') return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

function formatAmount(value: number | null): string {
  if (value === null) return '—';
  return `¥${value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * M-13: 「通过」二次确认框的正文 —— 把这单的关键信息摆出来, 而不是只问一句"确定吗?"。
 * 复用 businessCodeOf / counterpartyOf / amountOf / formatAmount, ⛔ 不再拼一份新的口径。
 * dangerouslyUseHTMLString 渲染, 所以每个值都要转义 —— 往来单位/单号来自后端数据, 不可信。
 */
function approveConfirmMessage(row: PendingApproval): string {
  const moduleText = row.moduleLabel || enumLabel(row.moduleCode, MODULE_LABELS);
  const applicant = row.systemInitiated ? '系统自动发起' : (row.initiatedByUsername || '—');
  const fields: Array<[string, string]> = [
    ['业务类型', moduleText],
    ['业务单号', businessCodeOf(row)],
    ['往来单位', counterpartyOf(row) || '—'],
    ['金额', formatAmount(amountOf(row))],
    ['当前节点', row.currentNodeLabel || '—'],
    ['申请人', applicant],
  ];
  // ⚠️ ElMessageBox 挂在 App 根节点之外, 本组件的 scoped CSS 够不着它 —— 用行内样式,
  // 不要指望 <style scoped> 里的 class 生效。
  const rowsHtml = fields
    .map(([label, value]) => (
      `<div style="display:flex;justify-content:space-between;gap:16px;padding:4px 0;">`
      + `<span style="color:var(--el-text-color-secondary);">${escapeHtml(label)}</span>`
      + `<span style="font-weight:500;">${escapeHtml(value)}</span>`
      + `</div>`
    ))
    .join('');
  return `<div style="text-align:left;">${rowsHtml}</div>`;
}

/** 无金额的排在最后, 免得一堆「—」占住大额单的位置。 */
function sortByAmount(a: PendingApproval, b: PendingApproval): number {
  const x = amountOf(a);
  const y = amountOf(b);
  if (x === null && y === null) return 0;
  if (x === null) return 1;
  if (y === null) return -1;
  return x - y;
}

/**
 * 「当前节点」后面那句「· 财务主管」。
 * 原来这是独立一列「授权角色」, 与「当前节点=财务审批」说的是同一件事(M-12)。
 * ⇒ 并进节点列; 只有当它**确实带来新信息**(与节点名不同)时才显示。
 */
function approverHint(row: PendingApproval): string {
  const roles = (row.approverRoles || []).map((role) => enumLabel(role)).filter(Boolean);
  if (!roles.length) return '';
  const text = roles.join('、');
  return text === row.currentNodeLabel ? '' : text;
}

/** 点单号 —— 按模块跳到对应详情。⚠️ 原来只有调拨单接了跳转, 采购单没接(M-12 根因)。 */
function openDetail(row: PendingApproval): void {
  const id = row.businessEntityId;
  if (!id) return;
  const routeByModule: Record<string, string> = {
    PURCHASE_ORDER: `/procurement/orders/${id}`,
    SALES_ORDER: `/sales/orders/${id}`,
    INVENTORY_TRANSFER: `/transfer/${id}`,
  };
  const target = routeByModule[row.moduleCode];
  if (target) {
    router.push(target);
    return;
  }
  // ⛔ 不静默失败: 说清楚是「这个业务域还没接详情页」, 而不是让人以为点坏了。
  ElMessage.info(`「${row.moduleLabel || row.moduleCode}」暂未接入单据详情页`);
}

onMounted(async () => {
  mounted = true;
  await loadPending();
});
</script>

<template>
  <div class="approval-center-page">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div>
            <h2>OA 审批中心</h2>
            <p>这里只显示当前账号有权处理的本工厂待办。</p>
          </div>
          <el-select v-model="moduleCode" clearable placeholder="全部业务类型" style="width: 190px" @change="page = 1; loadPending()">
            <el-option
              v-for="(label, code) in MODULE_LABELS"
              :key="code"
              :label="label"
              :value="code"
            />
          </el-select>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="rows"
        :row-class-name="rowClassName"
        border
        stripe
        empty-text="暂无待您审批的任务"
      >
        <!-- 权威表在后端, moduleLabel 由 DTO 下发; MODULE_LABELS 仅兜底 (见其定义处注释) -->
        <el-table-column label="业务类型" width="120">
          <template #default="{ row }">{{ row.moduleLabel || enumLabel(row.moduleCode, MODULE_LABELS) }}</template>
        </el-table-column>
        <!--
          🔴 M-12 (2026-08-20, 财务主管实测): 这一列原来是后端拼好的一句话
          businessSummary = "PO-20260820-0002 ¥35000.00 (北京飞熊)" —— 点不动、展不开。
          财务看到的全部信息就是这一句, 然后被要求「通过 / 驳回」。
          Steve:「我作为一个财务, 看到这些信息是不够的, 甚至看不到价格是多少。」

          ⇒ 拆成三列, 各归各位。金额单独成列后才能右对齐 + 排序 ——
             审批的人最想做的就是按金额排, 先看大额的。

          ⚠️ businessSummary 保留为兜底: 后端未下发结构化字段的模块仍显示原来那句话,
          ⛔ 不要因为拆列就让它们变空白。
        -->
        <el-table-column label="业务单据" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button v-if="row.businessEntityId" link type="primary" @click="openDetail(row)">
              {{ businessCodeOf(row) }}
            </el-button>
            <span v-else>{{ row.businessSummary || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right" sortable :sort-method="sortByAmount">
          <template #default="{ row }">
            <span v-if="amountOf(row) !== null" class="amount-cell">{{ formatAmount(amountOf(row)) }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="往来单位" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ counterpartyOf(row) || '—' }}</template>
        </el-table-column>
        <!--
          「当前节点」已说明该谁审(如「财务审批」), 原来紧挨着的「授权角色」列写的是
          「财务主管」—— 同一件事说两遍。Steve:「授权角色我觉得也没必要, 如果要具体到人,
          就在财务审批后面加上他的名字就好了。」⇒ 删掉该列, 需指到人时并进本列。
        -->
        <el-table-column prop="currentNodeLabel" label="当前节点" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.currentNodeLabel
            }}<template v-if="approverHint(row)"> · {{ approverHint(row) }}</template>
          </template>
        </el-table-column>
        <!--
          申请人为空有两种成因, 不能混为一谈:
            systemInitiated=true  → 定时任务等系统流程发起, 本来就没有人 (如月度会计期间结账)
            username 查不到       → 用户已删, 该显示「—」
          后端的判据是 initiatedBy == null, 不是 username 是否为空。
        -->
        <el-table-column label="申请人" width="130">
          <template #default="{ row }">
            <span v-if="row.systemInitiated" class="system-initiator">系统自动发起</span>
            <span v-else>{{ row.initiatedByUsername || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="180">
          <template #default="{ row }">{{ formatDateTime(row.initiatedAt) }}</template>
        </el-table-column>
        <!--
          操作列不再 fixed: fixed 列在 Element Plus 里是独立浮层, 宽度与主表分别计算,
          内容一换行两边就对不齐(实测调拨待办的表头与内容错位)。本表 7 列合计约 1190px,
          常规屏幕不横向滚动, 钉住操作列换不来什么, 却把对齐搞坏了。
        -->
        <!--
          M-13 (2026-08-20): 加「详情」按钮, 独立于 canAct —— 只读域的单据也该能看细节。
          原 170px 放不下三个按钮会挤成两行, 同步加宽到 220px。
        -->
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <template v-if="canAct(row)">
              <el-button link type="primary" :loading="operatingId === row.instanceId" @click="act(row, 'APPROVE')">通过</el-button>
              <el-button link type="danger" :disabled="Boolean(operatingId)" @click="act(row, 'REJECT')">驳回</el-button>
            </template>
            <el-tooltip v-else content="该业务域正在接入统一 OA，当前仅可查看审批进度">
              <el-tag type="info">只读</el-tag>
            </el-tooltip>
            <el-button v-if="row.businessEntityId" link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > size"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="loadPending"
      />
    </el-card>
  </div>
</template>

<style scoped>
.approval-center-page { padding: 20px; }
/* 系统发起没有人类申请人, 弱化处理以免被当成某个真实用户名 */
.system-initiator { color: var(--el-text-color-secondary); }
/*
  M-12: 金额右对齐 + 等宽数字 —— 上下行的小数点对齐, 才能一眼扫出哪单大。
  ⛔ 不要用普通比例字体: "¥35,000.00" 和 "¥1,081.00" 的位数一错开, 排序就白做了。
*/
.amount-cell { font-variant-numeric: tabular-nums; font-weight: 500; }
.header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.header h2 { margin: 0 0 6px; }
.header p { margin: 0; color: var(--el-text-color-secondary); }
.el-pagination { margin-top: 16px; justify-content: flex-end; }
:deep(.deep-linked-approval-row > td.el-table__cell) { background: var(--el-color-primary-light-9) !important; }
</style>
