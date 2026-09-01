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
/** ⚠️ 金额仍受价格权限约束 —— 抽屉不绕过既有的价格闸。 */
const canViewPrice = computed(() => permissionStore.canViewPrice);

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

const taxRatePercent = computed<number | null>(() => {
  if (untaxedTotal.value === null || taxAmountTotal.value === null || untaxedTotal.value <= 0) return null;
  return (taxAmountTotal.value / untaxedTotal.value) * 100;
});

function formatTaxRate(pct: number | null): string {
  return pct === null ? '—' : `${Math.round(pct * 100) / 100}%`;
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

async function load() {
  if (!props.orderId || !factoryId.value) {
    order.value = null;
    return;
  }
  loading.value = true;
  errorKind.value = null;
  order.value = null;
  try {
    const base = props.kind === 'purchase' ? 'purchase' : 'sales';
    const res = await get(`/${factoryId.value}/${base}/orders/${props.orderId}`,
      { _silent: true } as never);
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
  <el-drawer v-model="visible" :title="title" size="520px" direction="rtl" destroy-on-close>
    <div v-loading="loading">
      <!-- ⛔ 403 与真故障分开: 权限不足时说清是权限边界, 不说「请重试」(重试一万次也没用) -->
      <el-alert
        v-if="errorKind === 'forbidden'"
        type="warning" :closable="false" show-icon
        title="没有权限查看这张订单"
        description="这需要对应模块的读取权限，请联系管理员。"
      />
      <el-alert
        v-else-if="errorKind === 'failed'"
        type="error" :closable="false" show-icon
        title="订单加载失败"
        description="网络或服务异常，请刷新后重试。"
      />

      <template v-else-if="order">
        <el-tag type="info" size="small" class="peek-readonly">只读速览</el-tag>
        <el-descriptions :column="2" border size="small" class="peek-desc">
          <el-descriptions-item label="订单号">
            {{ text(order.orderNumber ?? order.purchaseNumber) }}
          </el-descriptions-item>
          <el-descriptions-item :label="props.kind === 'purchase' ? '供应商' : '客户'">
            {{ text(order.customerName ?? order.supplierName) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ text(order.status) }}</el-descriptions-item>
          <el-descriptions-item label="下单日期">{{ text(order.orderDate) }}</el-descriptions-item>
          <!--
            缺陷 30 续 (2026-09-01): 🔒 owner 裁定「先算含税总额、说清税率、再倒算未税总额」——
            含税领先, 未税作为反算结果殿后。⚠️ 含税总额是**前端现算**(untaxedTotal + taxAmountTotal),
            ⛔ 不是 order.totalAmountWithTax —— 那个字段名在这两个端点上从不存在(见函数注释)。
          -->
          <el-descriptions-item label="含税总额">{{ moneyIfKnown(taxInclusiveTotal) }}</el-descriptions-item>
          <el-descriptions-item label="税率">{{ formatTaxRate(taxRatePercent) }}</el-descriptions-item>
          <el-descriptions-item label="未税总额">{{ money(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已开票">{{ money(order.invoicedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已收/付款">{{ money(order.paidAmount) }}</el-descriptions-item>
        </el-descriptions>

        <div class="peek-section">明细</div>
        <!-- 🔴「查不到」不能长成「没有」: 空明细要说出来, ⛔ 不留一张静默空表 -->
        <el-empty v-if="!items.length" description="这张订单没有明细行" :image-size="60" />
        <el-table v-else :data="items" size="small" border :scrollbar-always-on="true">
          <el-table-column label="品名" min-width="150">
            <template #default="{ row }">{{ text(row.productName ?? row.materialName) }}</template>
          </el-table-column>
          <el-table-column label="数量" width="90" align="right">
            <template #default="{ row }">{{ text(row.quantity) }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70" align="center">
            <template #default="{ row }">{{ text(row.unit) }}</template>
          </el-table-column>
          <!--
            缺陷 30 续: 原来这里**没有单价列**, 只有一个恒为「—」的「小计」——
            `row.totalAmount ?? row.subtotal` 两个属性在 PurchaseOrderItem/SalesOrderItem
            上都不存在, 真正的字段是 unitPrice + taxRate(算单价) / lineAmountWithTax(小计)。
            单价按 🔒 owner 裁定显含税(与 procurement/orders/detail.vue 同一套算法, 复用
            purchaseTaxIncludedUnitPrice, ⛔ 不再拼一份新公式)。
          -->
          <el-table-column v-if="canViewPrice" label="单价（含税）" min-width="130" align="right">
            <template #default="{ row }">{{ lineUnitPriceLabel(row) }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计（含税）" width="110" align="right">
            <template #default="{ row }">{{ moneyIfKnown(financeNumber(row.lineAmountWithTax)) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.peek-readonly { margin-bottom: 10px; }
.peek-desc { margin-bottom: 16px; }
.peek-section { font-weight: 600; margin: 8px 0; }
</style>
