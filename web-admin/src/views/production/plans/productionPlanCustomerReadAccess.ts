import { computed, ref, watch, type Ref } from 'vue';
import { getErrorMessage } from '@/utils/errorToast';
import type { ProductionPlanCustomerOption } from './productionPlanCustomerSelection';

interface CustomerReadPermissions {
  isDbLoaded: boolean;
  dbLoadError: string | null;
  currentUserId: string;
  getPermissionLevel: (module: 'sales' | 'finance') => string;
}

export function productionPlanCustomerReadAccess(permissions: CustomerReadPermissions) {
  if (permissions.dbLoadError) return 'permission-error';
  if (!permissions.isDbLoaded) return 'pending';
  // CustomerController.getCustomerList: sales:read OR finance:read (rw includes read).
  // UI module GRANT and write-only access do not grant this API permission.
  return (['sales', 'finance'] as const).some(module =>
    ['r', 'rw'].includes(permissions.getPermissionLevel(module)),
  ) ? 'allowed' : 'denied';
}

export function useProductionPlanCustomerReadAccess(
  factoryId: Readonly<Ref<string | undefined>>,
  permissions: CustomerReadPermissions,
  fetchCustomers: (factory: string) => Promise<ProductionPlanCustomerOption[]>,
) {
  const access = computed(() => productionPlanCustomerReadAccess(permissions));
  const customers = ref<ProductionPlanCustomerOption[]>([]);
  const status = ref<'pending' | 'denied' | 'loading' | 'ready' | 'error'>('pending');
  const error = ref('');
  let generation = 0;

  async function loadCustomers() {
    const requestGeneration = ++generation;
    const factory = factoryId.value;
    const userId = permissions.currentUserId;
    const isCurrent = () => requestGeneration === generation && factory === factoryId.value
      && userId === permissions.currentUserId && access.value === 'allowed';
    customers.value = [];
    error.value = '';
    if (access.value === 'permission-error') {
      status.value = 'error';
      error.value = '客户读取权限加载失败，请刷新后重试';
      return;
    }
    if (!factory || access.value === 'pending') { status.value = 'pending'; return; }
    if (access.value === 'denied') { status.value = 'denied'; return; }
    status.value = 'loading';
    try {
      const result = await fetchCustomers(factory);
      if (!isCurrent()) return;
      customers.value = result;
      status.value = 'ready';
    } catch (cause) {
      if (!isCurrent()) return;
      status.value = 'error';
      error.value = getErrorMessage(cause, '客户列表加载失败，请重试');
    }
  }

  watch(() => [factoryId.value, permissions.currentUserId, access.value],
    // setRole updates identity before clearing its loaded flag. Batch the whole
    // synchronous reset before considering a request for the next identity.
    () => { void loadCustomers(); }, { immediate: true });

  const hint = computed(() => {
    if (status.value === 'denied') return '当前账号无客户名单读取权限，可继续填写其他生产计划信息';
    if (status.value === 'pending') return '正在确认客户读取权限';
    if (status.value === 'loading') return '正在加载客户列表';
    if (status.value === 'error') return error.value;
    return customers.value.length ? '' : '暂无可关联客户';
  });
  const canRetryCustomers = computed(() => status.value === 'error' && access.value === 'allowed');
  return { customers, customerReadStatus: status, customerReadHint: hint, canRetryCustomers, loadCustomers };
}
