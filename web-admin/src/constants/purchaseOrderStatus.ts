/**
 * 采购订单状态 — 精确状态文案 + 展示归类
 *
 * 与后端 `backend/java/cretas-api/.../entity/enums/PurchaseOrderStatus.java` 一一对应
 * (11 值)。Single source of truth，供 list.vue / detail.vue 共用。
 *
 * M-10 (2026-08-20 手动测试台账): 旧版 list.vue 里手写了一份只有 7 项的 statusMap，
 * 漏了 WORKFLOW_RUNNING / PENDING_FINANCE_REVIEW / FINANCE_APPROVED / FINANCE_REJECTED
 * 这 4 个（恰好全是审批环节的状态）—— 筛选下拉里没有任何一项能选中它们，F006 一张
 * FINANCE_REJECTED 的单据完全筛不出来；列表行也因为查不到映射而走了没有专属样式的
 * 兜底文案。根因是「前端手写一份状态清单，后端加状态时没人同步」（仓库规则
 * measurement-and-wiring.md 形态 D：同一个东西有两份，它一定会漂）。
 *
 * Steve 的诉求（原话）：「不应该有『财务已审核』这种状态。就是待审核 → 审核完毕以后
 * 进行中 → 完成以后直接已完成。」⇒ 后端保留 11 个精确状态（审批流/权限/流转判断都
 * 依赖它们，不能合并），前端列表页只做一层展示归类（7 档：3 个是多状态并集，另 4 个
 * 是需要单独区分的终态，不与其它状态合并）。详情页仍显示精确状态。
 *
 * ⛔ 防漂移机制（这是本文件存在的核心理由，不是顺带）：
 * `PURCHASE_ORDER_STATUS_LABELS` 和 `PURCHASE_ORDER_STATUS_TIER_MAP` 都类型标注为
 * `Record<PurchaseOrderStatusValue, ...>` —— 这是一个**完全映射类型**，TS 要求对象
 * 字面量必须显式覆盖 `PurchaseOrderStatusValue`（即 `ALL_PURCHASE_ORDER_STATUSES`）
 * 的每一个成员。以后如果只在 `ALL_PURCHASE_ORDER_STATUSES` 里加了新状态、忘了在下面
 * 两个映射表里补上对应项，TS 编译会直接在对象字面量处报错（"缺少属性 XXX"），
 * 不会像旧 statusMap 那样运行时静默走 `?.text || enumLabel(...)` 兜底——而正是那个
 * 静默兜底把 FINANCE_APPROVED 的单显示成没有专属样式的原始枚举名、且从未出现在
 * 筛选选项里。
 *
 * 另外配一道运行时兜底（`getPurchaseOrderStatusTier` / `getPurchaseOrderStatusLabel`）：
 * TS 编译时检查堵住了「前端代码里漏加映射」，但堵不住「后端已经上线了新状态、前端
 * bundle 还没重新部署」这种运行期错位——遇到映射表里确实没有的值时 console.error
 * 报出来，而不是又走一次静默兜底。
 */

export const ALL_PURCHASE_ORDER_STATUSES = [
  'DRAFT',
  'SUBMITTED',
  'WORKFLOW_RUNNING',
  'APPROVED',
  'PENDING_FINANCE_REVIEW',
  'FINANCE_APPROVED',
  'FINANCE_REJECTED',
  'PARTIAL_RECEIVED',
  'COMPLETED',
  'CANCELLED',
  'CLOSED',
] as const;

export type PurchaseOrderStatusValue = (typeof ALL_PURCHASE_ORDER_STATUSES)[number];

export type PurchaseOrderStatusTagType = 'primary' | 'success' | 'warning' | 'danger' | 'info' | '';

/** 精确状态文案（11 项）—— detail.vue / 需要精确到后端原始状态的地方用这份。 */
export interface PurchaseOrderStatusLabel {
  text: string;
  type: PurchaseOrderStatusTagType;
}

// ⛔ 防漂移核心 #1: Record<PurchaseOrderStatusValue, ...> 要求这里必须覆盖全部 11 个值。
export const PURCHASE_ORDER_STATUS_LABELS: Record<PurchaseOrderStatusValue, PurchaseOrderStatusLabel> = {
  DRAFT: { text: '草稿', type: 'info' },
  SUBMITTED: { text: '已提交', type: 'warning' },
  WORKFLOW_RUNNING: { text: '审批中', type: 'warning' },
  APPROVED: { text: '已审批', type: '' },
  PENDING_FINANCE_REVIEW: { text: '待财务审核', type: 'warning' },
  FINANCE_APPROVED: { text: '财务已审核', type: 'success' },
  FINANCE_REJECTED: { text: '财务驳回', type: 'danger' },
  PARTIAL_RECEIVED: { text: '部分收货', type: 'warning' },
  COMPLETED: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' },
  CLOSED: { text: '已关闭', type: 'info' },
};

/**
 * 展示归类（7 档）：
 *   待审核   = SUBMITTED / WORKFLOW_RUNNING / PENDING_FINANCE_REVIEW（并集）
 *   进行中   = APPROVED / FINANCE_APPROVED / PARTIAL_RECEIVED（并集）
 *   已完成   = COMPLETED
 *   草稿 / 已取消 / 已关闭 / 已驳回(FINANCE_REJECTED) —— 各自单列，不并入上面三档
 *   （都是使用者需要单独区分的终态）
 *
 * tierKey 对 1:1 的档（草稿/已完成/已驳回/已取消/已关闭）刻意取和后端枚举同名的值，
 * 方便调用方在需要真实后端状态时直接复用（例如服务端单值状态筛选，见 list.vue）。
 */
export type PurchaseOrderStatusTierKey =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'FINANCE_REJECTED'
  | 'CANCELLED'
  | 'CLOSED';

export interface PurchaseOrderStatusTier {
  tierKey: PurchaseOrderStatusTierKey;
  tierLabel: string;
  tagType: PurchaseOrderStatusTagType;
}

const TIER_DRAFT: PurchaseOrderStatusTier = { tierKey: 'DRAFT', tierLabel: '草稿', tagType: 'info' };
const TIER_PENDING_REVIEW: PurchaseOrderStatusTier = { tierKey: 'PENDING_REVIEW', tierLabel: '待审核', tagType: 'warning' };
const TIER_IN_PROGRESS: PurchaseOrderStatusTier = { tierKey: 'IN_PROGRESS', tierLabel: '进行中', tagType: '' };
const TIER_COMPLETED: PurchaseOrderStatusTier = { tierKey: 'COMPLETED', tierLabel: '已完成', tagType: 'success' };
const TIER_FINANCE_REJECTED: PurchaseOrderStatusTier = { tierKey: 'FINANCE_REJECTED', tierLabel: '已驳回', tagType: 'danger' };
const TIER_CANCELLED: PurchaseOrderStatusTier = { tierKey: 'CANCELLED', tierLabel: '已取消', tagType: 'danger' };
const TIER_CLOSED: PurchaseOrderStatusTier = { tierKey: 'CLOSED', tierLabel: '已关闭', tagType: 'info' };

// ⛔ 防漂移核心 #2: 同上，这里也必须覆盖全部 11 个值——少一个 TS 编译报错
// ("Property 'XXX' is missing in type ... but required in type 'Record<PurchaseOrderStatusValue, ...>'")。
export const PURCHASE_ORDER_STATUS_TIER_MAP: Record<PurchaseOrderStatusValue, PurchaseOrderStatusTier> = {
  DRAFT: TIER_DRAFT,
  SUBMITTED: TIER_PENDING_REVIEW,
  WORKFLOW_RUNNING: TIER_PENDING_REVIEW,
  PENDING_FINANCE_REVIEW: TIER_PENDING_REVIEW,
  APPROVED: TIER_IN_PROGRESS,
  FINANCE_APPROVED: TIER_IN_PROGRESS,
  PARTIAL_RECEIVED: TIER_IN_PROGRESS,
  COMPLETED: TIER_COMPLETED,
  FINANCE_REJECTED: TIER_FINANCE_REJECTED,
  CANCELLED: TIER_CANCELLED,
  CLOSED: TIER_CLOSED,
};

/** 筛选下拉 / 列过滤器选项（7 档，按业务流程顺序排列）。 */
export const PURCHASE_ORDER_STATUS_TIER_OPTIONS: PurchaseOrderStatusTier[] = [
  TIER_DRAFT,
  TIER_PENDING_REVIEW,
  TIER_IN_PROGRESS,
  TIER_COMPLETED,
  TIER_FINANCE_REJECTED,
  TIER_CANCELLED,
  TIER_CLOSED,
];

/**
 * 运行时兜底：查不到映射时不静默返回 undefined 就完事——console.error 报出来，
 * 方便在浏览器控制台第一时间发现「前端映射落后于后端枚举」。
 * TS 的完全映射类型已经堵住了「前端代码里漏加」，这里堵的是「后端运行时给出一个
 * 当前前端 bundle 编译时还不存在的新状态值」（部署时间差）。
 */
export function getPurchaseOrderStatusTier(status: string | null | undefined): PurchaseOrderStatusTier | undefined {
  if (!status) return undefined;
  const tier = PURCHASE_ORDER_STATUS_TIER_MAP[status as PurchaseOrderStatusValue];
  if (!tier) {
    // eslint-disable-next-line no-console
    console.error(
      `[purchaseOrderStatus] 未知的采购单状态 "${status}" —— 不在 PURCHASE_ORDER_STATUS_TIER_MAP 里。` +
        '前端映射可能落后于后端 PurchaseOrderStatus 枚举，请检查并补上映射（M-10）。'
    );
  }
  return tier;
}

export function getPurchaseOrderStatusLabel(status: string | null | undefined): PurchaseOrderStatusLabel | undefined {
  if (!status) return undefined;
  const label = PURCHASE_ORDER_STATUS_LABELS[status as PurchaseOrderStatusValue];
  if (!label) {
    // eslint-disable-next-line no-console
    console.error(
      `[purchaseOrderStatus] 未知的采购单状态 "${status}" —— 不在 PURCHASE_ORDER_STATUS_LABELS 里。` +
        '前端映射可能落后于后端 PurchaseOrderStatus 枚举，请检查并补上映射（M-10）。'
    );
  }
  return label;
}
