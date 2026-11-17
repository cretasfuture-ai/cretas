<script setup lang="ts">
/**
 * 财务侧的「订单速览」只读抽屉。
 *
 * 🔴 2026-08-22 owner 需求原话:
 *   「在导航栏里面其实只能看到财务自己本身的内容，但是我们又要让财务看到
 *     这些订单详情的内容，但是又不能让他直接跨模块去看。」
 *
 * ▎「菜单里看得见」「路由进得去」「接口读得到」本来共用同一个开关, 这里把它们拆开:
 *   ⛔ **本组件不注册任何路由** —— 所以它物理上不可能出现在侧边栏, 也不可能被
 *      手输 URL 到达, URL 始终停在 /finance/*。
 *   ✅ 数据走财务本来就够得着的端点(GET /sales/orders 含 finance; GET /purchase/orders 不拦)。
 *
 * ⛔ **只读**: 本组件不渲染任何写操作按钮, 也不 import 任何提交函数。
 *    ⚠️ 这一条是刻意的设计约束, 不是"暂时没做" —— 复用销售订单详情页的方案被否掉,
 *    正是因为那个页面有十几个写操作按钮, 逐个挡漏一个就是权限漏洞。
 *    ▎**能力不是靠挡出来的, 是靠一开始就不给。**
 */
import { ref, watch, computed } from 'vue';
import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { purchaseTaxIncludedUnitPrice } from '@/views/procurement/orders/purchaseOrderTaxInclusivePricing';
import { displayUnit, canonicalUnitCode } from '@/utils/unitPricing';
import { enumLabel, SALES_ORDER_STATUS_LABELS, PURCHASE_ORDER_STATUS_LABELS } from '@/utils/enumDisplay';
import type { CsTableColumn } from '@/types/csTable';

const props = defineProps<{
  /** 订单 id。为空 -> 抽屉不发请求 */
  orderId?: string | null;
  /** 'sales' = 销售订单, 'purchase' = 采购订单 */
  kind?: 'sales' | 'purchase';
}>();
const visible = defineModel<boolean>('visible', { default: false });

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
/**
 * ⚠️ 金额仍受价格权限约束 —— 抽屉不绕过既有的价格闸。
 * S5-12: 采购单按配置的采购读权限判; 抽屉只从 OA 审批中心打开采购单, 审批读 (approval-view)
 * 对财务也给采购金额 (裁定 a), 所以采购单再加上财务读。销售单仍按原价格权限。
 */
const canViewPrice = computed(() => (props.kind === 'purchase'
  ? permissionStore.canViewPurchasePrice || permissionStore.canAccess('finance')
  : permissionStore.canViewPrice));

const loading = ref(false);
/** 🔴 三态: null=还没查 / 'forbidden'=没权限 / 'failed'=真故障。⛔ 不合并 —— 行动指引不同。 */
const errorKind = ref<null | 'forbidden' | 'failed'>(null);
const order = ref<Record<string, unknown> | null>(null);

const items = computed<Array<Record<string, unknown>>>(() => {
  const raw = order.value?.items;
  return Array.isArray(raw) ? raw as Array<Record<string, unknown>> : [];
});

const title = computed(() => {
  const n = order.value?.orderNumber ?? order.value?.purchaseNumber ?? props.orderId ?? '';
  return `${props.kind === 'purchase' ? '采购订单' : '销售订单'} ${n}`;
});

function money(v: unknown): string {
  if (!canViewPrice.value) return '—';
  // 后端按权限剥掉的金额是 null —— Number(null) 是 0, 会显示成「¥0.00」, 先挡住
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  // ⚠️ 读不到就显示「—」, ⛔ 不兜底成 0 ——
  //    「我不知道」和「是 0」对看账的人是两件完全不同的事。
  return Number.isFinite(n) ? `¥${n.toFixed(2)}` : '—';
}
function text(v: unknown): string {
  return v === null || v === undefined || v === '' ? '-' : String(v);
}

/**
 * 缺陷 30 续 (2026-09-01, LIUSHANMEN 生产, PO-20260901-0004): 审批人点开这个抽屉,
 * 未税总额 ¥750.00 显示得好好的, 含税总额却是「—」、明细每行小计也是「—」、
 * 没有单价列 —— 而后端**其实**下发了 totalAmount / taxAmount, 数据在, 是显示没接上:
 *   ① `order.totalAmountWithTax` 这个字段**根本不存在** —— GET /purchase(sales)/orders/{id}
 *      直接序列化实体, 只有 FinanceCostBreakdown(另一个端点)才有这个字段名。
 *   ② `row.totalAmount ?? row.subtotal` 同理: PurchaseOrderItem/SalesOrderItem 都没有
 *      这两个属性, 真正的字段是 `lineAmount`(未税) / `lineAmountWithTax`(含税)。
 * ⇒ 用已下发的 totalAmount(未税) + taxAmount(税额) 现算, ⛔ 不新增后端字段。
 *
 * 🔒 owner 裁定(2026-09-01):「先算含税总额、说清税率、再倒算未税总额」—— 含税领先。
 * ⛔ taxAmount 读不到(null, 区别于显式 0)时不许假装是 0, 那会把「不知道」悄悄说成
 * 「没有税」, 含税总额跟着假装等于未税总额 —— 比显示「—」更糟, 因为它看着像个答案。
 */
function financeNumber(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

const untaxedTotal = computed<number | null>(() => financeNumber(order.value?.totalAmount));
const taxAmountTotal = computed<number | null>(() => financeNumber(order.value?.taxAmount));

const taxInclusiveTotal = computed<number | null>(() => {
  if (untaxedTotal.value === null || taxAmountTotal.value === null) return null;
  return untaxedTotal.value + taxAmountTotal.value;
});

/**
 * 缺陷 30 再续 (2026-09-02, LIUSHANMEN 生产, PO-20260902-0001): 头部「税率」原来是用
 * taxAmountTotal ÷ untaxedTotal **反算**的 —— 反算在四舍五入之后必然有精度损失:
 *   库权威值 taxRate = 13.00, 而 1.15 ÷ 8.85 = 12.9944…% 显示成 12.99%。
 *   「几乎对」比「明显错」更糟: 12.99% 会被看账的人当成"税率配置错了"去查一个
 *   根本没问题的设置。
 * ⇒ 明细行本来就下发权威的 `taxRate`(purchaseTaxIncludedUnitPrice 已经在读它算单价) ——
 *   直接读它, ⛔ 不再反算, 也⛔ 不新增后端字段。
 *
 * 多税率单据: 逐行税率不一致时, 不能挑一个代表(挑哪个都是武断的), 也不能显示
 * taxAmountTotal/untaxedTotal 算出的加权平均 —— 那是同一个"反算出的假精度"换了个
 * 形式, 一样会被当成一个确定的答案。⇒ 显式说「多税率」。
 */
const lineTaxRates = computed<number[]>(() => {
  const seen: number[] = [];
  for (const row of items.value) {
    const rate = financeNumber(row.taxRate);
    if (rate === null || rate < 0 || rate > 100) continue;
    // 四舍五入到 4 位小数再去重 —— 容忍浮点表示误差, 不容忍真实税率差异(标准税率
    // 精确到 2 位小数, 4 位足够分辨 13 vs 12.9944 这类反算残留, 同时不会把两个
    // 本该相同的真实值因为浮点噪声误判成"多税率")。
    const rounded = Math.round(rate * 10000) / 10000;
    if (!seen.includes(rounded)) seen.push(rounded);
  }
  return seen;
});

function formatAuthoritativeTaxRate(rates: number[]): string {
  if (rates.length === 0) return '—';
  if (rates.length > 1) return '多税率';
  return `${rates[0]}%`;
}

/**
 * 与 money() 共用权限门 + ¥ 格式, 但显式把「读不到」(null/undefined) 挡在 Number() 之前。
 * ⛔ 不能直接把 taxInclusiveTotal.value 这类 `number | null` 丢给 money(): Number(null)
 *    在 JS 里是 0(有限数), money() 会把它显示成「¥0.00」, 正是硬约束要挡的那个洞
 *    ——「我不知道」被显示成「是 0」。
 */
function moneyIfKnown(v: number | null): string {
  return v === null ? '—' : money(v);
}

/** 明细行含税单价 —— 未税单价与税率都要读得到才算得出来, 否则「—」。 */
function lineTaxIncludedUnitPrice(row: Record<string, unknown>): number | null {
  return purchaseTaxIncludedUnitPrice(row.unitPrice, row.taxRate);
}

function lineUnitPriceLabel(row: Record<string, unknown>): string {
  const price = moneyIfKnown(lineTaxIncludedUnitPrice(row));
  const unit = canonicalUnitCode(row.priceUnit || row.unit);
  return unit ? `${price} / ${displayUnit(unit)}` : price;
}

/** 头部信息: 含税领先、未税殿后 (owner 裁定); 状态走中文枚举。 */
const descItems = computed<Array<{ label: string; value: string }>>(() => {
  const o = order.value;
  if (!o) return [];
  const statusLabels = props.kind === 'purchase' ? PURCHASE_ORDER_STATUS_LABELS : SALES_ORDER_STATUS_LABELS;
  return [
    { label: '订单号', value: text(o.orderNumber ?? o.purchaseNumber) },
    { label: props.kind === 'purchase' ? '供应商' : '客户', value: text(o.customerName ?? o.supplierName) },
    { label: '状态', value: enumLabel(o.status, statusLabels, '-') },
    { label: '下单日期', value: text(o.orderDate) },
    { label: '含税总额', value: moneyIfKnown(taxInclusiveTotal.value) },
    { label: '税率', value: formatAuthoritativeTaxRate(lineTaxRates.value) },
    { label: '未税总额', value: money(o.totalAmount) },
    { label: '已开票', value: money(o.invoicedAmount) },
    { label: '已收/付款', value: money(o.paidAmount) },
  ];
});

const itemColumns = computed<CsTableColumn<Record<string, unknown>>[]>(() => {
  const right = { meta: { class: { th: 'text-right', td: 'text-right' } } };
  const cols: CsTableColumn<Record<string, unknown>>[] = [
    { id: 'name', header: '品名' },
    { id: 'quantity', header: '数量', ...right },
    { id: 'unit', header: '单位' },
  ];
  if (canViewPrice.value) {
    cols.push({ id: 'unitPrice', header: '单价（含税）', ...right });
    cols.push({ id: 'subtotal', header: '小计（含税）', ...right });
  }
  return cols;
});

async function load() {
  if (!props.orderId || !factoryId.value) {
    order.value = null;
    return;
  }
  loading.value = true;
  errorKind.value = null;
  order.value = null;
  try {
    // 采购单走 OA 审批读 (财务审批要看金额, S5-12 裁定 a); 销售单照旧
    const url = props.kind === 'purchase'
      ? `/${factoryId.value}/purchase/orders/${props.orderId}/approval-view`
      : `/${factoryId.value}/sales/orders/${props.orderId}`;
    const res = await get(url, { _silent: true } as never);
    order.value = (res?.data ?? null) as Record<string, unknown> | null;
  } catch (err) {
    // ⚠️ 请求层抛的是 **ApiError**(status/code 在顶层, 没有 response) —— 两种形状都认。
    const e = err as { status?: number; code?: string; response?: { status?: number } };
    const status = e?.status ?? e?.response?.status;
    errorKind.value = (status === 403 || e?.code === 'FORBIDDEN') ? 'forbidden' : 'failed';
  } finally {
    loading.value = false;
  }
}

watch(() => [visible.value, props.orderId], ([open]) => { if (open) void load(); });
</script>

<template>
  <!-- 非模态 + 无遮罩: 模态 USlideover 会给 body 加 pointer-events:none, 抽屉下面的列表/弹窗点不了。 -->
  <USlideover
    v-model:open="visible"
    :modal="false"
    :overlay="false"
    side="right"
    :title="title"
    description="订单只读速览"
    :ui="{ content: 'cs-slide peek-slide', description: 'sr-only' }"
  >
    <template #body>
      <div data-testid="order-peek">
        <p v-if="loading" class="peek-loading">加载中…</p>
        <!-- ⛔ 403 与真故障分开: 权限不足时说清是权限边界, 不说「请重试」(重试一万次也没用) -->
        <UAlert
          v-if="errorKind === 'forbidden'"
          color="warning" variant="soft" icon="i-lucide-triangle-alert"
          title="没有权限查看这张订单"
          description="这需要对应模块的读取权限，请联系管理员。"
        />
        <UAlert
          v-else-if="errorKind === 'failed'"
          color="error" variant="soft" icon="i-lucide-circle-alert"
          title="订单加载失败"
          description="网络或服务异常，请刷新后重试。"
        />

        <template v-else-if="order">
          <UBadge color="neutral" variant="soft" size="sm" class="peek-readonly">只读速览</UBadge>
          <dl class="peek-desc">
            <div v-for="d in descItems" :key="d.label" class="desc-item" :data-label="d.label">
              <dt>{{ d.label }}</dt>
              <dd>{{ d.value }}</dd>
            </div>
          </dl>
          <!--
            缺陷 30 续 (2026-09-01): 🔒 owner 裁定「先算含税总额、说清税率、再倒算未税总额」——
            含税领先, 未税作为反算结果殿后(见 descItems 顺序)。⚠️ 含税总额是**前端现算**
            (untaxedTotal + taxAmountTotal), ⛔ 不是 order.totalAmountWithTax。
            「税率」读明细行权威 taxRate, 与两个现算总额互不依赖。
          -->
          <div class="peek-section">明细</div>
          <!-- 🔴「查不到」不能长成「没有」: 空明细要说出来, ⛔ 不留一张静默空表 -->
          <p v-if="!items.length" class="peek-empty">这张订单没有明细行</p>
          <UTable v-else :data="items" :columns="itemColumns" class="cs-table" data-testid="order-peek-items">
            <template #name-cell="{ row }">{{ text(row.original.productName ?? row.original.materialName) }}</template>
            <template #quantity-cell="{ row }">{{ text(row.original.quantity) }}</template>
            <template #unit-cell="{ row }">{{ text(row.original.unit) }}</template>
            <template #unitPrice-cell="{ row }">{{ lineUnitPriceLabel(row.original) }}</template>
            <template #subtotal-cell="{ row }">{{ moneyIfKnown(financeNumber(row.original.lineAmountWithTax)) }}</template>
          </UTable>
        </template>
      </div>
    </template>
  </USlideover>
</template>

<style scoped>
.peek-readonly { margin-bottom: 10px; }
.peek-loading, .peek-empty { color: var(--cs-muted); font-size: var(--cs-font-small); }
.peek-desc { display: grid; grid-template-columns: 1fr 1fr; gap: 1px; margin: 0 0 16px; border: 1px solid var(--cs-border); background: var(--cs-border); }
.desc-item { display: flex; gap: var(--cs-space-2); padding: 6px 8px; background: var(--cs-surface); font-size: 13px; }
.desc-item dt { flex: none; width: 5.5em; color: var(--cs-muted); }
.desc-item dd { margin: 0; color: var(--cs-text); min-width: 0; word-break: break-all; }
.peek-section { font-weight: 600; margin: 8px 0; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
<style>
.peek-slide { --cs-slide-width: 560px; }
</style>
