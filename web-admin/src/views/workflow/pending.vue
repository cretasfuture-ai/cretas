<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { MODULE_LABELS } from './moduleLabels';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { useWorkspaceStore } from '@/store/modules/workspace';
import { oaInvalidatedModules } from '@/utils/workspaceInvalidation';
import { enumLabel } from '@/utils/enumDisplay';
import { approverHint, currentNodeText } from './nodeLabel';
import { usePermissionStore } from '@/store/modules/permission';
import OrderPeekDrawer from '@/views/finance/components/OrderPeekDrawer.vue';
import { isRouteReachable, PEEKABLE_BY_MODULE } from './approvalDocReach';

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
  /** ⚠️ 未税额 —— 配合下面 businessTaxAmount 才能算出含税, 不能单独当付款额显示。 */
  businessAmount?: number | string | null;
  /**
   * 缺陷 30 续 (2026-09-01): 目前只有 PURCHASE_ORDER 下发; 无税额概念/暂未接入时为 null。
   * ⛔ null 不是 0 —— 不许据此假装「这单没有税」。
   */
  businessTaxAmount?: number | string | null;
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
  reportCorrectionDetails?: {
    correctionId?: string;
    reportId?: number;
    planId?: string | null;
    batchId?: number | null;
    processCategory?: string | null;
    reason?: string;
    before?: Record<string, unknown>;
    after?: Record<string, unknown>;
  };
}

const authStore = useAuthStore();
const workspace = useWorkspaceStore();
const route = useRoute();
const router = useRouter();
const permissionStore = usePermissionStore();
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
  'PRODUCTION_REPORT_CORRECTION',
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
    if (await handleStaleDefinition(error, row)) return;
    handleCatchError(error, '审批操作失败');
  } finally {
    operatingId.value = '';
  }
}

/**
 * 缺陷 27 —— 「点了没反应」的那条路。
 *
 * <h3>为什么原来什么都不显示</h3>
 * 后端明确回了 409 `WORKFLOW_BOUND_DEFINITION_CHANGED`, 但两层各自以为对方会说话:
 * ① `request.ts` 的 `isVanillaOptimisticLock`(409 且无 actionHint 无 hintTarget)
 *    把 toast 整条吞掉; ② `handleCatchError` 见 `err.status` 有值就闭嘴, 认为
 *    拦截器已经显示过了。⇒ 用户唯一能做的就是反复点, 而每次都同样无声。
 * 后端现在给这条 409 带上了 actionHint, 它才越得过①。
 *
 * <h3>为什么恢复入口放在这里</h3>
 * 这是用户**撞上**这个故障的那一刻 —— 不是某个只有平台管理员知道的深层菜单。
 * 摘要含节点 position, 画布上挪一像素就会触发, 所以它是常见故障, 入口必须就在手边。
 * 防呆 Rule 5: dead-end 必须给下一步动作, ⛔ 不能只告诉用户"不行"。
 *
 * @returns true 表示这个错误已经被处理干净(已经给了用户明确说法与下一步)
 */
async function handleStaleDefinition(error: unknown, row: PendingApproval): Promise<boolean> {
  const err = error as { code?: string; status?: number };
  if (err?.status !== 409 || err?.code !== 'WORKFLOW_BOUND_DEFINITION_CHANGED') return false;

  const what = `${businessCodeOf(row)}（${row.moduleLabel || row.moduleCode}）`;
  // ⛔ 只有能真正执行恢复的人才看到"恢复"按钮 —— 给看不到权限的人一个点了必 403 的
  //    按钮, 只是把死胡同往后挪一格。后端同样按这两个角色守门。
  if (!authStore.hasRole(['factory_super_admin', 'platform_admin'])) {
    await ElMessageBox.alert(
      `${what} 绑定的审批流已经被修改过，这张单在旧流程上无法继续审批。\n\n`
        + '请联系工厂管理员，对该单据执行「按当前审批流重新发起」，之后你会重新收到待办。',
      '该单据的审批流已变更',
      { confirmButtonText: '知道了', type: 'warning' },
    );
    return true;
  }

  let reason: string;
  try {
    const prompt = await ElMessageBox.prompt(
      `${what} 绑定的审批流已经被修改过，无法在旧流程上继续。\n\n`
        + '继续将：作废当前审批实例 → 按【当前】审批流从头重新发起。\n'
        + '⚠️ 这张单此前已通过的审批会作废，需要重新审一遍；此次操作会记录在审批历史里。',
      '按当前审批流重新发起',
      {
        confirmButtonText: '确认重新发起',
        cancelButtonText: '暂不处理',
        inputPlaceholder: '请填写恢复原因（会留痕，至少 5 个字）',
        inputValidator: (v: string) => (v?.trim().length ?? 0) >= 5 || '请填写恢复原因（至少 5 个字）',
      },
    );
    reason = prompt.value.trim();
  } catch {
    return true; // 用户选「暂不处理」—— 已经明确告知过了, 不再走 fallback toast
  }

  try {
    const res = await post(
      `/${factoryId.value}/workflow/instances/${row.instanceId}/restart-binding`,
      { reason, confirm: true },
    );
    if (!res.success) throw new Error(res.message || '重新发起失败');
    ElMessage.success('已按当前审批流重新发起，请重新处理该单据');
    workspace.invalidateModules(oaInvalidatedModules(row.moduleCode));
    await loadPending();
  } catch (e) {
    handleCatchError(e, '按当前审批流重新发起失败');
  }
  return true;
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

/**
 * 税额 —— 与 amountOf 同一条纪律: 取不到就是 null, ⛔ 不是 0。
 * 目前只有 PURCHASE_ORDER 下发 businessTaxAmount, 其余模块恒为 null。
 */
function taxAmountOf(row: PendingApproval): number | null {
  const raw = row.businessTaxAmount;
  if (raw === null || raw === undefined || raw === '') return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

/**
 * 缺陷 30 续 —— owner 裁定(2026-09-01):「先算含税总额、说清税率、再倒算未税总额」。
 * 只有 amountOf(未税) 与 taxAmountOf(税额) **都**读得到时才拆得出含税 ——
 * ⛔ 税额未知(null)时不许当 0 处理去算一个「含税」出来, 那是把「不知道」悄悄说成
 * 「没有税」, 和「未税额本来就取不到时不许显示 0」是同一条纪律的另一半。
 */
function taxInclusiveAmountOf(row: PendingApproval): number | null {
  const base = amountOf(row);
  const tax = taxAmountOf(row);
  if (base === null || tax === null) return null;
  return base + tax;
}

/** 税率(%) —— 从「未税 + 税额」反推, 供确认框/金额列旁注「税率N%」。 */
function taxRatePercentOf(row: PendingApproval): number | null {
  const base = amountOf(row);
  const tax = taxAmountOf(row);
  if (base === null || tax === null || base <= 0) return null;
  return (tax / base) * 100;
}

function formatTaxRatePercent(row: PendingApproval): string {
  const pct = taxRatePercentOf(row);
  if (pct === null) return '—';
  const rounded = Math.round(pct * 100) / 100;
  return `${rounded}%`;
}

/**
 * 是否值得拆成「含税(税率) / 未税」两段 —— 与 buildBusinessSummary 同一条判据:
 * 税额为 0(真的免税)或未知时, 含税与未税相等或无法确定, 拆开只是徒增噪音,
 * 单一个数就够了。⛔ 不要在税额为 0/未知时也硬凑一段「税率0%」出来。
 */
function hasStatedTaxBasis(row: PendingApproval): boolean {
  const tax = taxAmountOf(row);
  return tax !== null && tax > 0;
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
  ];
  // 🔒 owner 裁定(2026-09-01):「首先肯定是计算含税总额, 然后告诉这个税率是多少,
  // 然后再计算未税总额」—— 通过前最后看一眼的这个数, 必须是含税领先。
  // ⚠️ 税额未知/为 0 时(hasStatedTaxBasis 为 false)拆成两段没有意义, 退回单一个数 ——
  // 与「金额」列同一条判据, ⛔ 两处不许各写一份。
  if (hasStatedTaxBasis(row)) {
    fields.push(
      ['金额（含税）', formatAmount(taxInclusiveAmountOf(row))],
      ['税率', formatTaxRatePercent(row)],
      ['金额（未税）', formatAmount(amountOf(row))],
    );
  } else {
    fields.push(['金额', formatAmount(amountOf(row))]);
  }
  fields.push(
    ['当前节点', currentNodeText(row)],
    ['申请人', applicant],
  );
  if (row.reportCorrectionDetails) {
    fields.push(
      ['工序', row.reportCorrectionDetails.processCategory || '—'],
      ['修正原因', row.reportCorrectionDetails.reason || '—'],
      ['修正前', reportCorrectionValues(row.reportCorrectionDetails.before)],
      ['修正后', reportCorrectionValues(row.reportCorrectionDetails.after)],
    );
  }
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

function reportCorrectionValues(values?: Record<string, unknown>): string {
  if (!values) return '—';
  const text = (value: unknown) => value === null || value === undefined || value === '' ? '—' : String(value);
  return `产出 ${text(values.outputQuantity)} / 良品 ${text(values.goodQuantity)} / 不良 ${text(values.defectQuantity)}`
    + ` / 人数 ${text(values.totalWorkers)} / 工时 ${text(values.totalWorkMinutes)} / 备注 ${text(values.notes)}`;
}

/** 无金额的排在最后, 免得一堆「—」占住大额单的位置。 */
/** 排序要按**列里实际显示的那个主数**排 —— 有含税就按含税排, 否则退回未税, 与「金额」列同一条判据。 */
function primaryAmountOf(row: PendingApproval): number | null {
  return hasStatedTaxBasis(row) ? taxInclusiveAmountOf(row) : amountOf(row);
}

function sortByAmount(a: PendingApproval, b: PendingApproval): number {
  const x = primaryAmountOf(a);
  const y = primaryAmountOf(b);
  if (x === null && y === null) return 0;
  if (x === null) return 1;
  if (y === null) return -1;
  return x - y;
}

/** 点单号 —— 按模块跳到对应详情。⚠️ 原来只有调拨单接了跳转, 采购单没接(M-12 根因)。 */
function openDetail(row: PendingApproval): void {
  const id = row.businessEntityId;
  if (!id) return;
  const routeByModule: Record<string, string> = {
    PURCHASE_ORDER: `/procurement/orders/${id}`,
    SALES_ORDER: `/sales/orders/${id}`,
    INVENTORY_TRANSFER: `/transfer/${id}`,
    // 🔴 2026-08-25 prod 实测: 库存调整没接进来, 厂长点「详情」只得到
    //    「暂未接入单据详情页」—— 而他正被要求批准一次真实的库存数量调整。
    //    盘点没有单条详情路由, 目标是列表页并带上单号让它自己定位。
    INVENTORY_ADJUSTMENT: `/warehouse/stocktakes?stocktakeId=${id}`,
    PRODUCTION_REPORT_CORRECTION: `/production/approval?reportId=${row.reportCorrectionDetails?.reportId || ''}`,
  };
  const target = routeByModule[row.moduleCode];
  if (!target) {
    // ⛔ 不静默失败: 说清楚是「这个业务域还没接详情页」, 而不是让人以为点坏了。
    ElMessage.info(`「${row.moduleLabel || row.moduleCode}」暂未接入单据详情页`);
    return;
  }
  // 缺陷 30 (2026-09-01 LIUSHANMEN 生产): 审批链把采购单派给财务审, 而财务点开
  // 自己审的单必跳 /403。⛔ 修法不是把那条路由开给财务(见 approvalDocReach.ts),
  //    而是够得着就跳转、够不着就开 owner 定的那个只读速览抽屉(不注册路由)。
  openApprovalDoc(target, row.moduleCode, id, row.moduleLabel || row.moduleCode);
}
// ── 缺陷 30: 够不着目标路由时的只读速览 ────────────────────────────────
const peekVisible = ref(false);
const peekOrderId = ref<string | null>(null);
const peekKind = ref<'sales' | 'purchase'>('purchase');

/** 打开单据: 够得着就跳转, 够不着就退到只读抽屉, 都不行才说清楚为什么。 */
function openApprovalDoc(target: string, moduleCode: string, entityId: string, label: string): void {
  if (isRouteReachable(router, authStore.currentRole, target, permissionStore.canAccess)) {
    router.push(target);
    return;
  }
  const kind = PEEKABLE_BY_MODULE[moduleCode];
  if (kind) {
    peekKind.value = kind;
    peekOrderId.value = entityId;
    peekVisible.value = true;
    return;
  }
  // ⛔ 不静默失败, 也不再把人送去 /403 丢掉上下文。
  ElMessage.info(`你没有查看「${label}」详情的权限, 且该单据暂无只读速览`);
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
            <div v-if="row.reportCorrectionDetails" class="correction-summary">
              {{ row.reportCorrectionDetails.processCategory || '工序未标注' }} · {{ row.reportCorrectionDetails.reason || '未填写原因' }}
            </div>
          </template>
        </el-table-column>
        <!--
          缺陷 30 续 (2026-09-01, LIUSHANMEN 生产, 财务主管 lsm009): 这一列原来只显示
          formatAmount(amountOf(row)) —— 而 amountOf 取的是 businessAmount, **未税**额,
          且没有任何标注。审批人看到「¥750.00」会读成要付 750, 实付含税 847.50。

          🔒 owner 裁定:「首先肯定是计算含税总额, 然后告诉这个税率是多少, 然后再计算未税
          总额」⇒ 含税领先(粗体主行), 未税+税率作为小字副行殿后。
          ⚠️ 税额未知/为 0 (hasStatedTaxBasis=false) 时拆分没有意义, 退回单一个未税数,
          与确认框(approveConfirmMessage)同一条判据, ⛔ 不另写一份。
        -->
        <el-table-column label="金额" width="190" align="right" sortable :sort-method="sortByAmount">
          <template #default="{ row }">
            <template v-if="amountOf(row) !== null">
              <template v-if="hasStatedTaxBasis(row)">
                <div class="amount-cell">{{ formatAmount(taxInclusiveAmountOf(row)) }}</div>
                <div class="amount-sub">税率{{ formatTaxRatePercent(row) }} · 未税{{ formatAmount(amountOf(row)) }}</div>
              </template>
              <div v-else class="amount-cell">{{ formatAmount(amountOf(row)) }}</div>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="往来单位" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ counterpartyOf(row) || '—' }}</template>
        </el-table-column>
        <el-table-column label="修正内容" min-width="280">
          <template #default="{ row }">
            <div v-if="row.reportCorrectionDetails" class="correction-summary">
              <div>前：{{ reportCorrectionValues(row.reportCorrectionDetails.before) }}</div>
              <div>后：{{ reportCorrectionValues(row.reportCorrectionDetails.after) }}</div>
              <div>原因：{{ row.reportCorrectionDetails.reason || '—' }}</div>
            </div>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <!--
          「当前节点」已说明该谁审(如「财务审批」), 原来紧挨着的「授权角色」列写的是
          「财务主管」—— 同一件事说两遍。Steve:「授权角色我觉得也没必要, 如果要具体到人,
          就在财务审批后面加上他的名字就好了。」⇒ 删掉该列, 需指到人时并进本列。
        -->
        <el-table-column prop="currentNodeLabel" label="当前节点" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            {{ currentNodeText(row)
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
  <OrderPeekDrawer v-model:visible="peekVisible" :order-id="peekOrderId" :kind="peekKind" />
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
/* 缺陷 30 续: 含税领先(主行) + 未税/税率殿后(副行, 小字弱化) —— 右对齐跟主行一致。 */
.amount-sub { font-size: 12px; color: var(--el-text-color-secondary); text-align: right; white-space: nowrap; }
.correction-summary { font-size: 12px; line-height: 1.55; color: var(--el-text-color-regular); }
.header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.header h2 { margin: 0 0 6px; }
.header p { margin: 0; color: var(--el-text-color-secondary); }
.el-pagination { margin-top: 16px; justify-content: flex-end; }
:deep(.deep-linked-approval-row > td.el-table__cell) { background: var(--el-color-primary-light-9) !important; }
</style>
