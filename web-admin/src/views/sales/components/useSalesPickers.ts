/**
 * 销售新建弹窗的客户 / 产品下拉数据 (与销售订单新建页同源):
 *   客户 GET /{factoryId}/customers?page=1&size=100
 *   产品 GET /{factoryId}/product-types/sellable, 再用 isSalesOrderProduct 过滤 (成品 / 半成品, 可售, 启用)
 * 加载失败如实提示并留空, 不造假选项。
 */
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { get } from '@/api/request';
import { isSalesOrderProduct, type SalesProductOption } from '../orders/salesOrderProductSearch';

export interface PickerItem { label: string; value: string }

function unwrap<T>(data: unknown): T[] {
  if (Array.isArray(data)) return data as T[];
  const content = (data as { content?: unknown[] } | null)?.content;
  return Array.isArray(content) ? content as T[] : [];
}

export function useSalesPickers(factoryId: () => string | null | undefined) {
  const customers = ref<Array<{ id: string | number; name?: string | null }>>([]);
  const products = ref<SalesProductOption[]>([]);
  const loading = ref(false);
  const loadFailed = ref(false);

  async function load(): Promise<void> {
    const fid = factoryId();
    if (!fid) return;
    loading.value = true;
    loadFailed.value = false;
    const [c, p] = await Promise.allSettled([
      get(`/${fid}/customers`, { params: { page: 1, size: 100 }, _silent: true } as never),
      get(`/${fid}/product-types/sellable`, { _silent: true } as never),
    ]);
    if (c.status === 'fulfilled') customers.value = unwrap(c.value?.data);
    else { loadFailed.value = true; ElMessage.error('客户选项加载失败，请稍后重试'); }
    if (p.status === 'fulfilled') products.value = unwrap<SalesProductOption>(p.value?.data).filter(isSalesOrderProduct);
    else { loadFailed.value = true; ElMessage.error('销售产品加载失败，请稍后重试'); }
    loading.value = false;
  }

  const customerItems = computed<PickerItem[]>(() => customers.value.map((x) => ({ label: String(x.name || x.id), value: String(x.id) })));
  const productItems = computed<PickerItem[]>(() => products.value.map((x) => {
    const name = String(x.name || x.productName || x.id);
    return { label: x.code ? `${name}（${x.code}）` : name, value: String(x.id) };
  }));
  const customerName = (id: string) => String(customers.value.find((x) => String(x.id) === id)?.name ?? '');
  const productName = (id: string) => {
    const x = products.value.find((p) => String(p.id) === id);
    return String(x?.name || x?.productName || '');
  };

  return { loading, loadFailed, load, customerItems, productItems, customerName, productName };
}
