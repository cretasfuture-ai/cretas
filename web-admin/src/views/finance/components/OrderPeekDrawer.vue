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
          <el-descriptions-item label="未税总额">{{ money(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="含税总额">{{ money(order.totalAmountWithTax) }}</el-descriptions-item>
          <el-descriptions-item label="已开票">{{ money(order.invoicedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已收/付款">{{ money(order.paidAmount) }}</el-descriptions-item>
        </el-descriptions>

        <div class="peek-section">明细</div>
        <!-- 🔴「查不到」不能长成「没有」: 空明细要说出来, ⛔ 不留一张静默空表 -->
        <el-empty v-if="!items.length" description="这张订单没有明细行" :image-size="60" />
        <el-table v-else :data="items" size="small" border>
          <el-table-column label="品名" min-width="150">
            <template #default="{ row }">{{ text(row.productName ?? row.materialName) }}</template>
          </el-table-column>
          <el-table-column label="数量" width="90" align="right">
            <template #default="{ row }">{{ text(row.quantity) }}</template>
          </el-table-column>
          <el-table-column label="单位" width="70" align="center">
            <template #default="{ row }">{{ text(row.unit) }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="110" align="right">
            <template #default="{ row }">{{ money(row.totalAmount ?? row.subtotal) }}</template>
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
