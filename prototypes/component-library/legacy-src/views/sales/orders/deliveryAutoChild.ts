import { post } from '@/api/request';
import { toDateString } from '@/utils/dateFormat';
import type { TableRow } from '@/types/api';

/**
 * 缺陷40 (2026-09-04, 硬约束 8 补丁): Issue #740「一次性全量发运」母单创建后要自动建立唯一
 * 全量子发运单 (仓库确认并扣库存) —— 这一步原来只写在 detail.vue 里, list.vue 的「创建发货单」
 * 走同一个母单创建接口却没接这一步, 于是 list 页建出来的母单永远停在 PENDING_SPLIT, 没有子单。
 *
 * 抽成共享 helper, detail.vue / list.vue 都调它, 不许各留一份 (形態 D — 同一个东西有两份, 它一定
 * 会漂)。本文件只负责「已经认定这是一次全量发运」之后要做什么, FULL 判定本身两个入口各自负责
 * (detail.vue 用显式 deliveryMode 单选 + 真实剩余量; list.vue 用「数量未被用户改小」的隐式判定 ——
 * 见 {@link isFullQuickDeliveryPlan})。
 */

/**
 * 一行"计划发货"的最小形状 — detail.vue 的 filteredItems / list.vue 的 deliveryForm.items 都满足.
 * 三个字段都设成可选: 两个调用点传的都是宽松的 TableRow (= Record<string, any>), 用必填属性会
 * 在 TS 结构类型检查里被判定"缺属性"而编译不过 —— 缺失时按运行时的 NaN/undefined 处理即可
 * (下面 createAutoFullChildShipment 会把匹配不上/非数字的行整体判定为失败, 不会带脏数据发出去)。
 */
export interface PlannedDeliveryLine {
  salesOrderItemId?: string | number | null;
  productTypeId?: string | null;
  deliveredQuantity?: number | string | null;
}

export interface AutoFullChildOptions {
  factoryId: string;
  /** 刚创建成功的母发货单 id. */
  masterId: string;
  /** 母发货单创建接口返回的 items (带真实的 SalesDeliveryItem.id, 子单要用它做 parentDeliveryItemId). */
  masterItems: TableRow[];
  /** 本次提交的计划发货行 (用于按 salesOrderItemId/productTypeId 反查母单行). */
  plannedItems: PlannedDeliveryLine[];
  deliveryMethod: string;
  logisticsCompany: string;
  trackingNumber: string;
  deliveryAddress: string;
}

/**
 * 一次性全量发运母单创建成功后, 自动建立唯一全量子发运单。
 *
 * @returns `true` 表示子发运单创建请求已发出 (未捕获后端错误 —— 调用方决定失败时怎么提示,
 *          两个入口现有行为不同, 故意不在这里统一吞掉); `false` 表示明细行匹配不上母单行
 *          (数据不一致, 未调用后端), 调用方应提示用户去发货记录里手动补建。
 */
export async function createAutoFullChildShipment(options: AutoFullChildOptions): Promise<boolean> {
  const masterItems = Array.isArray(options.masterItems) ? options.masterItems : [];
  const childItems = options.plannedItems.map((planned) => {
    const masterItem = masterItems.find((item) => String(item.salesOrderItemId || '') === String(planned.salesOrderItemId || ''))
      || masterItems.find((item) => String(item.productTypeId || '') === String(planned.productTypeId || ''));
    return masterItem ? { parentDeliveryItemId: Number(masterItem.id), quantity: Number(planned.deliveredQuantity) } : null;
  }).filter((item): item is { parentDeliveryItemId: number; quantity: number } =>
    Number.isFinite(item?.parentDeliveryItemId) && Number.isFinite(item?.quantity) && (item?.quantity ?? 0) > 0);

  if (!options.masterId || childItems.length !== options.plannedItems.length) {
    return false;
  }

  await post(`/${options.factoryId}/sales/deliveries/${options.masterId}/shipments`, {
    idempotencyKey: `web-full-shipment-${options.masterId}`,
    plannedShipmentDate: toDateString(new Date()),
    deliveryMethod: options.deliveryMethod,
    logisticsCompany: options.logisticsCompany,
    trackingNumber: options.trackingNumber,
    deliveryMode: 'FULL',
    deliveryAddress: options.deliveryAddress,
    items: childItems,
  });
  return true;
}

/**
 * list.vue 没有 detail.vue 那种显式的「一次性全量/分批」单选 —— 它的「创建发货单」对话框
 * 默认把每一行数量填成订单行的全部下单量 (见 list.vue handleQuickDelivery 的
 * `maxQuantity`)。只要用户没有把任何一行改小, 这次提交在语义上就等同于 detail.vue 的 FULL 模式;
 * 一旦有任何一行被改小 (例如这张单之前已经部分发过货, 用户手动调整到剩余量), 就不再是「一次性
 * 全量」——保守起见不触发自动建子单, 交回既有的"到发货记录手动新建分批发运"路径 (与本次修复前
 * 行为一致, 不是回归)。
 */
export function isFullQuickDeliveryPlan(items: Array<{ deliveredQuantity: number | string; maxQuantity?: number | string | null }>): boolean {
  if (!Array.isArray(items) || items.length === 0) return false;
  return items.every((item) => Number(item.deliveredQuantity) === Number(item.maxQuantity));
}
